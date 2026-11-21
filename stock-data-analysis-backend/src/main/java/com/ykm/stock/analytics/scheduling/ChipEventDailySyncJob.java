package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.OptionsSummaryDto;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEventAiSummary;
import com.ykm.stock.analytics.model.entity.StockOptionsSummary;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockEventAiSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockOptionsSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.enhancement.EnhancementAiGateway;
import com.ykm.stock.analytics.service.enhancement.OptionsEnhancementService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService.NewsRefreshResult;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 精準目標集籌碼與異動事件每日同步工作（絕不掃描全市場 5,000 股，亦絕不呼叫 Alpha Vantage）。
 * 目標優先序：HOLDING（必含） -> WATCHLIST -> 當日已發布雷達入選股（上限 60 檔，超過上限明確記錄延後數）。
 * 當 enhancement.ai.provider = NONE 時，完全跳過 AI 摘要且零報錯，仍正常同步期權、Form 4 與新聞快取。
 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.CHIP_EVENT_DAILY_SYNC)
public class ChipEventDailySyncJob implements Job {
    public static final String CONFIG_KEY_SYNCED_TRADE_DATE = "chip_event_synced_trade_date";
    private static final int TARGET_CAP = 60;
    private static final int AI_SUMMARY_DAILY_CAP = 10;
    private static final BigDecimal PRICE_SHOCK_THRESHOLD = new BigDecimal("0.05");
    private static final MathContext MC = MathContext.DECIMAL128;

    private final PortfolioItemRepository portfolioItemRepository;
    private final DailyOpportunityRadarRunRepository radarRunRepository;
    private final DailyOpportunityRadarRepository radarRepository;
    private final StockRepository stockRepository;
    private final StockQuoteRepository quoteRepository;
    private final StockSplitRepository splitRepository;
    private final MarketEventRepository marketEventRepository;
    private final StockEventAiSummaryRepository summaryRepository;
    private final StockOptionsSummaryRepository optionsSummaryRepository;
    private final OptionsEnhancementService optionsService;
    private final SecInsiderAndInstitutionalService insider13fService;
    private final StockEventSummaryService eventSummaryService;
    private final EnhancementAiGateway aiGateway;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private PortfolioSysConfigRepository sysConfigRepository;

    @Override
    public void execute(JobExecutionContext context) {
        Instant now = dateTimeService.now();
        JobDataMap params = context == null ? null : context.getMergedJobDataMap();
        LocalDate tradeDate = resolveTradeDate(params, now);
        boolean forceRun = parseBooleanParam(params, "force");

        // 0. 同交易日已完成含當日正式雷達之同步時直接略過（讓 11:00 兜底 Cron 零額外 API 呼叫；手動 force=true 可覆蓋重跑）
        LocalDate syncedDate = readSyncedWatermark();
        if (!forceRun && syncedDate != null && !syncedDate.isBefore(tradeDate)) {
            log.info("Chip & event daily sync skipped | reason=ALREADY_SYNCED_FOR_TRADE_DATE tradeDate={} watermark={}",
                    tradeDate, syncedDate);
            return;
        }

        // 1. 確認當日雷達是否已正式發布（含零入選日），並按優先序組裝精準目標集（HOLDING -> WATCHLIST -> 當日雷達）
        Optional<DailyOpportunityRadarRun> latestRun = radarRunRepository.findTopByOrderByRadarDateDesc();
        boolean radarPublished = (latestRun != null && latestRun.isPresent() && tradeDate.equals(latestRun.get().getRadarDate()))
                || radarRunRepository.existsById(tradeDate);
        List<String> targetFigis = selectPrioritizedTargets(tradeDate, radarPublished);
        if (targetFigis.isEmpty()) {
            if (radarPublished) {
                advanceSyncedWatermark(tradeDate);
            }
            log.info("Chip & event daily sync skipped | reason=NO_TARGET_STOCKS tradeDate={} radarPublished={} watermarkAdvanced={}",
                    tradeDate, radarPublished, radarPublished);
            return;
        }
        Map<String, Stock> stockMap = stockRepository.findAllById(targetFigis).stream()
                .collect(Collectors.toMap(Stock::getFigi, s -> s));
        Set<String> earningsWindowFigis = marketEventRepository.findEarningsFigisBetween(
                tradeDate.minusDays(3), tradeDate.plusDays(3));

        Instant sessionCloseAt = calendar.getSessionCloseAt(tradeDate);
        Set<String> settledOptionsFigis = Set.of();
        if (!forceRun && sessionCloseAt != null) {
            List<String> settled = optionsSummaryRepository.findSettledFigisForSession(targetFigis, tradeDate, sessionCloseAt);
            if (settled != null && !settled.isEmpty()) {
                settledOptionsFigis = new HashSet<>(settled);
            }
        }

        int optionsOk = 0;
        int optionsSkipped = 0;
        int optionsFailed = 0;
        int form4Ok = 0;
        int form4Failed = 0;
        int newsTriggered = 0;
        int newsFailed = 0;
        LocalDate aiBudgetDate = calendar.getLastClosedTradingDay(now);
        if (aiBudgetDate == null) {
            aiBudgetDate = tradeDate;
        }
        int aiSummarized = (int) summaryRepository.countByTriggerDateAndAiProviderNot(
                aiBudgetDate, StockEventAiSummary.PROVIDER_MANUAL_IMPORT);
        int aiFailed = 0;
        boolean optionsUnconfigured = false;
        boolean secUnconfigured = false;
        Set<String> scannedForm4Ciks = new LinkedHashSet<>();

        for (String figi : targetFigis) {
            Stock stock = stockMap.get(figi);
            if (stock == null) continue;

            // 2. 期權摘要（支援正股、ADR 與 ETF；非 force 重跑且當日收市後已有有效結算快照則跳過；未配置 Alpaca 不計為失敗；缺現價導致暫時性 INSUFFICIENT_DATA 保留重試）
            if (settledOptionsFigis.contains(figi)) {
                optionsSkipped++;
            } else if (!optionsUnconfigured) {
                try {
                    OptionsSummaryDto optResult = optionsService.refreshStock(stock);
                    if (isRetryableOptionsInsufficientData(optResult)) {
                        optionsFailed++;
                        log.debug("Options sync deferred (transient missing spot) | symbol={} reason={}",
                                stock.getSymbol(), optResult.reasonCode());
                    } else {
                        optionsOk++;
                    }
                } catch (RuntimeException ex) {
                    if (isAlpacaUnconfigured(ex)) {
                        optionsUnconfigured = true;
                        log.info("Options sync disabled: Alpaca credentials not configured");
                    } else {
                        optionsFailed++;
                        log.debug("Options sync skipped | symbol={} reason={}", stock.getSymbol(), ex.getMessage());
                    }
                }
            }

            // 3. SEC Form 4（按 CIK 去重，跳過 ETF；未配置 User-Agent 或確定性 404 不阻斷當日水位，403／5xx／網路錯誤保留重試）
            if (!secUnconfigured && !SecurityTypePolicy.isEtfType(stock.getStockType())
                    && stock.getCik() != null && !stock.getCik().isBlank()
                    && scannedForm4Ciks.add(stock.getCik())) {
                try {
                    insider13fService.syncForm4ForStock(stock);
                    form4Ok++;
                } catch (RuntimeException ex) {
                    if (isSecUnconfigured(ex)) {
                        secUnconfigured = true;
                        log.info("Form 4 sync disabled: SEC User-Agent not configured");
                    } else if (isSecNotFound(ex)) {
                        log.debug("Form 4 sync skipped (deterministic 404) | symbol={} cik={}", stock.getSymbol(), stock.getCik());
                    } else {
                        form4Failed++;
                        log.debug("Form 4 sync skipped | symbol={} reason={}", stock.getSymbol(), ex.getMessage());
                    }
                }
            }

            // 4. 新聞與 8-K/6-K：僅於單日漲跌逾 ±5% 或財報前後 ±3 日觸發；AI 摘要異常獨立隔離不阻斷完成水位
            String triggerReason = resolveEventTriggerReason(figi, tradeDate, earningsWindowFigis);
            if (triggerReason != null) {
                NewsRefreshResult refreshed = null;
                try {
                    refreshed = eventSummaryService.refreshNewsAndFilings(stock);
                    newsTriggered++;
                } catch (RuntimeException ex) {
                    newsFailed++;
                    log.debug("Event news refresh skipped | symbol={} reason={}", stock.getSymbol(), ex.getMessage());
                }
                if (refreshed != null && aiGateway.isAutomatedAvailable() && aiSummarized < AI_SUMMARY_DAILY_CAP
                        && refreshed.recentEvents() != null && !refreshed.recentEvents().isEmpty()) {
                    // 發起呼叫前即計入當日預算上限，避免供應商已計費但本地逾時或驗證失敗導致超額呼叫
                    aiSummarized++;
                    try {
                        eventSummaryService.summarizeAutomated(stock, triggerReason);
                    } catch (RuntimeException ex) {
                        aiFailed++;
                        log.debug("Event AI summary skipped (isolated) | symbol={} reason={}", stock.getSymbol(), ex.getMessage());
                    }
                }
            }
        }

        // 5. 僅當本輪已涵蓋當日正式雷達且無可重試外部同步失敗時才推進完成水位，避免來源中斷或 11:00 搶跑擋掉重跑補抓
        boolean hadSyncFailure = optionsFailed > 0 || form4Failed > 0 || newsFailed > 0;
        boolean watermarkAdvanced = radarPublished && !hadSyncFailure;
        if (watermarkAdvanced) {
            advanceSyncedWatermark(tradeDate);
        }
        log.info("Chip & event daily sync completed | tradeDate={} radarPublished={} watermarkAdvanced={} targets={} optionsOk={} optionsSkipped={} optionsFailed={} form4Ciks={} form4Failed={} newsTriggered={} newsFailed={} aiSummarized={} aiFailed={}",
                tradeDate, radarPublished, watermarkAdvanced, targetFigis.size(),
                optionsOk, optionsSkipped, optionsFailed, form4Ok, form4Failed, newsTriggered, newsFailed, aiSummarized, aiFailed);
    }

    private boolean isRetryableOptionsInsufficientData(OptionsSummaryDto result) {
        if (result == null || !StockOptionsSummary.STATUS_INSUFFICIENT_DATA.equals(result.status())) {
            return false;
        }
        String reason = result.reasonCode();
        return "SPOT_PRICE_UNAVAILABLE".equals(reason) || "INTRADAY_SPOT_PRICE_UNAVAILABLE".equals(reason);
    }

    private boolean isAlpacaUnconfigured(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("ALPACA_API_CREDENTIALS_REQUIRED")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean isSecUnconfigured(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("SEC_USER_AGENT_NOT_CONFIGURED")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean isSecNotFound(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SecEdgarClient.SecNotFoundException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.contains("SEC_EDGAR_NOT_FOUND")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private LocalDate resolveTradeDate(JobDataMap params, Instant now) {
        if (params != null && params.containsKey("tradeDate")) {
            Object raw = params.get("tradeDate");
            if (raw != null) {
                String text = String.valueOf(raw).trim();
                if (!text.isEmpty()) {
                    try {
                        return LocalDate.parse(text);
                    } catch (RuntimeException ex) {
                        log.warn("Invalid tradeDate job param ignored | raw={}", text);
                    }
                }
            }
        }
        return calendar.getLastClosedTradingDay(now);
    }

    private boolean parseBooleanParam(JobDataMap params, String key) {
        if (params == null || !params.containsKey(key)) {
            return false;
        }
        Object raw = params.get(key);
        if (raw instanceof Boolean value) {
            return value;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    private List<String> selectPrioritizedTargets(LocalDate tradeDate, boolean radarPublished) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        List<PortfolioItem> portfolioItems = portfolioItemRepository.findAll();
        // 先納全部 HOLDING（永不截斷持倉），再納 WATCHLIST
        for (PortfolioItem item : portfolioItems) {
            if (PortfolioItem.TYPE_HOLDING.equals(item.getItemType())) {
                ordered.add(item.getFigi());
            }
        }
        for (PortfolioItem item : portfolioItems) {
            if (ordered.size() >= TARGET_CAP) break;
            if (!PortfolioItem.TYPE_HOLDING.equals(item.getItemType())) {
                ordered.add(item.getFigi());
            }
        }
        // 再納入最新已發布當日雷達（區分正式零入選與尚未發布）
        if (radarPublished) {
            List<DailyOpportunityRadar> radarRows = radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(tradeDate);
            int deferred = 0;
            for (DailyOpportunityRadar row : radarRows) {
                if (ordered.size() >= TARGET_CAP) {
                    deferred++;
                    continue;
                }
                ordered.add(row.getFigi());
            }
            if (deferred > 0) {
                log.info("Chip & event target cap reached | cap={} deferredRadarCount={}", TARGET_CAP, deferred);
            }
        }
        return new ArrayList<>(ordered);
    }

    private LocalDate readSyncedWatermark() {
        if (jobConfigRepository != null) {
            LocalDate watermark = jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.CHIP_EVENT_DAILY_SYNC).orElse(null);
            if (watermark != null) {
                return watermark;
            }
            return PortfolioSysConfigRepository.migrateLegacyWatermark(
                    sysConfigRepository, jobConfigRepository, CONFIG_KEY_SYNCED_TRADE_DATE, TaskCode.CHIP_EVENT_DAILY_SYNC);
        }
        return PortfolioSysConfigRepository.resolveDateConfig(sysConfigRepository, CONFIG_KEY_SYNCED_TRADE_DATE, null);
    }

    private void advanceSyncedWatermark(LocalDate tradeDate) {
        if (tradeDate == null) {
            return;
        }
        if (jobConfigRepository != null) {
            JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.CHIP_EVENT_DAILY_SYNC, tradeDate);
            return;
        }
        if (sysConfigRepository == null) {
            return;
        }
        List<SysConfig> matches = sysConfigRepository.findByConfigKeyInOrderByIdAsc(List.of(CONFIG_KEY_SYNCED_TRADE_DATE));
        if (matches == null || matches.isEmpty()) {
            SysConfig created = new SysConfig();
            created.setConfigKey(CONFIG_KEY_SYNCED_TRADE_DATE);
            created.setConfigValue(tradeDate.toString());
            created.setDescription("籌碼與異動事件同步已涵蓋當日正式雷達之最新美東交易日水位");
            created.setEnabled(true);
            sysConfigRepository.save(created);
            return;
        }
        SysConfig primary = matches.getFirst();
        LocalDate existing = PortfolioSysConfigRepository.resolveDateConfig(sysConfigRepository, CONFIG_KEY_SYNCED_TRADE_DATE, null);
        if (existing == null || tradeDate.isAfter(existing)) {
            primary.setConfigValue(tradeDate.toString());
            primary.setEnabled(true);
            sysConfigRepository.save(primary);
        }
    }

    private String resolveEventTriggerReason(String figi, LocalDate tradeDate, Set<String> earningsWindowFigis) {
        List<LocalDate> recentSessions = calendar.getTradingDays(tradeDate.minusDays(14), tradeDate);
        LocalDate prevTradingDay = recentSessions != null && recentSessions.size() >= 2
                && tradeDate.equals(recentSessions.getLast())
                ? recentSessions.get(recentSessions.size() - 2)
                : null;
        List<StockQuote> lastTwo = quoteRepository.findByFigiAndQuoteDateLessThanEqualOrderByQuoteDateDesc(
                figi, tradeDate, Limit.of(2));
        if (prevTradingDay != null && lastTwo != null && lastTwo.size() >= 2
                && tradeDate.equals(lastTwo.get(0).getQuoteDate())
                && prevTradingDay.equals(lastTwo.get(1).getQuoteDate())
                && lastTwo.get(0).getClosePrice() != null && lastTwo.get(1).getClosePrice() != null
                && lastTwo.get(1).getClosePrice().signum() > 0) {
            List<StockSplit> splits = splitRepository.findByFigiOrderByExecutionDateAsc(figi);
            BigDecimal priceFactor = StockSplitCalculator.priceFactor(splits, lastTwo.get(1).getQuoteDate(), tradeDate);
            BigDecimal adjPrevClose = (priceFactor != null && priceFactor.signum() > 0)
                    ? lastTwo.get(1).getClosePrice().multiply(priceFactor, MC)
                    : lastTwo.get(1).getClosePrice();
            if (adjPrevClose.signum() > 0) {
                BigDecimal pct = lastTwo.get(0).getClosePrice().subtract(adjPrevClose, MC)
                        .divide(adjPrevClose, MC).abs();
                if (pct.compareTo(PRICE_SHOCK_THRESHOLD) >= 0) {
                    return StockEventAiSummary.TRIGGER_PRICE_SHOCK_5PCT;
                }
            }
        }
        if (earningsWindowFigis != null && earningsWindowFigis.contains(figi)) {
            return StockEventAiSummary.TRIGGER_EARNINGS_WINDOW;
        }
        return null;
    }
}
