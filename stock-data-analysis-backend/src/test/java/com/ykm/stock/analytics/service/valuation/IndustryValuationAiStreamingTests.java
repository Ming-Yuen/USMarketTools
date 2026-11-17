package com.ykm.stock.analytics.service.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class IndustryValuationAiStreamingTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final IndustryValuationAiClient.Input input = new IndustryValuationAiClient.Input(
            "I43", "Semiconductors", "NON_FINANCIAL_OPERATING", "PE", LocalDate.of(2026, 11, 4));
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final CountDownLatch firstFrame = new CountDownLatch(1);
    private final CountDownLatch releaseBody = new CountDownLatch(1);
    private HttpServer server;
    private IndustryValuationAiClient client;

    @BeforeEach
    void prepare() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = new IndustryValuationAiClient(mapper, "test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/responses",
                "gpt-6-astra", "max", 16000, 300);
    }

    @AfterEach
    void close() {
        releaseBody.countDown();
        if (server != null) server.stop(0);
        if (client != null) ((HttpClient) ReflectionTestUtils.getField(client, "http")).shutdownNow();
    }

    @Test
    void chunkedUtf8StreamUsesOnlyTheCompletedResponseAndKeepsTheExistingEvidenceChecks() throws Exception {
        String stream = ": keepalive\r\n\r\n"
                + event("response.created", "{\"type\":\"response.created\"}")
                + event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"partial only\"}")
                + completed(response());
        serve(exchange -> writeChunks(exchange, stream));
        IndustryValuationAiClient.Candidate candidate = client.propose(input);
        assertThat(candidate.proposed()).isTrue();
        assertThat(candidate.low()).isEqualByComparingTo("18");
        assertThat(candidate.sources()).singleElement().satisfies(source -> assertThat(source.title()).isEqualTo("官方產業來源"));
        assertThat(mapper.readTree(candidate.responseJson())).isEqualTo(response());
        assertThat(candidate.responseJson()).doesNotContain("data:", "partial only");
        assertThat(mapper.readTree(requestBody.get()).path("stream").asBoolean()).isTrue();
        assertThat(accept.get()).isEqualTo("text/event-stream");
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED_ONLY", "PARTIAL_OUTPUT", "DONE_ONLY"})
    void eofWithoutACompletedResponseNeverProducesADraft(String scenario) {
        String stream = switch (scenario) {
            case "CREATED_ONLY" -> event("response.created", "{\"type\":\"response.created\"}");
            case "PARTIAL_OUTPUT" -> event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"{complete-looking text}\"}");
            default -> "data: [DONE]\n\n";
        };
        serve(exchange -> writeChunks(exchange, stream));
        assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("完整").hasMessageContaining("req_stream_test");
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "response.failed", "response.incomplete"})
    void terminalFailureRetainsTheProviderReasonAndRequestIdWithoutAcceptingPartialOutput(String type) {
        ObjectNode event = mapper.createObjectNode().put("type", type);
        if (type.equals("response.incomplete")) {
            event.putObject("response").put("status", "incomplete").putObject("incomplete_details").put("reason", "max_output_tokens");
        } else if (type.equals("response.failed")) {
            event.putObject("response").put("status", "failed").putObject("error").put("code", "server_error").put("message", "upstream test-key failure");
        } else {
            event.put("code", "server_error").put("message", "upstream test-key failure");
        }
        serve(exchange -> writeChunks(exchange, event(type, event.toString())));
        Throwable failure = catchThrowable(() -> client.propose(input));
        assertThat(failure).hasMessageContaining(type.equals("response.incomplete") ? "max_output_tokens" : "server_error")
                .hasMessageContaining("req_stream_test").hasMessageContaining("未自動重試");
        assertThat(failure.getMessage()).doesNotContain("test-key");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void completedEventWithoutSearchedSourcesStillFailsTheProductionValidator() throws Exception {
        ObjectNode response = response();
        response.withArray("output").remove(0);
        serve(exchange -> writeChunks(exchange, completed(response)));
        assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("實際網頁搜尋");
    }

    @Test
    void malformedEventIsNotTreatedAsAnEmptySuccessfulResponse() {
        serve(exchange -> writeChunks(exchange, "data: {invalid-json}\n\n"));
        assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("串流").hasMessageContaining("req_stream_test");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void responseHeadersAndAnInitialFrameDoNotExtendTheWholeRequestDeadline() {
        ReflectionTestUtils.setField(client, "timeout", Duration.ofMillis(250));
        serve(this::holdBodyOpen);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThatThrownBy(() -> client.propose(input)).hasMessageContaining("逾時");
            assertThat(firstFrame.getCount()).isZero();
        });
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void interruptingTheCallerStopsTheWaitingRequestAndRestoresItsInterruptFlag() throws Exception {
        serve(this::holdBodyOpen);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { client.propose(input); }
            catch (RuntimeException ex) { failure.set(ex); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        }, "ai-stream-interrupt-test");
        worker.start();
        try {
            assertThat(firstFrame.await(2, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(2000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).hasMessageContaining("中斷");
            assertThat(interrupted.get()).isTrue();
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            releaseBody.countDown();
            worker.interrupt();
            worker.join(2000);
        }
    }

    private void serve(HttpHandler handler) {
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("x-request-id", "req_stream_test");
            try (exchange) { handler.handle(exchange); }
        });
        server.start();
    }

    private void writeChunks(HttpExchange exchange, String stream) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
        for (int start = 0; start < bytes.length; start += 7) {
            exchange.getResponseBody().write(bytes, start, Math.min(7, bytes.length - start));
            exchange.getResponseBody().flush();
        }
    }

    private void holdBodyOpen(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        firstFrame.countDown();
        try { releaseBody.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private String completed(ObjectNode response) {
        ObjectNode event = mapper.createObjectNode().put("type", "response.completed");
        event.set("response", response);
        return event("response.completed", event.toString());
    }

    private String event(String type, String data) { return "event: " + type + "\r\ndata: " + data + "\r\n\r\n"; }

    private ObjectNode response() throws Exception {
        ObjectNode result = (ObjectNode) mapper.readTree("""
                {"industryCode":"I43","valuationModelCode":"NON_FINANCIAL_OPERATING","metric":"PE","asOf":"2026-11-04",
                 "decision":"PROPOSE","low":18,"high":22,"rationale":"此為待審核的產業假設，不是投資建議。",
                 "sources":[{"url":"https://www.sec.gov/example","title":"官方產業來源","publishedDate":"2026-10-30","evidence":"共同會計與經濟依據。"}]}
                """);
        ObjectNode response = mapper.createObjectNode().put("status", "completed").put("id", "resp_stream_test").put("model", "gpt-6-astra");
        response.putArray("output").addObject().put("type", "web_search_call").put("status", "completed").putObject("action")
                .putArray("sources").addObject().put("url", "https://www.sec.gov/example");
        response.withArray("output").addObject().put("type", "message").putArray("content")
                .addObject().put("type", "output_text").put("text", result.toString());
        return response;
    }
}
