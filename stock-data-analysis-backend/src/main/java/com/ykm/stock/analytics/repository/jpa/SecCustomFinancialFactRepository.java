package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.SecCustomFinancialFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SecCustomFinancialFactRepository extends JpaRepository<SecCustomFinancialFact, Long> {

    List<SecCustomFinancialFact> findByCikAndPeriodEndGreaterThanEqual(String cik, LocalDate periodEndFrom);

    List<SecCustomFinancialFact> findByCikInAndPeriodEndGreaterThanEqual(Collection<String> ciks, LocalDate periodEndFrom);

    @Modifying
    @Query("delete from SecCustomFinancialFact f where f.cik = :cik and f.taxonomy = :taxonomy and f.tag = :tag and f.unit = :unit")
    void deleteByCikAndTaxonomyAndTagAndUnit(@Param("cik") String cik,
                                             @Param("taxonomy") String taxonomy,
                                             @Param("tag") String tag,
                                             @Param("unit") String unit);
}
