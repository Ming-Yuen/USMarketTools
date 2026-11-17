package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketSentimentDailyRepository extends JpaRepository<MarketSentimentDaily, LocalDate> {
    @Query(value = "select * from market_sentiment_daily where fear_and_greed_score is not null order by market_date desc limit 1", nativeQuery = true)
    Optional<MarketSentimentDaily> findLatestWithFearAndGreed();
    List<MarketSentimentDaily> findByMarketDateBetweenOrderByMarketDateAsc(LocalDate from, LocalDate through);
}
