package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.key.StockQuoteId;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StockQuoteRepository extends JpaRepository<StockQuote, StockQuoteId> {
    @Query("select q from StockQuote q where q.figi in :figis and q.quoteDate = :date")
    List<StockQuote> findStoredQuotes(@Param("figis") Collection<String> figis, @Param("date") LocalDate date);

    @Query("select q from StockQuote q where q.figi in :figis and q.quoteDate between :from and :through")
    List<StockQuote> findStoredQuotes(@Param("figis") Collection<String> figis, @Param("from") LocalDate from,
                                    @Param("through") LocalDate through);

    StockQuote findByFigiAndQuoteDate(String figi, LocalDate quoteDate);
    StockQuote findFirstByFigiOrderByQuoteDateDesc(String figi);
    List<StockQuote> findByFigiOrderByQuoteDateDesc(String figi, Limit limit);
    List<StockQuote> findByFigiAndQuoteDateLessThanEqualOrderByQuoteDateDesc(String figi, LocalDate date, Limit limit);
    List<StockQuote> findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(String figi, LocalDate from, LocalDate through);
}
