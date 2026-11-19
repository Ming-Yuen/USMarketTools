package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.SecCustomTagMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SecCustomTagMappingRepository extends JpaRepository<SecCustomTagMapping, Long> {

    List<SecCustomTagMapping> findByCikOrderByUpdatedAtDescIdDesc(String cik);

    List<SecCustomTagMapping> findByCikAndStatusOrderByIdAsc(String cik, String status);

    List<SecCustomTagMapping> findByCikInAndStatus(Collection<String> ciks, String status);

    Optional<SecCustomTagMapping> findByCikAndTaxonomyAndCustomTagAndUnit(String cik, String taxonomy, String customTag, String unit);

    @Query("select distinct m.cik from SecCustomTagMapping m where m.status = :status")
    List<String> findDistinctCiksByStatus(@Param("status") String status);
}
