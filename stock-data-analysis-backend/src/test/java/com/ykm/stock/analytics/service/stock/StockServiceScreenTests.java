package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse.Item;
import com.ykm.stock.analytics.repository.jpa.StockConceptRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockServiceScreenTests {
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockIndustryRepository industries = mock(StockIndustryRepository.class);
    private final StockClassificationConverter converter = mock(StockClassificationConverter.class);
    private final StockConceptRepository concepts = mock(StockConceptRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StockService service = new StockService(stocks, statuses, industries, converter, concepts, jdbc);

    // 修改前：逐股組裝估值快照。修改後：全市場篩選以最多三次批次 SELECT 回傳，不採集、不計算、不寫入。
    @Test
    void bindsFiltersAndPaginationThenLoadsQuotesValuationsAndConceptsInThreeQueries() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(52L);
        ResultSet first = row("F1", "AAPL");
        when(first.getString("industry_code")).thenReturn("I43");
        when(first.getString("industry")).thenReturn("Semiconductors");
        when(first.getDate("quote_date")).thenReturn(Date.valueOf("2026-01-02"));
        when(first.getBigDecimal("close_price")).thenReturn(new BigDecimal("150.25"));
        when(first.getDate("valuation_date")).thenReturn(Date.valueOf("2026-01-02"));
        when(first.getBigDecimal("pe")).thenReturn(new BigDecimal("24.00"));
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

        StockScreenResponse result = service.screen(" AAPL' OR 1=1 -- ", " I43 ", " C1 ", 2, 25);

        assertThat(result.total()).isEqualTo(52);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(25);
        assertThat(result.items()).extracting(Item::figi).containsExactly("F1", "F2");
        assertThat(result.items().getFirst().quoteDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(result.items().getFirst().closePrice()).isEqualByComparingTo("150.25");
        assertThat(result.items().getFirst().pe()).isEqualByComparingTo("24");
        assertThat(result.items().getFirst().concepts()).containsExactly(new StockScreenResponse.Concept("C1", "人工智能"));
        assertThat(result.items().get(1).concepts()).isEmpty();

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> countParameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(countSql.capture(), countParameters.capture(), eq(Long.class));
        SqlParameterSource parameters = countParameters.getValue();
        assertThat(parameters.getValue("symbol")).isEqualTo("%AAPL' OR 1=1 --%");
        assertThat(parameters.getValue("industry")).isEqualTo("I43");
        assertThat(parameters.getValue("concept")).isEqualTo("C1");
        assertThat(parameters.getValue("limit")).isEqualTo(25);
        assertThat(parameters.getValue("offset")).isEqualTo(50L);
        assertThat(parameters.getValue("stockTypes")).isEqualTo(com.ykm.stock.analytics.service.analysis.SecurityTypePolicy.OPERATING_STOCK_TYPES);
        assertThat(countSql.getValue()).contains("s.active_status = 'ACTIVE'", "s.tracked = true", "s.stock_type in (:stockTypes)",
                "h.observed_to is null",
                "s.symbol ilike :symbol", "i.code = :industry", "m.concept_code = :concept")
                .doesNotContain("AAPL' OR", "s.cik");

        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(pageSql.capture(), same(parameters), ArgumentMatchers.<RowMapper<Item>>any());
        assertThat(pageSql.getValue()).contains("limit :limit offset :offset", "q.quote_date = ss.quote_date",
                "left join stock_valuation_latest v on v.figi = s.figi")
                .doesNotContain("stock_valuation_daily", "AAPL' OR", "s.cik");
        assertThat(pageSql.getValue()).doesNotContain("'PARTIAL'", "v.pe is null or v.pb is null");
        ArgumentCaptor<SqlParameterSource> conceptParameters = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(anyString(), conceptParameters.capture(), any(RowCallbackHandler.class));
        assertThat(conceptParameters.getValue().getValue("figis")).isEqualTo(List.of("F1", "F2"));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(stocks, statuses, industries, converter, concepts);
    }

    @Test
    void absentPricesAndMultiplesStayNullInsteadOfBecomingZero() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        ResultSet missing = row("F1", "AAPL");
        when(missing.getString("valuation_status")).thenReturn("NOT_CALCULATED");
        when(missing.getString("reason_code")).thenReturn("VALUATION_NOT_CALCULATED");
        stubPage(missing);

        Item result = service.screen(null, null, null, 0, 50).items().getFirst();

        assertThat(result.quoteDate()).isNull();
        assertThat(result.closePrice()).isNull();
        assertThat(result.valuationDate()).isNull();
        assertThat(result.pe()).isNull();
        assertThat(result.pb()).isNull();
        assertThat(result.ps()).isNull();
        assertThat(result.pfcf()).isNull();
        assertThat(result.evEbitda()).isNull();
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

        StockScreenResponse result = service.screen(" ", null, " ", 0, 50);

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
    void pagePastLastRowReturnsTotalWithoutIssuingAConceptQuery() throws Exception {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(2L);
        stubPage();

        StockScreenResponse result = service.screen(null, null, null, Integer.MAX_VALUE, 200);

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
        assertThatThrownBy(() -> service.screen(null, null, null, page, size))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(jdbc, stocks, statuses, industries, converter, concepts);
    }

    private ResultSet row(String figi, String symbol) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("figi")).thenReturn(figi);
        when(row.getString("symbol")).thenReturn(symbol);
        when(row.getString("long_name")).thenReturn(symbol + " company");
        when(row.getString("stock_type")).thenReturn("Common Stock");
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
