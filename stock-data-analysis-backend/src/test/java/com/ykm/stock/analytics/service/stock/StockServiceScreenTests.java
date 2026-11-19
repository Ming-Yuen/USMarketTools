package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse.Item;
import com.ykm.stock.analytics.repository.jpa.StockConceptRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.stock.StockService.ScreenCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StockServiceScreenTests {
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockIndustryRepository industries = mock(StockIndustryRepository.class);
    private final StockClassificationConverter converter = mock(StockClassificationConverter.class);
    private final StockConceptRepository concepts = mock(StockConceptRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StockService service = new StockService(stocks, statuses, industries, converter, concepts, jdbc);

    @Test
    void bindsFiltersGlobalSortBeforePaginationAndLoadsConceptsInThreeQueries() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(52L);
        ResultSet first = row("F1", "AAPL");
        when(first.getString("industry_code")).thenReturn("I43");
        when(first.getString("industry")).thenReturn("Semiconductors");
        when(first.getDate("quote_date")).thenReturn(Date.valueOf("2026-01-02"));
        when(first.getBigDecimal("close_price")).thenReturn(new BigDecimal("150.25"));
        when(first.getString("val_currency")).thenReturn("USD");
        when(first.getDate("valuation_date")).thenReturn(Date.valueOf("2026-01-02"));
        when(first.getBigDecimal("market_cap")).thenReturn(new BigDecimal("2500000000000"));
        when(first.getString("market_cap_tier")).thenReturn("MEGA_LARGE");
        when(first.getBigDecimal("pe")).thenReturn(new BigDecimal("24.00"));
        when(first.getBigDecimal("revenue_growth_yoy")).thenReturn(new BigDecimal("0.18"));
        when(first.getBigDecimal("roe")).thenReturn(new BigDecimal("0.35"));
        when(first.getBigDecimal("fcf_margin")).thenReturn(new BigDecimal("0.22"));
        when(first.getBigDecimal("historical_percentile")).thenReturn(new BigDecimal("28.50"));
        when(first.getString("primary_metric")).thenReturn("PE");
        when(first.getString("verdict")).thenReturn("FAIR_LOWER");
        when(first.getString("valuation_status")).thenReturn("AVAILABLE");

        ResultSet second = row("F2", "MSFT");
        when(second.getString("valuation_status")).thenReturn("NOT_CALCULATED");
        when(second.getString("reason_code")).thenReturn("VALUATION_NOT_CALCULATED");
        stubPage(first, second);

        ResultSet concept = mock(ResultSet.class);
        when(concept.getString("figi")).thenReturn("F1");
        when(concept.getString("code")).thenReturn("C1");
        when(concept.getString("name")).thenReturn("人工智能");
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            handler.processRow(concept);
            return null;
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));

        ScreenCriteria criteria = new ScreenCriteria(
                " AAPL' OR 1=1 -- ", " I43 ", " C1 ",
                "mega_large", "fair_lower",
                new BigDecimal("0.15"), new BigDecimal("0.20"), new BigDecimal("0.10"),
                new BigDecimal("10"), new BigDecimal("40"),
                "roe", "desc", 2, 25);

        StockScreenResponse result = service.screen(criteria);

        assertThat(result.total()).isEqualTo(52);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(25);
        assertThat(result.items()).extracting(Item::figi).containsExactly("F1", "F2");
        Item firstItem = result.items().getFirst();
        assertThat(firstItem.quoteDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(firstItem.closePrice()).isEqualByComparingTo("150.25");
        assertThat(firstItem.currency()).isEqualTo("USD");
        assertThat(firstItem.marketCap()).isEqualByComparingTo("2500000000000");
        assertThat(firstItem.marketCapTier()).isEqualTo("MEGA_LARGE");
        assertThat(firstItem.pe()).isEqualByComparingTo("24");
        assertThat(firstItem.revenueGrowthYoy()).isEqualByComparingTo("0.18");
        assertThat(firstItem.roe()).isEqualByComparingTo("0.35");
        assertThat(firstItem.fcfMargin()).isEqualByComparingTo("0.22");
        assertThat(firstItem.historicalPercentile()).isEqualByComparingTo("28.50");
        assertThat(firstItem.primaryMetric()).isEqualTo("PE");
        assertThat(firstItem.verdict()).isEqualTo("FAIR_LOWER");
        assertThat(firstItem.concepts()).containsExactly(new StockScreenResponse.Concept("C1", "人工智能"));
        assertThat(result.items().get(1).concepts()).isEmpty();

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> countParameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(countSql.capture(), countParameters.capture(), eq(Long.class));
        SqlParameterSource parameters = countParameters.getValue();
        assertThat(parameters.getValue("symbol")).isEqualTo("%AAPL' OR 1=1 --%");
        assertThat(parameters.getValue("industry")).isEqualTo("I43");
        assertThat(parameters.getValue("concept")).isEqualTo("C1");
        assertThat(parameters.getValue("marketCapTier")).isEqualTo("MEGA_LARGE");
        assertThat(parameters.getValue("verdict")).isEqualTo("FAIR_LOWER");
        assertThat(parameters.getValue("minRevenueGrowthYoy")).isEqualTo(new BigDecimal("0.15"));
        assertThat(parameters.getValue("minRoe")).isEqualTo(new BigDecimal("0.20"));
        assertThat(parameters.getValue("minFcfMargin")).isEqualTo(new BigDecimal("0.10"));
        assertThat(parameters.getValue("minHistoricalPercentile")).isEqualTo(new BigDecimal("10"));
        assertThat(parameters.getValue("maxHistoricalPercentile")).isEqualTo(new BigDecimal("40"));
        assertThat(parameters.getValue("limit")).isEqualTo(25);
        assertThat(parameters.getValue("offset")).isEqualTo(50L);
        assertThat(countSql.getValue()).contains(
                "left join stock_valuation_latest v on v.figi = s.figi",
                "v.market_cap_tier = :marketCapTier",
                "v.verdict = :verdict",
                "v.revenue_growth_yoy >= :minRevenueGrowthYoy",
                "v.roe >= :minRoe",
                "v.fcf_margin >= :minFcfMargin",
                "v.historical_percentile >= :minHistoricalPercentile",
                "v.historical_percentile <= :maxHistoricalPercentile");

        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(pageSql.capture(), same(parameters), ArgumentMatchers.<RowMapper<Item>>any());
        // 驗證全域排序必須在 selected CTE 的 limit/offset 之前執行，且附帶 nulls last
        int innerSortPos = pageSql.getValue().indexOf("order by v.roe desc nulls last, match_rank, s.symbol, s.figi");
        int limitPos = pageSql.getValue().indexOf("limit :limit offset :offset");
        assertThat(innerSortPos).isGreaterThan(0).isLessThan(limitPos);
        assertThat(pageSql.getValue()).contains("order by s.roe desc nulls last, s.match_rank, s.symbol, s.figi");

        ArgumentCaptor<SqlParameterSource> conceptParameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(anyString(), conceptParameters.capture(), any(RowCallbackHandler.class));
        assertThat(conceptParameters.getValue().getValue("figis")).isEqualTo(List.of("F1", "F2"));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(stocks, statuses, industries, converter, concepts);
    }

    @Test
    void marketCapSortRestrictsToUsdAndVerdictSortPutsInsufficientEvidenceNullsLast() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        ResultSet row = row("F1", "AAPL");
        when(row.getString("valuation_status")).thenReturn("AVAILABLE");
        stubPage(row);

        service.screen(new ScreenCriteria(null, null, null, null, null, null, null, null, null, null, "marketCap", "desc", 0, 50));
        service.screen(new ScreenCriteria(null, null, null, null, null, null, null, null, null, null, "verdict", "asc", 0, 50));

        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(pageSql.capture(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Item>>any());
        assertThat(pageSql.getAllValues().get(0))
                .contains("case when v.currency = 'USD' then v.market_cap end desc nulls last");
        assertThat(pageSql.getAllValues().get(1))
                .contains("when 'DEEP_UNDERVALUED' then 1")
                .contains("when 'OVERVALUED_RISK' then 6")
                .doesNotContain("when 'INSUFFICIENT_EVIDENCE' then")
                .contains("asc nulls last");
    }

    @Test
    void absentPricesAndMultiplesStayNullInsteadOfBecomingZero() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        ResultSet missing = row("F1", "AAPL");
        when(missing.getString("valuation_status")).thenReturn("NOT_CALCULATED");
        when(missing.getString("reason_code")).thenReturn("VALUATION_NOT_CALCULATED");
        stubPage(missing);

        Item result = service.screen(ScreenCriteria.basic(null, null, null, 0, 50)).items().getFirst();

        assertThat(result.quoteDate()).isNull();
        assertThat(result.closePrice()).isNull();
        assertThat(result.valuationDate()).isNull();
        assertThat(result.marketCap()).isNull();
        assertThat(result.marketCapTier()).isNull();
        assertThat(result.pe()).isNull();
        assertThat(result.revenueGrowthYoy()).isNull();
        assertThat(result.roe()).isNull();
        assertThat(result.fcfMargin()).isNull();
        assertThat(result.historicalPercentile()).isNull();
        assertThat(result.verdict()).isNull();
        assertThat(result.status()).isEqualTo("NOT_CALCULATED");
        assertThat(result.reasonCode()).isEqualTo("VALUATION_NOT_CALCULATED");
        assertThat(result.concepts()).isEmpty();
        verifyNoInteractions(stocks, statuses, industries, converter, concepts);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 0)
    void noMatchesStopsAfterCountAndBlankFiltersAreOmitted(Long count) {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(count);

        StockScreenResponse result = service.screen(ScreenCriteria.basic(" ", null, " ", 0, 50));

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(sql.capture(), parameters.capture(), eq(Long.class));
        assertThat(parameters.getValue().hasValue("symbol")).isFalse();
        assertThat(parameters.getValue().hasValue("industry")).isFalse();
        assertThat(parameters.getValue().hasValue("concept")).isFalse();
        assertThat(sql.getValue()).doesNotContain(":symbol", ":industry", ":concept");
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void pagePastLastRowReturnsTotalWithoutIssuingAConceptQuery() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(2L);
        stubPage();

        StockScreenResponse result = service.screen(ScreenCriteria.basic(null, null, null, Integer.MAX_VALUE, 200));

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isEqualTo(2);
        ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(anyString(), parameters.capture(), eq(Long.class));
        assertThat(parameters.getValue().getValue("offset")).isEqualTo(429496729400L);
        verify(jdbc).query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Item>>any());
        verifyNoMoreInteractions(jdbc);
    }

    @ParameterizedTest
    @CsvSource({"-1,50", "0,0", "0,-1", "0,201"})
    void invalidPaginationDoesNotExecuteAnySql(int page, int size) {
        assertThatThrownBy(() -> service.screen(ScreenCriteria.basic(null, null, null, page, size)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(jdbc, stocks, statuses, industries, converter, concepts);
    }

    @Test
    void rejectsInvalidSortFieldSortDirectionEnumFiltersAndPercentileBounds() {
        assertThatThrownBy(() -> service.screen(new ScreenCriteria(null, null, null, "NANO_CAP", null, null, null, null, null, null, null, null, 0, 50)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.screen(new ScreenCriteria(null, null, null, null, "BUY_NOW", null, null, null, null, null, null, null, 0, 50)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.screen(new ScreenCriteria(null, null, null, null, null, null, null, null, new BigDecimal("-1"), null, null, null, 0, 50)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.screen(new ScreenCriteria(null, null, null, null, null, null, null, null, new BigDecimal("60"), new BigDecimal("30"), null, null, 0, 50)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.screen(new ScreenCriteria(null, null, null, null, null, null, null, null, null, null, "roe; drop table stock;--", "desc", 0, 50)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.screen(new ScreenCriteria(null, null, null, null, null, null, null, null, null, null, "roe", "sideways", 0, 50)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void symbolSearchRanksExactSymbolBeforePrefixContainsAndNameMatches() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        ResultSet mu = row("F_MU", "MU");
        when(mu.getString("valuation_status")).thenReturn("AVAILABLE");
        stubPage(mu);

        service.screen(ScreenCriteria.basic(" mu ", null, null, 0, 50));

        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> pageParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(pageSql.capture(), pageParams.capture(), ArgumentMatchers.<RowMapper<Item>>any());
        assertThat(pageParams.getValue().getValue("symbolExact")).isEqualTo("MU");
        assertThat(pageParams.getValue().getValue("symbolPrefix")).isEqualTo("mu%");
        assertThat(pageParams.getValue().getValue("symbol")).isEqualTo("%mu%");
        assertThat(pageSql.getValue())
                .contains("when upper(s.symbol) = :symbolExact then 0")
                .contains("when s.symbol ilike :symbolPrefix then 1")
                .contains("when s.symbol ilike :symbol then 2")
                .contains("order by match_rank, s.symbol, s.figi")
                .contains("order by s.match_rank, s.symbol, s.figi");
    }

    @Test
    void filtersEtfAssetTypeAndMarksEtfValuationNotApplicable() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        ResultSet qqq = row("F_QQQ", "QQQ");
        when(qqq.getString("stock_type")).thenReturn("ETF");
        when(qqq.getString("valuation_status")).thenReturn("NOT_APPLICABLE");
        when(qqq.getString("reason_code")).thenReturn("NOT_APPLICABLE_FOR_ETF");
        stubPage(qqq);

        ScreenCriteria criteria = new ScreenCriteria(
                null, null, null, null, null,
                null, null, null, null, null, null, null, 0, 50, "ETF");
        StockScreenResponse result = service.screen(criteria);

        assertThat(result.items()).hasSize(1);
        Item item = result.items().getFirst();
        assertThat(item.symbol()).isEqualTo("QQQ");
        assertThat(item.stockType()).isEqualTo("ETF");
        assertThat(item.status()).isEqualTo("NOT_APPLICABLE");
        assertThat(item.reasonCode()).isEqualTo("NOT_APPLICABLE_FOR_ETF");

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(anyString(), params.capture(), eq(Long.class));
        @SuppressWarnings("unchecked")
        List<String> boundTypes = (List<String>) params.getValue().getValue("stockTypes");
        assertThat(boundTypes).containsExactlyInAnyOrder("ETF", "ETP");

        assertThatThrownBy(() -> service.screen(new ScreenCriteria(
                null, null, null, null, null,
                null, null, null, null, null, null, null, 0, 50, "CRYPTO")))
                .isInstanceOf(ResponseStatusException.class);
    }

    private ResultSet row(String figi, String symbol) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("figi")).thenReturn(figi);
        when(row.getString("symbol")).thenReturn(symbol);
        when(row.getString("long_name")).thenReturn(symbol + " company");
        when(row.getString("stock_type")).thenReturn("Common Stock");
        when(row.getString("stock_currency")).thenReturn("USD");
        return row;
    }

    private void stubPage(ResultSet... rows) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Item>>any()))
                .thenAnswer(invocation -> {
                    RowMapper<Item> mapper = invocation.getArgument(2);
                    List<Item> result = new ArrayList<>();
                    for (int index = 0; index < rows.length; index++) result.add(mapper.mapRow(rows[index], index));
                    return result;
                });
    }
}
