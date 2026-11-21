package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockEventAiSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockOptionsSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.service.enhancement.EnhancementAiGateway;
import com.ykm.stock.analytics.service.enhancement.OptionsEnhancementService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChipEventDailySyncJobTests {
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 20);
    private static final Instant SESSION_CLOSE = Instant.parse("2026-11-20T21:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");

    private final PortfolioItemRepository portfolioItemRepository = mock(PortfolioItemRepository.class);
    private final DailyOpportunityRadarRunRepository radarRunRepository = mock(DailyOpportunityRadarRunRepository.class);
    private final DailyOpportunityRadarRepository radarRepository = mock(DailyOpportunityRadarRepository.class);
    private final StockRepository stockRepository = mock(StockRepository.class);
    private final StockQuoteRepository quoteRepository = mock(StockQuoteRepository.class);
    private final StockSplitRepository splitRepository = mock(StockSplitRepository.class);
    private final MarketEventRepository marketEventRepository = mock(MarketEventRepository.class);
    private final StockEventAiSummaryRepository summaryRepository = mock(StockEventAiSummaryRepository.class);
    private final StockOptionsSummaryRepository optionsSummaryRepository = mock(StockOptionsSummaryRepository.class);
    private final OptionsEnhancementService optionsService = mock(OptionsEnhancementService.class);
    private final SecInsiderAndInstitutionalService insider13fService = mock(SecInsiderAndInstitutionalService.class);
    private final StockEventSummaryService eventSummaryService = mock(StockEventSummaryService.class);
    private final EnhancementAiGateway aiGateway = mock(EnhancementAiGateway.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final DateTimeService dateTimeService = mock(DateTimeService.class);
    private final PortfolioSysConfigRepository sysConfigRepository = mock(PortfolioSysConfigRepository.class);

    private ChipEventDailySyncJob job;
    private SysConfig storedWatermark;

    @BeforeEach
    void setUp() {
        job = new ChipEventDailySyncJob(
                portfolioItemRepository, radarRunRepository, radarRepository, stockRepository,
                quoteRepository, splitRepository, marketEventRepository, summaryRepository,
                optionsSummaryRepository, optionsService, insider13fService, eventSummaryService,
                aiGateway, calendar, dateTimeService);
        ReflectionTestUtils.setField(job, "sysConfigRepository", sysConfigRepository);

        storedWatermark = null;
        when(dateTimeService.now()).thenReturn(NOW);
        when(calendar.getLastClosedTradingDay(NOW)).thenReturn(TRADE_DATE);
        when(calendar.getSessionCloseAt(TRADE_DATE)).thenReturn(SESSION_CLOSE);
        when(calendar.getTradingDays(any(), any())).thenReturn(List.of(TRADE_DATE.minusDays(1), TRADE_DATE));
        when(marketEventRepository.findEarningsFigisBetween(any(), any())).thenReturn(Set.of());
        when(summaryRepository.countByTriggerDateAndAiProviderNot(any(), any())).thenReturn(0L);
        when(optionsSummaryRepository.findSettledFigisForSession(anyCollection(), any(), any())).thenReturn(List.of());
        when(quoteRepository.findByFigiAndQuoteDateLessThanEqualOrderByQuoteDateDesc(any(), any(), any())).thenReturn(List.of());
        when(sysConfigRepository.findByConfigKeyInOrderByIdAsc(List.of(ChipEventDailySyncJob.CONFIG_KEY_SYNCED_TRADE_DATE)))
                .thenAnswer(inv -> storedWatermark == null ? List.of() : List.of(storedWatermark));
        when(sysConfigRepository.save(any(SysConfig.class))).thenAnswer(inv -> {
            storedWatermark = inv.getArgument(0);
            return storedWatermark;
        });
    }

    /**
     * Scenario：11:00 兜底 Cron 先跑但當日雷達尚未發布（radarPublished=false）時，仍同步持倉但不推進完成水位；
     * 待 11:30 雷達發布後補觸發，可正常納入雷達股並推進水位；後續重複觸發即直接冪等略過。
     */
    @Test
    void doesNotAdvanceWatermarkUntilRadarIsPublishedAndSkipsOnceCompleted() {
        PortfolioItem holding = new PortfolioItem();
        holding.setFigi("F_NVDA");
        holding.setItemType(PortfolioItem.TYPE_HOLDING);
        Stock nvda = stock("F_NVDA", "NVDA", "0001045810");
        Stock avgo = stock("F_AVGO", "AVGO", "0001730168");

        when(portfolioItemRepository.findAll()).thenReturn(List.of(holding));
        when(radarRunRepository.findTopByOrderByRadarDateDesc()).thenReturn(Optional.empty());
        when(radarRunRepository.existsById(TRADE_DATE)).thenReturn(false);
        when(stockRepository.findAllById(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            List<Stock> out = new java.util.ArrayList<>();
            if (ids.contains("F_NVDA")) out.add(nvda);
            if (ids.contains("F_AVGO")) out.add(avgo);
            return out;
        });

        // 1) 11:00 雷達未發布：同步持倉 NVDA，但絕不推進 sys_config 完成水位
        job.execute(null);
        verify(optionsService, times(1)).refreshStock(nvda);
        verify(optionsService, never()).refreshStock(avgo);
        assertThat(storedWatermark).isNull();

        // 2) 11:30 雷達正式發布（新增 AVGO）並補觸發：不被水位擋掉，成功補抓 AVGO 並推進水位至 TRADE_DATE
        DailyOpportunityRadarRun run = new DailyOpportunityRadarRun();
        run.setRadarDate(TRADE_DATE);
        DailyOpportunityRadar radarRow = new DailyOpportunityRadar();
        radarRow.setFigi("F_AVGO");
        radarRow.setSymbol("AVGO");
        when(radarRunRepository.findTopByOrderByRadarDateDesc()).thenReturn(Optional.of(run));
        when(radarRunRepository.existsById(TRADE_DATE)).thenReturn(true);
        when(radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(TRADE_DATE))
                .thenReturn(List.of(radarRow));

        job.execute(null);
        verify(optionsService, times(2)).refreshStock(nvda);
        verify(optionsService, times(1)).refreshStock(avgo);
        assertThat(storedWatermark).isNotNull();
        assertThat(storedWatermark.getConfigValue()).isEqualTo(TRADE_DATE.toString());

        // 3) 後續重複執行（如 11:00 兜底 Cron）：見水位已等於 TRADE_DATE 直接略過，零額外呼叫
        job.execute(null);
        verify(optionsService, times(2)).refreshStock(nvda);
        verify(optionsService, times(1)).refreshStock(avgo);

        // 4) 手動帶 JSON Boolean force=true 可正常解析不拋 ClassCastException 且覆蓋重跑
        JobExecutionContext forcedCtx = mock(JobExecutionContext.class);
        JobDataMap map = new JobDataMap();
        map.put("force", Boolean.TRUE);
        when(forcedCtx.getMergedJobDataMap()).thenReturn(map);
        job.execute(forcedCtx);
        verify(optionsService, times(3)).refreshStock(nvda);
    }

    /**
     * Scenario：
     * 1) 外部來源（期權／Form 4）於首輪失敗時，即使雷達已發布也絕不推進完成水位，確保 11:00 兜底可重抓；
     * 2) 雷達發布後異步觸發傳入 tradeDate 時，即使系統時間已跨至下一個收盤日 D+1，仍精準針對 D 日雷達同步。
     */
    @Test
    void sourceFailureBlocksWatermarkAdvanceAndExplicitTradeDatePreventsDrift() {
        PortfolioItem holding = new PortfolioItem();
        holding.setFigi("F_NVDA");
        holding.setItemType(PortfolioItem.TYPE_HOLDING);
        Stock nvda = stock("F_NVDA", "NVDA", "0001045810");
        Stock avgo = stock("F_AVGO", "AVGO", "0001730168");

        DailyOpportunityRadarRun run = new DailyOpportunityRadarRun();
        run.setRadarDate(TRADE_DATE);
        DailyOpportunityRadar radarRow = new DailyOpportunityRadar();
        radarRow.setFigi("F_AVGO");
        radarRow.setSymbol("AVGO");

        when(portfolioItemRepository.findAll()).thenReturn(List.of(holding));
        when(radarRunRepository.findTopByOrderByRadarDateDesc()).thenReturn(Optional.of(run));
        when(radarRunRepository.existsById(TRADE_DATE)).thenReturn(true);
        when(radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(TRADE_DATE))
                .thenReturn(List.of(radarRow));
        when(stockRepository.findAllById(anyCollection())).thenReturn(List.of(nvda, avgo));

        // 1) Alpaca 期權失敗 -> 不推進水位
        when(optionsService.refreshStock(avgo)).thenThrow(new RuntimeException("ALPACA_503"));
        job.execute(null);
        assertThat(storedWatermark).isNull();

        // 2) 系統最新已收市日已推進至 D+1，但觸發參數明確指定 tradeDate=D，且來源恢復 -> 精準同步 D 日並寫入 D 水位
        when(calendar.getLastClosedTradingDay(NOW)).thenReturn(TRADE_DATE.plusDays(1));
        org.mockito.Mockito.doReturn(null).when(optionsService).refreshStock(avgo);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        JobDataMap params = new JobDataMap();
        params.put("tradeDate", TRADE_DATE.toString());
        when(ctx.getMergedJobDataMap()).thenReturn(params);

        job.execute(ctx);
        assertThat(storedWatermark).isNotNull();
        assertThat(storedWatermark.getConfigValue()).isEqualTo(TRADE_DATE.toString());
    }

    /**
     * Scenario：
     * 1) 未配置 Alpaca、SEC 未配置或單股確定性 404（SecNotFoundException）與 AI 摘要逾時均不阻斷完成水位；
     * 2) SEC 403 限流仍屬可重試失敗，必須阻斷水位保留兜底重跑；
     * 3) 非 force 重跑時，當日收市後已落庫且狀態為 AVAILABLE/NOT_APPLICABLE 之期權標的跳過重抓，未結算（如盤中刷新或 INSUFFICIENT_DATA）照常補抓；force=true 則全部重抓。
     */
    @Test
    void nonRetryableMissingCredentials404AndAiErrorsDoNotBlockWatermarkWhileSettledOptionsSkipExceptForce() {
        PortfolioItem h1 = new PortfolioItem();
        h1.setFigi("F_NVDA");
        h1.setItemType(PortfolioItem.TYPE_HOLDING);
        PortfolioItem h2 = new PortfolioItem();
        h2.setFigi("F_AMD");
        h2.setItemType(PortfolioItem.TYPE_HOLDING);
        Stock nvda = stock("F_NVDA", "NVDA", "0001045810");
        Stock amd = stock("F_AMD", "AMD", "0000002488");

        DailyOpportunityRadarRun run = new DailyOpportunityRadarRun();
        run.setRadarDate(TRADE_DATE);
        when(portfolioItemRepository.findAll()).thenReturn(List.of(h1, h2));
        when(radarRunRepository.findTopByOrderByRadarDateDesc()).thenReturn(Optional.of(run));
        when(radarRunRepository.existsById(TRADE_DATE)).thenReturn(true);
        when(radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(TRADE_DATE)).thenReturn(List.of());
        when(stockRepository.findAllById(anyCollection())).thenReturn(List.of(nvda, amd));
        when(marketEventRepository.findEarningsFigisBetween(any(), any())).thenReturn(Set.of("F_NVDA"));
        when(aiGateway.isAutomatedAvailable()).thenReturn(true);
        when(eventSummaryService.refreshNewsAndFilings(nvda))
                .thenReturn(new StockEventSummaryService.NewsRefreshResult(1, 0, List.of(mock(EnhancementDtos.StockNewsEventDto.class))));
        when(eventSummaryService.summarizeAutomated(nvda, "EARNINGS_WINDOW"))
                .thenThrow(new RuntimeException("AI_TIMEOUT"));

        // 1) Alpaca 未配置 + SEC 確定性 404 + AI 逾時 -> 仍推進水位，且 Alpaca 未配置首筆即短路不再呼叫第二筆
        when(optionsService.refreshStock(nvda)).thenThrow(new IllegalStateException("ALPACA_API_CREDENTIALS_REQUIRED"));
        when(insider13fService.syncForm4ForStock(nvda))
                .thenThrow(new SecEdgarClient.SecNotFoundException("SEC_EDGAR_NOT_FOUND:0001045810", null));
        when(insider13fService.syncForm4ForStock(amd))
                .thenThrow(new SecEdgarClient.SecUnauthorizedException("SEC_USER_AGENT_NOT_CONFIGURED", null));

        job.execute(null);
        verify(optionsService, times(1)).refreshStock(nvda);
        verify(optionsService, never()).refreshStock(amd);
        assertThat(storedWatermark).isNotNull();
        assertThat(storedWatermark.getConfigValue()).isEqualTo(TRADE_DATE.toString());

        // 2) SEC 403 限流仍計入 form4Failed 阻斷水位；同時非 force 重跑只跳過已於 sessionCloseAt 後結算的 F_NVDA，仍補抓 F_AMD
        storedWatermark = null;
        org.mockito.Mockito.doReturn(null).when(optionsService).refreshStock(nvda);
        org.mockito.Mockito.doReturn(null).when(optionsService).refreshStock(amd);
        org.mockito.Mockito.doThrow(new SecEdgarClient.SecUnauthorizedException("SEC_EDGAR_UNAUTHORIZED:403:SEC_SUBMISSIONS:0001045810", null))
                .when(insider13fService).syncForm4ForStock(nvda);
        org.mockito.Mockito.doReturn(null).when(insider13fService).syncForm4ForStock(amd);
        when(optionsSummaryRepository.findSettledFigisForSession(anyCollection(), org.mockito.ArgumentMatchers.eq(TRADE_DATE),
                org.mockito.ArgumentMatchers.eq(SESSION_CLOSE))).thenReturn(List.of("F_NVDA"));

        job.execute(null);
        assertThat(storedWatermark).isNull();
        verify(optionsService, times(1)).refreshStock(nvda);
        verify(optionsService, times(1)).refreshStock(amd);

        // 3) force=true 不受已結算期權跳過限制，F_NVDA 亦強制重刷；若遇暫時性 SPOT_PRICE_UNAVAILABLE 仍阻斷水位，結構性 OPTIONS_METRICS_INCOMPLETE 則可推進水位
        org.mockito.Mockito.doReturn(null).when(insider13fService).syncForm4ForStock(nvda);
        org.mockito.Mockito.doReturn(EnhancementDtos.OptionsSummaryDto.unavailable(
                "F_NVDA", "NVDA", com.ykm.stock.analytics.model.entity.StockOptionsSummary.STATUS_INSUFFICIENT_DATA,
                "SPOT_PRICE_UNAVAILABLE")).when(optionsService).refreshStock(nvda);
        JobExecutionContext forcedCtx = mock(JobExecutionContext.class);
        JobDataMap map = new JobDataMap();
        map.put("force", Boolean.TRUE);
        when(forcedCtx.getMergedJobDataMap()).thenReturn(map);

        job.execute(forcedCtx);
        verify(optionsService, times(2)).refreshStock(nvda);
        verify(optionsService, times(2)).refreshStock(amd);
        assertThat(storedWatermark).isNull();

        org.mockito.Mockito.doReturn(EnhancementDtos.OptionsSummaryDto.unavailable(
                "F_NVDA", "NVDA", com.ykm.stock.analytics.model.entity.StockOptionsSummary.STATUS_INSUFFICIENT_DATA,
                "OPTIONS_METRICS_INCOMPLETE")).when(optionsService).refreshStock(nvda);
        job.execute(forcedCtx);
        assertThat(storedWatermark).isNotNull();
    }

    private Stock stock(String figi, String symbol, String cik) {
        Stock s = new Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setStockType("Common Stock");
        s.setCik(cik);
        return s;
    }
}
