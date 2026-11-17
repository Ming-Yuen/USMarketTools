package com.ykm.stock.analytics.service.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IndustryValuationAiClientTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IndustryValuationAiClient client = new IndustryValuationAiClient(mapper, "test-key", "https://api.openai.com/v1/responses", "gpt-6-astra", "max", 16000, 300);
    private final IndustryValuationAiClient.Input input = new IndustryValuationAiClient.Input("I43", "Semiconductors", "NON_FINANCIAL_OPERATING", "PE", LocalDate.of(2026, 11, 4));

    @Test
    void requestRequiresSearchAndStrictOutputAndContainsOnlyThePublicIndustryInput() throws Exception {
        ObjectNode body = client.requestBody(input);
        assertThat(body.path("model").asText()).isEqualTo("gpt-6-astra");
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("max");
        assertThat(body.path("tool_choice").asText()).isEqualTo("required");
        assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("web_search");
        assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(16000);
        assertThat(body.path("store").asBoolean()).isFalse();
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(mapper.readTree(body.path("input").asText())).isEqualTo(mapper.readTree("""
                {"industryCode":"I43","industry":"Semiconductors","valuationModelCode":"NON_FINANCIAL_OPERATING","metric":"PE","asOf":"2026-11-04"}
                """));
        assertThat(body.toString()).doesNotContain("test-key");
    }

    @Test
    void onlyAnActuallySearchedAndDatedSourceCanSupportTheDraft() throws Exception {
        IndustryValuationAiClient.Candidate candidate = client.parseResponse(input, envelope(result()).toString());
        assertThat(candidate.proposed()).isTrue();
        assertThat(candidate.low()).isEqualByComparingTo("18");
        assertThat(candidate.high()).isEqualByComparingTo("22");
        assertThat(candidate.sources()).singleElement().satisfies(source -> {
            assertThat(source.url()).isEqualTo("https://www.sec.gov/example");
            assertThat(source.publishedDate()).isEqualTo(LocalDate.of(2026, 10, 30));
            assertThat(source.evidence()).isNotBlank();
        });
        assertThat(candidate.responseId()).isEqualTo("resp_test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SEARCH", "INCOMPLETE", "UNRETRIEVED_URL", "FUTURE_SOURCE", "MISSING_REASON", "MISSING_SOURCE", "WRONG_METRIC", "REVERSED_RANGE", "ONE_SIDED", "OVER_PRECISION", "FUTURE_ASOF"})
    void unsupportedEvidenceOrChangedInputsNeverBecomeAnApprovableRange(String problem) throws Exception {
        ObjectNode result = result();
        if (problem.equals("UNRETRIEVED_URL")) ((ObjectNode) result.path("sources").get(0)).put("url", "https://made-up.example/value");
        if (problem.equals("FUTURE_SOURCE")) ((ObjectNode) result.path("sources").get(0)).put("publishedDate", "2026-11-05");
        if (problem.equals("MISSING_REASON")) result.put("rationale", " ");
        if (problem.equals("MISSING_SOURCE")) result.putArray("sources");
        if (problem.equals("WRONG_METRIC")) result.put("metric", "PS");
        if (problem.equals("REVERSED_RANGE")) result.put("low", 30);
        if (problem.equals("ONE_SIDED")) result.putNull("high");
        if (problem.equals("OVER_PRECISION")) result.put("low", new java.math.BigDecimal("18.123456789"));
        if (problem.equals("FUTURE_ASOF")) result.put("asOf", "2026-11-05");
        ObjectNode envelope = envelope(result);
        if (problem.equals("NO_SEARCH")) ((ArrayNode) envelope.path("output")).remove(0);
        if (problem.equals("INCOMPLETE")) envelope.put("status", "incomplete");
        assertThatThrownBy(() -> client.parseResponse(input, envelope.toString())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void insufficientEvidenceCarriesAReasonAndNoNumericAssumption() throws Exception {
        ObjectNode result = result();
        result.put("decision", "INSUFFICIENT_EVIDENCE").putNull("low").putNull("high");
        result.putArray("sources");
        result.put("rationale", "找不到可以支持共同數值假設的已公開第一手資料。");
        IndustryValuationAiClient.Candidate candidate = client.parseResponse(input, envelope(result).toString());
        assertThat(candidate.proposed()).isFalse();
        assertThat(candidate.low()).isNull(); assertThat(candidate.high()).isNull();
        assertThat(candidate.rationale()).contains("找不到");
    }

    @Test
    void unavailableCredentialsAndUnboundedLimitsAreRejectedWithoutRequests() {
        IndustryValuationAiClient missing = new IndustryValuationAiClient(mapper, "", "https://api.openai.com/v1/responses", "gpt-6-astra", "max", 16000, 300);
        assertThat(missing.configured()).isFalse();
        assertThatThrownBy(() -> missing.propose(input)).hasMessageContaining("OPENAI_API_KEY");
        assertThatThrownBy(() -> new IndustryValuationAiClient(mapper, "test", "https://api.openai.com/v1/responses", "gpt-6-astra", "max", 100000, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ultra", "MAX", "unsupported"})
    void unsupportedReasoningEffortIsRejectedBeforeAnyRequest(String effort) {
        assertThatThrownBy(() -> new IndustryValuationAiClient(mapper, "test", "https://api.openai.com/v1/responses",
                "gpt-6-astra", effort, 16000, 300))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reasoning effort");
    }

    @Test
    void rawOverPrecisionCannotBeRoundedIntoAnApprovableRange() throws Exception {
        String rawResult = result().toString().replace("\"low\":18", "\"low\":18.000000000000000001");
        assertThatThrownBy(() -> client.parseResponse(input, envelope(rawResult).toString()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("數值精度");
    }

    @Test
    void validRawEightDecimalBoundsKeepTheirExactValues() throws Exception {
        String rawResult = result().toString()
                .replace("\"low\":18", "\"low\":9007199254740991.12345678")
                .replace("\"high\":22", "\"high\":9007199254740992.12345678");
        IndustryValuationAiClient.Candidate candidate = client.parseResponse(input, envelope(rawResult).toString());
        assertThat(candidate.low()).isEqualByComparingTo("9007199254740991.12345678");
        assertThat(candidate.high()).isEqualByComparingTo("9007199254740992.12345678");
    }

    @Test
    void successfulTransportStillValidatesTheSearchedDraft() throws Exception {
        ObjectNode completed = mapper.createObjectNode().put("type", "response.completed");
        completed.set("response", envelope(result()));
        HttpClient http = returningHttp(200, "data: " + completed + "\n\n");
        IndustryValuationAiClient.Candidate candidate = client.propose(input);
        assertThat(candidate.proposed()).isTrue();
        assertThat(candidate.low()).isEqualByComparingTo("18");
        assertThat(candidate.responseId()).isEqualTo("resp_test");
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429})
    void providerErrorPreservesStatusCodeParameterMessageAndRequestIdWithoutRetry(int status) throws Exception {
        String code = status == 400 ? "unsupported_value" : "insufficient_quota";
        String type = status == 400 ? "invalid_request_error" : "insufficient_quota";
        String message = status == 400 ? "Unsupported reasoning effort." : "You exceeded your current quota.";
        ObjectNode body = mapper.createObjectNode();
        body.putObject("error").put("type", type).put("code", code)
                .put("param", status == 400 ? "reasoning.effort" : null).put("message", message);
        HttpClient http = returningHttp(status, body.toString());
        Throwable failure = catchThrowable(() -> client.propose(input));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP " + status)
                .hasMessageContaining(type).hasMessageContaining(code).hasMessageContaining(message)
                .hasMessageContaining("req_transport_test").hasMessageContaining("未自動重試");
        if (status == 400) assertThat(failure).hasMessageContaining("reasoning.effort");
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void authenticationErrorRedactsExactBearerAndMaskedCredentials() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("error").put("code", "invalid_api_key")
                .put("message", "Incorrect API key provided: test-key; Bearer response-token; sk-proj-masked***suffix");
        HttpClient http = returningHttp(401, body.toString());
        Throwable failure = catchThrowable(() -> client.propose(input));
        assertThat(failure).hasMessageContaining("HTTP 401").hasMessageContaining("invalid_api_key")
                .hasMessageContaining("[REDACTED]");
        assertThat(failure.getMessage()).doesNotContain("test-key", "sk-proj-masked", "response-token");
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void nonJsonGatewayFailureKeepsTheHttpStatusWithoutExposingTheRawBody() throws Exception {
        HttpClient http = returningHttp(502, "<html>upstream error: test-key</html>");
        Throwable failure = catchThrowable(() -> client.propose(input));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 502")
                .hasMessageContaining("req_transport_test").hasMessageContaining("未自動重試");
        assertThat(failure.getMessage()).doesNotContain("<html>", "test-key");
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void errorDetailsAreBoundedBeforeTheyReachTheProposalFailureReason() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("error").put("code", "invalid_request_error").put("message", "test-key " + "detail ".repeat(400));
        returningHttp(400, body.toString());
        Throwable failure = catchThrowable(() -> client.propose(input));
        assertThat(failure).hasMessageContaining("HTTP 400").hasMessageContaining("invalid_request_error")
                .hasMessageContaining("req_transport_test").hasMessageContaining("未自動重試");
        assertThat(failure.getMessage()).doesNotContain("test-key").hasSizeLessThanOrEqualTo(1000);
    }

    @Test
    void connectionResetPreservesTheRootCauseAndDoesNotRetry() throws Exception {
        HttpClient http = mockTransport();
        IOException original = new IOException("request failed", new SocketException("Connection reset"));
        when(http.<String>sendAsync(any(HttpRequest.class), any())).thenReturn(CompletableFuture.failedFuture(original));
        assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("SocketException")
                .hasMessageContaining("Connection reset").hasMessageContaining("未自動重試").hasCause(original);
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void requestTimeoutRemainsDistinctFromConnectionReset() throws Exception {
        HttpClient http = mockTransport();
        HttpTimeoutException original = new HttpTimeoutException("request timed out");
        when(http.<String>sendAsync(any(HttpRequest.class), any())).thenReturn(CompletableFuture.failedFuture(original));
        assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("逾時")
                .hasMessageContaining("HttpTimeoutException").hasMessageContaining("request timed out").hasCause(original);
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void interruptionRestoresTheThreadFlagWithoutRetrying() throws Exception {
        HttpClient http = mockTransport();
        InterruptedException original = new InterruptedException("cancelled");
        @SuppressWarnings("unchecked")
        CompletableFuture<HttpResponse<String>> pending = mock(CompletableFuture.class);
        when(pending.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(original);
        when(http.<String>sendAsync(any(HttpRequest.class), any())).thenReturn(pending);
        try {
            assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("中斷").hasCause(original);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(pending).cancel(true);
            verify(http).sendAsync(any(HttpRequest.class), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wholeRequestDeadlineCancelsTheOutstandingHttpExchange() throws Exception {
        HttpClient http = mockTransport();
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        when(http.<String>sendAsync(any(HttpRequest.class), any())).thenReturn(pending);
        ReflectionTestUtils.setField(client, "timeout", Duration.ofMillis(30));
        assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("逾時").hasMessageContaining("完整串流回應");
        assertThat(pending.isCancelled()).isTrue();
        verify(http).sendAsync(any(HttpRequest.class), any());
    }

    private HttpClient mockTransport() {
        HttpClient http = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "http", http);
        return http;
    }

    private HttpClient returningHttp(int status, String body) throws Exception {
        HttpClient http = mockTransport();
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("x-request-id", List.of("req_transport_test"), "content-type", List.of("text/event-stream")), (name, value) -> true));
        when(http.<String>sendAsync(any(HttpRequest.class), any())).thenReturn(CompletableFuture.completedFuture(response));
        return http;
    }

    private ObjectNode result() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"industryCode":"I43","valuationModelCode":"NON_FINANCIAL_OPERATING","metric":"PE","asOf":"2026-11-04",
                 "decision":"PROPOSE","low":18,"high":22,"rationale":"同產業共同會計與現金流支持的情境假設，仍須人工核對。",
                 "sources":[{"url":"https://www.sec.gov/example","title":"官方產業資料","publishedDate":"2026-10-30","evidence":"支持產業共同業務及假設的公開數據。"}]}
                """);
    }
    private ObjectNode envelope(ObjectNode result) {
        return envelope(result.toString());
    }
    private ObjectNode envelope(String result) {
        ObjectNode response = mapper.createObjectNode().put("status", "completed").put("id", "resp_test").put("model", "gpt-6-astra");
        ArrayNode output = response.putArray("output");
        output.addObject().put("type", "web_search_call").put("status", "completed").putObject("action")
                .putArray("sources").addObject().put("type", "url").put("url", "https://www.sec.gov/example");
        output.addObject().put("type", "message").putArray("content").addObject().put("type", "output_text").put("text", result);
        return response;
    }
}
