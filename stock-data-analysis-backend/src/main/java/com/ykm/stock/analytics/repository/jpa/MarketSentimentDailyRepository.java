package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketSentimentDailyRepository extends JpaRepository<MarketSentimentDaily, LocalDate> {
    @Query(value = "select * from market_sentiment_daily where fear_and_greed_score is not null order by market_date desc limit 1", nativeQuery = true)
    Optional<MarketSentimentDaily> findLatestWithFearAndGreed();

    @Query("select m.marketDate from MarketSentimentDaily m where m.marketDate between :from and :through and m.vixClose is not null order by m.marketDate asc")
    List<LocalDate> findDatesWithVixBetween(@Param("from") LocalDate from, @Param("through") LocalDate through);

    List<MarketSentimentDaily> findByMarketDateBetweenOrderByMarketDateAsc(LocalDate from, LocalDate through);
}
