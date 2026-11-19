package com.ykm.stock.analytics.service.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.repository.jpa.ExternalApiDailyQuotaRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 基於 Alpha Vantage EARNINGS_ESTIMATES 的美股年度共識預測按需同步服務。
 * 嚴格遵守免費層配額護欄（發送前原子持久化每日配額與熔斷狀態防止重啟或空回應超支、1 req/sec 請求起始節流、
 * 有界鎖等待避免阻塞其他個股報告、多日 DB 快取、401/403/429 與每日額度耗盡即熔斷不重試），
 * 並要求來自財報或 SEC 金額事實之明確 USD 證據。
 */
@Service
@Slf4j
public class AnnualEarningsForecastSyncService {
    public static final String SOURCE_ALPHAVANTAGE = "ALPHAVANTAGE";
    private static final String PERIOD_TYPE_ANNUAL = "ANNUAL";
    private static final String CURRENCY_USD = "USD";
    private static final long PAST_PERIOD_GRACE_DAYS = 7L;
    private static final double FISCAL_YEAR_SHIFT_TOLERANCE_DAYS = 21.0d;
    private static final long LOCK_WAIT_MILLIS = 2000L;

    private final StockRepository stocks;
    private final StockFinancialPeriodRepository financialPeriods;
    private final FinancialReportRepository financialReports;
    private final SecFinancialFactRepository secFacts;
    private final StockEarningsForecastRepository forecasts;
    private final ExternalApiDailyQuotaRepository dailyQuotas;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Function<String, String> payloadFetcher;
    private final boolean enabled;
    private final String apiKey;
    private final int dailyLimit;
    private final int cacheDays;
    private final long minIntervalMs;

    private final ReentrantLock pacingLock = new ReentrantLock();
    private final Set<String> inFlightFigis = ConcurrentHashMap.newKeySet();
    private final Map<String, LocalDate> attemptedDateByFigi = new ConcurrentHashMap<>();
    private volatile LocalDate quotaExhaustedDate;
    private long lastCallNanos;

    @Autowired
    public AnnualEarningsForecastSyncService(
            StockRepository stocks,
            StockFinancialPeriodRepository financialPeriods,
            FinancialReportRepository financialReports,
            SecFinancialFactRepository secFacts,
            StockEarningsForecastRepository forecasts,
            ExternalApiDailyQuotaRepository dailyQuotas,
            DateTimeService dateTimeService,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            @Value("${alphavantage.base-url:https://www.alphavantage.co}") String baseUrl,
            @Value("${alphavantage.apiKey:}") String apiKey,
            @Value("${alphavantage.earnings-estimates.enabled:true}") boolean enabled,
            @Value("${alphavantage.earnings-estimates.daily-limit:20}") int dailyLimit,
            @Value("${alphavantage.earnings-estimates.cache-days:7}") int cacheDays,
            @Value("${alphavantage.earnings-estimates.min-interval-ms:1200}") long minIntervalMs,
            @Value("${alphavantage.earnings-estimates.timeout-seconds:15}") int timeoutSeconds) {
        this(stocks, financialPeriods, financialReports, secFacts, forecasts, dailyQuotas,
                dateTimeService, transactions, objectMapper,
                buildHttpFetcher(baseUrl, apiKey, timeoutSeconds),
                enabled, apiKey, dailyLimit, cacheDays, minIntervalMs);
    }

    AnnualEarningsForecastSyncService(
            StockRepository stocks,
            StockFinancialPeriodRepository financialPeriods,
            FinancialReportRepository financialReports,
            SecFinancialFactRepository secFacts,
            StockEarningsForecastRepository forecasts,
            ExternalApiDailyQuotaRepository dailyQuotas,
            DateTimeService dateTimeService,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Function<String, String> payloadFetcher,
            boolean enabled,
            String apiKey,
            int dailyLimit,
            int cacheDays,
            long minIntervalMs) {
        this.stocks = stocks;
        this.financialPeriods = financialPeriods;
        this.financialReports = financialReports;
        this.secFacts = secFacts;
        this.forecasts = forecasts;
        this.dailyQuotas = dailyQuotas;
        this.dateTimeService = dateTimeService;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.payloadFetcher = payloadFetcher;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.dailyLimit = Math.max(0, dailyLimit);
        this.cacheDays = Math.max(1, cacheDays);
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    private static Function<String, String> buildHttpFetcher(String baseUrl, String apiKey, int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(1, timeoutSeconds) * 1000;
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        return avSymbol -> client.get()
                .uri(uriBuilder -> uriBuilder.path("/query")
                        .queryParam("function", "EARNINGS_ESTIMATES")
                        .queryParam("symbol", avSymbol)
                        .queryParam("apikey", trimmedKey)
                        .build())
                .retrieve()
                .body(String.class);
    }

    /**
     * 查詢最新報告前按需確保目標股已有近期年度共識預測（多日快取＋持久化每日配額護欄）；
     * 任何配額耗盡、忙碌逾時或外部／寫入失敗皆記錄告警並優雅返回，絕不阻斷主分析報告。
     */
    public boolean ensureFreshAnnualForecast(String figi) {
        if (!enabled || apiKey.isBlank() || dailyLimit <= 0 || figi == null || figi.isBlank()) {
            return false;
        }
        LocalDate initialDay = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        if (initialDay.equals(quotaExhaustedDate) || initialDay.equals(attemptedDateByFigi.get(figi))) {
            return false;
        }

        Stock stock = stocks.findById(figi).orElse(null);
        // 不快取停追／非活躍資格失敗，確保恢復追蹤後同日立即生效
        if (!isEligibleStock(stock)) {
            return false;
        }
        if (!hasAuthoritativeUsdReportingCurrency(stock)) {
            attemptedDateByFigi.put(figi, initialDay);
            return false;
        }

        LocalDate initialCacheFloor = initialDay.minusDays(cacheDays - 1L);
        if (forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(
                figi, SOURCE_ALPHAVANTAGE, PERIOD_TYPE_ANNUAL, initialCacheFloor)) {
            attemptedDateByFigi.put(figi, initialDay);
            return false;
        }

        if (!inFlightFigis.add(figi)) {
            return false;
        }
        try {
            // 先載入財季分母，確保節流放行後立刻發送外部 HTTP，不在節流與發送之間穿插 DB 查詢
            List<StockFinancialPeriod> periods = financialPeriods.findByFigiOrderByPeriodEndAsc(figi);
            LocalDate quotaDay = reserveQuotaSlotAndPace(figi);
            if (quotaDay == null) {
                return false;
            }

            String avSymbol = toAlphaVantageSymbol(stock.getSymbol());
            String body;
            try {
                body = payloadFetcher.apply(avSymbol);
            } catch (HttpStatusCodeException ex) {
                HttpStatusCode code = ex.getStatusCode();
                int rawCode = code.value();
                if (rawCode == 401 || rawCode == 403 || rawCode == 429) {
                    markDailyQuotaExhausted(quotaDay);
                }
                log.warn("AlphaVantage annual forecast request rejected | figi={} symbol={} status={}",
                        figi, stock.getSymbol(), rawCode);
                return false;
            } catch (RuntimeException ex) {
                log.warn("AlphaVantage annual forecast request failed | figi={} symbol={} error={}",
                        figi, stock.getSymbol(), ex.getClass().getSimpleName());
                return false;
            }

            Instant observedAt = dateTimeService.now();
            LocalDate observationDateNy = observedAt.atZone(MarketStatusService.NY_ZONE).toLocalDate();
            ParseOutcome outcome = parseAnnualEstimates(stock, body, observationDateNy, observedAt, periods);
            if (outcome.dailyQuotaExhausted()) {
                markDailyQuotaExhausted(quotaDay);
                log.warn("AlphaVantage daily quota exhausted; pausing until next NY day | figi={} symbol={}",
                        figi, stock.getSymbol());
                return false;
            }
            if (outcome.burstRateLimited()) {
                log.warn("AlphaVantage per-second burst throttle encountered | figi={} symbol={}",
                        figi, stock.getSymbol());
                return false;
            }
            if (outcome.rows().isEmpty()) {
                attemptedDateByFigi.put(figi, observationDateNy);
                return false;
            }

            try {
                transactions.executeWithoutResult(status -> forecasts.saveAllAndFlush(outcome.rows()));
            } catch (RuntimeException ex) {
                log.warn("AlphaVantage annual forecast persist failed | figi={} symbol={} error={}",
                        figi, stock.getSymbol(), ex.getClass().getSimpleName());
                return false;
            }

            attemptedDateByFigi.put(figi, observationDateNy);
            log.info("AlphaVantage annual forecasts saved | figi={} symbol={} rows={}",
                    figi, stock.getSymbol(), outcome.rows().size());
            return true;
        } finally {
            inFlightFigis.remove(figi);
        }
    }

    /** 僅在節流等待與配額原子扣額期間短暫持鎖（最多等 2 秒）：先節流等待、再以放行當下紐約日期重查熔斷並原子扣額，釋鎖後立即發送 HTTP。 */
    private LocalDate reserveQuotaSlotAndPace(String figi) {
        boolean acquired;
        try {
            acquired = pacingLock.tryLock(LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!acquired) {
            log.info("AlphaVantage sync skipped due to busy pacing lock | figi={}", figi);
            return null;
        }
        try {
            LocalDate prePaceDay = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            if (prePaceDay.equals(quotaExhaustedDate) || prePaceDay.equals(attemptedDateByFigi.get(figi))) {
                return null;
            }
            if (forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(
                    figi, SOURCE_ALPHAVANTAGE, PERIOD_TYPE_ANNUAL, prePaceDay.minusDays(cacheDays - 1L))) {
                attemptedDateByFigi.put(figi, prePaceDay);
                return null;
            }

            if (!paceRequest()) {
                return null;
            }

            LocalDate cacheCheckDay = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            if (cacheCheckDay.equals(quotaExhaustedDate) || cacheCheckDay.equals(attemptedDateByFigi.get(figi))) {
                return null;
            }
            if (forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(
                    figi, SOURCE_ALPHAVANTAGE, PERIOD_TYPE_ANNUAL, cacheCheckDay.minusDays(cacheDays - 1L))) {
                attemptedDateByFigi.put(figi, cacheCheckDay);
                return null;
            }

            // 完成最後一次快取查詢後才讀取扣額時鐘，並於扣額提交後複核是否跨過午夜，防止舊日扣額卻在新日發送
            Instant dispatchNow = dateTimeService.now();
            LocalDate dispatchDayNy = dispatchNow.atZone(MarketStatusService.NY_ZONE).toLocalDate();
            if (dispatchDayNy.equals(quotaExhaustedDate) || dispatchDayNy.equals(attemptedDateByFigi.get(figi))) {
                return null;
            }

            Integer updated;
            try {
                updated = transactions.execute(status ->
                        dailyQuotas.tryIncrementDailyQuota(SOURCE_ALPHAVANTAGE, dispatchDayNy, dailyLimit, dispatchNow));
            } catch (RuntimeException ex) {
                log.warn("AlphaVantage daily quota reservation failed | figi={} error={}",
                        figi, ex.getClass().getSimpleName());
                return null;
            }
            if (updated == null || updated <= 0) {
                quotaExhaustedDate = dispatchDayNy;
                log.info("AlphaVantage annual forecast daily budget reached or exhausted | date={} limit={}",
                        dispatchDayNy, dailyLimit);
                return null;
            }

            LocalDate postCommitDayNy = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            if (!postCommitDayNy.equals(dispatchDayNy)) {
                log.info("AlphaVantage sync skipped after NY midnight rollover during quota commit | figi={} reservedDay={} nowDay={}",
                        figi, dispatchDayNy, postCommitDayNy);
                return null;
            }

            lastCallNanos = System.nanoTime();
            return dispatchDayNy;
        } finally {
            pacingLock.unlock();
        }
    }

    private void markDailyQuotaExhausted(LocalDate quotaDay) {
        quotaExhaustedDate = quotaDay;
        try {
            transactions.executeWithoutResult(status ->
                    dailyQuotas.markQuotaExhausted(SOURCE_ALPHAVANTAGE, quotaDay, dailyLimit, dateTimeService.now()));
        } catch (RuntimeException ex) {
            log.warn("AlphaVantage quota exhausted flag persist failed | date={} error={}",
                    quotaDay, ex.getClass().getSimpleName());
        }
    }

    private boolean paceRequest() {
        if (minIntervalMs <= 0L) {
            return true;
        }
        long nowNanos = System.nanoTime();
        if (lastCallNanos != 0L) {
            long elapsedMs = (nowNanos - lastCallNanos) / 1_000_000L;
            long waitMs = minIntervalMs - elapsedMs;
            if (waitMs > 0L) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isEligibleStock(Stock stock) {
        if (stock == null || stock.getActiveStatus() != ActiveStatus.ACTIVE || !stock.isTracked()) {
            return false;
        }
        if (!SecurityTypePolicy.isOperatingCompanyType(stock.getStockType())) {
            return false;
        }
        if (stock.getSymbol() == null || stock.getSymbol().isBlank()) {
            return false;
        }
        return CURRENCY_USD.equalsIgnoreCase(stock.getCurrency());
    }

    /** 僅認可來自富途原始財報或 SEC 金額事實之明確 USD 證據，不依賴 StockFinancialPeriod 由掛牌幣別回填的欄位。 */
    private boolean hasAuthoritativeUsdReportingCurrency(Stock stock) {
        List<String> reportCurrencies = financialReports.findLatestReportCurrencies(stock.getFigi(), PageRequest.of(0, 1));
        if (reportCurrencies != null && !reportCurrencies.isEmpty()) {
            String latestCurrency = reportCurrencies.getFirst();
            return latestCurrency != null && CURRENCY_USD.equalsIgnoreCase(latestCurrency.trim());
        }
        if (stock.getCik() == null || stock.getCik().isBlank()) {
            return false;
        }
        Set<String> units = secFacts.findMonetaryReportingUnitsByCik(stock.getCik().trim());
        if (units == null || units.isEmpty()) {
            return false;
        }
        boolean sawUsd = false;
        for (String unit : units) {
            if (unit == null || unit.isBlank()) {
                continue;
            }
            if (CURRENCY_USD.equalsIgnoreCase(unit.trim())) {
                sawUsd = true;
            } else {
                return false;
            }
        }
        return sawUsd;
    }

    private static String toAlphaVantageSymbol(String symbol) {
        return symbol.trim().replace('.', '-');
    }

    record ParseOutcome(List<StockEarningsForecast> rows, boolean dailyQuotaExhausted, boolean burstRateLimited) {}

    private record AnnualAnchor(int fiscalYear, LocalDate periodEnd) {}

    ParseOutcome parseAnnualEstimates(
            Stock stock, String jsonBody, LocalDate observationDate, Instant observedAt,
            List<StockFinancialPeriod> storedPeriods) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return new ParseOutcome(List.of(), false, false);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBody);
        } catch (Exception ex) {
            log.warn("AlphaVantage annual forecast JSON parse error | figi={} symbol={}",
                    stock.getFigi(), stock.getSymbol());
            return new ParseOutcome(List.of(), false, false);
        }
        if (root == null || !root.isObject()) {
            return new ParseOutcome(List.of(), false, false);
        }

        String info = textOrNull(root.get("Information"));
        String note = textOrNull(root.get("Note"));
        String notice = info != null ? info : note;
        if (notice != null) {
            String lower = notice.toLowerCase(Locale.ROOT);
            boolean burst = lower.contains("per second") || lower.contains("sparingly");
            if (burst) {
                return new ParseOutcome(List.of(), false, true);
            }
            return new ParseOutcome(List.of(), true, false);
        }

        JsonNode estimates = root.get("estimates");
        if (estimates == null || !estimates.isArray() || estimates.isEmpty()) {
            return new ParseOutcome(List.of(), false, false);
        }

        AnnualAnchor anchor = latestAnnualAnchor(storedPeriods);
        // 檢查所有歷史年結是否曾出現非月末日（涵蓋如 Wendy's 偶爾恰逢 12-31 的 52/53 週公司）
        boolean usesWeeklyCalendar = hasAnyNonMonthEndAnnualPeriod(storedPeriods);
        LocalDate earliestRelevantEnd = observationDate.minusDays(PAST_PERIOD_GRACE_DAYS);
        Map<String, StockEarningsForecast> byFiscalPeriod = new LinkedHashMap<>();
        Map<String, LocalDate> rawSortEndByFiscalPeriod = new LinkedHashMap<>();

        for (JsonNode item : estimates) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String horizon = textOrNull(item.get("horizon"));
            if (!"fiscal year".equalsIgnoreCase(horizon)) {
                continue;
            }
            LocalDate avDate = parseDate(textOrNull(item.get("date")));
            if (avDate == null || !avDate.isAfter(earliestRelevantEnd)) {
                continue;
            }

            BigDecimal eps = parseDecimal(item.get("eps_estimate_average"));
            BigDecimal revenue = parseDecimal(item.get("revenue_estimate_average"));
            if (eps == null && revenue == null) {
                continue;
            }
            BigDecimal eps30d = parseDecimal(item.get("eps_estimate_average_30_days_ago"));
            BigDecimal eps90d = parseDecimal(item.get("eps_estimate_average_90_days_ago"));

            int fy = resolveFiscalYear(avDate, anchor);
            String fiscalPeriod = "FY" + fy;
            // 對 52/53 週結算公司，Alpha Vantage 回傳的曆月末僅為近似值，保留 periodEnd=NULL 交由計算器套用 ±7 日未核實保護
            LocalDate persistedPeriodEnd = usesWeeklyCalendar && isCalendarMonthEnd(avDate) ? null : avDate;

            StockEarningsForecast row = new StockEarningsForecast();
            row.setFigi(stock.getFigi());
            row.setFiscalPeriod(fiscalPeriod);
            row.setPeriodType(PERIOD_TYPE_ANNUAL);
            row.setObservationDate(observationDate);
            row.setPeriodEnd(persistedPeriodEnd);
            row.setObservedAt(observedAt);
            row.setCurrency(CURRENCY_USD);
            row.setEps(eps);
            row.setEps30dAgo(eps30d);
            row.setEps90dAgo(eps90d);
            row.setRevenue(revenue);
            row.setSource(SOURCE_ALPHAVANTAGE);

            LocalDate existingRawEnd = rawSortEndByFiscalPeriod.get(fiscalPeriod);
            if (existingRawEnd == null || avDate.isAfter(existingRawEnd)) {
                byFiscalPeriod.put(fiscalPeriod, row);
                rawSortEndByFiscalPeriod.put(fiscalPeriod, avDate);
            }
        }

        List<StockEarningsForecast> result = new ArrayList<>(byFiscalPeriod.values());
        result.sort(Comparator.comparing(row -> rawSortEndByFiscalPeriod.get(row.getFiscalPeriod())));
        return new ParseOutcome(List.copyOf(result), false, false);
    }

    private AnnualAnchor latestAnnualAnchor(List<StockFinancialPeriod> periods) {
        if (periods == null || periods.isEmpty()) {
            return null;
        }
        AnnualAnchor latest = null;
        for (StockFinancialPeriod p : periods) {
            if (p == null || p.getPeriodEnd() == null || p.getFiscalYear() == null) {
                continue;
            }
            if (isAnnualPeriod(p)) {
                if (latest == null || p.getPeriodEnd().isAfter(latest.periodEnd())) {
                    latest = new AnnualAnchor(p.getFiscalYear(), p.getPeriodEnd());
                }
            }
        }
        return latest;
    }

    private boolean hasAnyNonMonthEndAnnualPeriod(List<StockFinancialPeriod> periods) {
        if (periods == null || periods.isEmpty()) {
            return false;
        }
        for (StockFinancialPeriod p : periods) {
            if (p != null && p.getPeriodEnd() != null && isAnnualPeriod(p) && !isCalendarMonthEnd(p.getPeriodEnd())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnnualPeriod(StockFinancialPeriod p) {
        if (Integer.valueOf(4).equals(p.getFiscalQuarter())) {
            return true;
        }
        return p.getPeriodStart() != null && ChronoUnit.DAYS.between(p.getPeriodStart(), p.getPeriodEnd()) >= 350L;
    }

    private int resolveFiscalYear(LocalDate periodEnd, AnnualAnchor anchor) {
        if (anchor != null) {
            long days = ChronoUnit.DAYS.between(anchor.periodEnd(), periodEnd);
            long yearShift = Math.round(days / 365.25d);
            if (yearShift >= 1L && yearShift <= 10L
                    && Math.abs(days - yearShift * 365.25d) <= FISCAL_YEAR_SHIFT_TOLERANCE_DAYS) {
                return anchor.fiscalYear() + (int) yearShift;
            }
        }
        return periodEnd.getYear();
    }

    private static boolean isCalendarMonthEnd(LocalDate date) {
        return date != null && date.getDayOfMonth() == date.lengthOfMonth();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        if (text == null || text.isBlank() || "None".equalsIgnoreCase(text.trim())) {
            return null;
        }
        return text.trim();
    }

    private static LocalDate parseDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(JsonNode node) {
        String text = textOrNull(node);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
