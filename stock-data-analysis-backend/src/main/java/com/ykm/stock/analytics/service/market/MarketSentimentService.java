package com.ykm.stock.analytics.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.client.CnnFearAndGreedClient;
import com.ykm.stock.analytics.converter.jpa.MarketSentimentDailyMapper;
import com.ykm.stock.analytics.model.dto.thirdparty.response.FearAndGreedResponse;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily.SentimentMetric;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketSentimentService {
    private final ObjectMapper objectMapper;
    private final MarketSentimentDailyMapper mapper;
    private final MarketSentimentDailyRepository repository;
    private final CnnFearAndGreedClient client;

    @Transactional(readOnly = true)
    public Optional<MarketSentimentDaily> getLatestSnapshot() {
        return repository.findLatestWithFearAndGreed();
    }

    public List<MarketSentimentDaily> downloadDailySnapshot() {
        try {
            FearAndGreedResponse response = objectMapper.readValue(client.fetchRawJson(), FearAndGreedResponse.class);
            return mapper.toDailyRows(response, Instant.now());
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to download CNN sentiment", failure);
        }
    }

    /** CNN 與 VIX 分別更新各自欄位；相同來源數值重跑不改掉首次可用時間。 */
    @Transactional
    public void save(List<MarketSentimentDaily> incoming) {
        if (incoming.isEmpty()) return;
        Map<LocalDate, MarketSentimentDaily> existing = repository.findAllById(incoming.stream().map(MarketSentimentDaily::getMarketDate).toList())
                .stream().collect(Collectors.toMap(MarketSentimentDaily::getMarketDate, Function.identity()));
        for (MarketSentimentDaily source : incoming) {
            MarketSentimentDaily row = existing.computeIfAbsent(source.getMarketDate(), date -> {
                MarketSentimentDaily created = new MarketSentimentDaily();
                created.setMarketDate(date);
                return created;
            });
            if (source.getCnnSource() != null) row.setCnnSource(source.getCnnSource());
            row.setFearAndGreed(merge(row.getFearAndGreed(), source.getFearAndGreed()));
            row.setMarketMomentumSp500(merge(row.getMarketMomentumSp500(), source.getMarketMomentumSp500()));
            row.setStockPriceStrength(merge(row.getStockPriceStrength(), source.getStockPriceStrength()));
            row.setStockPriceBreadth(merge(row.getStockPriceBreadth(), source.getStockPriceBreadth()));
            row.setPutCallOptions(merge(row.getPutCallOptions(), source.getPutCallOptions()));
            row.setMarketVolatilityVix(merge(row.getMarketVolatilityVix(), source.getMarketVolatilityVix()));
            row.setJunkBondDemand(merge(row.getJunkBondDemand(), source.getJunkBondDemand()));
            row.setSafeHavenDemand(merge(row.getSafeHavenDemand(), source.getSafeHavenDemand()));
            if (source.getVixClose() != null && (row.getVixClose() == null || row.getVixClose().compareTo(source.getVixClose()) != 0
                    || !Objects.equals(row.getVixSourceAt(), source.getVixSourceAt()))) {
                row.setVixClose(source.getVixClose());
                row.setVixSource(source.getVixSource());
                row.setVixSourceAt(source.getVixSourceAt());
                row.setVixObservedAt(source.getVixObservedAt());
            }
        }
        repository.saveAllAndFlush(existing.values());
    }

    private SentimentMetric merge(SentimentMetric current, SentimentMetric incoming) {
        if (incoming == null) return current;
        if (current != null && Objects.equals(current.getCapturedAt(), incoming.getCapturedAt())
                && current.getScore() != null && incoming.getScore() != null && current.getScore().compareTo(incoming.getScore()) == 0) return current;
        return incoming;
    }
}
