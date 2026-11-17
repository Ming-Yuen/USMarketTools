package com.ykm.stock.analytics.service.valuation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 明確啟用才發出一筆有費用的真實請求；不建立 Spring context、repository 或排程。 */
@EnabledIfSystemProperty(named = "industry.valuation.ai.live", matches = "true")
class IndustryValuationAiLiveTests {
    @Test
    @Timeout(value = 320, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void oneRealIndustryRequestMustFinishAndPassTheProductionValidatorWithoutPersistence() throws Exception {
        Properties settings = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/application.properties")) {
            assertThat(stream).as("Packaged public API settings").isNotNull();
            settings.load(stream);
        }
        String apiKey = System.getenv("OPENAI_API_KEY");
        assertThat(apiKey != null && !apiKey.isBlank()).as("OPENAI_API_KEY must be explicitly provided").isTrue();
        String endpoint = settings.getProperty("industry.valuation.ai.endpoint");
        assertThat(endpoint).isEqualTo("https://api.openai.com/v1/responses");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        IndustryValuationAiClient client = new IndustryValuationAiClient(mapper, apiKey, endpoint,
                settings.getProperty("industry.valuation.ai.model"), settings.getProperty("industry.valuation.ai.reasoning-effort"),
                Integer.parseInt(settings.getProperty("industry.valuation.ai.max-output-tokens")),
                Integer.parseInt(settings.getProperty("industry.valuation.ai.timeout-seconds")));
        IndustryValuationAiClient.Input input = new IndustryValuationAiClient.Input("I43", "Semiconductors", "NON_FINANCIAL_OPERATING", "PE",
                LocalDate.now(ZoneId.of("Asia/Hong_Kong")));
        long started = System.nanoTime();
        System.out.println("LIVE_AI_START model=" + settings.getProperty("industry.valuation.ai.model")
                + " effort=" + settings.getProperty("industry.valuation.ai.reasoning-effort") + " input=" + input + " persisted=false");
        try {
            IndustryValuationAiClient.Candidate candidate = client.propose(input);
            JsonNode response = mapper.readTree(candidate.responseJson());
            assertThat(response.path("status").asText()).isEqualTo("completed");
            assertThat(candidate.rationale()).isNotBlank();
            if (candidate.proposed()) {
                assertThat(candidate.sources()).isNotEmpty();
                assertThat(candidate.low()).isPositive();
                assertThat(candidate.high()).isGreaterThanOrEqualTo(candidate.low());
            } else {
                assertThat(candidate.low()).isNull();
                assertThat(candidate.high()).isNull();
            }
            System.out.println("LIVE_AI_DONE elapsedMs=" + (System.nanoTime() - started) / 1_000_000
                    + " decision=" + (candidate.proposed() ? "PROPOSE" : "INSUFFICIENT_EVIDENCE")
                    + " model=" + candidate.model() + " responseId=" + candidate.responseId()
                    + " sourceCount=" + candidate.sources().size() + " persisted=false approved=false");
            System.out.println("LIVE_AI_USAGE " + response.path("usage"));
            System.out.println("LIVE_AI_REASON " + candidate.rationale().substring(0, Math.min(1200, candidate.rationale().length())));
        } finally {
            ((HttpClient) ReflectionTestUtils.getField(client, "http")).shutdownNow();
        }
    }
}
