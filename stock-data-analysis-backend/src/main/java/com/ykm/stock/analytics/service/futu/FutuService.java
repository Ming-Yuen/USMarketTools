package com.ykm.stock.analytics.service.futu;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotGetOwnerPlate;
import com.futu.openapi.pb.QotGetStaticInfo;
import com.futu.openapi.pb.QotGetPlateSet;
import com.futu.openapi.pb.QotGetValuationPlateStockList;
import com.futu.openapi.pb.QotGetEarningsCalendar;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetValuationDetail;
import com.futu.openapi.pb.QotGetSecuritySnapshot;

import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import com.google.common.util.concurrent.RateLimiter;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.model.dto.classification.ClassificationPlate;
import com.ykm.stock.analytics.model.dto.classification.StockClassificationSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class FutuService {
    private static final int US = QotCommon.QotMarket.QotMarket_US_Security_VALUE;
    private static final int US_PINK = QotCommon.ExchType.ExchType_US_Pink_VALUE;
    private static final int STOCK = QotCommon.SecurityType.SecurityType_Eqty_VALUE;
    private static final int TRUST = QotCommon.SecurityType.SecurityType_Trust_VALUE;
    private static final String US_OTC_UNAVAILABLE_PREFIX = "US OTC market quote is not available for ";
    private static final int BATCH_SIZE = 200;
    private final Supplier<FutuQuoteClient> clients;
    private final Runnable ownerPlatePermit;
    private final FutuQuoteConverter converter;
    private final RateLimiter valuationLimiter;
    private final RateLimiter earningsLimiter;
    // 復權接口上限每 30 秒 60 次；跨 session 共用並保留餘量，不累積閒置突發額度。
    private final RateLimiter rehabLimiter = RateLimiter.create(1.8, 1, TimeUnit.SECONDS);
    private final RateLimiter snapshotLimiter = RateLimiter.create(1.8, 1, TimeUnit.SECONDS);

    @Autowired
    public FutuService(@Value("${futu.opend.host}") String host, @Value("${futu.opend.port}") int port,
                       @Value("${futu.opend.request-timeout-seconds}") int timeoutSeconds,
                       @Value("${futu.valuation.requests-per-second}") double valuationRate,
                       @Value("${futu.earnings.requests-per-second}") double earningsRate, FutuQuoteConverter converter) {
        this(() -> new FutuQuoteClient(host, port, timeoutSeconds), ownerPlateLimiter(), converter, valuationRate, earningsRate);
        if (host == null || host.isBlank() || port < 1 || port > 65535 || timeoutSeconds < 1)
            throw new IllegalArgumentException("FUTU_CONNECTION_CONFIGURATION_INVALID");
    }

    FutuService(Supplier<FutuQuoteClient> clients, Runnable ownerPlatePermit, FutuQuoteConverter converter) {
        this(clients, ownerPlatePermit, converter, 0.9, 1.8);
    }

    private FutuService(Supplier<FutuQuoteClient> clients, Runnable ownerPlatePermit, FutuQuoteConverter converter,
                        double valuationRate, double earningsRate) {
        // 每個接口跨 session 共用節流，閒置時間不累積突發請求額度。
        if (!(valuationRate > 0 && valuationRate <= 0.9 && earningsRate > 0 && earningsRate <= 1.8))
            throw new IllegalArgumentException("FUTU_REQUEST_RATE_INVALID");
        this.valuationLimiter = RateLimiter.create(valuationRate, 1, TimeUnit.SECONDS);
        this.earningsLimiter = RateLimiter.create(earningsRate, 1, TimeUnit.SECONDS);
        this.clients = clients;
        this.ownerPlatePermit = ownerPlatePermit;
        this.converter = converter;
    }

    /** 一個 Job 共用連線、正股及分類字典；每批下載與 DB 提交由呼叫端依序執行。 */
    public ClassificationSession openSession() {
        FutuQuoteClient client = clients.get();
        try {
            return new ClassificationSession(client, supportedUsStocks(client, false));
        } catch (RuntimeException ex) {
            client.close();
            throw ex;
        }
    }

    private Set<String> supportedUsStocks(FutuQuoteClient client, boolean excludePink) {
        return supportedUsSymbols(client.getStockBasicInfo(), STOCK, excludePink);
    }

    private Set<String> supportedUsFinancialReportStocks(FutuQuoteClient client) {
        Set<String> supported = new LinkedHashSet<>(supportedUsStocks(client, true));
        supported.addAll(supportedUsSymbols(client.getTrustBasicInfo(), TRUST, true));
        return Set.copyOf(supported);
    }

    private Set<String> supportedUsSymbols(QotGetStaticInfo.Response info, int securityType, boolean excludePink) {
        if (info.getRetType() != 0) throw new IllegalStateException("FUTU_GET_STATIC_INFO_FAILED:" + info.getRetType() + ":" + info.getRetMsg());
        if (!info.hasS2C()) throw new IllegalStateException("FUTU_GET_STATIC_INFO_DATA_MISSING");
        Set<String> supported = new LinkedHashSet<>();
        for (QotCommon.SecurityStaticInfo item : info.getS2C().getStaticInfoListList()) {
            QotCommon.SecurityStaticBasic basic = item.getBasic();
            if (basic.getSecurity().getMarket() == US && basic.getSecType() == securityType
                    && !basic.getDelisting() && (!excludePink || basic.getExchType() != US_PINK))
                supported.add(basic.getSecurity().getCode());
        }
        return Set.copyOf(supported);
    }

    private Map<String, ClassificationPlate> readDictionary(FutuQuoteClient client, int type) {
        QotGetPlateSet.Response response = client.getPlateSet(type);
        if (response.getRetType() != 0)
            throw new IllegalStateException("FUTU_GET_PLATE_SET_FAILED:" + type + ":" + response.getRetType() + ":" + response.getRetMsg());
        if (!response.hasS2C()) throw new IllegalStateException("FUTU_PLATE_DICTIONARY_DATA_MISSING:" + type);
        Map<String, ClassificationPlate> dictionary = new LinkedHashMap<>();
        for (QotCommon.PlateInfo plate : response.getS2C().getPlateInfoListList()) {
            String code = plate.getPlate().getCode();
            if (plate.getPlate().getMarket() != US || code.isBlank() || plate.getName().isBlank())
                throw new IllegalStateException("FUTU_PLATE_DICTIONARY_INVALID:" + type);
            if (dictionary.putIfAbsent(code, converter.toPlate(plate)) != null)
                throw new IllegalStateException("FUTU_PLATE_DICTIONARY_DUPLICATE:US." + code);
        }
        if (dictionary.isEmpty()) throw new IllegalStateException("FUTU_PLATE_DICTIONARY_EMPTY:" + type);
        return Map.copyOf(dictionary);
    }

    public final class ClassificationSession implements AutoCloseable {
        private final FutuQuoteClient client;
        private final Set<String> supported;
        private Map<String, ClassificationPlate> industryDictionary;
        private Map<String, ClassificationPlate> conceptDictionary;
        private boolean closed;

        private ClassificationSession(FutuQuoteClient client, Set<String> supported) {
            this.client = client;
            this.supported = supported;
        }

        /** 依本輪美股正股清單判斷是否可用精確 Symbol 查詢。 */
        public boolean supportsStock(String symbol) {
            return symbol != null && supported.contains(symbol);
        }

        /** 只讀取本批美股代碼；保留大小寫及股類標點，最多200隻，不修改FIGI或DB。 */
        public synchronized Map<String, StockClassificationSnapshot> getStockClassifications(List<String> symbols) {
            if (closed) throw new IllegalStateException("FUTU_CLASSIFICATION_SESSION_CLOSED");
            if (symbols.size() > BATCH_SIZE) throw new IllegalArgumentException("FUTU_CLASSIFICATION_BATCH_TOO_LARGE");
            Set<String> requested = new LinkedHashSet<>(symbols);
            for (String symbol : requested)
                if (symbol == null || symbol.isBlank() || !symbol.equals(symbol.trim()))
                    throw new IllegalArgumentException("FUTU_SYMBOL_INVALID");
            List<String> targets = requested.stream().filter(supported::contains).toList();
            log.info("Futu classification batch download | requested={} supported={}", requested.size(), targets.size());
            Map<String, StockClassificationSnapshot> result = new LinkedHashMap<>();
            if (!targets.isEmpty()) {
                if (industryDictionary == null) loadDictionaries();
                ownerPlatePermit.run();
                QotGetOwnerPlate.Response response = client.getOwnerPlate(targets);
                if (response.getRetType() != 0) throw new IllegalStateException("FUTU_GET_OWNER_PLATE_FAILED:" + response.getRetType() + ":" + response.getRetMsg());
                if (!response.hasS2C()) throw new IllegalStateException("FUTU_GET_OWNER_PLATE_DATA_MISSING");
                Set<String> batchSymbols = Set.copyOf(targets);
                for (QotGetOwnerPlate.SecurityOwnerPlate owner : response.getS2C().getOwnerPlateListList()) {
                    String symbol = owner.getSecurity().getCode();
                    if (owner.getSecurity().getMarket() != US || !batchSymbols.contains(symbol))
                        throw new IllegalStateException("FUTU_OWNER_PLATE_IDENTITY_MISMATCH:" + symbol);
                    List<ClassificationPlate> industries = new ArrayList<>();
                    List<ClassificationPlate> concepts = new ArrayList<>();
                    Set<String> memberships = new LinkedHashSet<>();
                    for (QotCommon.PlateInfo plate : owner.getPlateInfoListList()) {
                        // owner 只提供歸屬關係；類型及名稱以本輪指定類型取得的權威字典為準。
                        if (plate.getPlate().getMarket() != US) continue;
                        String code = plate.getPlate().getCode();
                        if (!memberships.add(code)) continue;
                        ClassificationPlate industry = industryDictionary.get(code);
                        ClassificationPlate concept = conceptDictionary.get(code);
                        if (industry != null) industries.add(industry);
                        if (concept != null) concepts.add(concept);
                    }
                    StockClassificationSnapshot classification = converter.toClassification(owner, industries, concepts);
                    if (result.putIfAbsent(symbol, classification) != null)
                        throw new IllegalStateException("FUTU_OWNER_PLATE_DUPLICATE_SYMBOL:" + symbol);
                }
            }
            return Map.copyOf(result);
        }

        private void loadDictionaries() {
            Map<String, ClassificationPlate> industries = readDictionary(client, QotCommon.PlateSetType.PlateSetType_Industry_VALUE);
            Map<String, ClassificationPlate> concepts = readDictionary(client, QotCommon.PlateSetType.PlateSetType_Concept_VALUE);
            for (String code : industries.keySet())
                if (concepts.containsKey(code)) throw new IllegalStateException("FUTU_PLATE_DICTIONARY_OVERLAP:US." + code);
            industryDictionary = industries;
            conceptDictionary = concepts;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                client.close();
            }
        }
    }

    /** 估值、財報、盈利及復權查詢共用既有 OpenD 設定。 */
    public ObservationSession openObservationSession() {
        return new ObservationSession(clients.get());
    }

    /** 同份快照的公司流通／已發行股數、價格、市值及原生估值倍數；行情時間不是股數生效時間。 */
    public record ShareSnapshot(long issuedShares, Long outstandingShares, BigDecimal price, BigDecimal marketCap,
                                BigDecimal outstandingMarketCap, BigDecimal peTtmRate, BigDecimal pbRate, Instant quoteTime) {
        public ShareSnapshot(long issuedShares, BigDecimal price, BigDecimal marketCap, Instant quoteTime) {
            this(issuedShares, null, price, marketCap, null, null, null, quoteTime);
        }

        public long effectiveShares() {
            return outstandingShares != null && outstandingShares > 0 ? outstandingShares : issuedShares;
        }

        public BigDecimal effectiveMarketCap() {
            return outstandingMarketCap != null && outstandingMarketCap.signum() > 0 ? outstandingMarketCap : marketCap;
        }
    }

    public final class ObservationSession implements AutoCloseable {
        private final FutuQuoteClient client;

        private ObservationSession(FutuQuoteClient client) {
            this.client = client;
        }

        /** 當前總股數，不提供歷史日期；取得時間由入庫端記錄，不能借行情時間倒填。 */
        public Map<String, ShareSnapshot> shareCounts(List<String> symbols) {
            if (symbols.isEmpty()) return Map.of();
            if (symbols.size() > 400 || new LinkedHashSet<>(symbols).size() != symbols.size())
                throw new IllegalArgumentException("FUTU_SNAPSHOT_SCOPE_INVALID");
            List<String> remaining = new ArrayList<>(symbols);
            QotGetSecuritySnapshot.Response response;
            while (true) {
                snapshotLimiter.acquire();
                response = client.getSecuritySnapshots(List.copyOf(remaining));
                if (response.getRetType() == 0) break;
                String error = response.getRetMsg();
                String unsupported = unsupportedUsOtcSymbol(error);
                if (unsupported != null && remaining.remove(unsupported)) {
                    log.warn("Share snapshot unsupported | symbol={} reason={}", unsupported, error);
                    if (remaining.isEmpty()) return Map.of();
                    continue;
                }
                throw new IllegalStateException("FUTU_SHARE_SNAPSHOT_FAILED:" + response.getRetType() + ":" + error);
            }
            if (!response.hasS2C()) throw new IllegalStateException("FUTU_SHARE_SNAPSHOT_DATA_MISSING");
            Map<String, ShareSnapshot> shares = new LinkedHashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            for (QotGetSecuritySnapshot.Snapshot item : response.getS2C().getSnapshotListList()) {
                QotCommon.Security security = item.getBasic().getSecurity();
                String symbol = security.getCode();
                if (security.getMarket() != US || !remaining.contains(symbol) || !seen.add(symbol))
                    throw new IllegalStateException("FUTU_SHARE_SNAPSHOT_SECURITY_MISMATCH:" + symbol);
                if (item.hasEquityExData()) {
                    QotGetSecuritySnapshot.EquitySnapshotExData ex = item.getEquityExData();
                    long issued = ex.getIssuedShares();
                    Long outstanding = ex.hasOutstandingShares() && ex.getOutstandingShares() > 0 ? ex.getOutstandingShares() : null;
                    if (issued > 0 || outstanding != null) {
                        long effectiveIssued = issued > 0 ? issued : outstanding;
                        double price = item.getBasic().getCurPrice();
                        double marketCap = ex.getIssuedMarketVal();
                        double outstandingMarketVal = ex.getOutstandingMarketVal();
                        BigDecimal peTtm = ex.hasPeTTMRate() && Double.isFinite(ex.getPeTTMRate())
                                ? BigDecimal.valueOf(ex.getPeTTMRate()) : null;
                        BigDecimal pb = ex.hasPbRate() && Double.isFinite(ex.getPbRate())
                                ? BigDecimal.valueOf(ex.getPbRate()) : null;
                        Instant quoteTime = item.getBasic().getUpdateTime().isBlank() ? null
                                : LocalDateTime.parse(item.getBasic().getUpdateTime(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                .atZone(ZoneId.of("America/New_York")).toInstant();
                        shares.put(symbol, new ShareSnapshot(effectiveIssued, outstanding,
                                Double.isFinite(price) && price > 0 ? BigDecimal.valueOf(price) : null,
                                Double.isFinite(marketCap) && marketCap > 0 ? BigDecimal.valueOf(marketCap) : null,
                                ex.hasOutstandingMarketVal() && Double.isFinite(outstandingMarketVal) && outstandingMarketVal > 0
                                        ? BigDecimal.valueOf(outstandingMarketVal) : null,
                                peTtm, pb, quoteTime));
                    }
                }
            }
            return Map.copyOf(shares);
        }

        public List<QotCommon.Rehab> rehab(String symbol) {
            rehabLimiter.acquire();
            QotRequestRehab.Response response = client.getRehab(symbol);
            if (response.getRetType() != 0)
                throw new IllegalStateException("FUTU_REQUEST_REHAB_FAILED:" + response.getRetType() + ":" + response.getErrCode() + ":" + response.getRetMsg());
            if (!response.hasS2C()) throw new IllegalStateException("FUTU_REHAB_DATA_MISSING");
            return response.getS2C().getRehabListList();
        }

        public QotGetValuationPlateStockList.S2C valuationPage(String plate, QotCommon.ValuationType metric, String cursor) {
            valuationLimiter.acquire();
            QotGetValuationPlateStockList.Response response = client.getValuationPage(plate, metric, cursor);
            if (response.getRetType() != 0)
                throw new IllegalStateException("FUTU_GET_VALUATION_PLATE_STOCK_LIST_FAILED:" + response.getRetType() + ":" + response.getErrCode() + ":" + response.getRetMsg());
            if (!response.hasS2C()) throw new IllegalStateException("FUTU_VALUATION_PAGE_MISSING");
            return response.getS2C();
        }

        public QotGetValuationDetail.S2C valuationDetail(String symbol, QotCommon.ValuationType metric,
                                                          QotCommon.ValuationIntervalType interval) {
            valuationLimiter.acquire();
            QotGetValuationDetail.Response response = client.getValuationDetail(symbol, metric, interval);
            if (response.getRetType() != 0)
                throw new IllegalStateException("FUTU_GET_VALUATION_DETAIL_FAILED:" + response.getRetType() + ":" + response.getErrCode() + ":" + response.getRetMsg());
            if (!response.hasS2C()) throw new IllegalStateException("FUTU_VALUATION_DETAIL_MISSING");
            return response.getS2C();
        }

        public Set<String> supportedUsStocks() {
            Set<String> supported = FutuService.this.supportedUsStocks(client, true);
            if (supported.isEmpty()) throw new IllegalStateException("FUTU_GET_STATIC_INFO_EMPTY");
            return supported;
        }

        public Set<String> supportedUsFinancialReportStocks() {
            Set<String> supported = FutuService.this.supportedUsFinancialReportStocks(client);
            if (supported.isEmpty()) throw new IllegalStateException("FUTU_GET_STATIC_INFO_EMPTY");
            return supported;
        }

        /** 未開通美股 OTC 行情時回傳 null 讓呼叫端跳過該股，其餘錯誤一律拋出。 */
        public QotGetFinancialsStatements.S2C financialStatements(String symbol, QotCommon.FinancialStatementsType statement,
                                                                  String cursor, int size) {
            valuationLimiter.acquire();
            QotGetFinancialsStatements.Response response = client.getFinancialStatements(symbol, statement, cursor, size);
            if (response.getRetType() != 0) {
                if (symbol.equals(unsupportedUsOtcSymbol(response.getRetMsg()))) {
                    log.warn("Financial statements unsupported | symbol={} statement={} reason={}", symbol, statement, response.getRetMsg());
                    return null;
                }
                throw new IllegalStateException("FUTU_GET_FINANCIALS_STATEMENTS_FAILED:" + response.getRetType() + ":" + response.getErrCode() + ":" + response.getRetMsg());
            }
            if (!response.hasS2C()) throw new IllegalStateException("FUTU_FINANCIAL_STATEMENTS_MISSING");
            return response.getS2C();
        }

        public QotGetEarningsCalendar.S2C earningsCalendar(LocalDate from, LocalDate through) {
            earningsLimiter.acquire();
            QotGetEarningsCalendar.Response response = client.getEarningsCalendar(from, through);
            if (response.getRetType() != 0)
                throw new IllegalStateException("FUTU_GET_EARNINGS_CALENDAR_FAILED:" + response.getRetType() + ":" + response.getErrCode() + ":" + response.getRetMsg());
            if (!response.hasS2C()) throw new IllegalStateException("FUTU_EARNINGS_WINDOW_MISSING");
            return response.getS2C();
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static String unsupportedUsOtcSymbol(String error) {
        if (error != null && error.startsWith(US_OTC_UNAVAILABLE_PREFIX) && error.endsWith(".")) {
            return error.substring(US_OTC_UNAVAILABLE_PREFIX.length(), error.length() - 1);
        }
        return null;
    }

    private static Runnable ownerPlateLimiter() {
        // 比官方每30秒10次稍保守，並讓同一Service的不同下載共用限流狀態。
        RateLimiter limiter = RateLimiter.create(10.0 / 32.0);
        return limiter::acquire;
    }
}
