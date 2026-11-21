package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ShortInterestSummaryDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockShortInterest;
import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockShortInterestRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import com.ykm.stock.analytics.service.stock.MassiveService.MassiveShortInterestRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 2.3 做空比例（Short Interest）與回補天數（Days to Cover）同步與實戰判讀服務。
 * 資料來源以既有 Massive Starter 所含官方 FINRA 雙週結算資料為主（零額外訂閱）。
 * 口徑防呆：
 * - Short % of Shares Outstanding 嚴格僅在「單一股類且已核實流通股數」時計算；多股類全發行人股數與未核實 ADR 流通 ADS 股數攔截不硬除，仍保留原始做空股數與 DTC。
 * - ETF 呈現原始做空股數與 DTC，不套用公司基本面 WEAKENING／STRONG 組合判定。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShortInterestService {
    public static final BigDecimal HIGH_SHORT_PCT_THRESHOLD = new BigDecimal("0.15");
    public static final String SIGNAL_SHORT_TRAP_WARNING = "SHORT_TRAP_WARNING";
    public static final String SIGNAL_SHORT_SQUEEZE_POTENTIAL = "SHORT_SQUEEZE_POTENTIAL";
    public static final String SIGNAL_ELEVATED_SHORT_INTEREST = "ELEVATED_SHORT_INTEREST";
    public static final String SIGNAL_NORMAL = "NORMAL";
    public static final String SIGNAL_ETF_INFO_ONLY = "ETF_INFO_ONLY";
    public static final String CONFIG_KEY_MARKET_SYNCED_DATE = "short_interest_market_synced_date";

    private static final MathContext MC = MathContext.DECIMAL128;

    private final MassiveService massiveService;
    private final StockRepository stockRepository;
    private final StockShortInterestRepository shortInterestRepository;
    private final StockShareObservationRepository shareObservationRepository;
    private final StockFinancialPeriodRepository financialPeriodRepository;
    private final StockQuoteRepository quoteRepository;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactionTemplate;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private PortfolioSysConfigRepository sysConfigRepository;

    public record ShortSyncResult(int fetchedRows, int savedRows, LocalDate latestSettlementDate) {}

    /** 雙週全市場增量同步（逐頁至 next_url 耗盡，以既有 Massive Starter FINRA 官方結算資料為主）。 */
    public ShortSyncResult syncMarketBatch() {
        LocalDate marketWatermark = readMarketSyncedWatermark();
        if (!massiveService.isConfigured()) {
            log.warn("Short interest batch sync skipped: MASSIVE_API_KEY not configured");
            return new ShortSyncResult(0, 0, marketWatermark);
        }
        // 有重疊回看 21 天以涵蓋 FINRA 修訂，首次同步拉最近 45 天涵蓋最新 2–3 期結算日。
        LocalDate from = marketWatermark != null
                ? marketWatermark.minusDays(21)
                : dateTimeService.now().atZone(com.ykm.stock.analytics.service.market.MarketStatusService.NY_ZONE).toLocalDate().minusDays(45);
        List<MassiveShortInterestRow> fetched = massiveService.getShortInterestFrom(from);
        if (fetched.isEmpty()) {
            return new ShortSyncResult(0, 0, marketWatermark);
        }
        int saved = persistMarketBatchRows(fetched, StockShortInterest.SOURCE_MASSIVE_FINRA, true);
        LocalDate latestAfter = readMarketSyncedWatermark();
        if (latestAfter == null) {
            latestAfter = shortInterestRepository.findLatestSettlementDate().orElse(marketWatermark);
        }
        log.info("Short interest batch sync completed | from={} fetched={} saved={} latestSettlementDate={}",
                from, fetched.size(), saved, latestAfter);
        return new ShortSyncResult(fetched.size(), saved, latestAfter);
    }

    /** 單股按需刷新最近 4 期雙週做空餘額。 */
    public ShortSyncResult refreshStock(Stock stock) {
        if (stock == null || stock.getSymbol() == null || stock.getSymbol().isBlank()) {
            return new ShortSyncResult(0, 0, null);
        }
        if (!massiveService.isConfigured()) {
            throw new IllegalStateException("MASSIVE_API_KEY_REQUIRED");
        }
        List<MassiveShortInterestRow> rows = massiveService.getShortInterestForTicker(stock.getSymbol(), 4);
        int saved = persistSingleStockRows(stock, rows, StockShortInterest.SOURCE_MASSIVE_FINRA);
        LocalDate latest = rows.stream().map(MassiveShortInterestRow::settlementDate).max(Comparator.naturalOrder()).orElse(null);
        return new ShortSyncResult(rows.size(), saved, latest);
    }

    public int persistRows(List<MassiveShortInterestRow> rows, String source) {
        return persistMarketBatchRows(rows, source, false);
    }

    private int persistMarketBatchRows(List<MassiveShortInterestRow> rows, String source, boolean advanceWatermark) {
        Integer saved = transactionTemplate.execute(txStatus -> {
            List<Stock> activeStocks = stockRepository.findAll().stream()
                    .filter(s -> s.getActiveStatus() == ActiveStatus.ACTIVE && s.getSymbol() != null)
                    .toList();
            Map<String, List<Stock>> bySymbol = new LinkedHashMap<>();
            for (Stock s : activeStocks) {
                bySymbol.computeIfAbsent(s.getSymbol().toUpperCase(), k -> new ArrayList<>()).add(s);
            }
            Instant now = dateTimeService.now();
            List<StockShortInterest> batch = new ArrayList<>();
            LocalDate persistedLatest = null;
            for (MassiveShortInterestRow row : rows) {
                if (row == null || row.ticker() == null || row.settlementDate() == null || row.shortInterest() == null) {
                    continue;
                }
                List<Stock> matched = bySymbol.get(row.ticker().toUpperCase());
                if (matched == null || matched.size() != 1) {
                    continue;
                }
                Stock stock = matched.getFirst();
                batch.add(toEntity(stock, row, source, now));
                if (persistedLatest == null || row.settlementDate().isAfter(persistedLatest)) {
                    persistedLatest = row.settlementDate();
                }
            }
            if (!batch.isEmpty()) {
                shortInterestRepository.saveAll(batch);
                if (advanceWatermark && persistedLatest != null) {
                    advanceMarketSyncedWatermarkInCurrentTx(persistedLatest);
                }
            }
            return batch.size();
        });
        return saved == null ? 0 : saved;
    }

    private LocalDate readMarketSyncedWatermark() {
        if (jobConfigRepository != null) {
            LocalDate watermark = jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.SHORT_INTEREST_SYNC).orElse(null);
            if (watermark != null) {
                return watermark;
            }
            return PortfolioSysConfigRepository.migrateLegacyWatermark(
                    sysConfigRepository, jobConfigRepository, CONFIG_KEY_MARKET_SYNCED_DATE, TaskCode.SHORT_INTEREST_SYNC);
        }
        LocalDate legacy = PortfolioSysConfigRepository.resolveDateConfig(sysConfigRepository, CONFIG_KEY_MARKET_SYNCED_DATE, null);
        if (legacy != null) {
            return legacy;
        }
        return shortInterestRepository.findLatestSettlementDate().orElse(null);
    }

    private void advanceMarketSyncedWatermarkInCurrentTx(LocalDate candidate) {
        if (candidate == null) {
            return;
        }
        if (jobConfigRepository != null) {
            JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.SHORT_INTEREST_SYNC, candidate);
            return;
        }
        if (sysConfigRepository == null) {
            return;
        }
        List<SysConfig> matches = sysConfigRepository.findByConfigKeyInOrderByIdAsc(List.of(CONFIG_KEY_MARKET_SYNCED_DATE));
        if (matches == null || matches.isEmpty()) {
            SysConfig created = new SysConfig();
            created.setConfigKey(CONFIG_KEY_MARKET_SYNCED_DATE);
            created.setConfigValue(candidate.toString());
            created.setDescription("全市場做空比例批次同步已成功提交的最新 FINRA 結算日（不受單股刷新影響）");
            created.setEnabled(true);
            sysConfigRepository.save(created);
            return;
        }
        SysConfig primary = matches.getFirst();
        LocalDate existing = PortfolioSysConfigRepository.resolveDateConfig(sysConfigRepository, CONFIG_KEY_MARKET_SYNCED_DATE, null);
        if (existing == null || candidate.isAfter(existing)) {
            primary.setConfigValue(candidate.toString());
            primary.setEnabled(true);
            sysConfigRepository.save(primary);
        }
    }

    public int persistSingleStockRows(Stock stock, List<MassiveShortInterestRow> rows, String source) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        Integer saved = transactionTemplate.execute(txStatus -> {
            Instant now = dateTimeService.now();
            List<StockShortInterest> entities = new ArrayList<>();
            for (MassiveShortInterestRow row : rows) {
                if (row == null || row.settlementDate() == null || row.shortInterest() == null) {
                    continue;
                }
                entities.add(toEntity(stock, row, source, now));
            }
            if (!entities.isEmpty()) {
                shortInterestRepository.saveAll(entities);
            }
            return entities.size();
        });
        return saved == null ? 0 : saved;
    }

    private StockShortInterest toEntity(Stock stock, MassiveShortInterestRow row, String source, Instant observedAt) {
        StockShortInterest existing = shortInterestRepository
                .findById(new com.ykm.stock.analytics.model.entity.key.StockShortInterestId(stock.getFigi(), row.settlementDate()))
                .orElse(null);
        BigDecimal scaledDtc = row.daysToCover() == null ? null : row.daysToCover().setScale(4, RoundingMode.HALF_UP);
        // 21 天重疊重抓時，若同一結算日數值未變更則保留首次 observedAt，絕不覆寫歷史首次可見時間戳
        if (existing != null
                && java.util.Objects.equals(existing.getShortInterestShares(), row.shortInterest())
                && java.util.Objects.equals(existing.getAvgDailyVolumeSource(), row.avgDailyVolume())
                && ((existing.getDaysToCoverSource() == null && scaledDtc == null)
                || (existing.getDaysToCoverSource() != null && scaledDtc != null
                && existing.getDaysToCoverSource().compareTo(scaledDtc) == 0))) {
            return existing;
        }
        StockShortInterest entity = existing != null ? existing : new StockShortInterest();
        entity.setFigi(stock.getFigi());
        entity.setSettlementDate(row.settlementDate());
        entity.setSymbol(stock.getSymbol());
        entity.setShortInterestShares(row.shortInterest());
        entity.setAvgDailyVolumeSource(row.avgDailyVolume());
        entity.setDaysToCoverSource(scaledDtc);
        entity.setSource(source);
        entity.setObservedAt(observedAt);
        return entity;
    }

    /** 唯讀評估單股截至 asOfDate 之做空比例、20 日均量回補天數與空頭地雷／軋空動能訊號。 */
    @Transactional(readOnly = true)
    public ShortInterestSummaryDto evaluateStock(Stock stock, LocalDate asOfDate, String fundamentalBacking, boolean resistanceBreakout) {
        if (stock == null || asOfDate == null) {
            return ShortInterestSummaryDto.unavailable("INSUFFICIENT_DATA", "STOCK_OR_DATE_MISSING");
        }
        List<StockShortInterest> rawHistory = shortInterestRepository.findTop6ByFigiAndSettlementDateLessThanEqualOrderBySettlementDateDesc(
                stock.getFigi(), asOfDate);
        // 歷史回看（asOfDate 早於最新已收市日）嚴格過濾掉當時尚未公布／尚未觀察到的 FINRA 結算資料（消除前視偏差）
        LocalDate latestClosed = calendar.getLastClosedTradingDay(dateTimeService.now());
        boolean historicalQuery = latestClosed != null && asOfDate.isBefore(latestClosed);
        Instant asOfCutoff = historicalQuery ? calendar.getSessionCloseAt(asOfDate) : null;
        List<StockShortInterest> history = rawHistory == null ? List.of() : rawHistory.stream()
                .filter(row -> !historicalQuery
                        || (row.getObservedAt() != null && asOfCutoff != null && !row.getObservedAt().isAfter(asOfCutoff)))
                .toList();
        if (history.isEmpty()) {
            return ShortInterestSummaryDto.unavailable("INSUFFICIENT_DATA", "SHORT_INTEREST_NOT_SYNCED");
        }
        StockShortInterest latest = history.getFirst();
        StockShortInterest prior = history.size() >= 2 ? history.get(1) : null;
        BigDecimal changePct = null;
        if (prior != null && prior.getShortInterestShares() != null && prior.getShortInterestShares() > 0 && latest.getShortInterestShares() != null) {
            changePct = BigDecimal.valueOf(latest.getShortInterestShares() - prior.getShortInterestShares())
                    .divide(BigDecimal.valueOf(prior.getShortInterestShares()), 4, RoundingMode.HALF_UP);
        }

        boolean isEtf = SecurityTypePolicy.isEtfType(stock.getStockType());
        boolean isAdr = SecurityTypePolicy.isAdrType(stock.getStockType());
        boolean multiClass = false;
        if (!isEtf && !isAdr && stock.getCik() != null && !stock.getCik().isBlank()) {
            Set<String> multi = stockRepository.findMultiSecurityIssuers(List.of(stock.getCik()), ActiveStatus.ACTIVE);
            multiClass = multi.contains(stock.getCik());
        }

        BigDecimal shortPct = null;
        String shortPctStatus;
        String shortPctReason = null;
        if (isEtf) {
            shortPctStatus = "NOT_APPLICABLE";
            shortPctReason = "NOT_APPLICABLE_FOR_ETF";
        } else if (isAdr) {
            // ADR 單一換股比例無法單獨證明市場上已發行之美國 ADS 總數，維持安全攔截不硬除。
            shortPctStatus = "NOT_APPLICABLE";
            shortPctReason = "ADR_ADS_FLOAT_UNVERIFIED";
        } else if (multiClass) {
            shortPctStatus = "NOT_APPLICABLE";
            shortPctReason = "MULTI_CLASS_SHARE_DENOMINATOR_MISMATCH";
        } else {
            BigDecimal verifiedShares = resolveVerifiedSingleClassShares(stock.getFigi(), latest.getSettlementDate());
            if (verifiedShares == null || verifiedShares.signum() <= 0) {
                shortPctStatus = "INSUFFICIENT_DATA";
                shortPctReason = "OUTSTANDING_SHARES_UNAVAILABLE";
            } else {
                shortPct = BigDecimal.valueOf(latest.getShortInterestShares()).divide(verifiedShares, 6, RoundingMode.HALF_UP);
                shortPctStatus = "AVAILABLE";
            }
        }

        // 以結算日為錨點取最近 20 個已收市日成交量計算本地 20 日均量回補天數
        LocalDate volAnchor = latest.getSettlementDate().isBefore(asOfDate) ? latest.getSettlementDate() : asOfDate;
        List<StockQuote> quotes = quoteRepository.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(
                stock.getFigi(), volAnchor.minusDays(45), volAnchor);
        BigDecimal dtc20d = null;
        LocalDate avgVolAsOf = null;
        if (quotes != null && quotes.size() >= 20) {
            List<StockQuote> tail = quotes.subList(quotes.size() - 20, quotes.size());
            BigDecimal sumVol = BigDecimal.ZERO;
            boolean allValid = true;
            for (StockQuote q : tail) {
                if (q.getVolume() == null || q.getVolume().signum() <= 0) {
                    allValid = false;
                    break;
                }
                sumVol = sumVol.add(q.getVolume(), MC);
            }
            if (allValid && sumVol.signum() > 0) {
                BigDecimal avg20 = sumVol.divide(BigDecimal.valueOf(20), MC);
                dtc20d = BigDecimal.valueOf(latest.getShortInterestShares()).divide(avg20, 2, RoundingMode.HALF_UP);
                avgVolAsOf = tail.getLast().getQuoteDate();
            }
        }

        String signalCode;
        if (isEtf) {
            signalCode = SIGNAL_ETF_INFO_ONLY;
        } else if (shortPct != null && shortPct.compareTo(HIGH_SHORT_PCT_THRESHOLD) >= 0) {
            if ("WEAKENING".equalsIgnoreCase(fundamentalBacking)) {
                signalCode = SIGNAL_SHORT_TRAP_WARNING;
            } else if ("STRONG".equalsIgnoreCase(fundamentalBacking) && resistanceBreakout) {
                signalCode = SIGNAL_SHORT_SQUEEZE_POTENTIAL;
            } else {
                signalCode = SIGNAL_ELEVATED_SHORT_INTEREST;
            }
        } else {
            signalCode = SIGNAL_NORMAL;
        }

        return new ShortInterestSummaryDto(
                "AVAILABLE",
                null,
                latest.getSettlementDate(),
                latest.getShortInterestShares(),
                shortPct,
                shortPctStatus,
                shortPctReason,
                latest.getAvgDailyVolumeSource(),
                latest.getDaysToCoverSource(),
                dtc20d,
                avgVolAsOf,
                changePct,
                signalCode,
                latest.getSource(),
                latest.getObservedAt()
        );
    }

    private BigDecimal resolveVerifiedSingleClassShares(String figi, LocalDate settlementDate) {
        // 嚴格以結算日當日收盤時刻為截斷點，禁止取用未來股數快照或未來財季（消除增發／拆股歷史前視偏差）
        Instant settlementCutoff = settlementDate != null ? calendar.getSessionCloseAt(settlementDate) : dateTimeService.now();
        List<StockShareObservation> obs = shareObservationRepository.findLatestAt(List.of(figi), settlementCutoff);
        if (obs != null && !obs.isEmpty() && obs.getFirst().effectiveShares() > 0L) {
            return BigDecimal.valueOf(obs.getFirst().effectiveShares());
        }
        List<StockFinancialPeriod> periods = financialPeriodRepository.findByFigiOrderByPeriodEndAsc(figi);
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        for (int i = periods.size() - 1; i >= 0; i--) {
            StockFinancialPeriod p = periods.get(i);
            boolean hasAnyAvailabilityStamp = p.getSharesOutstandingAvailableAt() != null || p.getSharesSecAvailableFromDate() != null;
            boolean availableAtSettlement = hasAnyAvailabilityStamp
                    && (p.getSharesOutstandingAvailableAt() == null
                    || !p.getSharesOutstandingAvailableAt().isAfter(settlementCutoff))
                    && (p.getSharesSecAvailableFromDate() == null
                    || !p.getSharesSecAvailableFromDate().isAfter(settlementDate));
            if (p.getPeriodEnd() != null && !p.getPeriodEnd().isAfter(settlementDate)
                    && availableAtSettlement
                    && p.getSharesOutstanding() != null && p.getSharesOutstanding().signum() > 0) {
                return p.getSharesOutstanding();
            }
        }
        return null;
    }
}
