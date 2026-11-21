package com.ykm.stock.analytics.service.market;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.FredFxClient;
import com.ykm.stock.analytics.client.FredFxClient.RatePoint;
import com.ykm.stock.analytics.client.FredFxClient.SeriesSpec;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.FxRateDaily;
import com.ykm.stock.analytics.repository.jpa.FxRateDailyRepository;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 官方外幣匯率同步與批次預載查詢服務。
 * 統一以「1 單位財報原幣折合美元（usdPerUnit）」換算；批次估值時一次預載日期區間避免 N+1 查詢。
 */
@Service
@Slf4j
public class FxRateService {
    public static final String CURRENCY_USD = "USD";
    private static final long INCREMENTAL_OVERLAP_DAYS = 21L;

    private final FxRateDailyRepository fxRates;
    private final FredFxClient fredFxClient;
    private final DateTimeService dateTimeService;
    private final JdbcTemplate jdbcTemplate;
    private final long maxStaleDays;
    @Autowired(required = false)
    private TransactionTemplate transactionTemplate;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private PortfolioSysConfigRepository sysConfigRepository;

    public FxRateService(
            FxRateDailyRepository fxRates,
            FredFxClient fredFxClient,
            DateTimeService dateTimeService,
            long maxStaleDays) {
        this(fxRates, fredFxClient, dateTimeService, (JdbcTemplate) null, maxStaleDays);
    }

    @Autowired
    public FxRateService(
            FxRateDailyRepository fxRates,
            FredFxClient fredFxClient,
            DateTimeService dateTimeService,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            @Value("${app.fx.max-stale-days:14}") long maxStaleDays) {
        this(fxRates, fredFxClient, dateTimeService,
                jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable(),
                maxStaleDays);
    }

    private FxRateService(
            FxRateDailyRepository fxRates,
            FredFxClient fredFxClient,
            DateTimeService dateTimeService,
            JdbcTemplate jdbcTemplate,
            long maxStaleDays) {
        this.fxRates = fxRates;
        this.fredFxClient = fredFxClient;
        this.dateTimeService = dateTimeService;
        this.jdbcTemplate = jdbcTemplate;
        this.maxStaleDays = Math.max(1L, maxStaleDays);
    }

    public record FxResolution(
            boolean converted,
            BigDecimal usdPerUnit,
            String reportingCurrency,
            String targetCurrency,
            LocalDate rateDate,
            String source,
            String status,
            ValuationReason reason
    ) {
        public boolean isUsable() {
            return MetricStatus.AVAILABLE.name().equals(status) && usdPerUnit != null && usdPerUnit.signum() > 0;
        }

        public static FxResolution identity(String currency) {
            return new FxResolution(false, BigDecimal.ONE, currency, currency, null, null,
                    MetricStatus.AVAILABLE.name(), null);
        }

        public static FxResolution converted(BigDecimal rate, String reportingCurrency, String targetCurrency,
                                             LocalDate rateDate, String source) {
            return new FxResolution(true, rate, reportingCurrency, targetCurrency, rateDate, source,
                    MetricStatus.AVAILABLE.name(), null);
        }

        public static FxResolution unavailable(String reportingCurrency, String targetCurrency, ValuationReason reason) {
            ValuationReason effective = reason != null ? reason : ValuationReason.FX_RATE_UNAVAILABLE;
            return new FxResolution(false, null, reportingCurrency, targetCurrency, null, null,
                    effective.statusName(), effective);
        }
    }

    /** 批次預載至記憶體的日頻匯率查詢器，支援 O(log N) floorEntry 查值且零資料庫 I/O。 */
    public static final class FxRateLookup {
        public static final FxRateLookup EMPTY = new FxRateLookup(Map.of(), 14L);

        private final Map<String, NavigableMap<LocalDate, FxRateDaily>> byCurrency;
        private final long maxStaleDays;

        public FxRateLookup(Map<String, NavigableMap<LocalDate, FxRateDaily>> byCurrency, long maxStaleDays) {
            this.byCurrency = byCurrency == null ? Map.of() : byCurrency;
            this.maxStaleDays = Math.max(1L, maxStaleDays);
        }

        public FxResolution resolve(String reportingCurrency, String quoteCurrency, LocalDate valuationDate) {
            String rep = normalizeCurrency(reportingCurrency);
            String quote = normalizeCurrency(quoteCurrency);
            if (rep == null || quote == null) {
                return FxResolution.unavailable(rep, quote, ValuationReason.REPORTING_CURRENCY_MISMATCH);
            }
            if (rep.equals(quote)) {
                return FxResolution.identity(quote);
            }
            if (!CURRENCY_USD.equals(quote)) {
                return FxResolution.unavailable(rep, quote, ValuationReason.REPORTING_CURRENCY_MISMATCH);
            }
            if (valuationDate == null) {
                return FxResolution.unavailable(rep, quote, ValuationReason.FX_RATE_UNAVAILABLE);
            }
            NavigableMap<LocalDate, FxRateDaily> series = byCurrency.get(rep);
            if (series == null || series.isEmpty()) {
                return FxResolution.unavailable(rep, quote, ValuationReason.FX_RATE_UNAVAILABLE);
            }
            Map.Entry<LocalDate, FxRateDaily> floor = series.floorEntry(valuationDate);
            if (floor == null || floor.getValue() == null
                    || floor.getValue().getUsdPerUnit() == null
                    || floor.getValue().getUsdPerUnit().signum() <= 0) {
                return FxResolution.unavailable(rep, quote, ValuationReason.FX_RATE_UNAVAILABLE);
            }
            long gapDays = ChronoUnit.DAYS.between(floor.getKey(), valuationDate);
            if (gapDays > maxStaleDays) {
                return FxResolution.unavailable(rep, quote, ValuationReason.FX_RATE_STALE);
            }
            FxRateDaily rate = floor.getValue();
            return FxResolution.converted(rate.getUsdPerUnit(), rep, quote, rate.getRateDate(), rate.getSource());
        }
    }

    /** 批次載入多幣別在 [fromDate - maxStaleDays, throughDate] 區間的匯率，消除 N+1 查詢。 */
    @Transactional(readOnly = true)
    public FxRateLookup preload(Collection<String> currencies, LocalDate fromDate, LocalDate throughDate) {
        if (currencies == null || currencies.isEmpty() || fromDate == null || throughDate == null || fromDate.isAfter(throughDate)) {
            return FxRateLookup.EMPTY;
        }
        Set<String> nonUsd = new LinkedHashSet<>();
        for (String raw : currencies) {
            String norm = normalizeCurrency(raw);
            if (norm != null && !CURRENCY_USD.equals(norm)) {
                nonUsd.add(norm);
            }
        }
        if (nonUsd.isEmpty()) {
            return FxRateLookup.EMPTY;
        }
        LocalDate queryFrom = fromDate.minusDays(maxStaleDays);
        List<FxRateDaily> rows = fxRates.findByCurrenciesBetween(nonUsd, queryFrom, throughDate);
        Map<String, NavigableMap<LocalDate, FxRateDaily>> grouped = new HashMap<>();
        if (rows != null) {
            for (FxRateDaily row : rows) {
                if (row == null || row.getCurrency() == null || row.getRateDate() == null) {
                    continue;
                }
                grouped.computeIfAbsent(row.getCurrency().toUpperCase(Locale.ROOT), ignored -> new TreeMap<>())
                        .put(row.getRateDate(), row);
            }
        }
        // 對區間視窗內於 fromDate 當日或之前完全無資料的幣別，批次補查視窗前最後一筆，確保批次與單查對過期匯率一致回傳 FX_RATE_STALE
        Set<String> missingPrior = new LinkedHashSet<>();
        for (String ccy : nonUsd) {
            NavigableMap<LocalDate, FxRateDaily> series = grouped.get(ccy);
            if (series == null || series.floorEntry(fromDate) == null) {
                missingPrior.add(ccy);
            }
        }
        if (!missingPrior.isEmpty()) {
            List<FxRateDaily> priorRows = fxRates.findLatestBeforeByCurrencies(missingPrior, queryFrom);
            if (priorRows != null) {
                for (FxRateDaily row : priorRows) {
                    if (row == null || row.getCurrency() == null || row.getRateDate() == null) {
                        continue;
                    }
                    grouped.computeIfAbsent(row.getCurrency().toUpperCase(Locale.ROOT), ignored -> new TreeMap<>())
                            .put(row.getRateDate(), row);
                }
            }
        }
        return new FxRateLookup(grouped, maxStaleDays);
    }

    /** 單筆查詢指定日期之適用匯率（供單點計算或即時工具使用）。 */
    @Transactional(readOnly = true)
    public FxResolution resolveSingle(String reportingCurrency, String quoteCurrency, LocalDate valuationDate) {
        String rep = normalizeCurrency(reportingCurrency);
        String quote = normalizeCurrency(quoteCurrency);
        if (rep == null || quote == null) {
            return FxResolution.unavailable(rep, quote, ValuationReason.REPORTING_CURRENCY_MISMATCH);
        }
        if (rep.equals(quote)) {
            return FxResolution.identity(quote);
        }
        if (!CURRENCY_USD.equals(quote) || valuationDate == null) {
            return FxResolution.unavailable(rep, quote, ValuationReason.REPORTING_CURRENCY_MISMATCH);
        }
        FxRateDaily row = fxRates.findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(rep, valuationDate)
                .orElse(null);
        if (row == null || row.getUsdPerUnit() == null || row.getUsdPerUnit().signum() <= 0) {
            return FxResolution.unavailable(rep, quote, ValuationReason.FX_RATE_UNAVAILABLE);
        }
        long gapDays = ChronoUnit.DAYS.between(row.getRateDate(), valuationDate);
        if (gapDays > maxStaleDays) {
            return FxResolution.unavailable(rep, quote, ValuationReason.FX_RATE_STALE);
        }
        return FxResolution.converted(row.getUsdPerUnit(), rep, quote, row.getRateDate(), row.getSource());
    }

    /** 同步所有支援幣別之 FRED H.10 官方日頻匯率（無水位時自 fx_sync_start_date 全量回補，有水位時往回重疊 21 天增量同步）。 */
    public int syncFredRates(Collection<String> targetCurrencies, LocalDate explicitFromDate, LocalDate explicitThroughDate) {
        if (!fredFxClient.isConfigured()) {
            log.warn("FRED FX sync skipped: fred.api.api-key is not configured");
            return 0;
        }
        Instant now = dateTimeService.now();
        LocalDate throughDate = explicitThroughDate != null
                ? explicitThroughDate : now.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        boolean fullSync = (targetCurrencies == null || targetCurrencies.isEmpty())
                && explicitFromDate == null && explicitThroughDate == null;
        LocalDate backfillStart = PortfolioSysConfigRepository.resolveFxSyncStartDate(sysConfigRepository);
        LocalDate jobWatermark = (fullSync && jobConfigRepository != null)
                ? jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.FX_RATE_DAILY_SYNC).orElse(null)
                : null;
        List<SeriesSpec> specs = resolveSpecs(targetCurrencies);
        int totalSaved = 0;
        boolean anySeriesEmpty = false;
        LocalDate minCurrencyLatest = null;
        for (SeriesSpec spec : specs) {
            LocalDate start = explicitFromDate;
            if (start == null) {
                LocalDate currencyLatest = fxRates.findLatestRateDate(spec.currency()).orElse(null);
                if (fullSync && jobConfigRepository != null) {
                    if (jobWatermark == null || currencyLatest == null) {
                        start = backfillStart;
                    } else {
                        LocalDate effectiveBase = currencyLatest.isBefore(jobWatermark) ? currencyLatest : jobWatermark;
                        LocalDate overlapFrom = effectiveBase.minusDays(INCREMENTAL_OVERLAP_DAYS);
                        start = overlapFrom.isBefore(backfillStart) ? backfillStart : overlapFrom;
                    }
                } else {
                    start = currencyLatest == null ? backfillStart : currencyLatest.minusDays(INCREMENTAL_OVERLAP_DAYS);
                }
            }
            if (start.isAfter(throughDate)) {
                anySeriesEmpty = true;
                continue;
            }
            List<RatePoint> points = fredFxClient.fetchSeries(spec, start, throughDate);
            if (points.isEmpty()) {
                anySeriesEmpty = true;
                continue;
            }
            if (transactionTemplate != null) {
                transactionTemplate.executeWithoutResult(tx -> upsertRatePoints(points, now));
            } else {
                upsertRatePoints(points, now);
            }
            LocalDate seriesLatest = null;
            for (RatePoint point : points) {
                if (point != null && point.rateDate() != null
                        && (seriesLatest == null || point.rateDate().isAfter(seriesLatest))) {
                    seriesLatest = point.rateDate();
                }
            }
            if (seriesLatest == null) {
                anySeriesEmpty = true;
            } else if (minCurrencyLatest == null || seriesLatest.isBefore(minCurrencyLatest)) {
                minCurrencyLatest = seriesLatest;
            }
            totalSaved += points.size();
        }
        if (fullSync && !anySeriesEmpty && totalSaved > 0 && minCurrencyLatest != null) {
            JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.FX_RATE_DAILY_SYNC, minCurrencyLatest);
        }
        log.info("FRED FX daily sync completed | currencies={} savedRows={} watermarkCandidate={} anySeriesEmpty={}",
                specs.size(), totalSaved, minCurrencyLatest, anySeriesEmpty);
        return totalSaved;
    }

    private void upsertRatePoints(List<RatePoint> points, Instant now) {
        if (jdbcTemplate != null) {
            Timestamp ts = Timestamp.from(now);
            jdbcTemplate.batchUpdate("""
                    insert into fx_rate_daily (currency, rate_date, usd_per_unit, source, series_id, observed_at)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (currency, rate_date) do update set
                        usd_per_unit = excluded.usd_per_unit,
                        source = excluded.source,
                        series_id = excluded.series_id,
                        observed_at = excluded.observed_at
                    """, points, 500, (ps, point) -> {
                ps.setString(1, point.currency());
                ps.setDate(2, Date.valueOf(point.rateDate()));
                ps.setBigDecimal(3, point.usdPerUnit());
                ps.setString(4, FredFxClient.SOURCE_FRED);
                ps.setString(5, point.seriesId());
                ps.setTimestamp(6, ts);
            });
            return;
        }
        List<FxRateDaily> entities = new ArrayList<>(points.size());
        for (RatePoint point : points) {
            entities.add(new FxRateDaily(
                    point.currency(),
                    point.rateDate(),
                    point.usdPerUnit(),
                    FredFxClient.SOURCE_FRED,
                    point.seriesId(),
                    now
            ));
        }
        fxRates.saveAll(entities);
        fxRates.flush();
    }

    private List<SeriesSpec> resolveSpecs(Collection<String> targetCurrencies) {
        if (targetCurrencies == null || targetCurrencies.isEmpty()) {
            return List.copyOf(FredFxClient.SUPPORTED_SERIES.values());
        }
        List<SeriesSpec> selected = new ArrayList<>();
        for (String raw : targetCurrencies) {
            String norm = normalizeCurrency(raw);
            if (norm != null && FredFxClient.SUPPORTED_SERIES.containsKey(norm)) {
                selected.add(FredFxClient.SUPPORTED_SERIES.get(norm));
            }
        }
        return selected;
    }

    public static String normalizeCurrency(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
