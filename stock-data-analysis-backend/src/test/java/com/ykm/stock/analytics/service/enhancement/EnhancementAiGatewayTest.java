package com.ykm.stock.analytics.service.enhancement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnhancementAiGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void noneProviderWithoutApiKeyStartsCleanlyAndKeepsManualModeAvailable() {
        EnhancementAiGateway gateway = new EnhancementAiGateway(
                mapper, "NONE", "", "https://api.openai.com/v1/responses",
                "gpt-5-mini", "low", 4000, 45,
                "codex", "gpt-5-mini", "low", 60);

        EnhancementAiGateway.AiChannelStatus status = gateway.status();
        assertThat(status.provider()).isEqualTo("NONE");
        assertThat(status.automatedAvailable()).isFalse();
        assertThat(status.manualModeAlwaysAvailable()).isTrue();
        assertThatThrownBy(() -> gateway.generateStructuredJson("test", "{}", "inst", "data"))
                .isInstanceOf(EnhancementAiGateway.AiChannelUnavailableException.class);
    }

    @Test
    void rejectsHighCostModelOrHighReasoningEffort() {
        assertThatThrownBy(() -> new EnhancementAiGateway(
                mapper, "OPENAI_API", "sk-test", "https://api.openai.com/v1/responses",
                "gpt-6-astra", "low", 4000, 45,
                "codex", "gpt-5-mini", "low", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HIGH_COST_MODEL_FORBIDDEN");

        assertThatThrownBy(() -> new EnhancementAiGateway(
                mapper, "OPENAI_API", "sk-test", "https://api.openai.com/v1/responses",
                "gpt-5-mini", "max", 4000, 45,
                "codex", "gpt-5-mini", "low", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EFFORT_FORBIDDEN");

        assertThatThrownBy(() -> new EnhancementAiGateway(
                mapper, "CODEX_CLI", "", "https://api.openai.com/v1/responses",
                "gpt-5-mini", "low", 4000, 45,
                "codex", "gpt-6.2-astra", "low", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HIGH_COST_MODEL_FORBIDDEN");
    }
}
