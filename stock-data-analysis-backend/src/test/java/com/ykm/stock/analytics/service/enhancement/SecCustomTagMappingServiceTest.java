package com.ykm.stock.analytics.service.enhancement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagCandidateDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagConfirmItemRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagConfirmRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagProposalPreviewDto;
import org.springframework.web.server.ResponseStatusException;
import com.ykm.stock.analytics.model.entity.SecCustomFinancialFact;
import com.ykm.stock.analytics.model.entity.SecCustomTagMapping;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.SecCustomFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.SecCustomTagMappingRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TC-02：AI／手動自訂標籤提議未經人工確認前嚴格隔離零寫入，且確認後僅補缺漏期間絕不覆寫合法標準事實。 */
@ExtendWith(MockitoExtension.class)
class SecCustomTagMappingServiceTest {
    @Mock private SecCompanyFactsArchive companyFactsArchive;
    @Mock private SecEdgarClient secEdgarClient;
    @Mock private EnhancementAiGateway aiGateway;
    @Mock private SecCustomTagMappingRepository mappingRepository;
    @Mock private SecCustomFinancialFactRepository customFactRepository;
    @Mock private StockRepository stockRepository;
    @Mock private StockValuationService stockValuationService;
    @Mock private MarketStatusService calendar;
    @Mock private DateTimeService dateTimeService;
    @Mock private TransactionTemplate transactions;

    private final ObjectMapper mapper = new ObjectMapper();
    private SecCustomTagMappingService service;

    @BeforeEach
    void setUp() {
        service = new SecCustomTagMappingService(
                companyFactsArchive, secEdgarClient, aiGateway,
                mappingRepository, customFactRepository, stockRepository,
                stockValuationService, calendar, dateTimeService, transactions, mapper);
    }

    @Test
    void tc02ManualPreviewNeverWritesMappingFactsOrFinancialPeriod() throws Exception {
        Stock stock = new Stock();
        stock.setFigi("BBG000TEST01");
        stock.setSymbol("CUSTOM");
        stock.setStockType("Common Stock");
        stock.setCik("0000999999");

        String companyFactsJson = """
                {
                  "cik": 999999,
                  "facts": {
                    "ifrs-full": {
                      "RevenueFromRenderingOfServices": {
                        "label": "Revenue from rendering of services",
                        "units": {
                          "USD": [
                            {"start": "2026-01-01", "end": "2026-09-30", "val": 5000000000, "accn": "0000999999-26-000001", "fy": 2026, "fp": "Q3", "form": "20-F", "filed": "2026-10-30"}
                          ]
                        }
                      }
                    }
                  }
                }
                """;
        SecCompanyFactsArchive.Snapshot snapshot = mock(SecCompanyFactsArchive.Snapshot.class);
        when(secEdgarClient.isConfigured()).thenReturn(false);
        when(dateTimeService.now()).thenReturn(Instant.parse("2026-11-19T12:00:00Z"));
        when(companyFactsArchive.prepare()).thenReturn(snapshot);
        when(snapshot.read("0000999999")).thenReturn(mapper.readTree(companyFactsJson));
        when(mappingRepository.findByCikAndStatusOrderByIdAsc("0000999999", SecCustomTagMapping.STATUS_CONFIRMED))
                .thenReturn(List.of());

        String manualJson = """
                {
                  "proposals": [
                    {
                      "taxonomy": "ifrs-full",
                      "customTag": "RevenueFromRenderingOfServices",
                      "unit": "USD",
                      "targetConcept": "REVENUE",
                      "signMultiplier": 1,
                      "rationale": "IFRS 營業服務收入對應標準營收分母。"
                    }
                  ]
                }
                """;
        CustomTagProposalPreviewDto preview = service.importManualPreview(stock, manualJson);

        assertThat(preview.persistedToDatabase()).isFalse();
        assertThat(preview.proposals()).hasSize(1);
        assertThat(preview.proposals().getFirst().targetConcept()).isEqualTo("REVENUE");

        // 嚴格驗證：預覽階段對 mapping、custom fact 與 stock_financial_period 重建皆為 0 次寫入！
        verify(mappingRepository, never()).save(any());
        verify(mappingRepository, never()).saveAll(any());
        verify(customFactRepository, never()).save(any());
        verify(customFactRepository, never()).saveAll(any());
        verify(stockValuationService, never()).rebuildAndRefreshLatest(any());
    }

    @Test
    void confirmedGapFillOnlyFillsMissingPeriodsAndNeverOverridesStandardUsGaapFacts() {
        String cik = "0000999999";
        SecCustomTagMapping confirmed = new SecCustomTagMapping();
        confirmed.setCik(cik);
        confirmed.setTaxonomy("customco");
        confirmed.setCustomTag("CustomTotalRevenue");
        confirmed.setUnit("USD");
        confirmed.setTargetConcept("REVENUE");
        confirmed.setSignMultiplier(1);
        confirmed.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        // Period 1 (2026-06-30) ALREADY has standard us-gaap SalesRevenueNet (alternative standard revenue tag!) -> must NOT be overridden or conflicted
        SecFinancialFact standardQ2 = new SecFinancialFact();
        standardQ2.setCik(cik);
        standardQ2.setTaxonomy("us-gaap");
        standardQ2.setTag("SalesRevenueNet");
        standardQ2.setUnit("USD");
        standardQ2.setPeriodStart(LocalDate.of(2026, 4, 1));
        standardQ2.setPeriodEnd(LocalDate.of(2026, 6, 30));
        standardQ2.setValue(new BigDecimal("1000"));

        SecCustomFinancialFact customQ2 = new SecCustomFinancialFact();
        customQ2.setCik(cik);
        customQ2.setTaxonomy("customco");
        customQ2.setTag("CustomTotalRevenue");
        customQ2.setUnit("USD");
        customQ2.setPeriodStart(LocalDate.of(2026, 4, 1));
        customQ2.setPeriodEnd(LocalDate.of(2026, 6, 30));
        customQ2.setAccession("0000999999-26-000002");
        customQ2.setValue(new BigDecimal("9999"));
        customQ2.setFiled(LocalDate.of(2026, 8, 1));
        customQ2.setForm("10-Q");
        customQ2.setAvailableFromDate(LocalDate.of(2026, 8, 2));
        customQ2.setObservedAt(Instant.now());

        // Period 2 (2026-09-30) is MISSING standard Revenues -> MUST be gap-filled
        SecCustomFinancialFact customQ3 = new SecCustomFinancialFact();
        customQ3.setCik(cik);
        customQ3.setTaxonomy("customco");
        customQ3.setTag("CustomTotalRevenue");
        customQ3.setUnit("USD");
        customQ3.setPeriodStart(LocalDate.of(2026, 7, 1));
        customQ3.setPeriodEnd(LocalDate.of(2026, 9, 30));
        customQ3.setAccession("0000999999-26-000003");
        customQ3.setValue(new BigDecimal("1250"));
        customQ3.setFiled(LocalDate.of(2026, 11, 1));
        customQ3.setForm("10-Q");
        customQ3.setAvailableFromDate(LocalDate.of(2026, 11, 2));
        customQ3.setObservedAt(Instant.now());

        when(mappingRepository.findByCikInAndStatus(List.of(cik), SecCustomTagMapping.STATUS_CONFIRMED))
                .thenReturn(List.of(confirmed));
        when(customFactRepository.findByCikInAndPeriodEndGreaterThanEqual(any(), any()))
                .thenReturn(List.of(customQ2, customQ3));

        Map<String, List<SecFinancialFact>> gapFilled = service.buildConfirmedGapFillFactsByCik(
                List.of(cik), Map.of(cik, List.of(standardQ2)), LocalDate.of(2025, 1, 1));

        assertThat(gapFilled.get(cik)).hasSize(1);
        assertThat(gapFilled.get(cik).getFirst().getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(gapFilled.get(cik).getFirst().getTag()).isEqualTo("Revenues");
        assertThat(gapFilled.get(cik).getFirst().getValue()).isEqualByComparingTo("1250");
    }

    @Test
    void xbrlInstanceParserExcludesDimensionalSegmentFacts() {
        String instanceXml = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                            xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                            xmlns:iso4217="http://www.xbrl.org/2003/iso4217"
                            xmlns:acme="http://acme.com/2026">
                  <xbrli:context id="c_consolidated">
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-09-30</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:context id="c_segment_eu">
                    <xbrli:entity>
                      <xbrli:segment><xbrldi:explicitMember dimension="acme:GeoAxis">acme:EuropeMember</xbrldi:explicitMember></xbrli:segment>
                    </xbrli:entity>
                    <xbrli:period><xbrli:startDate>2026-01-01</xbrli:startDate><xbrli:endDate>2026-09-30</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:unit id="u_usd"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                  <xbrli:unit id="u_eps">
                    <xbrli:divide>
                      <xbrli:unitNumerator><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unitNumerator>
                      <xbrli:unitDenominator><xbrli:measure>xbrli:shares</xbrli:measure></xbrli:unitDenominator>
                    </xbrli:divide>
                  </xbrli:unit>
                  <acme:CustomConsolidatedRevenue contextRef="c_consolidated" unitRef="u_usd">5000000</acme:CustomConsolidatedRevenue>
                  <acme:CustomConsolidatedRevenue contextRef="c_segment_eu" unitRef="u_usd">1200000</acme:CustomConsolidatedRevenue>
                  <acme:CustomAdjustedEps contextRef="c_consolidated" unitRef="u_eps">2.50</acme:CustomAdjustedEps>
                </xbrli:xbrl>
                """;
        List<SecCustomTagMappingService.ParsedXbrlFact> parsed = service.parseNonDimensionalCustomFacts(instanceXml);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.getFirst().tag()).isEqualTo("CustomConsolidatedRevenue");
        assertThat(parsed.getFirst().unit()).isEqualTo("USD");
        assertThat(parsed.getFirst().value()).isEqualByComparingTo("5000000");
        assertThat(parsed.get(1).tag()).isEqualTo("CustomAdjustedEps");
        assertThat(parsed.get(1).unit()).isEqualTo("USD/shares");
    }

    @Test
    void longTermDebtProjectsToNoncurrentAndAliasDedupCoversDnaAndTotalDebt() {
        String cik = "0000888888";
        SecCustomTagMapping ltdMapping = new SecCustomTagMapping();
        ltdMapping.setId(10L);
        ltdMapping.setCik(cik);
        ltdMapping.setTaxonomy("customco");
        ltdMapping.setCustomTag("CustomLongTermDebt");
        ltdMapping.setUnit("USD");
        ltdMapping.setTargetConcept("LONG_TERM_DEBT");
        ltdMapping.setSignMultiplier(1);
        ltdMapping.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        SecCustomTagMapping dnaMapping = new SecCustomTagMapping();
        dnaMapping.setId(11L);
        dnaMapping.setCik(cik);
        dnaMapping.setTaxonomy("customco");
        dnaMapping.setCustomTag("CustomDna");
        dnaMapping.setUnit("USD");
        dnaMapping.setTargetConcept("DEPRECIATION_AMORTIZATION");
        dnaMapping.setSignMultiplier(1);
        dnaMapping.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        SecCustomTagMapping currDebtMapping = new SecCustomTagMapping();
        currDebtMapping.setId(12L);
        currDebtMapping.setCik(cik);
        currDebtMapping.setTaxonomy("customco");
        currDebtMapping.setCustomTag("CustomShortDebt");
        currDebtMapping.setUnit("USD");
        currDebtMapping.setTargetConcept("CURRENT_DEBT");
        currDebtMapping.setSignMultiplier(1);
        currDebtMapping.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        // Q2 has standard DepreciationAmortizationAndAccretionNet & DebtAndCapitalLeaseObligations -> must block custom D&A, LTD, and CURRENT_DEBT
        SecFinancialFact stdDnaQ2 = new SecFinancialFact();
        stdDnaQ2.setCik(cik);
        stdDnaQ2.setTaxonomy("us-gaap");
        stdDnaQ2.setTag("DepreciationAmortizationAndAccretionNet");
        stdDnaQ2.setUnit("USD");
        stdDnaQ2.setPeriodStart(LocalDate.of(2026, 4, 1));
        stdDnaQ2.setPeriodEnd(LocalDate.of(2026, 6, 30));
        stdDnaQ2.setValue(new BigDecimal("50"));

        SecFinancialFact stdTotalDebtQ2 = new SecFinancialFact();
        stdTotalDebtQ2.setCik(cik);
        stdTotalDebtQ2.setTaxonomy("us-gaap");
        stdTotalDebtQ2.setTag("DebtAndCapitalLeaseObligations");
        stdTotalDebtQ2.setUnit("USD");
        stdTotalDebtQ2.setPeriodStart(null);
        stdTotalDebtQ2.setPeriodEnd(LocalDate.of(2026, 6, 30));
        stdTotalDebtQ2.setValue(new BigDecimal("900"));

        SecCustomFinancialFact customDnaQ2 = buildCustomFact(cik, "customco", "CustomDna", "USD",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "acc-1", "99");
        SecCustomFinancialFact customLtdQ2 = buildCustomFact(cik, "customco", "CustomLongTermDebt", "USD",
                null, LocalDate.of(2026, 6, 30), "acc-2", "800");
        SecCustomFinancialFact customShortDebtQ2 = buildCustomFact(cik, "customco", "CustomShortDebt", "USD",
                null, LocalDate.of(2026, 6, 30), "acc-3", "100");
        // Q3 has NO standard debt -> custom LONG_TERM_DEBT must project to LongTermDebtNoncurrent
        SecCustomFinancialFact customLtdQ3 = buildCustomFact(cik, "customco", "CustomLongTermDebt", "USD",
                null, LocalDate.of(2026, 9, 30), "acc-4", "750");

        when(mappingRepository.findByCikInAndStatus(List.of(cik), SecCustomTagMapping.STATUS_CONFIRMED))
                .thenReturn(List.of(ltdMapping, dnaMapping, currDebtMapping));
        when(customFactRepository.findByCikInAndPeriodEndGreaterThanEqual(any(), any()))
                .thenReturn(List.of(customDnaQ2, customLtdQ2, customShortDebtQ2, customLtdQ3));

        Map<String, List<SecFinancialFact>> projected = service.buildConfirmedGapFillFactsByCik(
                List.of(cik), Map.of(cik, List.of(stdDnaQ2, stdTotalDebtQ2)), LocalDate.of(2025, 1, 1));

        assertThat(projected.get(cik)).hasSize(1);
        assertThat(projected.get(cik).getFirst().getTag()).isEqualTo("LongTermDebtNoncurrent");
        assertThat(projected.get(cik).getFirst().getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(projected.get(cik).getFirst().getValue()).isEqualByComparingTo("750");
    }

    @Test
    void dualConfirmedCustomMappingsForSameConceptAndPeriodEmitSingleDeterministicProjection() {
        String cik = "0000777777";
        SecCustomTagMapping mapping1 = new SecCustomTagMapping();
        mapping1.setId(1L);
        mapping1.setCik(cik);
        mapping1.setTaxonomy("customco");
        mapping1.setCustomTag("CustomRevPrimary");
        mapping1.setUnit("USD");
        mapping1.setTargetConcept("REVENUE");
        mapping1.setSignMultiplier(1);
        mapping1.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        SecCustomTagMapping mapping2 = new SecCustomTagMapping();
        mapping2.setId(2L);
        mapping2.setCik(cik);
        mapping2.setTaxonomy("customco");
        mapping2.setCustomTag("CustomRevSecondary");
        mapping2.setUnit("USD");
        mapping2.setTargetConcept("REVENUE");
        mapping2.setSignMultiplier(1);
        mapping2.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        LocalDate start = LocalDate.of(2026, 7, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        SecCustomFinancialFact factFromMapping2 = buildCustomFact(cik, "customco", "CustomRevSecondary", "USD", start, end, "acc-2", "2000");
        SecCustomFinancialFact factFromMapping1Orig = buildCustomFact(cik, "customco", "CustomRevPrimary", "USD", start, end, "acc-1a", "1000");
        SecCustomFinancialFact factFromMapping1Restated = buildCustomFact(cik, "customco", "CustomRevPrimary", "USD", start, end, "acc-1b", "1050");
        factFromMapping1Restated.setFiled(LocalDate.of(2026, 11, 15));

        when(mappingRepository.findByCikInAndStatus(List.of(cik), SecCustomTagMapping.STATUS_CONFIRMED))
                .thenReturn(List.of(mapping2, mapping1));
        // Deliberately pass mapping2's fact BEFORE mapping1's facts to verify deterministic ordering by mapping.id
        when(customFactRepository.findByCikInAndPeriodEndGreaterThanEqual(any(), any()))
                .thenReturn(List.of(factFromMapping2, factFromMapping1Restated, factFromMapping1Orig));

        Map<String, List<SecFinancialFact>> projected = service.buildConfirmedGapFillFactsByCik(
                List.of(cik), Map.of(), LocalDate.of(2025, 1, 1));

        // Mapping 1 wins deterministically (both original and restated filings kept), while Mapping 2 is suppressed
        assertThat(projected.get(cik)).hasSize(2);
        assertThat(projected.get(cik)).extracting(SecFinancialFact::getAccession)
                .containsExactly("acc-1a", "acc-1b");
    }

    @Test
    void differentCurrencyMappingsForSameConceptAndPeriodDoNotExcludeEachOther() {
        String cik = "0000666666";
        SecCustomTagMapping eurMapping = new SecCustomTagMapping();
        eurMapping.setId(1L);
        eurMapping.setCik(cik);
        eurMapping.setTaxonomy("ifrs-full");
        eurMapping.setCustomTag("RevenueEur");
        eurMapping.setUnit("EUR");
        eurMapping.setTargetConcept("REVENUE");
        eurMapping.setSignMultiplier(1);
        eurMapping.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        SecCustomTagMapping usdMapping = new SecCustomTagMapping();
        usdMapping.setId(2L);
        usdMapping.setCik(cik);
        usdMapping.setTaxonomy("ifrs-full");
        usdMapping.setCustomTag("RevenueUsd");
        usdMapping.setUnit("USD");
        usdMapping.setTargetConcept("REVENUE");
        usdMapping.setSignMultiplier(1);
        usdMapping.setStatus(SecCustomTagMapping.STATUS_CONFIRMED);

        LocalDate start = LocalDate.of(2026, 7, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        SecCustomFinancialFact eurFact = buildCustomFact(cik, "ifrs-full", "RevenueEur", "EUR", start, end, "acc-eur", "900");
        SecCustomFinancialFact usdFact = buildCustomFact(cik, "ifrs-full", "RevenueUsd", "USD", start, end, "acc-usd", "1000");

        when(mappingRepository.findByCikInAndStatus(List.of(cik), SecCustomTagMapping.STATUS_CONFIRMED))
                .thenReturn(List.of(eurMapping, usdMapping));
        when(customFactRepository.findByCikInAndPeriodEndGreaterThanEqual(any(), any()))
                .thenReturn(List.of(eurFact, usdFact));

        Map<String, List<SecFinancialFact>> projected = service.buildConfirmedGapFillFactsByCik(
                List.of(cik), Map.of(), LocalDate.of(2025, 1, 1));

        assertThat(projected.get(cik)).hasSize(2);
        assertThat(projected.get(cik)).extracting(SecFinancialFact::getUnit).containsExactly("EUR", "USD");
    }

    @Test
    void rejectsNonMonetaryUnitsInProposalValidationAndConfirmation() throws Exception {
        CustomTagCandidateDto badShareCandidate = new CustomTagCandidateDto(
                SecCustomTagMapping.SOURCE_COMPANYFACTS, "customco", "CustomShareCount", "shares",
                "Share count", new BigDecimal("100000"), LocalDate.of(2026, 9, 30), "10-Q", false, null);

        for (String badUnit : List.of("shares", "pure", "USD/shares", "FOO", "acme:USD")) {
            assertThat(SecCustomTagMappingService.isValidMonetaryUnit(badUnit)).isFalse();
            String json = """
                    {
                      "proposals": [
                        {
                          "taxonomy": "customco",
                          "customTag": "CustomShareCount",
                          "unit": "%s",
                          "targetConcept": "REVENUE",
                          "signMultiplier": 1,
                          "rationale": "Invalid unit"
                        }
                      ]
                    }
                    """.formatted(badUnit);
            assertThatThrownBy(() -> service.validateProposalJson(mapper.readTree(json), List.of(badShareCandidate)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("CUSTOM_TAG_MONETARY_UNIT_INVALID");
        }
        assertThat(SecCustomTagMappingService.isValidMonetaryUnit("USD")).isTrue();
        assertThat(SecCustomTagMappingService.isValidMonetaryUnit("EUR")).isTrue();

        // XBRL custom namespace measure (acme:USD), spoofed iso4217 URI, and unqualified bare USD must NOT pass as ISO 4217;
        // valid measure-level ISO 4217 declaration MUST pass
        String customNsXml = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance"
                            xmlns:acme="http://acme.com/2026"
                            xmlns:iso4217="http://evil.example/fake">
                  <xbrli:context id="c1">
                    <xbrli:period><xbrli:startDate>2026-07-01</xbrli:startDate><xbrli:endDate>2026-09-30</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:unit id="u_custom"><xbrli:measure>acme:USD</xbrli:measure></xbrli:unit>
                  <xbrli:unit id="u_spoof"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                  <xbrli:unit id="u_bare"><xbrli:measure>USD</xbrli:measure></xbrli:unit>
                  <xbrli:unit id="u_real"><xbrli:measure xmlns:curr="http://www.xbrl.org/2003/iso4217">curr:EUR</xbrli:measure></xbrli:unit>
                  <acme:CustomRev1 contextRef="c1" unitRef="u_custom">100</acme:CustomRev1>
                  <acme:CustomRev2 contextRef="c1" unitRef="u_spoof">200</acme:CustomRev2>
                  <acme:CustomRev3 contextRef="c1" unitRef="u_bare">300</acme:CustomRev3>
                  <acme:CustomRev4 contextRef="c1" unitRef="u_real">400</acme:CustomRev4>
                </xbrli:xbrl>
                """;
        List<SecCustomTagMappingService.ParsedXbrlFact> customNsFacts = service.parseNonDimensionalCustomFacts(customNsXml);
        assertThat(customNsFacts).hasSize(4);
        assertThat(SecCustomTagMappingService.isValidMonetaryUnit(customNsFacts.get(0).unit())).isFalse();
        assertThat(SecCustomTagMappingService.isValidMonetaryUnit(customNsFacts.get(1).unit())).isFalse();
        assertThat(SecCustomTagMappingService.isValidMonetaryUnit(customNsFacts.get(2).unit())).isFalse();
        assertThat(customNsFacts.get(3).unit()).isEqualTo("EUR");
        assertThat(SecCustomTagMappingService.isValidMonetaryUnit(customNsFacts.get(3).unit())).isTrue();

        // Undeclared iso4217 prefix must also be rejected (requires explicit ISO 4217 namespace URI)
        String undeclaredIsoXml = """
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:acme="http://acme.com/2026">
                  <xbrli:context id="c1">
                    <xbrli:period><xbrli:startDate>2026-07-01</xbrli:startDate><xbrli:endDate>2026-09-30</xbrli:endDate></xbrli:period>
                  </xbrli:context>
                  <xbrli:unit id="u_undecl"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                  <acme:CustomRevUndecl contextRef="c1" unitRef="u_undecl">500</acme:CustomRevUndecl>
                </xbrli:xbrl>
                """;
        List<SecCustomTagMappingService.ParsedXbrlFact> undeclFacts = service.parseNonDimensionalCustomFacts(undeclaredIsoXml);
        assertThat(undeclFacts).hasSize(1);
        assertThat(SecCustomTagMappingService.isValidMonetaryUnit(undeclFacts.getFirst().unit())).isFalse();

        Stock stock = new Stock();
        stock.setFigi("BBG000TEST02");
        stock.setSymbol("UNIT");
        stock.setStockType("Common Stock");
        stock.setCik("0000999999");

        when(secEdgarClient.isConfigured()).thenReturn(false);
        when(mappingRepository.findByCikAndStatusOrderByIdAsc("0000999999", SecCustomTagMapping.STATUS_CONFIRMED))
                .thenReturn(List.of());
        when(companyFactsArchive.prepare()).thenThrow(new RuntimeException("not needed"));
        when(dateTimeService.now()).thenReturn(Instant.parse("2026-11-19T12:00:00Z"));

        CustomTagConfirmRequest badConfirm = new CustomTagConfirmRequest("MANUAL_WEB_AI", List.of(
                new CustomTagConfirmItemRequest(
                        SecCustomTagMapping.SOURCE_COMPANYFACTS, "customco", "CustomShareCount",
                        "USD/shares", "REVENUE", 1, "bad unit")));

        assertThatThrownBy(() -> service.confirmMappings(stock, badConfirm))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CUSTOM_TAG_MONETARY_UNIT_INVALID");
        verify(mappingRepository, never()).saveAll(any());
    }

    private SecCustomFinancialFact buildCustomFact(String cik, String taxonomy, String tag, String unit,
                                                   LocalDate periodStart, LocalDate periodEnd,
                                                   String accession, String value) {
        SecCustomFinancialFact fact = new SecCustomFinancialFact();
        fact.setCik(cik);
        fact.setTaxonomy(taxonomy);
        fact.setTag(tag);
        fact.setUnit(unit);
        fact.setPeriodStart(periodStart);
        fact.setPeriodEnd(periodEnd);
        fact.setAccession(accession);
        fact.setValue(new BigDecimal(value));
        fact.setFiled(periodEnd.plusDays(30));
        fact.setForm("10-Q");
        fact.setAvailableFromDate(periodEnd.plusDays(31));
        fact.setObservedAt(Instant.parse("2026-11-01T12:00:00Z"));
        return fact;
    }
}
