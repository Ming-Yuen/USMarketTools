package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.OpenFigiClient;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.InsiderSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.Institutional13fSummaryDto;
import com.ykm.stock.analytics.model.entity.Sec13fHolding;
import com.ykm.stock.analytics.model.entity.SecForm4Transaction;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.Sec13fHoldingRepository;
import com.ykm.stock.analytics.repository.jpa.SecForm4TransactionRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.Filing13fMeta;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.FlagshipManager;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.Form4FilingMeta;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.InsiderWindowStats;
import com.ykm.stock.analytics.service.stock.MassiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TC-01：Form 4 交易代碼精準過濾（區分 Code P 與薪酬行權／贈與／10b5-1 例行賣股）與 13F 多列聚合驗證。 */
@ExtendWith(MockitoExtension.class)
class SecInsiderAndInstitutionalServiceTest {
    @Mock private SecEdgarClient secEdgarClient;
    @Mock private MassiveService massiveService;
    @Mock private OpenFigiClient openFigiClient;
    @Mock private SecForm4TransactionRepository form4Repository;
    @Mock private Sec13fHoldingRepository holding13fRepository;
    @Mock private StockRepository stockRepository;
    @Mock private StockSplitRepository splitRepository;
    @Mock private DateTimeService dateTimeService;
    @Mock private TransactionTemplate transactionTemplate;

    private SecInsiderAndInstitutionalService service;

    @BeforeEach
    void setUp() {
        service = new SecInsiderAndInstitutionalService(
                secEdgarClient, massiveService, openFigiClient,
                form4Repository, holding13fRepository, stockRepository, splitRepository, dateTimeService, transactionTemplate);
    }

    @Test
    void tc01Form4DistinguishesCodePOpenMarketBuyFromRoutine10b51SaleAndOptionExercise() {
        String xml = """
                <?xml version="1.0"?>
                <ownershipDocument>
                  <documentType>4</documentType>
                  <aff10b5One>1</aff10b5One>
                  <issuer>
                    <issuerCik>0000320193</issuerCik>
                    <issuerTradingSymbol>AAPL</issuerTradingSymbol>
                  </issuer>
                  <reportingOwner>
                    <reportingOwnerId>
                      <rptOwnerCik>0001214128</rptOwnerCik>
                      <rptOwnerName>TIMOTHY COOK</rptOwnerName>
                    </reportingOwnerId>
                    <reportingOwnerRelationship>
                      <isDirector>1</isDirector>
                      <isOfficer>1</isOfficer>
                      <officerTitle>Chief Executive Officer</officerTitle>
                    </reportingOwnerRelationship>
                  </reportingOwner>
                  <nonDerivativeTable>
                    <nonDerivativeTransaction>
                      <securityTitle><value>Common Stock</value></securityTitle>
                      <transactionDate><value>2026-11-10</value></transactionDate>
                      <transactionCoding><transactionCode>M</transactionCode></transactionCoding>
                      <transactionAmounts>
                        <transactionShares><value>10000</value></transactionShares>
                        <transactionPricePerShare><value>50.00</value></transactionPricePerShare>
                        <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
                      </transactionAmounts>
                    </nonDerivativeTransaction>
                    <nonDerivativeTransaction>
                      <securityTitle><value>Common Stock</value></securityTitle>
                      <transactionDate><value>2026-11-11</value></transactionDate>
                      <transactionCoding><transactionCode>S</transactionCode></transactionCoding>
                      <transactionAmounts>
                        <transactionShares><value>5000</value></transactionShares>
                        <transactionPricePerShare><value>220.00</value></transactionPricePerShare>
                        <transactionAcquiredDisposedCode><value>D</value></transactionAcquiredDisposedCode>
                      </transactionAmounts>
                    </nonDerivativeTransaction>
                    <nonDerivativeTransaction>
                      <securityTitle><value>Common Stock</value></securityTitle>
                      <transactionDate><value>2026-11-12</value></transactionDate>
                      <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                      <transactionAmounts>
                        <transactionShares><value>1000</value></transactionShares>
                        <transactionPricePerShare><value>200.00</value></transactionPricePerShare>
                        <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
                      </transactionAmounts>
                    </nonDerivativeTransaction>
                  </nonDerivativeTable>
                </ownershipDocument>
                """;
        Form4FilingMeta meta = new Form4FilingMeta(
                "0001140361-26-044773", "4", LocalDate.of(2026, 11, 13), Instant.parse("2026-11-13T21:00:00Z"), "form4.xml");
        List<SecForm4Transaction> parsed = service.parseForm4Xml(
                "BBG000B9XRY4", "0000320193", "AAPL", meta, xml, "https://www.sec.gov/test", Instant.now());

        assertThat(parsed).hasSize(3);
        // M (exercise) & S with 10b5-1 are routine, NOT Code P
        assertThat(SecInsiderAndInstitutionalService.isQualifiedCodePBuy(parsed.get(0))).isFalse();
        assertThat(SecInsiderAndInstitutionalService.isRoutineOrPlanTrade(parsed.get(0))).isTrue();
        assertThat(SecInsiderAndInstitutionalService.isQualifiedCodePBuy(parsed.get(1))).isFalse();
        assertThat(SecInsiderAndInstitutionalService.isRoutineOrPlanTrade(parsed.get(1))).isTrue();
        // P (A) is genuine Code P buy ($200,000)
        assertThat(SecInsiderAndInstitutionalService.isQualifiedCodePBuy(parsed.get(2))).isTrue();
        assertThat(parsed.get(2).getTransactionValueUsd()).isEqualByComparingTo("200000.00");

        // Document-level 10b5-1 plan buy must NOT trigger undervaluedConfirm (still counted in raw Code P)
        InsiderWindowStats planStats = service.computeInsiderWindowStats(
                parsed, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 11, 19), "DEEP_UNDERVALUED");
        assertThat(planStats.undervaluedConfirm()).isFalse();
        assertThat(planStats.codePBuyCount()).isEqualTo(1);
        assertThat(planStats.routineOrSaleCount()).isEqualTo(2);

        // Discretionary non-10b5-1 officer Code P ($200,000) + DEEP_UNDERVALUED => confirm TRUE
        String discretionaryBuyXml = xml.replace("<aff10b5One>1</aff10b5One>", "<aff10b5One>0</aff10b5One>");
        List<SecForm4Transaction> discretionary = service.parseForm4Xml(
                "BBG000B9XRY4", "0000320193", "AAPL", meta, discretionaryBuyXml, "https://www.sec.gov/test2", Instant.now());
        InsiderWindowStats undervaluedStats = service.computeInsiderWindowStats(
                List.of(discretionary.get(2)), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 11, 19), "DEEP_UNDERVALUED");
        assertThat(undervaluedStats.undervaluedConfirm()).isTrue();
        assertThat(undervaluedStats.codePBuyCount()).isEqualTo(1);

        // Overvalued => confirm FALSE even with discretionary Code P
        InsiderWindowStats overvaluedStats = service.computeInsiderWindowStats(
                List.of(discretionary.get(2)), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 11, 19), "FAIR_UPPER");
        assertThat(overvaluedStats.undervaluedConfirm()).isFalse();

        // Only routine M + 10b5-1 S => never triggers confirm
        InsiderWindowStats routineOnly = service.computeInsiderWindowStats(
                List.of(parsed.get(0), parsed.get(1)), LocalDate.of(2026, 9, 20), LocalDate.of(2026, 11, 19), "DEEP_UNDERVALUED");
        assertThat(routineOnly.undervaluedConfirm()).isFalse();
        assertThat(routineOnly.codePBuyCount()).isEqualTo(0);
    }

    @Test
    void form4JointFilingDoesNotDoubleCountDollarValueOrInsiders() {
        String jointXml = """
                <?xml version="1.0"?>
                <ownershipDocument>
                  <documentType>4</documentType>
                  <issuer><issuerCik>0000320193</issuerCik><issuerTradingSymbol>AAPL</issuerTradingSymbol></issuer>
                  <reportingOwner>
                    <reportingOwnerId><rptOwnerCik>0000000001</rptOwnerCik><rptOwnerName>OFFICER A</rptOwnerName></reportingOwnerId>
                    <reportingOwnerRelationship><isOfficer>1</isOfficer></reportingOwnerRelationship>
                  </reportingOwner>
                  <reportingOwner>
                    <reportingOwnerId><rptOwnerCik>0000000002</rptOwnerCik><rptOwnerName>TRUST B</rptOwnerName></reportingOwnerId>
                    <reportingOwnerRelationship><isOfficer>1</isOfficer></reportingOwnerRelationship>
                  </reportingOwner>
                  <nonDerivativeTable>
                    <nonDerivativeTransaction>
                      <securityTitle><value>Common Stock</value></securityTitle>
                      <transactionDate><value>2026-11-10</value></transactionDate>
                      <transactionCoding><transactionCode>P</transactionCode></transactionCoding>
                      <transactionAmounts>
                        <transactionShares><value>600</value></transactionShares>
                        <transactionPricePerShare><value>100.00</value></transactionPricePerShare>
                        <transactionAcquiredDisposedCode><value>A</value></transactionAcquiredDisposedCode>
                      </transactionAmounts>
                    </nonDerivativeTransaction>
                  </nonDerivativeTable>
                </ownershipDocument>
                """;
        Form4FilingMeta meta = new Form4FilingMeta("0001140361-26-099999", "4", LocalDate.of(2026, 11, 12), null, "form4.xml");
        List<SecForm4Transaction> parsed = service.parseForm4Xml("FIGI1", "0000320193", "AAPL", meta, jointXml, "https://sec.gov/x", Instant.now());
        // Single transaction row ($60,000), NOT expanded into 2 rows ($120,000)
        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().getReportingOwnerCount()).isEqualTo(2);
        InsiderWindowStats stats = service.computeInsiderWindowStats(parsed, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 11, 19), "DEEP_UNDERVALUED");
        assertThat(stats.codePTotalValueUsd()).isEqualByComparingTo("60000.00");
        assertThat(stats.distinctInsiders()).isEqualTo(1);
        assertThat(stats.undervaluedConfirm()).isFalse();
    }

    @Test
    void aggregatesMultiple13fInfoTableRowsByCusipAndPutCall() {
        String xml = """
                <informationTable xmlns="http://www.sec.gov/edgar/document/thirteenf/informationtable">
                  <infoTable>
                    <nameOfIssuer>ALLY FINL INC</nameOfIssuer>
                    <titleOfClass>COM</titleOfClass>
                    <cusip>02005N100</cusip>
                    <value>400000000</value>
                    <shrsOrPrnAmt><sshPrnamt>10000000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt>
                  </infoTable>
                  <infoTable>
                    <nameOfIssuer>ALLY FINL INC</nameOfIssuer>
                    <titleOfClass>COM</titleOfClass>
                    <cusip>02005N100</cusip>
                    <value>100000000</value>
                    <shrsOrPrnAmt><sshPrnamt>2500000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt>
                  </infoTable>
                  <infoTable>
                    <nameOfIssuer>ALLY FINL INC</nameOfIssuer>
                    <titleOfClass>COM</titleOfClass>
                    <cusip>02005N100</cusip>
                    <value>5000000</value>
                    <shrsOrPrnAmt><sshPrnamt>100000</sshPrnamt><sshPrnamtType>SH</sshPrnamtType></shrsOrPrnAmt>
                    <putCall>Put</putCall>
                  </infoTable>
                </informationTable>
                """;
        FlagshipManager mgr = new FlagshipManager("0001067983", "Berkshire Hathaway");
        Filing13fMeta filing = new Filing13fMeta("0001193125-26-463399", "13F-HR", LocalDate.of(2026, 11, 16), LocalDate.of(2026, 9, 30));
        List<Sec13fHolding> rows = service.parseAndAggregate13fXml(mgr, filing, xml, Instant.now());
        assertThat(rows).hasSize(2);
        Sec13fHolding equity = rows.stream().filter(r -> Sec13fHolding.PUT_CALL_NONE.equals(r.getPutCall())).findFirst().orElseThrow();
        Sec13fHolding putOpt = rows.stream().filter(r -> Sec13fHolding.PUT_CALL_PUT.equals(r.getPutCall())).findFirst().orElseThrow();
        assertThat(equity.getShares()).isEqualByComparingTo("12500000.0000");
        assertThat(equity.getMarketValueUsd()).isEqualByComparingTo("500000000.00");
        assertThat(putOpt.getShares()).isEqualByComparingTo("100000.0000");
    }

    @Test
    void evaluate13fDoesNotReplayExitedHoldingInLaterQuarters() {
        Stock stock = new Stock();
        stock.setFigi("BBG000B9XRY4");
        stock.setSymbol("AAPL");
        stock.setStockType("Common Stock");

        LocalDate q1 = LocalDate.of(2026, 3, 31);
        LocalDate q2 = LocalDate.of(2026, 6, 30);
        LocalDate q3 = LocalDate.of(2026, 9, 30);

        Sec13fHolding q1Holding = new Sec13fHolding();
        q1Holding.setManagerCik("0001067983");
        q1Holding.setManagerName("Berkshire Hathaway");
        q1Holding.setReportPeriod(q1);
        q1Holding.setFilingDate(LocalDate.of(2026, 5, 15));
        q1Holding.setFormType("13F-HR");
        q1Holding.setCusip("037833100");
        q1Holding.setShareType("SH");
        q1Holding.setPutCall(Sec13fHolding.PUT_CALL_NONE);
        q1Holding.setShares(new BigDecimal("1000000"));
        q1Holding.setMarketValueUsd(new BigDecimal("200000000"));
        q1Holding.setFigi("BBG000B9XRY4");

        // As of Q2 filing visibility (2026-08-20): manager reported Q2 and Q1 -> exited in Q2 -> NOT_LISTED shown once
        LocalDate asOfQ2 = LocalDate.of(2026, 8, 20);
        when(holding13fRepository.findByFigiAndReportPeriodLessThanEqualOrderByReportPeriodDescMarketValueUsdDesc("BBG000B9XRY4", asOfQ2))
                .thenReturn(List.of(q1Holding));
        when(splitRepository.findByFigiOrderByExecutionDateAsc("BBG000B9XRY4")).thenReturn(List.of());
        when(holding13fRepository.findDistinctReportPeriodsByManagerCikVisibleAt("0001067983", asOfQ2))
                .thenReturn(List.of(q2, q1));
        when(holding13fRepository.existsByManagerCikAndReportPeriodAndCusipAndShareTypeAndPutCall(
                "0001067983", q2, "037833100", "SH", Sec13fHolding.PUT_CALL_NONE)).thenReturn(false);

        Institutional13fSummaryDto q2Summary = service.evaluate13fForStock(stock, asOfQ2);
        assertThat(q2Summary.status()).isEqualTo("AVAILABLE");
        assertThat(q2Summary.latestReportPeriod()).isEqualTo(q2);
        assertThat(q2Summary.managersReducedOrExitedCount()).isEqualTo(1);
        assertThat(q2Summary.holdings()).hasSize(1);
        assertThat(q2Summary.holdings().getFirst().actionType()).isEqualTo("NOT_LISTED");

        // As of Q3 filing visibility (2026-11-20): manager reported Q3, Q2, Q1 -> exited two quarters ago -> MUST NOT replay NOT_LISTED
        LocalDate asOfQ3 = LocalDate.of(2026, 11, 20);
        when(holding13fRepository.findByFigiAndReportPeriodLessThanEqualOrderByReportPeriodDescMarketValueUsdDesc("BBG000B9XRY4", asOfQ3))
                .thenReturn(List.of(q1Holding));
        when(holding13fRepository.findDistinctReportPeriodsByManagerCikVisibleAt("0001067983", asOfQ3))
                .thenReturn(List.of(q3, q2, q1));

        Institutional13fSummaryDto q3Summary = service.evaluate13fForStock(stock, asOfQ3);
        assertThat(q3Summary.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(q3Summary.reasonCode()).isEqualTo("NO_13F_HOLDINGS_MATCHED");
        assertThat(q3Summary.holdings()).isEmpty();

        // Missing middle quarter (only Q3 & Q1 in DB, Q2 absent): must NOT falsely claim exited in Q3
        when(holding13fRepository.findDistinctReportPeriodsByManagerCikVisibleAt("0001067983", asOfQ3))
                .thenReturn(List.of(q3, q1));
        Institutional13fSummaryDto missingQ2Summary = service.evaluate13fForStock(stock, asOfQ3);
        assertThat(missingQ2Summary.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(missingQ2Summary.holdings()).isEmpty();
    }

    @Test
    void evaluateInsidersUsesFilingDateBoundedTop100AndFull60DayWindowQueries() {
        Stock stock = new Stock();
        stock.setFigi("BBG000B9XRY4");
        stock.setSymbol("AAPL");
        stock.setStockType("Common Stock");
        stock.setCik("320193");

        LocalDate asOf = LocalDate.of(2025, 6, 15);
        LocalDate windowStart = asOf.minusDays(60);

        SecForm4Transaction routineRecent = new SecForm4Transaction();
        routineRecent.setAccession("0001-25-000100");
        routineRecent.setFormType("4");
        routineRecent.setFilingDate(LocalDate.of(2025, 6, 14));
        routineRecent.setTransactionDate(LocalDate.of(2025, 6, 13));
        routineRecent.setPrimaryOwnerCik("0009999999");
        routineRecent.setPrimaryOwnerName("VP ROUTINE");
        routineRecent.setOfficer(true);
        routineRecent.setTransactionCode("M");
        routineRecent.setAcquiredDisposed("A");
        routineRecent.setAff10b5One(false);

        // Code P buy earlier in the 60-day window (not present in Top-100 list) must still be captured via window query
        SecForm4Transaction earlierCodeP = new SecForm4Transaction();
        earlierCodeP.setAccession("0001-25-000001");
        earlierCodeP.setFormType("4");
        earlierCodeP.setFilingDate(LocalDate.of(2025, 5, 10));
        earlierCodeP.setTransactionDate(LocalDate.of(2025, 5, 9));
        earlierCodeP.setPrimaryOwnerCik("0001214128");
        earlierCodeP.setPrimaryOwnerName("TIMOTHY COOK");
        earlierCodeP.setOfficer(true);
        earlierCodeP.setTransactionCode("P");
        earlierCodeP.setAcquiredDisposed("A");
        earlierCodeP.setAff10b5One(false);
        earlierCodeP.setShares(new BigDecimal("1000"));
        earlierCodeP.setPricePerShare(new BigDecimal("200.00"));
        earlierCodeP.setTransactionValueUsd(new BigDecimal("200000.00"));

        when(form4Repository.findTop100ByCikAndFilingDateLessThanEqualOrderByTransactionDateDescFilingDateDescIdDesc("0000320193", asOf))
                .thenReturn(List.of(routineRecent));
        when(form4Repository.findByCikAndTransactionDateBetweenAndFilingDateLessThanEqualOrderByTransactionDateDescFilingDateDescIdDesc(
                "0000320193", windowStart, asOf, asOf))
                .thenReturn(List.of(routineRecent, earlierCodeP));

        InsiderSummaryDto summary = service.evaluateInsiders(stock, asOf, "DEEP_UNDERVALUED");
        assertThat(summary.status()).isEqualTo("AVAILABLE");
        assertThat(summary.insiderBuyUndervaluedConfirm()).isTrue();
        assertThat(summary.codePBuyCount60d()).isEqualTo(1);
        verify(form4Repository).findTop100ByCikAndFilingDateLessThanEqualOrderByTransactionDateDescFilingDateDescIdDesc("0000320193", asOf);
        verify(form4Repository).findByCikAndTransactionDateBetweenAndFilingDateLessThanEqualOrderByTransactionDateDescFilingDateDescIdDesc(
                "0000320193", windowStart, asOf, asOf);
    }
}
