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
import org.springframework.data.domain.Limit;
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

    /** 查詢指定標的截至 through（含）的最近一筆已入庫交易日，供 ETF 預設最新報告對齊本地行情水位。 */
    @Transactional(readOnly = true)
    public LocalDate findLatestStoredQuoteDate(String figi, LocalDate through) {
        if (figi == null || figi.isBlank() || through == null) {
            return null;
        }
        List<StockQuote> latest = quotes.findByFigiAndQuoteDateLessThanEqualOrderByQuoteDateDesc(figi, through, Limit.of(1));
        return latest == null || latest.isEmpty() ? null : latest.getFirst().getQuoteDate();
    }

    /** 單日收盤價查詢（不依賴完整五年序列正規化），供技術序列缺口時仍保留 ETF 目標日參考收盤。 */
    @Transactional(readOnly = true)
    public BigDecimal findStoredClosePrice(String figi, LocalDate quoteDate) {
        if (figi == null || figi.isBlank() || quoteDate == null) {
            return null;
        }
        List<StockQuote> rows = quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(figi, quoteDate, quoteDate);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        BigDecimal close = rows.getLast().getClosePrice();
        return close != null && close.signum() > 0 ? close : null;
    }

    @Transactional(readOnly = true)
    public MetricResult<NormalizedPriceSeriesDto> normalize(AnalysisContext context, LocalDate from, LocalDate through) {
        LocalDate day = calendar.getLastClosedTradingDay(context.analysisAsOf());
        LocalDate to = through == null || through.isAfter(day) ? day : through;
        if (from == null || from.isAfter(to)) return missing("PRICE_RANGE_INVALID", "行情日期範圍無效。");
        Stock stock = stocks.findById(context.figi()).orElse(null);
        List<StockQuote> rawQuotes = quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(context.figi(), from, to);
        if (rawQuotes.isEmpty()) return missing("PRICE_MISSING", "指定期間沒有已保存行情。");
        List<StockQuoteRepository.QuoteBarRow> rows = new ArrayList<>(rawQuotes.size());
        for (StockQuote q : rawQuotes) {
            rows.add(new StockQuoteRepository.QuoteBarRow(
                    q.getFigi(), q.getQuoteDate(), q.getOpenPrice(), q.getHighPrice(),
                    q.getLowPrice(), q.getClosePrice(), q.getVolume(), q.getObservedAt()));
        }
        List<StockSplit> events = splits.findByFigiOrderByExecutionDateAsc(context.figi());
        StockStatus status = statuses.findById(context.figi()).orElse(null);
        return normalizeFromLoaded(context, from, to, stock, status, rows, events);
    }

    /** 供單股與兩階段雷達批次精算共用之純記憶體拆股正規化核心，嚴格保留缺日、非法 OHLCV 與拆股同步完整性檢查。 */
    public MetricResult<NormalizedPriceSeriesDto> normalizeFromLoaded(
            AnalysisContext context,
            LocalDate from,
            LocalDate through,
            Stock stock,
            StockStatus status,
            List<StockQuoteRepository.QuoteBarRow> raw,
            List<StockSplit> events) {
        LocalDate day = calendar.getLastClosedTradingDay(context.analysisAsOf());
        LocalDate to = through == null || through.isAfter(day) ? day : through;
        if (from == null || from.isAfter(to)) return missing("PRICE_RANGE_INVALID", "行情日期範圍無效。");
        if (raw == null || raw.isEmpty()) return missing("PRICE_MISSING", "指定期間沒有已保存行情。");
        if (raw.stream().anyMatch(q -> q == null || !QuoteNormalizer.validOhlcv(q.openPrice(), q.highPrice(), q.lowPrice(), q.closePrice(), q.volume()))) {
            return missing("INVALID_OHLCV", "已保存行情有無效數值。");
        }
        LocalDate previous = null;
        for (StockQuoteRepository.QuoteBarRow quote : raw) {
            LocalDate quoteDate = quote.quoteDate();
            if (quoteDate == null || !calendar.isTradingDay(quoteDate) || quoteDate.isBefore(from) || quoteDate.isAfter(to)
                    || previous != null && !quoteDate.isAfter(previous)) {
                return missing("PRICE_SESSION_INVALID", "已保存行情日期不是所選範圍內依序的交易日。");
            }
            previous = quoteDate;
        }
        List<StockSplit> splitList = events == null ? List.of() : events;
        LocalDate first = raw.getFirst().quoteDate();
        LocalDate target = raw.getLast().quoteDate();
        if (!first.equals(target) && (status == null || status.getSplitFromDate() == null || status.getSplitDate() == null
                || status.getSplitFromDate().isAfter(first) || status.getSplitDate().isBefore(target)
                || status.getSplitMessage() != null && !status.getSplitMessage().isBlank() && !status.getSplitMessage().startsWith("WARNING:"))) {
            return missing("SPLIT_SYNC_RANGE_INCOMPLETE", "行情期間的拆股資料尚未成功同步完整，不能假定沒有拆股。");
        }
        String basisId = context.figi() + ":" + target + ":SPLIT_ADJUSTED";
        PriceBasisDescriptor basis = new PriceBasisDescriptor(basisId, "STORED_QUOTES", PriceAdjustment.SPLIT_ADJUSTED, target,
                null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, stock == null ? null : stock.getCurrency(), true, null);
        List<NormalizedBarDto> bars = new ArrayList<>(raw.size());
        for (StockQuoteRepository.QuoteBarRow quote : raw) {
            BigDecimal factor = StockSplitCalculator.priceFactor(splitList, quote.quoteDate(), target);
            MetricResult<BigDecimal> volume = quote.volume() == null
                    ? MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("VOLUME_MISSING", "PRICE", "成交量缺失。"))
                    : MetricResult.available(quote.volume().divide(factor, MathContext.DECIMAL128));
            bars.add(new NormalizedBarDto(quote.quoteDate(), quote.openPrice().multiply(factor), quote.highPrice().multiply(factor),
                    quote.lowPrice().multiply(factor), quote.closePrice().multiply(factor), volume,
                    calendar.getSessionOpenAt(quote.quoteDate()), calendar.getSessionCloseAt(quote.quoteDate()),
                    calendar.getSessionCloseAt(quote.quoteDate()), quote.observedAt(), null, basisId));
        }
        List<LocalDate> sessions = calendar.getTradingDays(first, to);
        Set<LocalDate> observed = new HashSet<>(raw.size());
        for (StockQuoteRepository.QuoteBarRow row : raw) {
            observed.add(row.quoteDate());
        }
        List<LocalDate> gaps = sessions.stream().filter(d -> !observed.contains(d)).toList();
        if (!gaps.isEmpty()) return missing("PRICE_SESSIONS_MISSING", "技術分析期間有 " + gaps.size() + " 個交易日未有行情；不補價或猜停牌。");
        PriceSeriesCoverageDto coverage = new PriceSeriesCoverageDto(from, to, first, target, bars.size(), sessions.size(), gaps, List.of(basisId));
        List<AnalysisReasonDto> warnings = status != null && status.getSplitMessage() != null && status.getSplitMessage().startsWith("WARNING:")
                ? List.of(AnalysisReasonDto.of("SPLIT_SOURCE_WARNING", "PRICE", status.getSplitMessage())) : List.of();
        return new MetricResult<>(MetricStatus.AVAILABLE, new NormalizedPriceSeriesDto(context.figi(), "DAILY", bars, basis, coverage,
                MetricStatus.AVAILABLE, List.of(), null), warnings, List.of(), null);
    }
    private MetricResult<NormalizedPriceSeriesDto> missing(String code, String message) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(code, "PRICE", message));
    }
}