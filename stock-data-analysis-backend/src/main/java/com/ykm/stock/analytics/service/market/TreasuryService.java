package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.converter.jpa.TreasuryParYieldDailyConverter;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class TreasuryService {
    private final TreasuryParYieldDailyRepository treasuryParYieldDailyRepository;
    private final TreasuryParYieldDailyConverter treasuryParYieldDailyConverter;

    /** 直接合併當月 2Y/10Y 資料；不再寫入另一套通用 observation/evidence 表。 */
    @Transactional
    public void saveOrUpdateTreasuryParYieldDailies(TreasuryYieldCurveFeed feed) {
        if (feed == null || feed.getEntries() == null || feed.getEntries().isEmpty()) return;
        List<TreasuryYieldCurveFeed.Entry> entries = feed.getEntries().stream()
                .filter(entry -> entry != null && entry.getContent() != null
                        && entry.getContent().getProperties() != null
                        && entry.getContent().getProperties().getDate() != null
                        && (entry.getContent().getProperties().getTenYear() != null
                            || entry.getContent().getProperties().getTwoYear() != null)).toList();
        if (entries.isEmpty()) return;
        List<LocalDate> dates = entries.stream()
                .map(entry -> entry.getContent().getProperties().getDate().toLocalDate()).distinct().toList();
        Map<LocalDate, TreasuryParYieldDaily> rows = treasuryParYieldDailyRepository.findAllById(dates).stream()
                .collect(Collectors.toMap(TreasuryParYieldDaily::getObservationDate, value -> value,
                        (left, right) -> left, LinkedHashMap::new));
        Instant observedAt = Instant.now();
        for (TreasuryYieldCurveFeed.Entry entry : entries) {
            TreasuryYieldCurveFeed.Properties values = entry.getContent().getProperties();
            LocalDate date = values.getDate().toLocalDate();
            TreasuryParYieldDaily row = rows.get(date);
            if (row == null) {
                row = treasuryParYieldDailyConverter.toEntity(values);
                row.setObservedAt(observedAt);
                rows.put(date, row);
            } else {
                boolean changed = (values.getTenYear() != null && !same(values.getTenYear(), row.getTenYear()))
                        || (values.getTwoYear() != null && !same(values.getTwoYear(), row.getTwoYear()));
                treasuryParYieldDailyConverter.updateEntity(values, row);
                if (changed || row.getObservedAt() == null) row.setObservedAt(observedAt);
            }
            row.setSourceUpdatedAtUtc(treasuryParYieldDailyConverter.atomUpdatedToUtcLocalDateTime(entry.getUpdated()));
        }
        treasuryParYieldDailyRepository.saveAll(rows.values());
    }

    @Transactional(readOnly = true)
    public List<TreasuryParYieldDaily> getLatestTwoTreasuryParYieldDailies() {
        List<TreasuryParYieldDaily> rows = treasuryParYieldDailyRepository.findTop2ByOrderByObservationDateDesc();
        if (rows == null || rows.size() < 2) throw new IllegalStateException("TREASURY_HISTORY_INSUFFICIENT");
        return rows;
    }

    private boolean same(java.math.BigDecimal left, java.math.BigDecimal right) {
        return right != null && left.compareTo(right) == 0;
    }
}
