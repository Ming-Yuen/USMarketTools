package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.key.IndustryValuationConfigId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IndustryValuationConfigRepository extends JpaRepository<IndustryValuationConfig, IndustryValuationConfigId> {
    Optional<IndustryValuationConfig> findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(String industryCode, Instant cutoff);
    List<IndustryValuationConfig> findByIndustryCodeOrderByEffectiveAtDesc(String industryCode);

    @Query("select c from IndustryValuationConfig c where c.industryCode in :codes and c.effectiveAt = "
            + "(select max(v.effectiveAt) from IndustryValuationConfig v where v.industryCode = c.industryCode and v.effectiveAt <= :cutoff)")
    List<IndustryValuationConfig> findByIndustryCodesAt(@Param("codes") Collection<String> codes, @Param("cutoff") Instant cutoff);
}
