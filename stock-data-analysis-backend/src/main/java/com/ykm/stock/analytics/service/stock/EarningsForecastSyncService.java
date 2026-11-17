package com.ykm.stock.analytics.service.stock;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetEarningsCalendar;
import com.futu.openapi.pb.QotGetEarningsCalendar.EarningsCalendarItem;
import com.futu.openapi.pb.QotGetEarningsCalendar.EstimateData;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.futu.FutuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.futu.openapi.pb.QotGetEarningsCalendar.EarningsCalendarEstimateType.*;
import static com.futu.openapi.pb.QotGetEarningsCalendar.EarningsCalendarPeriodType.*;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.finite;

@Service
@RequiredArgsConstructor
@Slf4j
public class EarningsForecastSyncService {
    private static final int US = QotCommon.QotMarket.QotMarket_US_Security_VALUE;
    private final StockRepository stocks;
    private final StockEarningsForecastRepository forecasts;

    public record Window(LocalDate from, LocalDate through) {}

    /** 來源只用於預期值；財報日曆由 MarketEventSyncService 統一取得，不重複存事件。 */
    public List<StockEarningsForecast> download(FutuService.ObservationSession session, Window requested) {
        List<EarningsCalendarItem> entries = new ArrayList<>();
        for (LocalDate start = requested.from(); !start.isAfter(requested.through()); start = start.plusDays(7)) {
            LocalDate end = start.plusDays(6).isAfter(requested.through()) ? requested.through() : start.plusDays(6);
            QotGetEarningsCalendar.S2C reply = session.earningsCalendar(start, end);
            for (EarningsCalendarItem item : reply.getItemListList()) {
                if (!item.hasSecurity() || item.getSecurity().getMarket() != US || item.getSecurity().getCode().isBlank())
                    throw new IllegalStateException("EARNINGS_FORECAST_SECURITY_INVALID");
                if (!item.hasEarningsDate() || item.getEarningsDate().isBlank()) continue;
                LocalDate date = LocalDate.parse(item.getEarningsDate());
                if (date.isBefore(start) || date.isAfter(end)) throw new IllegalStateException("EARNINGS_FORECAST_DATE_OUTSIDE_WINDOW:" + date);
                entries.add(item);
            }
        }
        return matchExpectations(entries, Instant.now());
    }

    List<StockEarningsForecast> matchExpectations(List<EarningsCalendarItem> entries, Instant observedAt) {
        Set<String> symbols = entries.stream().map(item -> item.getSecurity().getCode()).collect(Collectors.toSet());
        if (symbols.isEmpty()) return List.of();
        // 盈利預期只按富途代碼匹配唯一普通股，不依賴 SEC CIK，ETF、ADR、權證不進分析資料。
        Map<String, List<Stock>> bySymbol = stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, symbols).stream()
                .filter(Stock::isTracked)
                .filter(stock -> SecurityTypePolicy.isOperatingCompanyType(stock.getStockType()))
                .collect(Collectors.groupingBy(Stock::getSymbol));
        Map<StockEarningsForecast.Key, StockEarningsForecast> rows = new LinkedHashMap<>();
        for (EarningsCalendarItem entry : entries) {
            String symbol = entry.getSecurity().getCode();
            List<Stock> matches = bySymbol.getOrDefault(symbol, List.of());
            if (matches.size() != 1) continue;
            String fiscalPeriod = entry.hasPeriodText() ? entry.getPeriodText().strip() : "";
            for (EstimateData estimate : entry.getEstimateListList()) {
                int metric = estimate.getEstimateType();
                if (!estimate.hasPredictValue() || (metric != EarningsCalendarEstimateType_EPS_VALUE
                        && metric != EarningsCalendarEstimateType_Revenue_VALUE)) continue;
                String periodType = periodType(estimate.getPeriodType());
                String currency = currency(estimate.getCurrency());
                if (fiscalPeriod.isBlank() || periodType == null || currency == null) {
                    log.warn("Earnings forecast omitted | symbol={} reason=FISCAL_PERIOD_OR_CURRENCY_MISSING", symbol);
                    continue;
                }
                StockEarningsForecast.Key key = new StockEarningsForecast.Key(matches.getFirst().getFigi(), fiscalPeriod,
                        periodType, observedAt.atZone(ZoneId.of("America/New_York")).toLocalDate());
                StockEarningsForecast row = rows.computeIfAbsent(key, ignored -> forecast(key, currency, observedAt));
                if (!currency.equals(row.getCurrency())) throw new IllegalStateException("EARNINGS_FORECAST_CURRENCY_CONFLICT:" + symbol);
                BigDecimal value = finite(estimate.getPredictValue());
                BigDecimal previous = metric == EarningsCalendarEstimateType_EPS_VALUE ? row.getEps() : row.getRevenue();
                if (previous != null && previous.compareTo(value) != 0) throw new IllegalStateException("EARNINGS_FORECAST_VALUE_CONFLICT:" + symbol);
                if (metric == EarningsCalendarEstimateType_EPS_VALUE) row.setEps(value);
                else row.setRevenue(value);
            }
        }
        return List.copyOf(rows.values());
    }

    private StockEarningsForecast forecast(StockEarningsForecast.Key key, String currency, Instant observedAt) {
        StockEarningsForecast row = new StockEarningsForecast();
        row.setFigi(key.getFigi());
        row.setFiscalPeriod(key.getFiscalPeriod());
        row.setPeriodType(key.getPeriodType());
        row.setObservationDate(key.getObservationDate());
        row.setObservedAt(observedAt);
        row.setCurrency(currency);
        row.setSource("FUTU");
        // SDK 回應沒有明確起止日；保留來源財政期文字，不用發布日期猜測財季日期。
        return row;
    }

    @Transactional
    public void save(List<StockEarningsForecast> rows) {
        forecasts.saveAllAndFlush(rows);
    }

    private static String periodType(int code) {
        return switch (code) {
            case EarningsCalendarPeriodType_Quarterly_VALUE -> "QUARTERLY";
            case EarningsCalendarPeriodType_SemiAnnual_VALUE -> "SEMIANNUAL";
            case EarningsCalendarPeriodType_Annual_VALUE -> "ANNUAL";
            default -> null;
        };
    }

    private static String currency(String code) {
        try { return Currency.getInstance(code).getCurrencyCode(); }
        catch (IllegalArgumentException invalid) { return null; }
    }
}
