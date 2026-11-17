package com.ykm.stock.analytics.converter.sec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.FilingSubmission;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecFinancialFactConverterTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final SecFinancialFactConverter converter = new SecFinancialFactConverter(calendar);
    private final Instant observed = Instant.parse("2026-11-13T08:00:00Z");

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

    /** Scenario：期後事項（如長債於季末後新發行日）同屬一份 10-Q 時，submissions reportDate 保護當期財季標記不被抹除。 */
    @Test
    void subsequentEventFactDoesNotWipeCurrentQuarterFiscalMetadataWhenSubmissionsReportDatePresent() throws Exception {
        when(calendar.getNextTradingDay(LocalDate.parse("2026-05-20"))).thenReturn(LocalDate.parse("2026-05-21"));
        String accn = "0001045810-26-000021";
        String body = """
                {"cik":1045810,"facts":{"us-gaap":{
                  "EarningsPerShareDiluted":{"units":{"USD/shares":[
                    {"start":"2026-01-26","end":"2026-04-26","val":2.39,"filed":"2026-05-20","accn":"0001045810-26-000021","form":"10-Q","fy":2027,"fp":"Q1"}
                  ]}},
                  "LongTermDebt":{"units":{"USD":[
                    {"end":"2026-05-15","val":500000000,"filed":"2026-05-20","accn":"0001045810-26-000021","form":"10-Q","fy":2027,"fp":"Q1"}
                  ]}}
                }}}
                """;
        Map<String, FilingSubmission> submissions = Map.of(accn, new FilingSubmission(
                accn, "10-Q", LocalDate.of(2026, 5, 20), LocalDate.of(2026, 4, 26), Instant.parse("2026-05-20T20:10:00Z")));

        List<SecFinancialFact> facts = converter.convert("0001045810", mapper.readTree(body), observed, LocalDate.of(2014, 1, 1), submissions);

        SecFinancialFact eps = facts.stream().filter(fact -> "EarningsPerShareDiluted".equals(fact.getTag())).findFirst().orElseThrow();
        SecFinancialFact subsequentDebt = facts.stream().filter(fact -> "LongTermDebt".equals(fact.getTag())).findFirst().orElseThrow();
        assertThat(eps.getReportFiscalYear()).isEqualTo(2027);
        assertThat(eps.getReportFiscalPeriod()).isEqualTo("Q1");
        assertThat(subsequentDebt.getReportFiscalYear()).isNull();
        assertThat(subsequentDebt.getReportFiscalPeriod()).isNull();
    }

    /** Scenario：補入 WeightedAverageNumberOfDilutedSharesOutstanding 並只保留 shares 單位，供拆股 EPS 交叉驗證。 */
    @Test
    void extractsWeightedAverageDilutedSharesInSharesUnitOnly() throws Exception {
        when(calendar.getNextTradingDay(LocalDate.parse("2026-08-26"))).thenReturn(LocalDate.parse("2026-08-27"));
        String body = """
                {"cik":1045810,"facts":{"us-gaap":{
                  "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{
                    "shares":[{"start":"2026-04-27","end":"2026-07-26","val":24510000000,"filed":"2026-08-26","accn":"0001045810-26-000033","form":"10-Q"}],
                    "USD":[{"start":"2026-04-27","end":"2026-07-26","val":999,"filed":"2026-08-26","accn":"0001045810-26-000033","form":"10-Q"}]
                  }}
                }}}
                """;
        List<SecFinancialFact> facts = converter.convert("0001045810", mapper.readTree(body), observed);
        assertThat(facts).singleElement().satisfies(fact -> {
            assertThat(fact.getTag()).isEqualTo("WeightedAverageNumberOfDilutedSharesOutstanding");
            assertThat(fact.getUnit()).isEqualTo("shares");
            assertThat(fact.getValue()).isEqualByComparingTo("24510000000");
        });
    }

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
        assertThat(result).allSatisfy(fact -> {
            assertThat(fact.getAccession()).isEqualTo("0001467373-26-000032");
            assertThat(fact.getFiled()).isEqualTo(LocalDate.of(2026, 6, 18));
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
        assertThat(facts).extracting(SecFinancialFact::getValue).extracting(Object::toString)
                .containsExactly("4443236000", "17772945000", "17772945000");
    }

    @Test
    void fullHistoryFloorIncludesOldQuarterAmendmentsWithoutDroppingPriorYears() throws Exception {
        String body = """
                {"cik":320193,"facts":{"us-gaap":{"StockholdersEquity":{"units":{"USD":[
                  {"end":"2013-12-31","val":10,"filed":"2014-01-02","accn":"0000320193-14-000001","form":"10-K"},
                  {"end":"2014-01-01","val":20,"filed":"2014-03-01","accn":"0000320193-14-000002","form":"10-Q"},
                  {"end":"2014-01-01","val":30,"filed":"2026-11-12","accn":"0000320193-26-000003","form":"10-K/A"},
                  {"end":"2026-09-30","val":40,"filed":"2026-11-13","accn":"0000320193-26-000004","form":"10-Q"}
                ]}}}}}
                """;
        assertThat(converter.convert("0000320193", mapper.readTree(body), observed, LocalDate.of(2014, 1, 1)))
                .extracting(SecFinancialFact::getAccession)
                .containsExactly("0000320193-14-000002", "0000320193-26-000003", "0000320193-26-000004");
    }

    @Test
    void missingTagsAndNonSharesUnitsDoNotFabricateFacts() throws Exception {
        assertThat(converter.convert("0000320193", mapper.readTree("{\"cik\":320193,\"facts\":{}}"), observed)).isEmpty();
        assertThat(converter.convert("0000320193", mapper.readTree(
                "{\"cik\":320193,\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"USD\":[]}}}}}"), observed)).isEmpty();
    }

    @Test
    void acceptsOfficialZeroPaddedStringCikAsWellAsNumericCik() throws Exception {
        assertThat(converter.convert("0000081955", mapper.readTree("{\"cik\":\"0000081955\",\"facts\":{}}"), observed)).isEmpty();
        assertThat(converter.convert("0001790169", mapper.readTree("{\"entityName\":\"ZeroStack Corp.\",\"facts\":{}}"), observed)).isEmpty();
    }

    /** Scenario：同一 accession 重複或重編同鍵值項目時採最新申報版本覆蓋，不再拋 SEC_FACT_CONFLICT 中斷整家公司同步。 */
    @Test
    void duplicateSameKeyKeepsLatestDisclosureWithoutThrowingConflict() throws Exception {
        when(calendar.getNextTradingDay(any())).thenReturn(LocalDate.parse("2026-11-16"));
        String row1 = "{\"end\":\"2026-09-30\",\"val\":100,\"filed\":\"2026-11-13\",\"accn\":\"0000320193-26-000001\",\"form\":\"10-Q\"}";
        String row2 = "{\"end\":\"2026-09-30\",\"val\":101,\"filed\":\"2026-11-13\",\"accn\":\"0000320193-26-000001\",\"form\":\"10-Q\"}";
        String prefix = "{\"cik\":320193,\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"shares\":[";
        String suffix = "]}}}}}";
        List<SecFinancialFact> result = converter.convert("0000320193", mapper.readTree(prefix + row1 + "," + row2 + suffix), observed);
        assertThat(result).singleElement().satisfies(fact -> assertThat(fact.getValue()).isEqualByComparingTo("101"));
    }

    @Test
    void mismatchedCompanyAndIncompleteFactsFailInsteadOfNoData() {
        assertThatThrownBy(() -> converter.convert("0000320193", mapper.readTree("{\"cik\":1,\"facts\":{}}"), observed))
                .hasMessageContaining("COMPANY_INVALID");
        String prefix = "{\"cik\":320193,\"facts\":{\"dei\":{\"EntityCommonStockSharesOutstanding\":{\"units\":{\"shares\":[";
        assertThatThrownBy(() -> converter.convert("0000320193", mapper.readTree(prefix + "{}]}}}}}"), observed))
                .hasMessageContaining("FIELD_MISSING");
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
        assertThat(result.stream().filter(fact -> "NetCashProvidedByUsedInOperatingActivities".equals(fact.getTag()))
                .findFirst().orElseThrow().getValue()).isEqualByComparingTo("-100");
        SecFinancialFact anomalousShares = result.stream().filter(fact -> "shares".equals(fact.getUnit())).findFirst().orElseThrow();
        assertThat(anomalousShares.getValue()).isEqualByComparingTo("-10");
        assertThat(anomalousShares.getPeriodStart()).isNull();
    }
}
