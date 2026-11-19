package com.ykm.stock.analytics.controller;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.AiChannelStatusDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagCandidateDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagConfirmRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagConfirmResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagProposalPreviewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.EventAiSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.InsiderSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.Institutional13fSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ManualJsonImportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ManualPromptDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.OptionsSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ShortInterestSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.StockEnhancementOverviewDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.enhancement.EnhancementAiGateway;
import com.ykm.stock.analytics.service.enhancement.EnhancementAiGateway.AiChannelStatus;
import com.ykm.stock.analytics.service.enhancement.MacroCreditSpreadService;
import com.ykm.stock.analytics.service.enhancement.MacroCreditSpreadService.SyncResult;
import com.ykm.stock.analytics.service.enhancement.OptionsEnhancementService;
import com.ykm.stock.analytics.service.enhancement.SecCustomTagMappingService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.Form4SyncResult;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.Sync13fResult;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService.ShortSyncResult;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService.NewsRefreshResult;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 06_Enhancement 獨立籌碼、事件、信用利差與自訂標籤顯式映射 REST API。
 * 嚴格區分唯讀查詢（GET，0 次外部 API）與明確按需刷新／AI 摘要／人工確認副作用（POST/DELETE）。
 */
@RestController
@RequestMapping("enhancements")
@RequiredArgsConstructor
public class StockEnhancementController {
    private final StockRepository stockRepository;
    private final StockValuationLatestRepository latestValuationRepository;
    private final MacroCreditSpreadService creditSpreadService;
    private final OptionsEnhancementService optionsService;
    private final SecInsiderAndInstitutionalService insider13fService;
    private final ShortInterestService shortInterestService;
    private final StockEventSummaryService eventSummaryService;
    private final SecCustomTagMappingService customTagMappingService;
    private final EnhancementAiGateway aiGateway;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;

    @GetMapping("/stocks/{figi}")
    public StockEnhancementOverviewDto getStockEnhancements(
            @PathVariable String figi,
            @RequestParam(required = false) LocalDate asOfDate) {
        Stock stock = requireStock(figi);
        LocalDate effectiveDate = asOfDate != null ? asOfDate : calendar.getLastClosedTradingDay(dateTimeService.now());
        boolean isEtf = SecurityTypePolicy.isEtfType(stock.getStockType());
        StockValuationLatest latestVal = latestValuationRepository.findById(figi).orElse(null);
        // 歷史回看（asOfDate 早於最新估值日）嚴格不滲漏未來 verdict、基本面支撐與收盤價
        boolean latestVisibleAtDate = latestVal != null && latestVal.getValuationDate() != null
                && !latestVal.getValuationDate().isAfter(effectiveDate);
        String verdict = latestVisibleAtDate ? latestVal.getVerdict() : null;
        String fundamentalBacking = latestVisibleAtDate ? latestVal.getFundamentalBacking() : null;
        java.math.BigDecimal visibleClose = latestVisibleAtDate ? latestVal.getClosePrice() : null;
        java.time.Instant visibleCutoff = asOfDate != null ? calendar.getSessionCloseAt(effectiveDate) : null;

        AiChannelStatus ch = aiGateway.status();
        AiChannelStatusDto aiChannel = new AiChannelStatusDto(
                ch.provider(), ch.automatedAvailable(), ch.activeModel(), ch.reasoningEffort(), ch.manualModeAlwaysAvailable());
        CreditSpreadSummaryDto creditSpread = creditSpreadService.evaluateAt(effectiveDate, visibleCutoff);
        OptionsSummaryDto options = optionsService.readAt(stock, effectiveDate, null, visibleClose);
        InsiderSummaryDto insiders = insider13fService.evaluateInsiders(stock, effectiveDate, verdict);
        Institutional13fSummaryDto inst13f = insider13fService.evaluate13fForStock(stock, effectiveDate);
        ShortInterestSummaryDto shortInterest = shortInterestService.evaluateStock(stock, effectiveDate, fundamentalBacking, false);

        return new StockEnhancementOverviewDto(
                stock.getFigi(),
                stock.getSymbol(),
                stock.getStockType(),
                stock.getCik(),
                isEtf,
                aiChannel,
                creditSpread,
                options,
                insiders,
                inst13f,
                shortInterest,
                eventSummaryService.listRecentNewsEvents(figi, visibleCutoff),
                eventSummaryService.listSummaries(figi, visibleCutoff),
                customTagMappingService.listMappings(stock),
                SecCustomTagMappingService.ALLOWED_TARGET_CONCEPTS
        );
    }

    @PostMapping("/stocks/{figi}/options/refresh")
    public OptionsSummaryDto refreshOptions(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        return optionsService.refreshStock(stock);
    }

    @PostMapping("/stocks/{figi}/insiders/refresh")
    public Map<String, Object> refreshInsiders(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        Form4SyncResult sync = insider13fService.syncForm4ForStock(stock);
        LocalDate today = calendar.getLastClosedTradingDay(dateTimeService.now());
        StockValuationLatest latestVal = latestValuationRepository.findById(figi).orElse(null);
        InsiderSummaryDto summary = insider13fService.evaluateInsiders(stock, today, latestVal == null ? null : latestVal.getVerdict());
        return Map.of("scannedFilings", sync.scannedFilings(), "savedTransactions", sync.savedTransactions(), "insiders", summary);
    }

    @PostMapping("/stocks/{figi}/short-interest/refresh")
    public Map<String, Object> refreshShortInterest(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        ShortSyncResult sync = shortInterestService.refreshStock(stock);
        LocalDate today = calendar.getLastClosedTradingDay(dateTimeService.now());
        ShortInterestSummaryDto summary = shortInterestService.evaluateStock(stock, today, null, false);
        return Map.of("fetchedRows", sync.fetchedRows(), "savedRows", sync.savedRows(), "shortInterest", summary);
    }

    @PostMapping("/stocks/{figi}/news/refresh")
    public NewsRefreshResult refreshNews(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        return eventSummaryService.refreshNewsAndFilings(stock);
    }

    @PostMapping("/stocks/{figi}/news/summarize")
    public EventAiSummaryDto summarizeNewsAutomated(
            @PathVariable String figi,
            @RequestParam(defaultValue = "MANUAL") String triggerReason) {
        Stock stock = requireStock(figi);
        return eventSummaryService.summarizeAutomated(stock, triggerReason);
    }

    @GetMapping("/stocks/{figi}/news/prompt")
    public ManualPromptDto getNewsManualPrompt(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        return eventSummaryService.buildManualPrompt(stock);
    }

    @PostMapping("/stocks/{figi}/news/import")
    public EventAiSummaryDto importNewsManualSummary(
            @PathVariable String figi,
            @RequestBody ManualJsonImportRequest request) {
        Stock stock = requireStock(figi);
        return eventSummaryService.importManualJson(
                stock,
                request == null ? null : request.rawJson(),
                request == null ? null : request.inputFingerprint());
    }

    @GetMapping("/stocks/{figi}/custom-tags/candidates")
    public List<CustomTagCandidateDto> getCustomTagCandidates(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        return customTagMappingService.sampleCandidates(stock);
    }

    @GetMapping("/stocks/{figi}/custom-tags/prompt")
    public ManualPromptDto getCustomTagManualPrompt(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        return customTagMappingService.buildManualPrompt(stock);
    }

    @PostMapping("/stocks/{figi}/custom-tags/propose")
    public CustomTagProposalPreviewDto proposeCustomTagsAutomated(@PathVariable String figi) {
        Stock stock = requireStock(figi);
        return customTagMappingService.proposeAutomated(stock);
    }

    @PostMapping("/stocks/{figi}/custom-tags/import")
    public CustomTagProposalPreviewDto importCustomTagManualPreview(
            @PathVariable String figi,
            @RequestBody ManualJsonImportRequest request) {
        Stock stock = requireStock(figi);
        return customTagMappingService.importManualPreview(stock, request == null ? null : request.rawJson());
    }

    @PostMapping("/stocks/{figi}/custom-tags/confirm")
    public CustomTagConfirmResultDto confirmCustomTags(
            @PathVariable String figi,
            @RequestBody CustomTagConfirmRequest request) {
        Stock stock = requireStock(figi);
        return customTagMappingService.confirmMappings(stock, request);
    }

    @DeleteMapping("/stocks/{figi}/custom-tags/{mappingId}")
    public CustomTagConfirmResultDto disableCustomTag(
            @PathVariable String figi,
            @PathVariable Long mappingId) {
        Stock stock = requireStock(figi);
        return customTagMappingService.disableMapping(stock, mappingId);
    }

    @GetMapping("/macro/credit-spread")
    public CreditSpreadSummaryDto getCreditSpread(@RequestParam(required = false) LocalDate asOfDate) {
        LocalDate effective = asOfDate != null ? asOfDate : calendar.getLastClosedTradingDay(dateTimeService.now());
        return creditSpreadService.evaluateAt(effective);
    }

    @PostMapping("/macro/credit-spread/refresh")
    public Map<String, Object> refreshCreditSpread() {
        SyncResult result = creditSpreadService.syncLatest();
        LocalDate effective = calendar.getLastClosedTradingDay(dateTimeService.now());
        CreditSpreadSummaryDto summary = creditSpreadService.evaluateAt(effective);
        return Map.of("fetchedCount", result.fetchedCount(), "savedCount", result.savedCount(), "creditSpread", summary);
    }

    @PostMapping("/13f/refresh")
    public Sync13fResult refresh13f(@RequestParam(defaultValue = "15") int maxManagers) {
        return insider13fService.syncFlagship13fManagers(maxManagers);
    }

    private Stock requireStock(String figi) {
        return stockRepository.findById(figi)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", ex.getReason() == null ? "ENHANCEMENT_OPERATION_FAILED" : ex.getReason()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", ex.getMessage() == null ? "ENHANCEMENT_SERVICE_UNAVAILABLE" : ex.getMessage()));
    }
}
