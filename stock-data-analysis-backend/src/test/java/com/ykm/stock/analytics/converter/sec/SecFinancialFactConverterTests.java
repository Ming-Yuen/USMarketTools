package com.ykm.stock.analytics.converter.sec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.common.common_lib.service.DateTimeService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecFinancialFactConverterTests {
    @Test
    void fiscalMetadataBelongsOnlyToCurrentPeriodAndCustomTaxonomyIsIgnored() throws Exception {
        String body = """
                {"cik":1,"facts":{"us-gaap":{"EarningsPerShareDiluted":{"units":{"USD/shares":[
                {"start":"2025-07-01","end":"2025-09-30","val":1,"filed":"2026-11-01","accn":"0000000001-26-000001","form":"10-Q","fy":2026,"fp":"Q3"},
                {"start":"2026-07-01","end":"2026-09-30","val":2,"filed":"2026-11-01","accn":"0000000001-26-000001","form":"10-Q","fy":2026,"fp":"Q3"}
                ]}}},"company":{"EarningsPerShareDiluted":{"units":{"USD/shares":[
                {"start":"2026-07-01","end":"2026-09-30","val":999,"filed":"2026-11-01","accn":"0000000001-26-000001","form":"10-Q","fy":2026,"fp":"Q3"}
                ]}}}}}
                """;
        List<SecFinancialFact> facts = converter.convert("0000000001", mapper.readTree(body), observed);
        assertThat(facts).hasSize(2).allSatisfy(fact -> assertThat(fact.getTaxonomy()).isEqualTo("us-gaap"));
        assertThat(facts.getFirst().getReportFiscalYear()).isNull();
        assertThat(facts.getFirst().getReportFiscalPeriod()).isNull();
        assertThat(facts.getLast().getReportFiscalYear()).isEqualTo(2026);
        assertThat(facts.getLast().getReportFiscalPeriod()).isEqualTo("Q3");
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final SecFinancialFactConverter converter = new SecFinancialFactConverter(calendar);
    private final Instant observed = Instant.parse("2026-11-13T08:00:00Z");

    @Test
    void legacyCombinedBasicAndDilutedEpsKeepsOriginalTagAndPeriodFromCompanyFacts() throws Exception {
        when(calendar.getNextTradingDay(LocalDate.parse("2017-02-27"))).thenReturn(LocalDate.parse("2017-02-28"));
        String body = """
                {"cik":1,"facts":{"us-gaap":{"EarningsPerShareBasicAndDiluted":{"units":{"USD/shares":[
                {"start":"2016-10-01","end":"2016-12-31","val":0.12,"filed":"2017-02-27","accn":"0000000001-17-000001","form":"10-K"}
                ]}}}}}
                """;
        List<SecFinancialFact> facts = converter.convert("0000000001", mapper.readTree(body), observed);
        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.getTag()).isEqualTo("EarningsPerShareBasicAndDiluted");
            assertThat(fact.getUnit()).isEqualTo("USD/shares");
            assertThat(fact.getValue()).isEqualByComparingTo("0.12");
            assertThat(fact.getPeriodStart()).isEqualTo(LocalDate.of(2016, 10, 1));
            assertThat(fact.getPeriodEnd()).isEqualTo(LocalDate.of(2016, 12, 31));
            assertThat(fact.getAvailableFromDate()).isEqualTo(LocalDate.of(2017, 2, 28));
        });
    }

    @Test
    void accentureThirdQuarterFilingKeepsNineMonthCashFlowsAndConservativeAvailability() throws Exception {
        // ACN 2026-05-31 10-Q 現金流表（千美元）；官方 companyfacts 已還原為 USD。
        // https://www.sec.gov/Archives/edgar/data/1467373/000146737326000032/acn-20260531.htm
        String body = """
                {"cik":1467373,"facts":{"us-gaap":{"NetCashProvidedByUsedInOperatingActivities":{"units":{"USD":[
                  {"start":"2024-09-01","end":"2025-05-31","val":7560252000,"accn":"0001467373-26-000032","form":"10-Q","filed":"2026-06-18"},
                  {"start":"2025-09-01","end":"2026-05-31","val":9267947000,"accn":"0001467373-26-000032","form":"10-Q","filed":"2026-06-18"}
                ]}}}}}
                """;
        MarketStatusService realCalendar = new MarketStatusService(mock(DateTimeService.class));
        SecFinancialFactConverter realConverter = new SecFinancialFactConverter(realCalendar);
        List<SecFinancialFact> result = realConverter.convert("0001467373", mapper.readTree(body), observed);

        assertThat(result).hasSize(2);
        SecFinancialFact current = result.stream().filter(fact -> fact.getPeriodEnd().equals(LocalDate.of(2026, 5, 31))).findFirst().orElseThrow();
        assertThat(current.getPeriodStart()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(current.getValue()).isEqualByComparingTo("9267947000");
        assertThat(current.getUnit()).isEqualTo("USD");
        assertThat(result).noneMatch(fact -> LocalDate.of(2026, 3, 1).equals(fact.getPeriodStart()));
        assertThat(result).allSatisfy(fact -> {
            assertThat(fact.getAccession()).isEqualTo("0001467373-26-000032");
            assertThat(fact.getFiled()).isEqualTo(LocalDate.of(2026, 6, 18));
            // 無收市前接受時刻證據；6/19 Juneteenth 及週末後，6/22 才通過披露時間門。
            assertThat(fact.getAvailableFromDate()).isEqualTo(LocalDate.of(2026, 6, 22));
            assertThat(fact.getObservedAt()).isEqualTo(observed);
        });
    }

    @Test
    void keepsTagsAndAllAccessionsSeparateIncludingSplitRestatement() throws Exception {
        when(calendar.getNextTradingDay(LocalDate.parse("2020-07-31"))).thenReturn(LocalDate.parse("2020-08-03"));
        when(calendar.getNextTradingDay(LocalDate.parse("2020-10-30"))).thenReturn(LocalDate.parse("2020-11-02"));
        String body = """
                {"cik":320193,"facts":{
                  "dei":{"EntityCommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2019-09-28","val":4443236000,"filed":"2020-07-31","accn":"0000320193-20-000062","form":"10-Q"},
                    {"end":"2019-09-28","val":17772945000,"filed":"2020-10-30","accn":"0000320193-20-000096","form":"10-K"}]}}},
                  "us-gaap":{"CommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2019-09-28","val":17772945000,"filed":"2020-10-30","accn":"0000320193-20-000096","form":"10-K"}]}}}}}
                """;
        List<SecFinancialFact> facts = converter.convert("0000320193", mapper.readTree(body), observed);
        assertThat(facts).hasSize(3);
        assertThat(facts).extracting(SecFinancialFact::getValue).extracting(Object::toString).containsExactly("4443236000", "17772945000", "17772945000");
        assertThat(facts).extracting(SecFinancialFact::getAvailableFromDate).containsExactly(LocalDate.parse("2020-08-03"), LocalDate.parse("2020-11-02"), LocalDate.parse("2020-11-02"));
        assertThat(facts).allSatisfy(fact -> assertThat(fact.getObservedAt()).isEqualTo(observed));
    }

    @Test
    void incrementalIncludesWatermarkPeriodAndExcludesOlderPeriodEvenWhenFiledLater() throws Exception {
        String body = """
                {"cik":320193,"facts":{"us-gaap":{"StockholdersEquity":{"units":{"USD":[
                  {"end":"2013-12-31","val":10,"filed":"2014-01-02","accn":"0000320193-14-000001","form":"10-K"},
                  {"end":"2014-01-01","val":20,"filed":"2014-03-01","accn":"0000320193-14-000002","form":"10-Q"},
                  {"end":"2014-01-01","val":30,"filed":"2026-11-12","accn":"0000320193-26-000003","form":"10-K/A"},
                  {"end":"2026-09-30","val":40,"filed":"2026-11-13","accn":"0000320193-26-000004","form":"10-Q"},
                  {"end":"2026-12-31","val":50,"filed":"2027-01-28","accn":"0000320193-27-000001","form":"10-K"}
                ]}}}}}
                """;
        LocalDate backfill = LocalDate.of(2014, 1, 1);
        assertThat(converter.convert("0000320193", mapper.readTree(body), observed, backfill))
                .extracting(SecFinancialFact::getAccession)
                .containsExactly("0000320193-14-000002", "0000320193-26-000003", "0000320193-26-000004", "0000320193-27-000001");
        assertThat(converter.convert("0000320193", mapper.readTree(body), observed, LocalDate.of(2026, 9, 30)))
                .extracting(SecFinancialFact::getAccession).containsExactly("0000320193-26-000004", "0000320193-27-000001");
    }

    @Test
    void olderTreasuryStockFactsAreAvailableForTheEquityBackfill() throws Exception {
        String body = """
                {"facts":{"us-gaap":{"TreasuryStockValue":{"units":{"USD":[
                  {"end":"2016-03-31","val":156404000000,"filed":"2016-04-26","accn":"0000051143-16-000013","form":"10-Q"}]}},
                  "TreasuryStockCommonValue":{"units":{"USD":[
                  {"end":"2026-09-30","val":171191000000,"filed":"2026-10-22","accn":"0000051143-26-000095","form":"10-Q"}]}}}}}
                """;
        assertThat(converter.historicalEquityComponents("0000051143", mapper.readTree(body), observed,
                LocalDate.of(2014, 1, 1), LocalDate.of(2026, 9, 30)))
                .extracting(SecFinancialFact::getTag).containsExactly("TreasuryStockValue");
        assertThat(converter.convert("0000051143", mapper.readTree(body), observed, LocalDate.of(2026, 9, 30)))
                .extracting(SecFinancialFact::getTag).containsExactly("TreasuryStockCommonValue");
    }

    @Test
    void readsSeparateCommonParAndPaidInCapitalAndBackfillsEarlierPeriods() throws Exception {
        when(calendar.getNextTradingDay(LocalDate.parse("2026-11-12"))).thenReturn(LocalDate.parse("2026-11-13"));
        when(calendar.getNextTradingDay(LocalDate.parse("2018-11-12"))).thenReturn(LocalDate.parse("2018-11-13"));
        String body = """
                {"cik":897077,"facts":{"us-gaap":{
                  "CommonStockValue":{"units":{"USD":[
                  {"end":"2018-09-30","val":1000000,"filed":"2018-11-12","accn":"0000897077-18-000001","form":"10-Q"},
                  {"end":"2026-09-30","val":1211000,"filed":"2026-11-12","accn":"0000897077-26-000110","form":"10-Q"}]}},
                  "AdditionalPaidInCapitalCommonStock":{"units":{"USD":[
                  {"end":"2018-09-30","val":150000000,"filed":"2018-11-12","accn":"0000897077-18-000001","form":"10-Q"},
                  {"end":"2026-09-30","val":159795000,"filed":"2026-11-12","accn":"0000897077-26-000110","form":"10-Q"}]}}
                }}}
                """;
        assertThat(converter.convert("0000897077", mapper.readTree(body), observed, LocalDate.of(2026, 9, 30)))
                .extracting(SecFinancialFact::getTag).containsExactly("CommonStockValue", "AdditionalPaidInCapitalCommonStock");
        assertThat(converter.historicalEquityComponents("0000897077", mapper.readTree(body), observed,
                LocalDate.of(2014, 1, 1), LocalDate.of(2026, 9, 30)))
                .extracting(SecFinancialFact::getTag).containsExactly("CommonStockValue", "AdditionalPaidInCapitalCommonStock");
    }

    @Test
    void missingTagsAndNonSharesUnitsDoNotFabricateFacts() throws Exception {
        assertThat(converter.convert("0000320193", mapper.readTree("{\"cik\":320193,\"facts\":{}}"), observed)).isEmpty();
        assertThat(converter.convert("0000320193", mapper.readTree("{\"cik\":320193,\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"USD\":[]}}}}}"), observed)).isEmpty();
    }

    @Test
    void acceptsOfficialZeroPaddedStringCikAsWellAsNumericCik() throws Exception {
        assertThat(converter.convert("0000081955", mapper.readTree("{\"cik\":\"0000081955\",\"facts\":{}}"), observed)).isEmpty();
    }

    @Test
    void missingEmbeddedCikUsesAlreadyMatchedOfficialZipEntryIdentity() throws Exception {
        assertThat(converter.convert("0001790169", mapper.readTree("{\"entityName\":\"ZeroStack Corp.\",\"facts\":{}}"), observed)).isEmpty();
    }

    @Test
    void preservesSecDocumentedScaleErrorWithoutGuessingOrRejectingDifferentTagDates() throws Exception {
        when(calendar.getNextTradingDay(any())).thenReturn(LocalDate.parse("2026-11-16"));
        // SEC 品質提醒的 555,555 vs 555,555,000 示例；是刻意異常 fixture，不是真實公司的已核實股數。
        String body = """
                {"cik":320193,"facts":{
                  "dei":{"EntityCommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2026-10-23","val":555555,"filed":"2026-11-13","accn":"0000320193-26-000001","form":"10-K"}]}}},
                  "us-gaap":{"CommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2026-09-26","val":555555000,"filed":"2026-11-13","accn":"0000320193-26-000001","form":"10-K"}]}}}}}
                """;
        List<SecFinancialFact> result = converter.convert("0000320193", mapper.readTree(body), observed);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getValue()).isEqualByComparingTo("555555");
        assertThat(result.get(1).getValue()).isEqualByComparingTo("555555000");
        assertThat(result.get(0).getPeriodEnd()).isNotEqualTo(result.get(1).getPeriodEnd());
    }

    @Test
    void duplicateSameDisclosureCollapsesButConflictingValueFailsCompany() throws Exception {
        when(calendar.getNextTradingDay(any())).thenReturn(LocalDate.parse("2026-11-16"));
        String row = "{\"end\":\"2026-09-30\",\"val\":100,\"filed\":\"2026-11-13\",\"accn\":\"0000320193-26-000001\",\"form\":\"10-Q\"}";
        String prefix = "{\"cik\":320193,\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"shares\":[";
        String suffix = "]}}}}}";
        assertThat(converter.convert("0000320193", mapper.readTree(prefix + row + "," + row + suffix), observed)).hasSize(1);
        assertThatThrownBy(() -> converter.convert("0000320193", mapper.readTree(prefix + row + "," + row.replace(":100", ":101") + suffix), observed))
                .hasMessageContaining("SEC_FACT_CONFLICT");
    }

    @Test
    void mismatchedCompanyAndIncompleteFactsFailInsteadOfNoData() throws Exception {
        assertThatThrownBy(() -> converter.convert("0000320193", mapper.readTree("{\"cik\":1,\"facts\":{}}"), observed)).hasMessageContaining("COMPANY_INVALID");
        String prefix = "{\"cik\":320193,\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"shares\":[";
        assertThatThrownBy(() -> converter.convert("0000320193", mapper.readTree(prefix + "{}]}}}}}"), observed)).hasMessageContaining("FIELD_MISSING");
    }

    @Test
    void currencyQuarterAndYtdRemainSeparateAndSignedRawValuesAreNotZeroFilled() throws Exception {
        when(calendar.getNextTradingDay(any())).thenReturn(LocalDate.parse("2026-11-16"));
        String body = """
                {"cik":320193,"facts":{"us-gaap":{
                  "Revenues":{"units":{
                    "USD":[
                      {"start":"2026-01-01","end":"2026-09-30","val":900,"filed":"2026-11-13","accn":"0000320193-26-000001","form":"10-Q"},
                      {"start":"2026-07-01","end":"2026-09-30","val":300,"filed":"2026-11-13","accn":"0000320193-26-000001","form":"10-Q"}],
                    "EUR":[{"start":"2026-07-01","end":"2026-09-30","val":250,"filed":"2026-11-13","accn":"0000320193-26-000001","form":"10-Q"}]}},
                  "NetCashProvidedByUsedInOperatingActivities":{"units":{"USD":[
                    {"start":"2026-01-01","end":"2026-09-30","val":-100,"filed":"2026-11-13","accn":"0000320193-26-000001","form":"10-Q"}]}},
                  "CommonStockSharesOutstanding":{"units":{"shares":[
                    {"start":null,"end":"2026-09-30","val":-10,"filed":"2026-11-13","accn":"0000320193-26-000001","form":"10-Q"}]}}}}}
                """;
        List<SecFinancialFact> result = converter.convert("0000320193", mapper.readTree(body), observed);
        assertThat(result).hasSize(5);
        assertThat(result.stream().filter(fact -> "Revenues".equals(fact.getTag())).toList()).hasSize(3);
        assertThat(result.stream().filter(fact -> "NetCashProvidedByUsedInOperatingActivities".equals(fact.getTag())).findFirst().orElseThrow().getValue()).isEqualByComparingTo("-100");
        SecFinancialFact anomalousShares = result.stream().filter(fact -> "shares".equals(fact.getUnit())).findFirst().orElseThrow();
        assertThat(anomalousShares.getValue()).isEqualByComparingTo("-10");
        assertThat(anomalousShares.getPeriodStart()).isNull();
    }
}
