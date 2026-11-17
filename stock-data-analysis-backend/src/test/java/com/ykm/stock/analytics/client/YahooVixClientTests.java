package com.ykm.stock.analytics.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooVixClientTests {
    // 正式 URL 已編碼 ^；RestClient 不得再次編碼成 Yahoo 無法識別的 %255EVIX。
    @Test void requestsTheActualVixSymbolWithoutDoubleEncoding() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {
            assertThat(request.getURI().getHost()).isEqualTo("query1.finance.yahoo.com");
            assertThat(request.getURI().getRawPath()).isEqualTo("/v8/finance/chart/%5EVIX");
            assertThat(request.getURI().getQuery()).isEqualTo("range=1mo&interval=1d&includePrePost=false&events=history");
        }).andRespond(withSuccess("{\"chart\":{\"result\":[{\"timestamp\":[1792099800],\"indicators\":{\"quote\":[{\"close\":[24.75]}]}}]}}", MediaType.APPLICATION_JSON));
        YahooVixClient client = new YahooVixClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        assertThat(client.fetchDaily().getChart().getResult().getFirst().getIndicators().getQuote().getFirst().getClose()).containsExactly(24.75);
        server.verify();
    }
}
