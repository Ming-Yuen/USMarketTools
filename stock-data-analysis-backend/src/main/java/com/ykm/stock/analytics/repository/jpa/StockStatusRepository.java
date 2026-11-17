package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;

public interface StockStatusRepository extends JpaRepository<StockStatus, String>, StockStatusLockRepository {

    @Query("select max(s.quoteDate) from StockStatus s")
    LocalDate findMaxQuoteDate();
}
