package com.ykm.stock.analytics.service.market;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.EventItem;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketEventService {
    private final MarketEventRepository events;
    private final StockRepository stocks;
    private final DateTimeService dateTimeService;

    /** 直接讀取近期已知日程；沒有自選股限制，也不自行捏造日程的鐘點。 */
    @Transactional(readOnly = true)
    public List<EventItem> getUpcomingEvents() {
        LocalDate from = dateTimeService.nowZonedDateTime(ZoneId.of("America/New_York")).toLocalDate();
        List<MarketEvent> rows = events.findByEventDateBetweenOrderByEventDateAscOccurrenceAtAsc(from, from.plusDays(14));
        List<String> figis = rows.stream().map(MarketEvent::getFigi).filter(Objects::nonNull).distinct().toList();
        Map<String, Stock> identities = stocks.findAllById(figis).stream().collect(Collectors.toMap(Stock::getFigi, Function.identity()));
        return rows.stream().filter(row -> !"CANCELLED".equals(row.getStatus())).map(row -> {
            Stock stock = identities.get(row.getFigi());
            return new EventItem(row.getEventKey(), row.getTitle(), row.getEventSubtype(), row.getEventType(),
                    row.getOccurrenceAt() == null ? row.getEventDate().toString() : row.getOccurrenceAt().toString(),
                    "HIGH", stock == null ? null : stock.getSymbol(), row.getSource(), row.getSourceUrl(), row.getStatus(),
                    row.getOccurrenceAt() == null);
        }).toList();
    }
}
