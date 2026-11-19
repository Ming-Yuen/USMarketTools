package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.PortfolioItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortfolioItemRepository extends JpaRepository<PortfolioItem, String> {
    List<PortfolioItem> findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc();

    List<PortfolioItem> findByItemTypeOrderByUpdatedAtDescFigiAsc(String itemType);
}
