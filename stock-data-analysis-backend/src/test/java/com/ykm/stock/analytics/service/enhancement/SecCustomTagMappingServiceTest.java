package com.ykm.stock.analytics.service.enhancement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecEdgarClient;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CustomTagProposalPreviewDto;
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
}
