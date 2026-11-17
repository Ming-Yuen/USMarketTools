package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuoteNormalizationServiceTests {
    private static final LocalDate BEFORE = LocalDate.of(2026, 6, 9);
    private static final LocalDate AFTER = LocalDate.of(2026, 6, 10);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final QuoteNormalizationService service = new QuoteNormalizationService(quotes, stocks, splits, statuses, calendar);
    private final StockStatus status = new StockStatus();
    private final AnalysisContext context = new AnalysisContext("F1", Instant.parse("2026-06-10T22:00:00Z"),
            Instant.parse("2026-11-06T00:00:00Z"), AnalysisHorizon.POSITION);

    @BeforeEach
    void defaults() {
        Stock stock = new Stock();
        stock.setFigi("F1");
        stock.setCurrency("USD");
        when(stocks.findById("F1")).thenReturn(Optional.of(stock));
        status.setFigi("F1");
        status.setSplitFromDate(BEFORE);
        status.setSplitDate(AFTER);
        when(statuses.findById("F1")).thenReturn(Optional.of(status));
        when(calendar.getLastClosedTradingDay(context.analysisAsOf())).thenReturn(AFTER);
        when(calendar.getTradingDays(BEFORE, AFTER)).thenReturn(List.of(BEFORE, AFTER));
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getSessionOpenAt(any())).thenAnswer(call -> call.<LocalDate>getArgument(0)
                .atTime(9, 30).atZone(ZoneId.of("America/New_York")).toInstant());
        when(calendar.getSessionCloseAt(any())).thenAnswer(call -> call.<LocalDate>getArgument(0)
                .atTime(16, 0).atZone(ZoneId.of("America/New_York")).toInstant());
        when(splits.findByFigiOrderByExecutionDateAsc("F1")).thenReturn(List.of());
    }

    /** Scenario：修改前依身份proof；修改後typed split直接調整OHLC及反向成交量。 */
    @Test
    void adjustsHistoricalOhlcAndVolumeWithoutIdentityFlags() {
        StockSplit split = new StockSplit();
        split.setSourceEventId("S1");
        split.setFigi("F1");
        split.setExecutionDate(AFTER);
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(new BigDecimal("4"));
        when(splits.findByFigiOrderByExecutionDateAsc("F1")).thenReturn(List.of(split));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc("F1", BEFORE, AFTER))
                .thenReturn(List.of(quote(BEFORE, "100", "120", "90", "110", "100"),
                        quote(AFTER, "25", "30", "22.5", "27.5", "400")));

        MetricResult<NormalizedPriceSeriesDto> result = service.normalize(context, BEFORE, AFTER);

        assertThat(result.status()).isEqualTo(MetricStatus.AVAILABLE);
        NormalizedBarDto first = result.value().bars().getFirst();
        assertThat(first.open()).isEqualByComparingTo("25");
        assertThat(first.high()).isEqualByComparingTo("30");
        assertThat(first.low()).isEqualByComparingTo("22.5");
        assertThat(first.close()).isEqualByComparingTo("27.5");
        assertThat(first.volume().value()).isEqualByComparingTo("400");
        assertThat(result.value().evidenceRefs()).isEmpty();
    }

    /** Scenario：技術價格序列缺交易日只回報不足，不能用補價或猜停牌掩蓋缺口。 */
    @Test
    void missingSessionDoesNotInventBars() {
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc("F1", BEFORE, AFTER))
                .thenReturn(List.of(quote(BEFORE, "10", "12", "9", "11", "100")));
        MetricResult<NormalizedPriceSeriesDto> result = service.normalize(context, BEFORE, AFTER);
        assertThat(result.status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(result.value()).isNull();
        assertThat(result.reasons()).extracting(AnalysisReasonDto::code).contains("PRICE_SESSIONS_MISSING");
        verify(quotes, never()).saveAll(any());
    }

    /** Scenario：沒有split資料不等於沒有拆股；尚未成功查過區間時不可假裝已調整。 */
    @Test
    void absentSplitHistoryIsInsufficient() {
        status.setSplitFromDate(null);
        status.setSplitDate(null);
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc("F1", BEFORE, AFTER))
                .thenReturn(List.of(quote(BEFORE, "10", "12", "9", "11", "100"),
                        quote(AFTER, "10", "12", "9", "11", "100")));
        MetricResult<NormalizedPriceSeriesDto> result = service.normalize(context, BEFORE, AFTER);
        assertThat(result.status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(result.value()).isNull();
    }

    /** Scenario：未定位其他來源事件的WARNING可見但不封鎖已同步行情的拆股調整。 */
    @Test
    void sourceWarningIsVisibleAndDoesNotBlockNormalization() {
        status.setSplitMessage("WARNING:SPLIT_SOURCE_UNMATCHED:1:UNKNOWN_EVENT");
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc("F1", BEFORE, AFTER))
                .thenReturn(List.of(quote(BEFORE, "10", "12", "9", "11", "100"),
                        quote(AFTER, "10", "12", "9", "11", "100")));

        MetricResult<NormalizedPriceSeriesDto> result = service.normalize(context, BEFORE, AFTER);

        assertThat(result.status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(result.reasons()).extracting(AnalysisReasonDto::code).contains("SPLIT_SOURCE_WARNING");
    }

    /** Scenario：已確定本FIGI的拆股更正失敗，即使舊日期涵蓋區間仍不能假裝單位正確。 */
    @Test
    void identifiedStockErrorBlocksOnlyItsNormalization() {
        status.setSplitMessage("ERROR:SPLIT_EVENTS_UNRESOLVED:S1");
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc("F1", BEFORE, AFTER))
                .thenReturn(List.of(quote(BEFORE, "10", "12", "9", "11", "100"),
                        quote(AFTER, "10", "12", "9", "11", "100")));

        MetricResult<NormalizedPriceSeriesDto> result = service.normalize(context, BEFORE, AFTER);

        assertThat(result.status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(result.reasons()).extracting(AnalysisReasonDto::code).contains("SPLIT_SYNC_RANGE_INCOMPLETE");
        assertThat(result.value()).isNull();
    }

    @Test
    void futureRequestedEndIsClampedToLastCompletedSession() {
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc("F1", BEFORE, AFTER))
                .thenReturn(List.of(quote(BEFORE, "10", "12", "9", "11", "100"),
                        quote(AFTER, "10", "12", "9", "11", "100")));
        assertThat(service.normalize(context, BEFORE, AFTER.plusMonths(1)).status()).isEqualTo(MetricStatus.AVAILABLE);
        verify(quotes).findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc("F1", BEFORE, AFTER);
    }

    private StockQuote quote(LocalDate date, String open, String high, String low, String close, String volume) {
        StockQuote row = new StockQuote();
        row.setFigi("F1");
        row.setQuoteDate(date);
        row.setOpenPrice(new BigDecimal(open));
        row.setHighPrice(new BigDecimal(high));
        row.setLowPrice(new BigDecimal(low));
        row.setClosePrice(new BigDecimal(close));
        row.setVolume(new BigDecimal(volume));
        row.setObservedAt(Instant.parse("2026-06-10T22:00:00Z"));
        return row;
    }
}
