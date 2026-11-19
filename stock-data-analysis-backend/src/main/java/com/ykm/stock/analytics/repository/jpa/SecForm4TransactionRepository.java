package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.SecForm4Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SecForm4TransactionRepository extends JpaRepository<SecForm4Transaction, Long> {

    Optional<SecForm4Transaction> findByAccessionAndTransactionIndex(String accession, int transactionIndex);

    List<SecForm4Transaction> findByCikAndTransactionDateGreaterThanEqualOrderByTransactionDateDescFilingDateDescIdDesc(
            String cik, LocalDate fromDate);

    List<SecForm4Transaction> findByCikAndTransactionDateBetweenOrderByTransactionDateDescFilingDateDescIdDesc(
            String cik, LocalDate fromDate, LocalDate throughDate);

    List<SecForm4Transaction> findTop100ByCikOrderByTransactionDateDescFilingDateDescIdDesc(String cik);

    @Query("""
            select t from SecForm4Transaction t
            where t.cik in :ciks
              and t.transactionCode = 'P'
              and t.acquiredDisposed = 'A'
              and t.transactionDate >= :fromDate
              and t.transactionDate <= :throughDate
            """)
    List<SecForm4Transaction> findCodePBuysByCikInAndWindow(@Param("ciks") Collection<String> ciks,
                                                            @Param("fromDate") LocalDate fromDate,
                                                            @Param("throughDate") LocalDate throughDate);

    @Query("select distinct t.accession from SecForm4Transaction t where t.cik = :cik and t.accession in :accessions")
    List<String> findExistingAccessions(@Param("cik") String cik, @Param("accessions") Collection<String> accessions);
}
