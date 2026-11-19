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

    /** 輕量日線投影，避免全市場板塊資金流與雷達掃描載入完整 Hibernate Entity 造成堆記憶體膨脹。 */
    record QuoteBarRow(String figi, LocalDate quoteDate, java.math.BigDecimal openPrice, java.math.BigDecimal highPrice,
                       java.math.BigDecimal lowPrice, java.math.BigDecimal closePrice, java.math.BigDecimal volume,
                       java.time.Instant observedAt) {}

    @Query("""
            select new com.ykm.stock.analytics.repository.jpa.StockQuoteRepository$QuoteBarRow(
                q.figi, q.quoteDate, q.openPrice, q.highPrice, q.lowPrice, q.closePrice, q.volume, q.observedAt)
            from StockQuote q
            where q.figi in :figis and q.quoteDate between :from and :through
            order by q.figi asc, q.quoteDate asc
            """)
    List<QuoteBarRow> findQuoteBarsOrdered(@Param("figis") Collection<String> figis,
                                           @Param("from") LocalDate from,
                                           @Param("through") LocalDate through);

    StockQuote findByFigiAndQuoteDate(String figi, LocalDate quoteDate);
    StockQuote findFirstByFigiOrderByQuoteDateDesc(String figi);
    List<StockQuote> findByFigiOrderByQuoteDateDesc(String figi, Limit limit);
    List<StockQuote> findByFigiAndQuoteDateLessThanEqualOrderByQuoteDateDesc(String figi, LocalDate date, Limit limit);
    List<StockQuote> findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(String figi, LocalDate from, LocalDate through);

    /** 按實際估值日視窗批次擷取每股每月最後一個交易日未復權行情，避免全市場刷新載入千萬列日線。 */
    @Query(value = """
            select distinct on (q.figi, date_trunc('month', q.quote_date)) q.*
            from stock_quote q
            where q.figi in (:figis) and q.quote_date between :from and :through
            order by q.figi, date_trunc('month', q.quote_date), q.quote_date desc
            """, nativeQuery = true)
    List<StockQuote> findMonthEndQuotes(@Param("figis") Collection<String> figis,
                                        @Param("from") LocalDate from,
                                        @Param("through") LocalDate through);

    /** 批次取得每股最近一筆已入庫日線收盤紀錄（不超過指定日），透過 LATERAL + (figi, quote_date) 索引每檔僅回讀 1 列。 */
    default List<StockQuote> findLatestQuotesUpTo(Collection<String> figis, LocalDate through) {
        if (figis == null || figis.isEmpty() || through == null) {
            return List.of();
        }
        String[] distinctFigis = figis.stream()
                .filter(f -> f != null && !f.isBlank())
                .distinct()
                .toArray(String[]::new);
        if (distinctFigis.length == 0) {
            return List.of();
        }
        return findLatestQuotesUpToArray(distinctFigis, through);
    }

    @Query(value = """
            select q.*
            from unnest(cast(:figis as text[])) as u(figi)
            cross join lateral (
                select sq.*
                from stock_quote sq
                where sq.figi = u.figi and sq.quote_date <= :through
                order by sq.quote_date desc
                limit 1
            ) q
            """, nativeQuery = true)
    List<StockQuote> findLatestQuotesUpToArray(@Param("figis") String[] figis,
                                               @Param("through") LocalDate through);
}
