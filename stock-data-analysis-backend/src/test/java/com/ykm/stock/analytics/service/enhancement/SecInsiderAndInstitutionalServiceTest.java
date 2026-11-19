package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.OpenFigiClient;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.model.entity.Sec13fHolding;
import com.ykm.stock.analytics.model.entity.SecForm4Transaction;
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
}
