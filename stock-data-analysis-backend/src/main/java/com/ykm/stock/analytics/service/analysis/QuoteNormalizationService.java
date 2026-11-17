package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.service.market.MarketStatusService;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.QuoteNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.*;

/** 原始日線按業務拆股表轉成目標交易日單位；沒有外部下載、原文重驗或額外資格閘門。 */
@Service
@RequiredArgsConstructor
public class QuoteNormalizationService {
    private final StockQuoteRepository quotes;
    private final StockRepository stocks;
    private final StockSplitRepository splits;
    private final StockStatusRepository statuses;
    private final MarketStatusService calendar;

    @Transactional(readOnly = true)
    public MetricResult<NormalizedPriceSeriesDto> normalize(AnalysisContext context, LocalDate from, LocalDate through) {
        LocalDate day = calendar.getLastClosedTradingDay(context.analysisAsOf());
        LocalDate to = through == null || through.isAfter(day) ? day : through;
        if (from == null || from.isAfter(to)) return missing("PRICE_RANGE_INVALID", "行情日期範圍無效。");
        Stock stock = stocks.findById(context.figi()).orElse(null);
        List<StockQuote> raw = quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(context.figi(), from, to);
        if (raw.isEmpty()) return missing("PRICE_MISSING", "指定期間沒有已保存行情。");
        if (raw.stream().anyMatch(q -> !QuoteNormalizer.valid(q))) return missing("INVALID_OHLCV", "已保存行情有無效數值。");
        LocalDate previous = null;
        for (StockQuote quote : raw) {
            LocalDate quoteDate = quote.getQuoteDate();
            if (!calendar.isTradingDay(quoteDate) || quoteDate.isBefore(from) || quoteDate.isAfter(to)
                    || previous != null && !quoteDate.isAfter(previous))
                return missing("PRICE_SESSION_INVALID", "已保存行情日期不是所選範圍內依序的交易日。");
            previous = quoteDate;
        }
        List<StockSplit> events = splits.findByFigiOrderByExecutionDateAsc(context.figi());
        LocalDate first = raw.getFirst().getQuoteDate();
        LocalDate target = raw.getLast().getQuoteDate();
        StockStatus status = statuses.findById(context.figi()).orElse(null);
        if (!first.equals(target) && (status == null || status.getSplitFromDate() == null || status.getSplitDate() == null
                || status.getSplitFromDate().isAfter(first) || status.getSplitDate().isBefore(target)
                || status.getSplitMessage() != null && !status.getSplitMessage().isBlank() && !status.getSplitMessage().startsWith("WARNING:")))
            return missing("SPLIT_SYNC_RANGE_INCOMPLETE", "行情期間的拆股資料尚未成功同步完整，不能假定沒有拆股。");
        String basisId = context.figi() + ":" + target + ":SPLIT_ADJUSTED";
        PriceBasisDescriptor basis = new PriceBasisDescriptor(basisId, "STORED_QUOTES", PriceAdjustment.SPLIT_ADJUSTED, target,
                null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, stock == null ? null : stock.getCurrency(), true, null);
        List<NormalizedBarDto> bars = new ArrayList<>();
        for (StockQuote quote : raw) {
            BigDecimal factor = StockSplitCalculator.priceFactor(events, quote.getQuoteDate(), target);
            MetricResult<BigDecimal> volume = quote.getVolume() == null
                    ? MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("VOLUME_MISSING", "PRICE", "成交量缺失。"))
                    : MetricResult.available(quote.getVolume().divide(factor, MathContext.DECIMAL128));
            bars.add(new NormalizedBarDto(quote.getQuoteDate(), quote.getOpenPrice().multiply(factor), quote.getHighPrice().multiply(factor),
                    quote.getLowPrice().multiply(factor), quote.getClosePrice().multiply(factor), volume,
                    calendar.getSessionOpenAt(quote.getQuoteDate()), calendar.getSessionCloseAt(quote.getQuoteDate()),
                    calendar.getSessionCloseAt(quote.getQuoteDate()), quote.getObservedAt(), null, basisId));
        }
        List<LocalDate> sessions = calendar.getTradingDays(raw.getFirst().getQuoteDate(), to);
        Set<LocalDate> observed = new HashSet<>(raw.stream().map(StockQuote::getQuoteDate).toList());
        List<LocalDate> gaps = sessions.stream().filter(d -> !observed.contains(d)).toList();
        if (!gaps.isEmpty()) return missing("PRICE_SESSIONS_MISSING", "技術分析期間有 " + gaps.size() + " 個交易日未有行情；不補價或猜停牌。");
        PriceSeriesCoverageDto coverage = new PriceSeriesCoverageDto(from, to, raw.getFirst().getQuoteDate(), target, bars.size(), sessions.size(), gaps, List.of(basisId));
        List<AnalysisReasonDto> warnings = status != null && status.getSplitMessage() != null && status.getSplitMessage().startsWith("WARNING:")
                ? List.of(AnalysisReasonDto.of("SPLIT_SOURCE_WARNING", "PRICE", status.getSplitMessage())) : List.of();
        return new MetricResult<>(MetricStatus.AVAILABLE, new NormalizedPriceSeriesDto(context.figi(), "DAILY", bars, basis, coverage,
                MetricStatus.AVAILABLE, List.of(), null), warnings, List.of(), null);
    }
    private MetricResult<NormalizedPriceSeriesDto> missing(String code, String message) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(code, "PRICE", message));
    }
}