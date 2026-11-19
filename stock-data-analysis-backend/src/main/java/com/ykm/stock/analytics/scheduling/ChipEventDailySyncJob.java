package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEventAiSummary;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.StockEventAiSummaryRepository;
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
import org.quartz.JobExecutionContext;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final OptionsEnhancementService optionsService;
    private final SecInsiderAndInstitutionalService insider13fService;
    private final StockEventSummaryService eventSummaryService;
    private final EnhancementAiGateway aiGateway;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;

    @Override
    public void execute(JobExecutionContext context) {
        Instant now = dateTimeService.now();
        LocalDate tradeDate = calendar.getLastClosedTradingDay(now);

        // 1. 按優先序組裝精準目標集（HOLDING -> WATCHLIST -> 最新已發布雷達）
        List<String> targetFigis = selectPrioritizedTargets(tradeDate);
        if (targetFigis.isEmpty()) {
            log.info("Chip & event daily sync skipped | reason=NO_TARGET_STOCKS tradeDate={}", tradeDate);
            return;
        }
        Map<String, Stock> stockMap = stockRepository.findAllById(targetFigis).stream()
                .collect(Collectors.toMap(Stock::getFigi, s -> s));
        Set<String> earningsWindowFigis = marketEventRepository.findEarningsFigisBetween(
                tradeDate.minusDays(3), tradeDate.plusDays(3));

        int optionsOk = 0;
        int form4Ok = 0;
        int newsTriggered = 0;
        int aiSummarized = (int) summaryRepository.countByTriggerDateAndAiProviderNot(
                tradeDate, StockEventAiSummary.PROVIDER_MANUAL_IMPORT);
        Set<String> scannedForm4Ciks = new LinkedHashSet<>();

        for (String figi : targetFigis) {
            Stock stock = stockMap.get(figi);
            if (stock == null) continue;

            // 2. 期權摘要（支援正股、ADR 與 ETF）
            try {
                optionsService.refreshStock(stock);
                optionsOk++;
            } catch (RuntimeException ex) {
                log.debug("Options sync skipped | symbol={} reason={}", stock.getSymbol(), ex.getMessage());
            }

            // 3. SEC Form 4（按 CIK 去重，跳過 ETF）
            if (!SecurityTypePolicy.isEtfType(stock.getStockType())
                    && stock.getCik() != null && !stock.getCik().isBlank()
                    && scannedForm4Ciks.add(stock.getCik())) {
                try {
                    insider13fService.syncForm4ForStock(stock);
                    form4Ok++;
                } catch (RuntimeException ex) {
                    log.debug("Form 4 sync skipped | symbol={} reason={}", stock.getSymbol(), ex.getMessage());
                }
            }

            // 4. 新聞與 8-K/6-K：僅於單日漲跌逾 ±5% 或財報前後 ±3 日觸發
            String triggerReason = resolveEventTriggerReason(figi, tradeDate, earningsWindowFigis);
            if (triggerReason != null) {
                try {
                    NewsRefreshResult refreshed = eventSummaryService.refreshNewsAndFilings(stock);
                    newsTriggered++;
                    if (aiGateway.isAutomatedAvailable() && aiSummarized < AI_SUMMARY_DAILY_CAP
                            && refreshed.recentEvents() != null && !refreshed.recentEvents().isEmpty()) {
                        // 發起呼叫前即計入當日預算上限，避免供應商已計費但本地逾時或驗證失敗導致超額呼叫
                        aiSummarized++;
                        eventSummaryService.summarizeAutomated(stock, triggerReason);
                    }
                } catch (RuntimeException ex) {
                    log.debug("Event news/summary skipped | symbol={} reason={}", stock.getSymbol(), ex.getMessage());
                }
            }
        }
        log.info("Chip & event daily sync completed | tradeDate={} targets={} optionsOk={} form4Ciks={} newsTriggered={} aiSummarized={}",
                tradeDate, targetFigis.size(), optionsOk, form4Ok, newsTriggered, aiSummarized);
    }

    private List<String> selectPrioritizedTargets(LocalDate tradeDate) {
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
        Optional<DailyOpportunityRadarRun> latestRun = radarRunRepository.findTopByOrderByRadarDateDesc();
        if (latestRun.isPresent() && latestRun.get().getRadarDate().equals(tradeDate)) {
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
