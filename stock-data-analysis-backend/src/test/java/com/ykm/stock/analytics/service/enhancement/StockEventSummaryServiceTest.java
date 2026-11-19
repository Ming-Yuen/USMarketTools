package com.ykm.stock.analytics.service.enhancement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ManualPromptDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockNewsEventCache;
import com.ykm.stock.analytics.repository.jpa.StockEventAiSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockNewsEventCacheRepository;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService.ValidatedSummary;
import com.ykm.stock.analytics.service.market.FinnhubService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.AlpacaService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockEventSummaryServiceTest {
    @Mock private AlpacaService alpacaService;
    @Mock private FinnhubService finnhubService;
    @Mock private MassiveService massiveService;
    @Mock private SecEdgarClient secEdgarClient;
    @Mock private EnhancementAiGateway aiGateway;
    @Mock private StockNewsEventCacheRepository cacheRepository;
    @Mock private StockEventAiSummaryRepository summaryRepository;
    @Mock private MarketStatusService calendar;
    @Mock private DateTimeService dateTimeService;
    @Mock private TransactionTemplate transactions;

    private final ObjectMapper mapper = new ObjectMapper();
    private StockEventSummaryService service;

    @BeforeEach
    void setUp() {
        service = new StockEventSummaryService(
                alpacaService, finnhubService, massiveService, secEdgarClient, aiGateway,
                cacheRepository, summaryRepository, calendar, dateTimeService, transactions, mapper);
        lenient().when(dateTimeService.now()).thenReturn(Instant.parse("2026-11-05T12:00:00Z"));
        lenient().when(calendar.getLastClosedTradingDay(any())).thenReturn(LocalDate.of(2026, 11, 4));
    }

    @Test
    void manualPromptWorksWithoutAiKeyAndValidatesOfficialGuidanceFromEx991() throws Exception {
        Stock stock = new Stock();
        stock.setFigi("BBG000B9XRY4");
        stock.setSymbol("AAPL");
        stock.setLongName("Apple Inc.");

        StockNewsEventCache filing = new StockNewsEventCache();
        filing.setFigi("BBG000B9XRY4");
        filing.setSymbol("AAPL");
        filing.setSourceType(StockNewsEventCache.SOURCE_SEC_8K);
        filing.setExternalId("0000320193-26-000024");
        filing.setPublisher("SEC EDGAR");
        filing.setHeadline("8-K (Items 2.02,9.01) · AAPL · 2026-11-02");
        filing.setUrl("https://www.sec.gov/Archives/edgar/data/320193/000032019326000024/aapl-20261102.htm");
        filing.setExhibit991Url("https://www.sec.gov/Archives/edgar/data/320193/000032019326000024/ex991.htm");
        filing.setExhibit991Excerpt("Outlook for Q1 FY2027: Revenue expected between $125.0B and $130.0B, diluted EPS $2.40 to $2.55.");
        filing.setPublishedAt(Instant.parse("2026-11-02T21:30:00Z"));

        when(cacheRepository.findTop30ByFigiOrderByPublishedAtDescIdDesc("BBG000B9XRY4")).thenReturn(List.of(filing));

        ManualPromptDto prompt = service.buildManualPrompt(stock);
        assertThat(prompt.sourceItemCount()).isEqualTo(1);
        assertThat(prompt.promptText()).contains("Outlook for Q1 FY2027");
        assertThat(prompt.inputFingerprint()).hasSize(64);

        String validJson = """
                {
                  "eventNature": "POSITIVE_CATALYST",
                  "bulletPoints": [
                    "公司公布 8-K Item 2.02 業績並於 Exhibit 99.1 給出下一季官方財測區間。"
                  ],
                  "guidance": {
                    "periodLabel": "Q1 FY2027",
                    "action": "ISSUED",
                    "basis": "GAAP",
                    "currency": "USD",
                    "unitScale": "BILLIONS",
                    "revenueLow": 125.0,
                    "revenueHigh": 130.0,
                    "epsLow": 2.40,
                    "epsHigh": 2.55,
                    "quoteExcerpt": "Revenue expected between $125.0B and $130.0B, diluted EPS $2.40 to $2.55."
                  }
                }
                """;
        ValidatedSummary validated = service.validateAndNormalizeSummaryJson(mapper.readTree(validJson), List.of(filing));
        assertThat(validated.eventNature()).isEqualTo("POSITIVE_CATALYST");
        assertThat(validated.bulletPoints()).hasSize(1);
        assertThat(validated.guidanceMap()).isNotNull();
        assertThat(validated.guidanceMap().get("sourceFilingUrl"))
                .isEqualTo("https://www.sec.gov/Archives/edgar/data/320193/000032019326000024/ex991.htm");

        // Inverted guidance range must be rejected
        String badRangeJson = validJson.replace("\"revenueLow\": 125.0", "\"revenueLow\": 140.0");
        assertThatThrownBy(() -> service.validateAndNormalizeSummaryJson(mapper.readTree(badRangeJson), List.of(filing)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SUMMARY_GUIDANCE_REVENUE_RANGE_INVALID");

        // Real quote with fabricated structured numbers (250/260 instead of 125/130) must be rejected
        String mismatchedNumbersJson = validJson
                .replace("\"revenueLow\": 125.0", "\"revenueLow\": 250.0")
                .replace("\"revenueHigh\": 130.0", "\"revenueHigh\": 260.0");
        assertThatThrownBy(() -> service.validateAndNormalizeSummaryJson(mapper.readTree(mismatchedNumbersJson), List.of(filing)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SUMMARY_GUIDANCE_NUMBER_NOT_IN_QUOTE");

        // Fabricated quoteExcerpt not present in official EX-99.1 must be rejected
        String fabricatedQuoteJson = validJson.replace(
                "Revenue expected between $125.0B and $130.0B, diluted EPS $2.40 to $2.55.",
                "We project $999B next quarter");
        assertThatThrownBy(() -> service.validateAndNormalizeSummaryJson(mapper.readTree(fabricatedQuoteJson), List.of(filing)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SUMMARY_GUIDANCE_QUOTE_NOT_IN_EXHIBIT");

        // Missing or stale fingerprint on manual import must be rejected
        assertThatThrownBy(() -> service.importManualJson(stock, validJson, "  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("NEWS_INPUT_FINGERPRINT_REQUIRED");
        assertThatThrownBy(() -> service.importManualJson(stock, validJson, "stale-fingerprint-0000"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("NEWS_INPUT_FINGERPRINT_STALE_REFRESH_PROMPT");
        assertThat(SecEdgarClient.isAuthFailure(new SecEdgarClient.SecUnauthorizedException("SEC_EDGAR_UNAUTHORIZED:403:ex991", null))).isTrue();
    }

    @Test
    void extractsExhibit991FromSecIndexHtmlAndDoesNotConfuseEx9910() {
        String html = """
                <html><body><table class="tableFile">
                  <tr><th>Seq</th><th>Description</th><th>Document</th><th>Type</th></tr>
                  <tr><td>1</td><td>8-K</td><td><a href="/Archives/edgar/data/320193/000032019326000024/aapl.htm">aapl.htm</a></td><td>8-K</td></tr>
                  <tr><td>2</td><td>Other Exhibit</td><td><a href="/Archives/edgar/data/320193/000032019326000024/ex9910.htm">ex9910.htm</a></td><td>EX-99.10</td></tr>
                  <tr><td>3</td><td>Press Release</td><td><a href="/Archives/edgar/data/320193/000032019326000024/a8-kex991.htm">a8-kex991.htm</a></td><td>EX-99.1</td></tr>
                </table></body></html>
                """;
        assertThat(service.findExhibit991FromIndexHtml(html)).isEqualTo("a8-kex991.htm");
    }

    @Test
    void concurrentClaimLoserDoesNotInvokeAiGatewayOrOverwriteWinner() {
        Stock stock = new Stock();
        stock.setFigi("BBG000B9XRY4");
        stock.setSymbol("AAPL");
        stock.setLongName("Apple Inc.");

        StockNewsEventCache filing = new StockNewsEventCache();
        filing.setFigi("BBG000B9XRY4");
        filing.setSymbol("AAPL");
        filing.setSourceType(StockNewsEventCache.SOURCE_ALPACA_NEWS);
        filing.setExternalId("news-1");
        filing.setHeadline("Apple launches new product");
        filing.setPublishedAt(Instant.parse("2026-11-04T20:00:00Z"));

        com.ykm.stock.analytics.model.entity.StockEventAiSummary winnerRow = new com.ykm.stock.analytics.model.entity.StockEventAiSummary();
        winnerRow.setId(99L);
        winnerRow.setFigi("BBG000B9XRY4");
        winnerRow.setSymbol("AAPL");
        winnerRow.setTriggerDate(LocalDate.of(2026, 11, 4));
        winnerRow.setTriggerReason("MANUAL");
        winnerRow.setStatus(com.ykm.stock.analytics.model.entity.StockEventAiSummary.STATUS_IN_PROGRESS);
        winnerRow.setAiProvider("OPENAI_API");
        winnerRow.setInputFingerprint("fp");
        winnerRow.setCreatedAt(Instant.parse("2026-11-05T12:00:00Z"));

        when(aiGateway.isAutomatedAvailable()).thenReturn(true);
        when(aiGateway.configuredProvider()).thenReturn("OPENAI_API");
        when(cacheRepository.findTop30ByFigiOrderByPublishedAtDescIdDesc("BBG000B9XRY4")).thenReturn(List.of(filing));
        when(transactions.getTransactionManager()).thenReturn(mock(PlatformTransactionManager.class));
        // Initial read misses; claim insert hits unique constraint race; loser reads back winner IN_PROGRESS
        when(summaryRepository.findByFigiAndInputFingerprint(any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerRow));
        when(summaryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate fingerprint"));

        com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.EventAiSummaryDto result =
                service.summarizeAutomated(stock, "MANUAL");

        assertThat(result.status()).isEqualTo(com.ykm.stock.analytics.model.entity.StockEventAiSummary.STATUS_IN_PROGRESS);
        verify(aiGateway, never()).generateStructuredJson(any(), any(), any(), any());
    }
}
