package com.ykm.stock.analytics.service.enhancement;

import com.fasterxml.jackson.databind.JsonNode;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.OpenFigiClient;
import com.ykm.stock.analytics.client.OpenFigiClient.FigiMatch;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.Form4TransactionDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.InsiderSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.Institutional13fRowDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.Institutional13fSummaryDto;
import com.ykm.stock.analytics.model.entity.Sec13fHolding;
import com.ykm.stock.analytics.model.entity.SecForm4Transaction;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.repository.jpa.Sec13fHoldingRepository;
import com.ykm.stock.analytics.repository.jpa.SecForm4TransactionRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.stock.MassiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2.2 SEC Form 4 高管內部人買賣與 13F-HR 機構季度持倉解析服務（純官方 XML，0 AI 消耗）。
 * 關鍵防呆：
 * - Form 4 以 (accession, transaction_index) 去重，聯名申報不雙計金額與人數；精準區分 Code P 公開市場買入與 S/M/A/G/F/10b5-1 例行交易。
 * - 「高管逢低買進確認」嚴格要求 DEEP_UNDERVALUED / MODERATELY_UNDERVALUED 且近 60 日出現 >= 2 位不同高管/董事或合計 >= $100,000 之 Code P (A) 買入。
 * - 13F 多列依 (cusip, shareType, putCall) 聚合；映射依 13F shareClassFigi -> Massive CUSIP -> OpenFIGI 唯一核實（不做危險模糊名稱去後綴配對）；QoQ 變動於讀取時套用拆股對齊即時計算。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecInsiderAndInstitutionalService {
    public static final BigDecimal INSIDER_CONFIRM_MIN_USD = new BigDecimal("100000");
    public static final int INSIDER_CONFIRM_MIN_DISTINCT_INSIDERS = 2;
    public static final int INSIDER_LOOKBACK_DAYS = 60;
    private static final int FORM4_MAX_FILINGS_PER_SYNC = 25;
    private static final MathContext MC = MathContext.DECIMAL128;

    public record FlagshipManager(String cik, String name) {}

    public static final List<FlagshipManager> FLAGSHIP_13F_MANAGERS = List.of(
            new FlagshipManager("0001067983", "Berkshire Hathaway"),
            new FlagshipManager("0001350694", "Bridgewater Associates"),
            new FlagshipManager("0001037389", "Renaissance Technologies"),
            new FlagshipManager("0001423053", "Citadel Advisors"),
            new FlagshipManager("0001336528", "Pershing Square Capital"),
            new FlagshipManager("0001061768", "Baupost Group"),
            new FlagshipManager("0001536411", "Duquesne Family Office"),
            new FlagshipManager("0001167483", "Tiger Global Management"),
            new FlagshipManager("0001135730", "Coatue Management"),
            new FlagshipManager("0001656456", "Appaloosa Management"),
            new FlagshipManager("0001040273", "Third Point"),
            new FlagshipManager("0001079114", "Greenlight Capital"),
            new FlagshipManager("0001103804", "Viking Global Investors"),
            new FlagshipManager("0001029160", "Soros Fund Management"),
            new FlagshipManager("0001649339", "Scion Asset Management")
    );

    private final SecEdgarClient secEdgarClient;
    private final MassiveService massiveService;
    private final OpenFigiClient openFigiClient;
    private final SecForm4TransactionRepository form4Repository;
    private final Sec13fHoldingRepository holding13fRepository;
    private final StockRepository stockRepository;
    private final StockSplitRepository splitRepository;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactionTemplate;

    public record Form4SyncResult(String cik, int scannedFilings, int savedTransactions) {}

    public record Sync13fResult(int managersSynced, int filingsParsed, int holdingsSaved) {}

    /** 按需同步單股發行人近 120 日 SEC Form 4 申報明細（遵守 5 req/s 限流，不跳過具 CIK 之 ADR）。 */
    public Form4SyncResult syncForm4ForStock(Stock stock) {
        if (stock == null || SecurityTypePolicy.isEtfType(stock.getStockType())
                || stock.getCik() == null || stock.getCik().isBlank()) {
            return new Form4SyncResult(stock == null ? null : stock.getCik(), 0, 0);
        }
        String cik = SecEdgarClient.normalizeCik(stock.getCik());
        JsonNode submissions = secEdgarClient.fetchCompanySubmissions(cik);
        JsonNode recent = submissions.path("filings").path("recent");
        JsonNode forms = recent.path("form");
        JsonNode accessions = recent.path("accessionNumber");
        JsonNode filingDates = recent.path("filingDate");
        JsonNode acceptanceTimes = recent.path("acceptanceDateTime");
        JsonNode primaryDocs = recent.path("primaryDocument");

        if (!forms.isArray() || !accessions.isArray()) {
            return new Form4SyncResult(cik, 0, 0);
        }
        LocalDate cutoffDate = dateTimeService.now().atZone(com.ykm.stock.analytics.service.market.MarketStatusService.NY_ZONE)
                .toLocalDate().minusDays(120);
        List<Form4FilingMeta> candidates = new ArrayList<>();
        int count = Math.min(forms.size(), accessions.size());
        for (int i = 0; i < count; i++) {
            String form = forms.get(i).asText("").trim();
            if (!"4".equals(form) && !"4/A".equals(form)) {
                continue;
            }
            String filingDateStr = i < filingDates.size() ? filingDates.get(i).asText("").trim() : "";
            LocalDate filingDate = parseLocalDate(filingDateStr);
            if (filingDate == null || filingDate.isBefore(cutoffDate)) {
                continue;
            }
            String acc = accessions.get(i).asText("").trim();
            String doc = i < primaryDocs.size() ? primaryDocs.get(i).asText("").trim() : "";
            Instant accepted = i < acceptanceTimes.size() ? parseAcceptedInstant(acceptanceTimes.get(i).asText("").trim()) : null;
            if (!acc.isEmpty() && !doc.isEmpty()) {
                candidates.add(new Form4FilingMeta(acc, form, filingDate, accepted, SecEdgarClient.stripXsltPrefix(doc)));
            }
        }
        if (candidates.isEmpty()) {
            return new Form4SyncResult(cik, 0, 0);
        }

        Set<String> existingAccs = new HashSet<>(form4Repository.findExistingAccessions(
                cik, candidates.stream().map(Form4FilingMeta::accession).toList()));
        Instant now = dateTimeService.now();
        List<SecForm4Transaction> toPersist = new ArrayList<>();
        int scanned = 0;
        for (Form4FilingMeta meta : candidates) {
            if (existingAccs.contains(meta.accession())) {
                continue;
            }
            if (scanned >= FORM4_MAX_FILINGS_PER_SYNC) {
                break;
            }
            scanned++;
            try {
                String xml = secEdgarClient.fetchArchiveFile(cik, meta.accession(), meta.rawXmlFile());
                String filingUrl = secEdgarClient.buildArchiveUrl(cik, meta.accession(), meta.rawXmlFile());
                List<SecForm4Transaction> parsed = parseForm4Xml(stock.getFigi(), cik, stock.getSymbol(), meta, xml, filingUrl, now);
                toPersist.addAll(parsed);
            } catch (RuntimeException ex) {
                if (SecEdgarClient.isAuthFailure(ex)) {
                    throw ex;
                }
                log.warn("Form 4 XML fetch/parse skipped | cik={} accession={} reason={}", cik, meta.accession(), ex.getMessage());
            }
        }
        int saved = persistForm4Transactions(toPersist);
        return new Form4SyncResult(cik, scanned, saved);
    }

    public int persistForm4Transactions(List<SecForm4Transaction> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Integer saved = transactionTemplate.execute(status -> {
            int count = 0;
            for (SecForm4Transaction row : rows) {
                if (form4Repository.findByAccessionAndTransactionIndex(row.getAccession(), row.getTransactionIndex()).isEmpty()) {
                    form4Repository.save(row);
                    count++;
                }
            }
            return count;
        });
        return saved == null ? 0 : saved;
    }

    /** 純 XML 解析 Form 4 非衍生交易明細（公開供單元測試直接驗證 TC-01）。 */
    public List<SecForm4Transaction> parseForm4Xml(String figi, String cik, String fallbackSymbol,
                                                   Form4FilingMeta meta, String xmlContent,
                                                   String filingUrl, Instant observedAt) {
        Document doc = parseXmlSecurely(xmlContent);
        Element root = doc.getDocumentElement();
        if (root == null) {
            return List.of();
        }
        String issuerSymbol = firstTagText(root, "issuerTradingSymbol");
        String symbol = (issuerSymbol == null || issuerSymbol.isBlank()) ? fallbackSymbol : issuerSymbol.trim().toUpperCase(Locale.ROOT);
        Boolean aff10b5One = parseOptionalBoolean(firstTagText(root, "aff10b5One"));

        List<Map<String, Object>> owners = new ArrayList<>();
        String primaryOwnerCik = null;
        String primaryOwnerName = "UNKNOWN";
        String primaryOwnerTitle = null;
        boolean anyOfficer = false;
        boolean anyDirector = false;
        boolean anyTenPct = false;

        NodeList ownerNodes = root.getElementsByTagName("reportingOwner");
        for (int i = 0; i < ownerNodes.getLength(); i++) {
            if (!(ownerNodes.item(i) instanceof Element ownerEl)) continue;
            String ownerCik = firstTagText(ownerEl, "rptOwnerCik");
            String ownerName = firstTagText(ownerEl, "rptOwnerName");
            String ownerTitle = firstTagText(ownerEl, "officerTitle");
            boolean isOff = isTrueFlag(firstTagText(ownerEl, "isOfficer"));
            boolean isDir = isTrueFlag(firstTagText(ownerEl, "isDirector"));
            boolean isTen = isTrueFlag(firstTagText(ownerEl, "isTenPercentOwner"));
            anyOfficer = anyOfficer || isOff;
            anyDirector = anyDirector || isDir;
            anyTenPct = anyTenPct || isTen;
            if (i == 0) {
                primaryOwnerCik = ownerCik;
                if (ownerName != null && !ownerName.isBlank()) {
                    primaryOwnerName = ownerName.trim();
                }
                primaryOwnerTitle = ownerTitle;
            }
            Map<String, Object> ownerMap = new LinkedHashMap<>();
            if (ownerCik != null) ownerMap.put("cik", ownerCik.trim());
            ownerMap.put("name", ownerName == null ? "UNKNOWN" : ownerName.trim());
            if (ownerTitle != null && !ownerTitle.isBlank()) ownerMap.put("title", ownerTitle.trim());
            ownerMap.put("isOfficer", isOff);
            ownerMap.put("isDirector", isDir);
            ownerMap.put("isTenPercentOwner", isTen);
            owners.add(ownerMap);
        }

        NodeList txNodes = root.getElementsByTagName("nonDerivativeTransaction");
        List<SecForm4Transaction> result = new ArrayList<>();
        for (int idx = 0; idx < txNodes.getLength(); idx++) {
            if (!(txNodes.item(idx) instanceof Element txEl)) continue;
            String txCode = firstTagText(txEl, "transactionCode");
            if (txCode == null || txCode.isBlank()) {
                continue;
            }
            String securityTitle = nestedValueText(txEl, "securityTitle");
            LocalDate txDate = parseLocalDate(nestedValueText(txEl, "transactionDate"));
            String acqDisp = nestedValueText(txEl, "transactionAcquiredDisposedCode");
            BigDecimal shares = parseDecimal(nestedValueText(txEl, "transactionShares"));
            BigDecimal price = parseDecimal(nestedValueText(txEl, "transactionPricePerShare"));
            BigDecimal sharesAfter = parseDecimal(nestedValueText(txEl, "sharesOwnedFollowingTransaction"));
            BigDecimal valueUsd = (shares != null && price != null && shares.signum() > 0 && price.signum() > 0)
                    ? shares.multiply(price, MC).setScale(2, RoundingMode.HALF_UP) : null;

            SecForm4Transaction entity = new SecForm4Transaction();
            entity.setFigi(figi);
            entity.setCik(cik);
            entity.setSymbol(symbol);
            entity.setAccession(meta.accession());
            entity.setTransactionIndex(idx);
            entity.setFormType(meta.formType());
            entity.setFilingDate(meta.filingDate());
            entity.setAcceptedAt(meta.acceptedAt());
            entity.setTransactionDate(txDate != null ? txDate : meta.filingDate());
            entity.setSecurityTitle(truncate(securityTitle, 160));
            entity.setPrimaryOwnerCik(truncate(primaryOwnerCik, 16));
            entity.setPrimaryOwnerName(truncate(primaryOwnerName, 160));
            entity.setPrimaryOwnerTitle(truncate(primaryOwnerTitle, 160));
            entity.setReportingOwnerCount(Math.max(1, owners.size()));
            entity.setReportingOwnersJson(owners);
            entity.setOfficer(anyOfficer);
            entity.setDirector(anyDirector);
            entity.setTenPercentOwner(anyTenPct);
            entity.setTransactionCode(txCode.trim().toUpperCase(Locale.ROOT));
            entity.setAcquiredDisposed(acqDisp == null ? null : acqDisp.trim().toUpperCase(Locale.ROOT));
            entity.setAff10b5One(aff10b5One);
            entity.setShares(shares);
            entity.setPricePerShare(price);
            entity.setTransactionValueUsd(valueUsd);
            entity.setSharesOwnedAfter(sharesAfter);
            entity.setFilingUrl(filingUrl);
            entity.setObservedAt(observedAt);
            result.add(entity);
        }
        return result;
    }

    /** 唯讀評估單股截至 asOfDate 之內部人買賣摘要與「高管逢低買進確認」訊號。 */
    @Transactional(readOnly = true)
    public InsiderSummaryDto evaluateInsiders(Stock stock, LocalDate asOfDate, String valuationVerdict) {
        if (stock == null) {
            return InsiderSummaryDto.unavailable("INSUFFICIENT_DATA", "STOCK_MISSING", null);
        }
        if (SecurityTypePolicy.isEtfType(stock.getStockType())) {
            return InsiderSummaryDto.unavailable("NOT_APPLICABLE", "NOT_APPLICABLE_FOR_ETF", null);
        }
        if (stock.getCik() == null || stock.getCik().isBlank()) {
            return InsiderSummaryDto.unavailable("INSUFFICIENT_DATA", "SEC_CIK_MISSING", null);
        }
        String cik = SecEdgarClient.normalizeCik(stock.getCik());
        List<SecForm4Transaction> allRecent = form4Repository
                .findTop100ByCikAndFilingDateLessThanEqualOrderByTransactionDateDescFilingDateDescIdDesc(cik, asOfDate).stream()
                .filter(t -> t.getFilingDate() != null && !t.getFilingDate().isAfter(asOfDate))
                .toList();
        if (allRecent.isEmpty()) {
            return InsiderSummaryDto.unavailable("INSUFFICIENT_DATA", "NO_FORM4_FILINGS_SYNCED", cik);
        }
        LocalDate windowStart = asOfDate.minusDays(INSIDER_LOOKBACK_DAYS);
        // 60 日統計走完整交易日視窗查詢（並於 SQL 限制 filingDate <= asOfDate），避免高頻薪酬交易超過 100 筆時截斷漏算 Code P 買入
        List<SecForm4Transaction> windowTxs = form4Repository
                .findByCikAndTransactionDateBetweenAndFilingDateLessThanEqualOrderByTransactionDateDescFilingDateDescIdDesc(
                        cik, windowStart, asOfDate, asOfDate).stream()
                .filter(t -> t.getFilingDate() != null && !t.getFilingDate().isAfter(asOfDate))
                .toList();
        InsiderWindowStats stats = computeInsiderWindowStats(windowTxs, windowStart, asOfDate, valuationVerdict);
        List<Form4TransactionDto> txDtos = allRecent.stream().limit(30).map(this::toForm4Dto).toList();
        LocalDate latestFiling = allRecent.stream().map(SecForm4Transaction::getFilingDate).max(Comparator.naturalOrder()).orElse(null);

        return new InsiderSummaryDto(
                "AVAILABLE",
                null,
                cik,
                stats.undervaluedConfirm(),
                stats.codePBuyCount(),
                stats.distinctInsiders(),
                stats.codePTotalValueUsd(),
                stats.routineOrSaleCount(),
                latestFiling,
                txDtos
        );
    }

    /** 批次評估多檔股票之「高管逢低買進確認」標籤（供每日機會雷達首頁零 N+1 查詢使用）。 */
    @Transactional(readOnly = true)
    public Map<String, Boolean> evaluateInsiderConfirmBatch(Map<String, String> cikByFigi,
                                                            Map<String, String> verdictByFigi,
                                                            LocalDate asOfDate) {
        if (cikByFigi == null || cikByFigi.isEmpty() || asOfDate == null) {
            return Map.of();
        }
        Set<String> ciks = new LinkedHashSet<>();
        for (String cik : cikByFigi.values()) {
            if (cik != null && cik.matches("[0-9]{1,10}")) {
                ciks.add(SecEdgarClient.normalizeCik(cik));
            }
        }
        if (ciks.isEmpty()) {
            return Map.of();
        }
        LocalDate windowStart = asOfDate.minusDays(INSIDER_LOOKBACK_DAYS);
        List<SecForm4Transaction> codePBuys = form4Repository.findCodePBuysByCikInAndWindow(ciks, windowStart, asOfDate);
        Map<String, List<SecForm4Transaction>> byCik = new HashMap<>();
        for (SecForm4Transaction tx : codePBuys) {
            if (tx.getFilingDate() != null && !tx.getFilingDate().isAfter(asOfDate)) {
                byCik.computeIfAbsent(tx.getCik(), k -> new ArrayList<>()).add(tx);
            }
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : cikByFigi.entrySet()) {
            String figi = entry.getKey();
            String rawCik = entry.getValue();
            if (rawCik == null || !rawCik.matches("[0-9]{1,10}")) {
                result.put(figi, false);
                continue;
            }
            String normCik = SecEdgarClient.normalizeCik(rawCik);
            String verdict = verdictByFigi.get(figi);
            InsiderWindowStats stats = computeInsiderWindowStats(byCik.getOrDefault(normCik, List.of()), windowStart, asOfDate, verdict);
            result.put(figi, stats.undervaluedConfirm());
        }
        return result;
    }

    public record InsiderWindowStats(boolean undervaluedConfirm, int codePBuyCount, int distinctInsiders,
                                     BigDecimal codePTotalValueUsd, int routineOrSaleCount) {}

    /** 純邏輯計算 60 日內 Code P 買入與高管逢低買進確認（排除 4/A 疑義重複與例行薪酬/10b5-1 賣股）。 */
    public InsiderWindowStats computeInsiderWindowStats(List<SecForm4Transaction> txs,
                                                        LocalDate windowStart,
                                                        LocalDate asOfDate,
                                                        String valuationVerdict) {
        int codePCount = 0;
        int routineCount = 0;
        Set<String> distinctInsiders = new HashSet<>();
        BigDecimal totalUsd = BigDecimal.ZERO;
        boolean hasValue = false;

        for (SecForm4Transaction tx : txs) {
            if (tx == null || tx.getTransactionDate() == null) continue;
            if (tx.getTransactionDate().isBefore(windowStart) || tx.getTransactionDate().isAfter(asOfDate)) continue;
            if (tx.getFilingDate() != null && tx.getFilingDate().isAfter(asOfDate)) continue;

            boolean isCodePBuy = isQualifiedCodePBuy(tx);
            if (isCodePBuy) {
                codePCount++;
                // 僅計高管或董事且非 10b5-1 預定計劃之自發公開市場買入；聯合申報取第一位具備高管／董事身分者之單一識別，絕不將一筆聯合申報重複計為多人或歸給非高管信託
                if (!Boolean.TRUE.equals(tx.getAff10b5One())) {
                    String qualifyingKey = extractPrimaryQualifyingOfficerOrDirectorKey(tx);
                    if (qualifyingKey != null) {
                        distinctInsiders.add(qualifyingKey);
                        if (tx.getTransactionValueUsd() != null && tx.getTransactionValueUsd().signum() > 0) {
                            totalUsd = totalUsd.add(tx.getTransactionValueUsd(), MC);
                            hasValue = true;
                        }
                    }
                }
            } else if (isRoutineOrPlanTrade(tx)) {
                routineCount++;
            }
        }
        boolean undervalued = "DEEP_UNDERVALUED".equalsIgnoreCase(valuationVerdict)
                || "MODERATELY_UNDERVALUED".equalsIgnoreCase(valuationVerdict);
        boolean strongBuying = distinctInsiders.size() >= INSIDER_CONFIRM_MIN_DISTINCT_INSIDERS
                || (hasValue && totalUsd.compareTo(INSIDER_CONFIRM_MIN_USD) >= 0);
        boolean confirm = undervalued && strongBuying && !distinctInsiders.isEmpty();
        return new InsiderWindowStats(confirm, codePCount, distinctInsiders.size(), hasValue ? totalUsd.setScale(2, RoundingMode.HALF_UP) : null, routineCount);
    }

    private String extractPrimaryQualifyingOfficerOrDirectorKey(SecForm4Transaction tx) {
        List<Map<String, Object>> owners = tx.getReportingOwnersJson();
        if (owners != null && !owners.isEmpty()) {
            for (Map<String, Object> o : owners) {
                boolean isOff = Boolean.TRUE.equals(o.get("isOfficer"));
                boolean isDir = Boolean.TRUE.equals(o.get("isDirector"));
                if (!isOff && !isDir) continue;
                Object cikObj = o.get("cik");
                Object nameObj = o.get("name");
                String key = cikObj != null && !String.valueOf(cikObj).isBlank()
                        ? String.valueOf(cikObj).trim()
                        : (nameObj == null ? "" : String.valueOf(nameObj).trim().toUpperCase(Locale.ROOT));
                if (!key.isEmpty()) {
                    return key;
                }
            }
            return null;
        }
        if (tx.isOfficer() || tx.isDirector()) {
            return tx.getPrimaryOwnerCik() != null && !tx.getPrimaryOwnerCik().isBlank()
                    ? tx.getPrimaryOwnerCik() : tx.getPrimaryOwnerName().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    public static boolean isQualifiedCodePBuy(SecForm4Transaction tx) {
        return tx != null
                && "4".equalsIgnoreCase(tx.getFormType())
                && "P".equalsIgnoreCase(tx.getTransactionCode())
                && "A".equalsIgnoreCase(tx.getAcquiredDisposed());
    }

    public static boolean isRoutineOrPlanTrade(SecForm4Transaction tx) {
        if (tx == null) return false;
        String code = tx.getTransactionCode() == null ? "" : tx.getTransactionCode().toUpperCase(Locale.ROOT);
        if (Set.of("M", "A", "G", "F", "C", "W", "J").contains(code)) {
            return true;
        }
        return Boolean.TRUE.equals(tx.getAff10b5One()) && ("S".equals(code) || "P".equals(code));
    }

    private Form4TransactionDto toForm4Dto(SecForm4Transaction t) {
        return new Form4TransactionDto(
                t.getId(),
                t.getAccession(),
                t.getFormType(),
                t.getFilingDate(),
                t.getAcceptedAt(),
                t.getTransactionDate(),
                t.getSecurityTitle(),
                t.getPrimaryOwnerName(),
                t.getPrimaryOwnerTitle(),
                t.getReportingOwnerCount(),
                t.isOfficer(),
                t.isDirector(),
                t.isTenPercentOwner(),
                t.getTransactionCode(),
                t.getAcquiredDisposed(),
                t.getAff10b5One(),
                t.getShares(),
                t.getPricePerShare(),
                t.getTransactionValueUsd(),
                t.getSharesOwnedAfter(),
                isQualifiedCodePBuy(t),
                isRoutineOrPlanTrade(t),
                t.getFilingUrl()
        );
    }

    // ==================== 13F-HR 機構季度持倉 ====================

    /** 同步精選知名機構最近兩季 13F-HR 持倉（可限定最多同步家數供小範圍驗收）。 */
    public Sync13fResult syncFlagship13fManagers(int maxManagers) {
        int limit = maxManagers <= 0 ? FLAGSHIP_13F_MANAGERS.size() : Math.min(maxManagers, FLAGSHIP_13F_MANAGERS.size());
        Map<String, Stock> activeByFigi = new HashMap<>();
        Map<String, List<Stock>> activeByShareClass = new HashMap<>();
        for (Stock s : stockRepository.findAll()) {
            if (s.getActiveStatus() != ActiveStatus.ACTIVE) continue;
            activeByFigi.put(s.getFigi(), s);
            if (s.getShareClassFigi() != null && !s.getShareClassFigi().isBlank()) {
                activeByShareClass.computeIfAbsent(s.getShareClassFigi(), k -> new ArrayList<>()).add(s);
            }
        }
        Map<String, String> cusipToFigiCache = new HashMap<>();

        int managersSynced = 0;
        int filingsParsed = 0;
        int holdingsSaved = 0;
        for (int i = 0; i < limit; i++) {
            FlagshipManager manager = FLAGSHIP_13F_MANAGERS.get(i);
            try {
                int[] counts = syncSingleManager13f(manager, activeByFigi, activeByShareClass, cusipToFigiCache);
                managersSynced++;
                filingsParsed += counts[0];
                holdingsSaved += counts[1];
            } catch (RuntimeException ex) {
                log.warn("13F sync failed for manager | cik={} name={} reason={}", manager.cik(), manager.name(), ex.getMessage());
            }
        }
        log.info("13F flagship sync completed | managers={} filings={} holdings={}", managersSynced, filingsParsed, holdingsSaved);
        return new Sync13fResult(managersSynced, filingsParsed, holdingsSaved);
    }

    private int[] syncSingleManager13f(FlagshipManager manager,
                                       Map<String, Stock> activeByFigi,
                                       Map<String, List<Stock>> activeByShareClass,
                                       Map<String, String> cusipToFigiCache) {
        JsonNode submissions = secEdgarClient.fetchCompanySubmissions(manager.cik());
        JsonNode recent = submissions.path("filings").path("recent");
        JsonNode forms = recent.path("form");
        JsonNode accessions = recent.path("accessionNumber");
        JsonNode filingDates = recent.path("filingDate");
        JsonNode reportDates = recent.path("reportDate");
        if (!forms.isArray()) {
            return new int[]{0, 0};
        }

        // recent 陣列按申報日降序，為每個 reportPeriod 收集候選申報（同時支援 13F-HR/A 更正與 13F-HR 原申報，優先取最新具完整持股表者）
        Map<LocalDate, List<Filing13fMeta>> candidatesByPeriod = new LinkedHashMap<>();
        int count = Math.min(forms.size(), accessions.size());
        for (int i = 0; i < count; i++) {
            String form = forms.get(i).asText("").trim();
            if (!"13F-HR".equals(form) && !"13F-HR/A".equals(form)) {
                continue;
            }
            LocalDate filingDate = parseLocalDate(i < filingDates.size() ? filingDates.get(i).asText("") : "");
            LocalDate reportDate = parseLocalDate(i < reportDates.size() ? reportDates.get(i).asText("") : "");
            String acc = accessions.get(i).asText("").trim();
            if (filingDate == null || reportDate == null || acc.isEmpty()) {
                continue;
            }
            if (!candidatesByPeriod.containsKey(reportDate) && candidatesByPeriod.size() >= 2) {
                continue;
            }
            candidatesByPeriod.computeIfAbsent(reportDate, k -> new ArrayList<>())
                    .add(new Filing13fMeta(acc, form, filingDate, reportDate));
        }
        if (candidatesByPeriod.isEmpty()) {
            return new int[]{0, 0};
        }

        int filingsParsed = 0;
        int holdingsSaved = 0;
        Instant now = dateTimeService.now();
        for (Map.Entry<LocalDate, List<Filing13fMeta>> entry : candidatesByPeriod.entrySet()) {
            LocalDate reportPeriod = entry.getKey();
            List<Filing13fMeta> periodFilings = entry.getValue(); // 按申報日降序（最新在前）
            List<Sec13fHolding> existing = holding13fRepository.findByManagerCikAndReportPeriod(manager.cik(), reportPeriod);
            String latestCandidateAcc = periodFilings.getFirst().accession();
            if (!existing.isEmpty() && existing.stream().anyMatch(h -> latestCandidateAcc.equals(h.getAccession()))) {
                continue;
            }
            // 按 SEC Form 13F FAQ 58b–58c：依時間升序（基礎 13F-HR -> 後續 13F-HR/A）合成最終持股表；
            // RESTATEMENT 覆寫全表，NEW HOLDINGS 補充合併絕不刪除原本有效持股！
            List<Filing13fMeta> chronological = new ArrayList<>(periodFilings);
            chronological.sort(Comparator.comparing(Filing13fMeta::filingDate).thenComparing(Filing13fMeta::accession));
            Map<String, Sec13fHolding> synthesizedByKey = new LinkedHashMap<>();
            boolean hasFullBaseSnapshot = false;
            int parsedForPeriod = 0;
            for (Filing13fMeta filing : chronological) {
                String infoTableFile = locate13fInfoTableXml(manager.cik(), filing.accession());
                if (infoTableFile == null) {
                    continue;
                }
                String amendmentType = "13F-HR/A".equals(filing.formType())
                        ? resolve13fAmendmentType(manager.cik(), filing.accession())
                        : "ORIGINAL";
                if ("13F-HR/A".equals(filing.formType()) && amendmentType == null) {
                    // 無法確認更正類型時不冒險覆寫原持股
                    continue;
                }
                String xml = secEdgarClient.fetchArchiveFile(manager.cik(), filing.accession(), infoTableFile);
                List<Sec13fHolding> rows = parseAndAggregate13fXml(manager, filing, xml, now);
                if (rows.isEmpty()) {
                    continue;
                }
                if ("ORIGINAL".equals(amendmentType) || "RESTATEMENT".equals(amendmentType)) {
                    synthesizedByKey.clear();
                    hasFullBaseSnapshot = true;
                } else if (!hasFullBaseSnapshot) {
                    // 未取得完整基礎表前，絕不以補充型 NEW HOLDINGS 單獨覆寫整季持股
                    continue;
                }
                for (Sec13fHolding row : rows) {
                    String rowKey = row.getCusip() + "|" + row.getShareType() + "|" + row.getPutCall();
                    synthesizedByKey.put(rowKey, row);
                }
                parsedForPeriod++;
            }
            if (hasFullBaseSnapshot && !synthesizedByKey.isEmpty()) {
                List<Sec13fHolding> finalRows = new ArrayList<>(synthesizedByKey.values());
                resolve13fFigis(finalRows, reportPeriod, activeByFigi, activeByShareClass, cusipToFigiCache);
                replaceManagerPeriodHoldings(manager.cik(), reportPeriod, finalRows);
                filingsParsed += parsedForPeriod;
                holdingsSaved += finalRows.size();
            }
        }
        return new int[]{filingsParsed, holdingsSaved};
    }

    private String resolve13fAmendmentType(String managerCik, String accession) {
        try {
            String primaryXml = secEdgarClient.fetchArchiveFile(managerCik, accession, "primary_doc.xml");
            Document doc = parseXmlSecurely(primaryXml);
            String type = firstTagText(doc.getDocumentElement(), "amendmentType");
            if (type == null || type.isBlank()) {
                return null;
            }
            String upper = type.trim().toUpperCase(Locale.ROOT);
            if (upper.contains("RESTATEMENT")) return "RESTATEMENT";
            if (upper.contains("NEW HOLDINGS")) return "NEW_HOLDINGS";
            return null;
        } catch (RuntimeException ex) {
            if (SecEdgarClient.isAuthFailure(ex)) {
                throw ex;
            }
            return null;
        }
    }

    private String locate13fInfoTableXml(String managerCik, String accession) {
        JsonNode indexJson = secEdgarClient.fetchFilingIndexJson(managerCik, accession);
        JsonNode items = indexJson.path("directory").path("item");
        if (!items.isArray()) {
            return null;
        }
        String fallbackXml = null;
        long largestSize = -1L;
        for (JsonNode item : items) {
            String name = item.path("name").asText("").trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".xml") || "primary_doc.xml".equals(lower) || lower.contains("xsl")) {
                continue;
            }
            if (lower.contains("infotable") || lower.contains("form13f")) {
                return name;
            }
            long size = item.path("size").asLong(0L);
            if (size > largestSize) {
                largestSize = size;
                fallbackXml = name;
            }
        }
        return fallbackXml;
    }

    /** 解析 13F Information Table XML 並按 (cusip, shareType, putCall) 聚合多列持倉（公開供測試驗證）。 */
    public List<Sec13fHolding> parseAndAggregate13fXml(FlagshipManager manager, Filing13fMeta filing,
                                                      String xmlContent, Instant observedAt) {
        Document doc = parseXmlSecurely(xmlContent);
        NodeList rows = doc.getElementsByTagNameNS("*", "infoTable");
        if (rows.getLength() == 0) {
            rows = doc.getElementsByTagName("infoTable");
        }
        Map<String, Sec13fHolding> aggregated = new LinkedHashMap<>();
        for (int i = 0; i < rows.getLength(); i++) {
            if (!(rows.item(i) instanceof Element el)) continue;
            String cusip = firstLocalNameText(el, "cusip");
            String issuerName = firstLocalNameText(el, "nameOfIssuer");
            if (cusip == null || cusip.isBlank() || issuerName == null || issuerName.isBlank()) {
                continue;
            }
            String normCusip = cusip.trim().toUpperCase(Locale.ROOT);
            String titleOfClass = firstLocalNameText(el, "titleOfClass");
            String filingFigi = firstLocalNameText(el, "figi");
            BigDecimal valueUsd = parseDecimal(firstLocalNameText(el, "value"));
            BigDecimal shares = parseDecimal(firstLocalNameText(el, "sshPrnamt"));
            String shareType = firstLocalNameText(el, "sshPrnamtType");
            String rawPutCall = firstLocalNameText(el, "putCall");
            if (shares == null || shares.signum() <= 0 || valueUsd == null || valueUsd.signum() < 0) {
                continue;
            }
            String normShareType = (shareType == null || shareType.isBlank()) ? "SH" : shareType.trim().toUpperCase(Locale.ROOT);
            String normPutCall = Sec13fHolding.PUT_CALL_NONE;
            if (rawPutCall != null && !rawPutCall.isBlank()) {
                String pc = rawPutCall.trim().toUpperCase(Locale.ROOT);
                if (pc.startsWith("C")) normPutCall = Sec13fHolding.PUT_CALL_CALL;
                else if (pc.startsWith("P")) normPutCall = Sec13fHolding.PUT_CALL_PUT;
            }
            String key = normCusip + "|" + normShareType + "|" + normPutCall;
            Sec13fHolding existing = aggregated.get(key);
            if (existing == null) {
                Sec13fHolding created = new Sec13fHolding();
                created.setManagerCik(manager.cik());
                created.setManagerName(manager.name());
                created.setReportPeriod(filing.reportPeriod());
                created.setFilingDate(filing.filingDate());
                created.setAccession(filing.accession());
                created.setFormType(filing.formType());
                created.setCusip(truncate(normCusip, 12));
                created.setIssuerName(truncate(issuerName.trim(), 255));
                created.setTitleOfClass(truncate(titleOfClass, 128));
                created.setFilingFigi(truncate(filingFigi, 32));
                created.setShareType(truncate(normShareType, 8));
                created.setPutCall(normPutCall);
                created.setShares(shares.setScale(4, RoundingMode.HALF_UP));
                created.setMarketValueUsd(valueUsd.setScale(2, RoundingMode.HALF_UP));
                created.setObservedAt(observedAt);
                aggregated.put(key, created);
            } else {
                existing.setShares(existing.getShares().add(shares, MC).setScale(4, RoundingMode.HALF_UP));
                existing.setMarketValueUsd(existing.getMarketValueUsd().add(valueUsd, MC).setScale(2, RoundingMode.HALF_UP));
                if (existing.getFilingFigi() == null && filingFigi != null && !filingFigi.isBlank()) {
                    existing.setFilingFigi(truncate(filingFigi.trim(), 32));
                }
            }
        }
        return List.copyOf(aggregated.values());
    }

    private void resolve13fFigis(List<Sec13fHolding> holdings,
                                  LocalDate reportPeriod,
                                  Map<String, Stock> activeByFigi,
                                  Map<String, List<Stock>> activeByShareClass,
                                  Map<String, String> cusipToFigiCache) {
        List<String> unresolvedCusips = new ArrayList<>();
        for (Sec13fHolding h : holdings) {
            // 1. 優先以 13F 原始 shareClassFigi 精準比對本地主檔
            if (h.getFilingFigi() != null && !h.getFilingFigi().isBlank()) {
                List<Stock> matches = activeByShareClass.get(h.getFilingFigi());
                if (matches != null && matches.size() == 1) {
                    Stock matched = matches.getFirst();
                    h.setFigi(matched.getFigi());
                    h.setSymbol(matched.getSymbol());
                    cusipToFigiCache.putIfAbsent(h.getCusip(), matched.getFigi());
                    continue;
                }
            }
            if (cusipToFigiCache.containsKey(h.getCusip())) {
                String cachedFigi = cusipToFigiCache.get(h.getCusip());
                if (cachedFigi != null) {
                    Stock matched = activeByFigi.get(cachedFigi);
                    if (matched != null) {
                        h.setFigi(matched.getFigi());
                        h.setSymbol(matched.getSymbol());
                    }
                }
                continue;
            }
            unresolvedCusips.add(h.getCusip());
        }

        // 2. 優先用 OpenFIGI 免費批次映射（每次 10 筆，避免對 13F 數百筆 CUSIP 逐筆耗盡 Massive 限流），剩餘未解少數再以 Massive CUSIP 定點補查
        List<String> distinctUnresolved = unresolvedCusips.stream().distinct().toList();
        if (!distinctUnresolved.isEmpty()) {
            // 透過免費 OpenFIGI 10 筆/批完整映射未解 CUSIP（不人為截斷至 80 檔，杜絕未映射持股誤報為退出）
            List<String> allUnresolvedCusips = holdings.stream()
                    .filter(h -> h.getFigi() == null && !cusipToFigiCache.containsKey(h.getCusip()))
                    .sorted(Comparator.comparing(Sec13fHolding::getMarketValueUsd, Comparator.reverseOrder()))
                    .map(Sec13fHolding::getCusip)
                    .distinct()
                    .toList();
            Map<String, FigiMatch> openFigiMap = openFigiClient.mapUsCusips(allUnresolvedCusips);
            int massiveLookups = 0;
            for (String cusip : allUnresolvedCusips) {
                FigiMatch m = openFigiMap.get(cusip);
                String resolvedFigi = null;
                if (m != null && m.compositeFigi() != null && activeByFigi.containsKey(m.compositeFigi())) {
                    resolvedFigi = m.compositeFigi();
                } else if (m != null && m.shareClassFigi() != null) {
                    List<Stock> bySc = activeByShareClass.get(m.shareClassFigi());
                    if (bySc != null && bySc.size() == 1) {
                        resolvedFigi = bySc.getFirst().getFigi();
                    }
                } else if (massiveService.isConfigured() && massiveLookups < 20) {
                    massiveLookups++;
                    try {
                        List<MassiveTickerDetails> mMatches = massiveService.getTickersByCusip(cusip, reportPeriod);
                        List<MassiveTickerDetails> valid = mMatches.stream()
                                .filter(t -> t.getCompositeFigi() != null && activeByFigi.containsKey(t.getCompositeFigi()))
                                .toList();
                        if (valid.size() == 1) {
                            resolvedFigi = valid.getFirst().getCompositeFigi();
                        }
                    } catch (RuntimeException ex) {
                        log.debug("Massive CUSIP lookup skipped | cusip={} reason={}", cusip, ex.getMessage());
                    }
                }
                cusipToFigiCache.put(cusip, resolvedFigi);
            }
            for (Sec13fHolding h : holdings) {
                if (h.getFigi() != null) continue;
                String resolvedFigi = cusipToFigiCache.get(h.getCusip());
                if (resolvedFigi != null) {
                    Stock matched = activeByFigi.get(resolvedFigi);
                    if (matched != null) {
                        h.setFigi(matched.getFigi());
                        h.setSymbol(matched.getSymbol());
                    }
                }
            }
        }
    }

    public void replaceManagerPeriodHoldings(String managerCik, LocalDate reportPeriod, List<Sec13fHolding> rows) {
        transactionTemplate.executeWithoutResult(status -> {
            holding13fRepository.deleteByManagerCikAndReportPeriod(managerCik, reportPeriod);
            holding13fRepository.flush();
            if (rows != null && !rows.isEmpty()) {
                holding13fRepository.saveAll(rows);
            }
        });
    }

    /** 唯讀評估單股在精選機構 13F 之最新季度持倉與拆股對齊後 QoQ 增減持動向。 */
    @Transactional(readOnly = true)
    public Institutional13fSummaryDto evaluate13fForStock(Stock stock, LocalDate asOfDate) {
        if (stock == null || asOfDate == null) {
            return Institutional13fSummaryDto.unavailable("INSUFFICIENT_DATA", "STOCK_OR_DATE_MISSING");
        }
        List<Sec13fHolding> all = holding13fRepository.findByFigiAndReportPeriodLessThanEqualOrderByReportPeriodDescMarketValueUsdDesc(
                stock.getFigi(), asOfDate).stream()
                .filter(h -> h.getFilingDate() != null && !h.getFilingDate().isAfter(asOfDate))
                .toList();
        if (all.isEmpty()) {
            return Institutional13fSummaryDto.unavailable("INSUFFICIENT_DATA", "NO_13F_HOLDINGS_MATCHED");
        }
        List<StockSplit> splits = splitRepository.findByFigiOrderByExecutionDateAsc(stock.getFigi());

        // 按 managerCik + putCall 分組比對最新兩季
        Map<String, List<Sec13fHolding>> byManagerKey = new LinkedHashMap<>();
        for (Sec13fHolding h : all) {
            if (!"SH".equalsIgnoreCase(h.getShareType())) continue;
            byManagerKey.computeIfAbsent(h.getManagerCik() + "|" + h.getPutCall(), k -> new ArrayList<>()).add(h);
        }

        List<Institutional13fRowDto> rows = new ArrayList<>();
        int holdingCount = 0;
        int addOrNewCount = 0;
        int reduceOrExitCount = 0;

        for (List<Sec13fHolding> series : byManagerKey.values()) {
            series.sort(Comparator.comparing(Sec13fHolding::getReportPeriod).reversed());
            Sec13fHolding current = series.getFirst();
            // 檢查該機構截至 asOfDate 已公開申報（filingDate <= asOfDate）之最新報告期是否跨過 current.reportPeriod
            List<LocalDate> managerPeriods = holding13fRepository.findDistinctReportPeriodsByManagerCikVisibleAt(current.getManagerCik(), asOfDate);
            List<LocalDate> visibleManagerPeriods = managerPeriods.stream()
                    .filter(d -> d != null && !d.isAfter(asOfDate))
                    .toList();
            LocalDate managerLatest = visibleManagerPeriods.isEmpty() ? current.getReportPeriod() : visibleManagerPeriods.getFirst();

            if (managerLatest.isAfter(current.getReportPeriod())) {
                // 僅在「緊鄰上一季仍持有、最新已公開季消失」時標註為當季退出（NOT_LISTED）；更早清倉或中間缺季均跳過不誤報
                boolean exitedInLatestQuarter = visibleManagerPeriods.size() >= 2
                        && current.getReportPeriod().equals(visibleManagerPeriods.get(1))
                        && isImmediatelyPrecedingQuarter(current.getReportPeriod(), managerLatest);
                if (!exitedInLatestQuarter) {
                    continue;
                }
                // 若最新季原表仍以相同 CUSIP 持有（僅因 FIGI 未映射），絕不誤報為 NOT_LISTED / -100% 減持
                boolean stillPresentByCusip = holding13fRepository.existsByManagerCikAndReportPeriodAndCusipAndShareTypeAndPutCall(
                        current.getManagerCik(), managerLatest, current.getCusip(), current.getShareType(), current.getPutCall());
                if (!stillPresentByCusip) {
                    rows.add(new Institutional13fRowDto(
                            current.getManagerCik(),
                            current.getManagerName(),
                            managerLatest,
                            current.getFilingDate(),
                            current.getFormType(),
                            current.getCusip(),
                            current.getShareType(),
                            current.getPutCall(),
                            BigDecimal.ZERO,
                            current.getShares(),
                            BigDecimal.ONE.negate(),
                            BigDecimal.ZERO,
                            "NOT_LISTED"
                    ));
                    reduceOrExitCount++;
                    continue;
                }
            }

            holdingCount++;
            Sec13fHolding prev = series.size() >= 2 ? series.get(1) : null;
            BigDecimal alignedPrevShares = null;
            BigDecimal changePct = null;
            String action;
            if (prev == null) {
                boolean hasEarlierManagerQuarter = visibleManagerPeriods.stream().anyMatch(d -> d.isBefore(current.getReportPeriod()));
                action = hasEarlierManagerQuarter ? "NEW" : "HOLDING";
                if ("NEW".equals(action)) addOrNewCount++;
            } else {
                BigDecimal splitFactor = computeSplitMultiplierBetween(splits, prev.getReportPeriod(), current.getReportPeriod());
                alignedPrevShares = prev.getShares().multiply(splitFactor, MC).setScale(4, RoundingMode.HALF_UP);
                if (alignedPrevShares.signum() > 0) {
                    changePct = current.getShares().subtract(alignedPrevShares, MC)
                            .divide(alignedPrevShares, 4, RoundingMode.HALF_UP);
                    if (changePct.compareTo(new BigDecimal("0.01")) >= 0) {
                        action = "ADD";
                        addOrNewCount++;
                    } else if (changePct.compareTo(new BigDecimal("-0.01")) <= 0) {
                        action = "REDUCE";
                        reduceOrExitCount++;
                    } else {
                        action = "UNCHANGED";
                    }
                } else {
                    action = "NEW";
                    addOrNewCount++;
                }
            }
            rows.add(new Institutional13fRowDto(
                    current.getManagerCik(),
                    current.getManagerName(),
                    current.getReportPeriod(),
                    current.getFilingDate(),
                    current.getFormType(),
                    current.getCusip(),
                    current.getShareType(),
                    current.getPutCall(),
                    current.getShares(),
                    alignedPrevShares,
                    changePct,
                    current.getMarketValueUsd(),
                    action
            ));
        }
        if (rows.isEmpty()) {
            return Institutional13fSummaryDto.unavailable("INSUFFICIENT_DATA", "NO_13F_HOLDINGS_MATCHED");
        }
        LocalDate latestPeriod = rows.stream()
                .map(Institutional13fRowDto::reportPeriod)
                .filter(d -> d != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        rows.sort(Comparator.comparing(Institutional13fRowDto::marketValueUsd, Comparator.nullsLast(Comparator.reverseOrder())));
        return new Institutional13fSummaryDto(
                "AVAILABLE",
                null,
                latestPeriod,
                holdingCount,
                addOrNewCount,
                reduceOrExitCount,
                rows
        );
    }

    private BigDecimal computeSplitMultiplierBetween(List<StockSplit> splits, LocalDate prevPeriod, LocalDate currentPeriod) {
        if (splits == null || splits.isEmpty() || prevPeriod == null || currentPeriod == null) {
            return BigDecimal.ONE;
        }
        BigDecimal factor = BigDecimal.ONE;
        for (StockSplit sp : splits) {
            if (sp.getExecutionDate() == null || !sp.getExecutionDate().isAfter(prevPeriod) || sp.getExecutionDate().isAfter(currentPeriod)) {
                continue;
            }
            if (sp.getSplitFrom() != null && sp.getSplitTo() != null
                    && sp.getSplitFrom().signum() > 0 && sp.getSplitTo().signum() > 0) {
                factor = factor.multiply(sp.getSplitTo().divide(sp.getSplitFrom(), MC), MC);
            }
        }
        return factor;
    }

    private static boolean isImmediatelyPrecedingQuarter(LocalDate prevPeriod, LocalDate latestPeriod) {
        if (prevPeriod == null || latestPeriod == null || !latestPeriod.isAfter(prevPeriod)) {
            return false;
        }
        return java.time.YearMonth.from(prevPeriod).plusMonths(3).equals(java.time.YearMonth.from(latestPeriod));
    }

    public record Form4FilingMeta(String accession, String formType, LocalDate filingDate, Instant acceptedAt, String rawXmlFile) {}

    public record Filing13fMeta(String accession, String formType, LocalDate filingDate, LocalDate reportPeriod) {}

    private Document parseXmlSecurely(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception ex) {
            throw new IllegalStateException("SEC_XML_PARSE_FAILED:" + ex.getMessage(), ex);
        }
    }

    private static String firstTagText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() == 0) return null;
        String text = list.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private static String firstLocalNameText(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS("*", localName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName(localName);
        }
        if (list.getLength() == 0) return null;
        String text = list.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private static String nestedValueText(Element parent, String wrapperTag) {
        NodeList list = parent.getElementsByTagName(wrapperTag);
        if (list.getLength() == 0) return null;
        Node node = list.item(0);
        if (node instanceof Element el) {
            String val = firstTagText(el, "value");
            if (val != null && !val.isBlank()) return val;
        }
        String text = node.getTextContent();
        return text == null ? null : text.trim();
    }

    private static boolean isTrueFlag(String raw) {
        if (raw == null) return false;
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        return "1".equals(norm) || "true".equals(norm);
    }

    private static Boolean parseOptionalBoolean(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(norm) || "true".equals(norm)) return Boolean.TRUE;
        if ("0".equals(norm) || "false".equals(norm)) return Boolean.FALSE;
        return null;
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseLocalDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim().substring(0, Math.min(10, raw.trim().length())));
        } catch (Exception ex) {
            return null;
        }
    }

    private static Instant parseAcceptedInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
