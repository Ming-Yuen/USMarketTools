package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Source;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Update;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IndustryValuationProposalServiceTests {
    private final StockIndustryRepository industries = mock(StockIndustryRepository.class);
    private final IndustryValuationConfigRepository configurations = mock(IndustryValuationConfigRepository.class);
    private final IndustryValuationAiClient ai = mock(IndustryValuationAiClient.class);
    private final IndustryValuationProposalService service = new IndustryValuationProposalService(industries, configurations, ai);
    private IndustryValuationConfig current;
    private StockIndustry industry;

    @BeforeEach
    void setUp() {
        industry = new StockIndustry();
        industry.setCode("I43");
        industry.setIndustry("Semiconductors");
        when(industries.findById("I43")).thenReturn(Optional.of(industry));
        current = new IndustryValuationConfig();
        current.setIndustryCode("I43");
        current.setEffectiveAt(Instant.parse("2020-01-01T00:00:00Z"));
        current.setModelCode("NON_FINANCIAL_OPERATING");
        current.setPrimaryMetric("PE");
        current.setAssumedMultipleLow(new BigDecimal("10"));
        current.setAssumedMultipleHigh(new BigDecimal("15"));
        when(configurations.findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(eq("I43"), any(Instant.class)))
                .thenAnswer(invocation -> Optional.ofNullable(current));
    }

    // 修改前：查看/提議依賴持久化草稿。修改後：讀取不產生提議；AI 結果只回傳給使用者確認。
    @Test
    void reviewReadsCurrentConfigurationWithoutCallingAiOrWriting() {
        when(ai.configured()).thenReturn(true);

        IndustryValuationReview result = service.get("I43");

        assertThat(result.industry()).isEqualTo("Semiconductors");
        assertThat(result.current().effectiveAt()).isEqualTo(current.getEffectiveAt());
        assertThat(result.current().assumedMultipleLow()).isEqualByComparingTo("10");
        assertThat(result.canPropose()).isTrue();
        assertThat(result.supportedMetrics().get("BANK")).containsExactly("PE", "PB");
        verify(ai, never()).propose(any());
        verify(configurations).findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(eq("I43"), any(Instant.class));
        verifyNoMoreInteractions(configurations);
        verify(industries, never()).lockForIndustryImport();
    }

    @Test
    void proposalReturnsDraftAndNeverChangesTheOfficialConfiguration() {
        when(ai.configured()).thenReturn(true);
        Source source = new Source("https://example.com/research", "研究來源", LocalDate.of(2020, 1, 1), "支持合理倍數的依據");
        when(ai.propose(any())).thenReturn(new IndustryValuationAiClient.Candidate(true,
                new BigDecimal("18"), new BigDecimal("22"), List.of(source), "待人工確認的假設", "response", "model", "{}"));

        IndustryValuationReview.Draft result = service.propose("I43");

        assertThat(result.modelCode()).isEqualTo("NON_FINANCIAL_OPERATING");
        assertThat(result.primaryMetric()).isEqualTo("PE");
        assertThat(result.assumedMultipleLow()).isEqualByComparingTo("18");
        assertThat(result.assumedMultipleHigh()).isEqualByComparingTo("22");
        assertThat(result.sources()).containsExactly(source);
        ArgumentCaptor<IndustryValuationAiClient.Input> input = ArgumentCaptor.forClass(IndustryValuationAiClient.Input.class);
        verify(ai).propose(input.capture());
        assertThat(input.getValue().industryCode()).isEqualTo("I43");
        assertThat(input.getValue().metric()).isEqualTo("PE");
        assertThat(input.getValue().asOf()).isEqualTo(result.asOf());
        assertThat(result.asOf()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Hong_Kong")));
        assertThat(current.getAssumedMultipleLow()).isEqualByComparingTo("10");
        assertNoWrites();
    }

    // 修改前：approve-id 讀取草稿。修改後：只有人工 PUT 設定才新增正式資料，生效時間不可回填過去。
    @Test
    void explicitSaveCreatesANewEffectiveConfigurationAndLeavesPreviousOneUntouched() {
        IndustryValuationConfig previous = current;
        when(configurations.saveAndFlush(any(IndustryValuationConfig.class))).thenAnswer(invocation -> {
            current = invocation.getArgument(0);
            return current;
        });
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        IndustryValuationReview result = service.save("I43", update(previous.getEffectiveAt(),
                "NON_FINANCIAL_OPERATING", "PE", "18", "22", "人工確認依據", "https://example.com/research"));

        ArgumentCaptor<IndustryValuationConfig> saved = ArgumentCaptor.forClass(IndustryValuationConfig.class);
        verify(configurations).saveAndFlush(saved.capture());
        IndustryValuationConfig next = saved.getValue();
        assertThat(next).isNotSameAs(previous);
        assertThat(next.getEffectiveAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(Instant.now());
        assertThat(next.getEffectiveAt().getNano() % 1000).isZero();
        assertThat(next.getIndustryCode()).isEqualTo("I43");
        assertThat(next.getAssumedMultipleLow()).isEqualByComparingTo("18");
        assertThat(next.getAssumedMultipleHigh()).isEqualByComparingTo("22");
        assertThat(result.current().effectiveAt()).isEqualTo(next.getEffectiveAt());
        assertThat(previous.getAssumedMultipleLow()).isEqualByComparingTo("10");
        assertThat(industry.getIndustry()).isEqualTo("Semiconductors");
        verify(industries).lockForIndustryImport();
        verify(industries, never()).save(any());
        verify(ai, never()).propose(any());
    }

    @Test
    void staleBaseIsRejectedWithoutReplacingAnotherUsersConfiguration() {
        Update input = update(Instant.parse("2019-01-01T00:00:00Z"),
                "NON_FINANCIAL_OPERATING", "PE", "18", "22", "依據", "https://example.com/research");

        assertStatus(() -> service.save("I43", input), HttpStatus.CONFLICT);

        assertThat(current.getAssumedMultipleLow()).isEqualByComparingTo("10");
        assertNoWrites();
        verifyNoInteractions(ai);
    }

    @Test
    void unknownModelCannotRequestAiButCanBeManuallyConfiguredWithoutAnAssumedRange() {
        current.setModelCode("UNKNOWN");
        current.setPrimaryMetric(null);
        assertStatus(() -> service.propose("I43"), HttpStatus.CONFLICT);
        verifyNoInteractions(ai);
        Instant previousEffectiveAt = current.getEffectiveAt();
        when(configurations.saveAndFlush(any(IndustryValuationConfig.class))).thenAnswer(invocation -> {
            current = invocation.getArgument(0);
            return current;
        });

        IndustryValuationReview result = service.save("I43",
                update(previousEffectiveAt, "BANK", "PB", null, null, null, null));

        assertThat(result.current().modelCode()).isEqualTo("BANK");
        assertThat(result.current().primaryMetric()).isEqualTo("PB");
        assertThat(result.current().assumedMultipleLow()).isNull();
        assertThat(result.current().assumedMultipleHigh()).isNull();
        assertThat(result.canPropose()).isFalse();
        verify(ai, never()).propose(any());
    }

    @ParameterizedTest
    @MethodSource("invalidUpdates")
    void invalidRangeModelOrSourceNeverWrites(Update input) {
        assertStatus(() -> service.save("I43", input), HttpStatus.BAD_REQUEST);
        assertNoWrites();
        verifyNoInteractions(ai);
    }

    private static Stream<Update> invalidUpdates() {
        Instant base = Instant.parse("2020-01-01T00:00:00Z");
        return Stream.of(
                update(base, "NON_FINANCIAL_OPERATING", "PE", null, "22", "依據", "https://example.com/research"),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "18", null, "依據", "https://example.com/research"),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "0", "22", "依據", "https://example.com/research"),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "-1", "22", "依據", "https://example.com/research"),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "22", "18", "依據", "https://example.com/research"),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "18", "22", " ", "https://example.com/research"),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "18", "22", "依據", " "),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "18", "22", "依據", "file:///private/research"),
                update(base, "NON_FINANCIAL_OPERATING", "PE", "18", "22", "依據", "https:///missing-host"),
                update(base, "BANK", "PFCF", null, null, null, null),
                update(base, "UNKNOWN", "PE", null, null, null, null));
    }

    @Test
    void insufficientEvidenceHasNoUsableDraftOrDatabaseWrite() {
        when(ai.configured()).thenReturn(true);
        when(ai.propose(any())).thenReturn(new IndustryValuationAiClient.Candidate(
                false, null, null, List.of(), "沒有足夠來源", "response", "model", "{}"));

        assertStatus(() -> service.propose("I43"), HttpStatus.UNPROCESSABLE_ENTITY);
        assertNoWrites();
        verify(ai, times(1)).propose(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OpenAI API HTTP 429", "AI 請求逾時"})
    void providerFailureIsNotRetriedOrSaved(String message) {
        when(ai.configured()).thenReturn(true);
        IllegalStateException failure = new IllegalStateException(message);
        when(ai.propose(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.propose("I43")).isSameAs(failure);
        verify(ai, times(1)).propose(any());
        assertNoWrites();
    }

    @Test
    void missingAiConfigurationReturnsUnavailableWithoutSendingARequest() {
        assertStatus(() -> service.propose("I43"), HttpStatus.SERVICE_UNAVAILABLE);
        verify(ai, never()).propose(any());
        assertNoWrites();
    }

    private void assertNoWrites() {
        verify(configurations, never()).save(any());
        verify(configurations, never()).saveAndFlush(any());
        verify(configurations, never()).saveAll(any());
        verify(industries, never()).save(any());
    }

    private static Update update(Instant base, String model, String metric, String low, String high, String rationale, String source) {
        return new Update(base, model, metric, low == null ? null : new BigDecimal(low),
                high == null ? null : new BigDecimal(high), rationale, source);
    }

    private static void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(expected));
    }
}
