package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockIndustryHistoryService {
    private final StockIndustryHistoryRepository histories;

    /** 呼叫端已鎖定分類字典；同交易關閉舊區間並建立新區間，重跑相同分類不新增記錄。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int assignIndustries(Map<String, StockIndustry> assignments) {
        if (assignments.isEmpty()) return 0;
        Map<String, StockIndustryHistory> current = histories.findCurrentByFigis(assignments.keySet()).stream()
                .collect(Collectors.toMap(StockIndustryHistory::getFigi, Function.identity()));
        Instant observedAt = Instant.now();
        List<StockIndustryHistory> closing = new ArrayList<>();
        List<StockIndustryHistory> opening = new ArrayList<>();
        for (Map.Entry<String, StockIndustry> entry : assignments.entrySet()) {
            StockIndustryHistory previous = current.get(entry.getKey());
            if (previous != null && Objects.equals(previous.getIndustry().getCode(), entry.getValue().getCode())) continue;
            if (previous != null) {
                if (!observedAt.isAfter(previous.getObservedFrom()))
                    throw new IllegalStateException("INDUSTRY_HISTORY_OBSERVATION_NOT_AFTER_PREVIOUS:" + entry.getKey());
                previous.setObservedTo(observedAt);
                closing.add(previous);
            }
            StockIndustryHistory next = new StockIndustryHistory();
            next.setFigi(entry.getKey());
            next.setIndustry(entry.getValue());
            next.setObservedFrom(observedAt);
            opening.add(next);
        }
        // 關閉既有區間先 flush，避免新舊分類在同一瞬間同時被視為目前有效。
        if (!closing.isEmpty()) histories.saveAllAndFlush(closing);
        if (!opening.isEmpty()) histories.saveAllAndFlush(opening);
        return opening.size();
    }
}
