package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SecTickerClientTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exactTickersAndMultipleClassesShareIssuerWithoutNameGuessing() throws Exception {
        Map<String, String> ciks = SecTickerClient.parse(mapper.readTree("""
                {"0":{"cik_str":1067983,"ticker":"BRK-A","title":"Berkshire"},
                 "9":{"cik_str":1067983,"ticker":"BRK-B","title":"Berkshire"},
                 "12":{"cik_str":60519,"ticker":"LPX","title":"Louisiana-Pacific"}}
                """));
        assertThat(ciks.get(SecTickerClient.tickerKey(" brk.a "))).isEqualTo("0001067983");
        assertThat(ciks.get("BRK-B")).isEqualTo("0001067983");
        assertThat(ciks).doesNotContainKey("NBN");
    }

    @Test
    void conflictingExactAndCanonicalTickersNeverGuess() throws Exception {
        Map<String, String> ciks = SecTickerClient.parse(mapper.readTree("""
                {"0":{"cik_str":1,"ticker":"X.A","title":"One"},
                 "1":{"cik_str":2,"ticker":"X-A","title":"Two"},
                 "2":{"cik_str":3,"ticker":"ABC","title":"Three"},
                 "3":{"cik_str":4,"ticker":"abc","title":"Four"},
                 "4":{"cik_str":1,"ticker":"X.A","title":"One"}}
                """));
        assertThat(ciks).isEmpty();
    }

    @Test
    void duplicateSameIssuerIsUnambiguous() throws Exception {
        Map<String, String> ciks = SecTickerClient.parse(mapper.readTree("""
                {"0":{"cik_str":1,"ticker":"X.A","title":"One"},
                 "1":{"cik_str":1,"ticker":"X-A","title":"One"}}
                """));
        assertThat(ciks).containsExactlyEntriesOf(Map.of("X-A", "0000000001"));
    }

    @Test
    void malformedRowsRejectWholeSnapshotInsteadOfReturningPartialMapping() throws Exception {
        for (String invalid : new String[]{"null", "{}", "[]", "{\"0\":{}}",
                "{\"0\":{\"cik_str\":0,\"ticker\":\"A\",\"title\":\"A\"}}",
                "{\"0\":{\"cik_str\":10000000000,\"ticker\":\"A\",\"title\":\"A\"}}",
                "{\"0\":{\"cik_str\":1,\"ticker\":\"A\",\"title\":\"A\"},\"1\":{}}"}) {
            assertThatThrownBy(() -> SecTickerClient.parse(mapper.readTree(invalid))).hasMessage("SEC_TICKERS_INVALID");
        }
    }

    @Test
    void downloadsOnceWithConfiguredContact() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SecTickerClient client = new SecTickerClient(builder, mapper, "Stock contact@stock.test");
        server.expect(requestTo(SecTickerClient.URL)).andExpect(header(HttpHeaders.USER_AGENT, "Stock contact@stock.test"))
                .andRespond(withSuccess("{\"0\":{\"cik_str\":1,\"ticker\":\"A\",\"title\":\"A\"}}", MediaType.APPLICATION_JSON));
        assertThat(client.getTickerCiks()).containsEntry("A", "0000000001");
        server.verify();
    }

    @Test
    void trailingContentAndDuplicateJsonKeysRejectSnapshot() {
        for (String body : new String[]{
                "{\"0\":{\"cik_str\":1,\"ticker\":\"A\",\"title\":\"A\"}} {}",
                "{\"0\":{\"cik_str\":1,\"cik_str\":2,\"ticker\":\"A\",\"title\":\"A\"}}"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            SecTickerClient client = new SecTickerClient(builder, mapper, "Stock contact@stock.test");
            server.expect(requestTo(SecTickerClient.URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(client::getTickerCiks).hasMessage("SEC_TICKERS_INVALID");
            server.verify();
        }
    }
    @Test
    void partialContentResponseIsRejectedEvenWhenJsonIsValid() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SecTickerClient client = new SecTickerClient(builder, mapper, "Stock contact@stock.test");
        server.expect(requestTo(SecTickerClient.URL)).andRespond(withStatus(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"0\":{\"cik_str\":1,\"ticker\":\"A\",\"title\":\"A\"}}"));
        assertThatThrownBy(client::getTickerCiks).hasMessage("SEC_TICKERS_HTTP_STATUS: 206");
        server.verify();
    }
    @Test
    void httpFailureAndTruncatedJsonDoNotBecomeEmptySuccess() {
        for (boolean httpFailure : new boolean[]{true, false}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            SecTickerClient client = new SecTickerClient(builder, mapper, "Stock contact@stock.test");
            server.expect(requestTo(SecTickerClient.URL)).andRespond(httpFailure ? withServerError()
                    : withSuccess("{\"0\":", MediaType.APPLICATION_JSON));
            assertThatThrownBy(client::getTickerCiks).isInstanceOf(RuntimeException.class);
            server.verify();
        }
    }
}
