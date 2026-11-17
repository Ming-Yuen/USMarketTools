package com.ykm.stock.analytics.service.futu;

import com.futu.openapi.pb.QotCommon.ValuationIntervalType;
import com.futu.openapi.pb.QotCommon.ValuationType;
import com.futu.openapi.pb.QotGetValuationDetail;
import com.futu.openapi.pb.QotGetValuationDetail.ValuationTrend.ValuationHistoricalItem;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.futu.openapi.pb.QotCommon.ValuationType.*;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.usMarketDate;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.finite;

@Service
@RequiredArgsConstructor
public class FutuStockDataService {
    private static final List<ValuationType> METRICS = List.of(ValuationType_PE, ValuationType_PB, ValuationType_PS);
    private final StockRepository stocks;
    private final MarketStatusService calendar;
    private final FutuService futu;

    /** 只使用富途歷史序列的實際日期及倍數，月頻取區間內每月最後一個觀察點。 */
    public List<ValuationResultDto> history(String figi, LocalDate from, LocalDate through, String frequency) {
        if (from == null || through == null || from.isAfter(through))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid from/through dates are required");
        boolean monthly = "MONTH_END".equalsIgnoreCase(frequency);
        if (!monthly && !"DAILY".equalsIgnoreCase(frequency))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "frequency must be DAILY or MONTH_END");
        Stock stock = stock(figi);
        Instant now = Instant.now();
        LocalDate lastSession = calendar.getLastClosedTradingDay(now);
        LocalDate end = through.isAfter(lastSession) ? lastSession : through;
        if (from.isAfter(end)) return List.of();
        ValuationIntervalType interval = interval(from, now.atZone(MarketStatusService.NY_ZONE).toLocalDate());
        Map<LocalDate, EnumMap<ValuationType, BigDecimal>> values = new TreeMap<>();
        try (FutuService.ObservationSession session = futu.openObservationSession()) {
            for (ValuationType metric : METRICS) {
                QotGetValuationDetail.S2C data = session.valuationDetail(stock.getSymbol(), metric, interval);
                if (data.hasValuationType() && data.getValuationType() != metric)
                    throw new IllegalStateException("FUTU_VALUATION_METRIC_MISMATCH:" + metric);
                for (ValuationHistoricalItem item : data.getTrend().getHistoricalItemsList()) {
                    if (!item.hasValue()) continue;
                    LocalDate day = usMarketDate(item.hasTimeStr() ? item.getTimeStr() : null,
                            item.hasTime() ? item.getTime() : null);
                    if (day == null) throw new IllegalStateException("FUTU_VALUATION_DATE_MISSING");
                    if (day.isBefore(from) || day.isAfter(end)) continue;
                    BigDecimal amount = finite(item.getValue());
                    BigDecimal previous = values.computeIfAbsent(day, key -> new EnumMap<>(ValuationType.class))
                            .putIfAbsent(metric, amount);
                    if (previous != null && previous.compareTo(amount) != 0)
                        throw new IllegalStateException("FUTU_VALUATION_DUPLICATE_DATE:" + metric + ":" + day);
                }
            }
        } catch (IllegalStateException | DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "富途歷史估值查詢失敗：" + ex.getMessage(), ex);
        }
        List<ValuationResultDto> rows = values.entrySet().stream()
                .map(entry -> ValuationResultDto.fromFutu(figi, entry.getKey(), null, null, entry.getValue().get(ValuationType_PE),
                        entry.getValue().get(ValuationType_PB), entry.getValue().get(ValuationType_PS)))
                .toList();
        if (!monthly) return rows;
        Map<YearMonth, ValuationResultDto> monthEnds = new LinkedHashMap<>();
        for (ValuationResultDto row : rows) monthEnds.put(YearMonth.from(row.valuationDate()), row);
        return List.copyOf(monthEnds.values());
    }

    private Stock stock(String figi) {
        return stocks.findById(figi).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
    }

    private static ValuationIntervalType interval(LocalDate from, LocalDate today) {
        if (!from.isBefore(today.minusMonths(3))) return ValuationIntervalType.ValuationIntervalType_Month3;
        if (!from.isBefore(today.minusMonths(6))) return ValuationIntervalType.ValuationIntervalType_Month6;
        if (!from.isBefore(today.minusYears(1))) return ValuationIntervalType.ValuationIntervalType_Year1;
        if (!from.isBefore(today.minusYears(2))) return ValuationIntervalType.ValuationIntervalType_Year2;
        if (!from.isBefore(today.minusYears(3))) return ValuationIntervalType.ValuationIntervalType_Year3;
        if (!from.isBefore(today.minusYears(5))) return ValuationIntervalType.ValuationIntervalType_Year5;
        if (!from.isBefore(today.minusYears(10))) return ValuationIntervalType.ValuationIntervalType_Year10;
        if (!from.isBefore(today.minusYears(20))) return ValuationIntervalType.ValuationIntervalType_Year20;
        if (!from.isBefore(today.minusYears(30))) return ValuationIntervalType.ValuationIntervalType_Year30;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FUTU_VALUATION_RANGE_EXCEEDS_THIRTY_YEARS");
    }
}
