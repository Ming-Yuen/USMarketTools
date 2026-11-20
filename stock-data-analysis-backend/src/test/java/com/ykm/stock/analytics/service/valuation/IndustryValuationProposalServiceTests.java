package com.ykm.stock.analytics.service.valuation;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Overview;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.OverviewItem;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Source;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Update;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository.IndustryActiveStockCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
        assertThat(result.supportedMetrics().get("NON_FINANCIAL_OPERATING")).containsExactly("PE", "PB", "PS", "PFCF", "EV_EBITDA");
        assertThat(result.supportedMetrics().get("BANK")).containsExactly("PE", "PB");
        assertThat(IndustryValuationProposalService.resolveModelCode(null, "US.LIST2481", "7370")).isEqualTo("BANK");
        assertThat(IndustryValuationProposalService.resolveModelCode(null, null, "6021")).isEqualTo("BANK");
        assertThat(IndustryValuationProposalService.resolveModelCode(null, "US.LIST2260", "6211")).isEqualTo("BANK");
        assertThat(IndustryValuationProposalService.resolveModelCode(null, "US.LIST2249", "6282")).isEqualTo("NON_FINANCIAL_OPERATING");
        assertThat(IndustryValuationProposalService.resolveModelCode(null, null, "6331")).isEqualTo("INSURANCE");
        assertThat(IndustryValuationProposalService.resolveModelCode(null, "US.LIST2230", "6411")).isEqualTo("NON_FINANCIAL_OPERATING");
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

    @Test
    void listOverviewReturnsEmptyWhenIndustryDictionaryIsEmptyWithoutBatchQueriesOrAi() {
        Instant fixedNow = Instant.parse("2026-11-20T03:00:00Z");
        IndustryValuationProposalService clockedService = new IndustryValuationProposalService(
                industries, configurations, ai, fixedClock(fixedNow));
        when(industries.findAll()).thenReturn(List.of());

        Overview result = clockedService.listOverview();

        assertThat(result.items()).isEmpty();
        assertThat(result.supportedMetrics()).containsKeys("NON_FINANCIAL_OPERATING", "BANK", "INSURANCE");
        verify(industries).findAll();
        verify(industries, never()).countActiveStocksByIndustry();
        verify(configurations, never()).findByIndustryCodesAt(any(), any());
        verifyNoInteractions(ai);
    }

    @Test
    void listOverviewAggregatesConfiguredStatusActiveCountsCanProposeAndDefaultOrderWithoutCallingAi() {
        Instant fixedNow = Instant.parse("2026-11-20T03:00:00Z");
        IndustryValuationProposalService clockedService = new IndustryValuationProposalService(
                industries, configurations, ai, fixedClock(fixedNow));

        StockIndustry semi = industryEntity("I43", "Semiconductors");
        StockIndustry bank = industryEntity("BANK01", "Regional Banks");
        StockIndustry bio = industryEntity("BIO01", "Biotech");
        StockIndustry empty = industryEntity("EMPTY01", "Empty Industry");
        when(industries.findAll()).thenReturn(List.of(semi, empty, bio, bank));
        when(industries.countActiveStocksByIndustry()).thenReturn(List.of(
                stockCount("I43", 42L),
                stockCount("BANK01", 12L),
                stockCount("BIO01", 25L)
        ));

        IndustryValuationConfig semiConfig = new IndustryValuationConfig();
        semiConfig.setIndustryCode("I43");
        semiConfig.setEffectiveAt(Instant.parse("2026-11-01T00:00:00Z"));
        semiConfig.setModelCode("NON_FINANCIAL_OPERATING");
        semiConfig.setPrimaryMetric("PE");
        semiConfig.setAssumedMultipleLow(new BigDecimal("15"));
        semiConfig.setAssumedMultipleHigh(new BigDecimal("25"));
        semiConfig.setSourceUrl("https://example.com/semi");

        IndustryValuationConfig bankPartialConfig = new IndustryValuationConfig();
        bankPartialConfig.setIndustryCode("BANK01");
        bankPartialConfig.setEffectiveAt(Instant.parse("2026-11-10T00:00:00Z"));
        bankPartialConfig.setModelCode("BANK");
        bankPartialConfig.setPrimaryMetric("PB");
        bankPartialConfig.setAssumedMultipleLow(null);
        bankPartialConfig.setAssumedMultipleHigh(null);

        when(configurations.findByIndustryCodesAt(eq(List.of("I43", "EMPTY01", "BIO01", "BANK01")), eq(fixedNow)))
                .thenReturn(List.of(semiConfig, bankPartialConfig));
        when(ai.configured()).thenReturn(true);

        Overview overview = clockedService.listOverview();

        // 預設排序：未設定優先（activeStockCount 降冪 -> industryCode 升冪），其次已設定
        assertThat(overview.items()).extracting(OverviewItem::industryCode)
                .containsExactly("BIO01", "BANK01", "EMPTY01", "I43");
        OverviewItem bioItem = overview.items().get(0);
        assertThat(bioItem.configured()).isFalse();
        assertThat(bioItem.canPropose()).isFalse();
        assertThat(bioItem.activeStockCount()).isEqualTo(25L);
        assertThat(bioItem.modelCode()).isNull();

        OverviewItem bankItem = overview.items().get(1);
        assertThat(bankItem.configured()).isFalse();
        assertThat(bankItem.modelCode()).isEqualTo("BANK");
        assertThat(bankItem.primaryMetric()).isEqualTo("PB");
        assertThat(bankItem.canPropose()).isTrue();
        assertThat(bankItem.activeStockCount()).isEqualTo(12L);

        OverviewItem emptyItem = overview.items().get(2);
        assertThat(emptyItem.configured()).isFalse();
        assertThat(emptyItem.activeStockCount()).isZero();
        assertThat(emptyItem.canPropose()).isFalse();

        OverviewItem semiItem = overview.items().get(3);
        assertThat(semiItem.configured()).isTrue();
        assertThat(semiItem.canPropose()).isTrue();
        assertThat(semiItem.assumedMultipleLow()).isEqualByComparingTo("15");
        assertThat(semiItem.assumedMultipleHigh()).isEqualByComparingTo("25");
        assertThat(semiItem.sourceUrl()).isEqualTo("https://example.com/semi");
        assertThat(semiItem.activeStockCount()).isEqualTo(42L);

        // 驗證僅各執行 1 次批量查詢，零 N+1 且零 AI propose 呼叫
        verify(industries, times(1)).findAll();
        verify(industries, times(1)).countActiveStocksByIndustry();
        verify(configurations, times(1)).findByIndustryCodesAt(eq(List.of("I43", "EMPTY01", "BIO01", "BANK01")), eq(fixedNow));
        verify(configurations, never()).findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(any(), any());
        verify(ai, times(1)).configured();
        verify(ai, never()).propose(any());

        // 當 AI 未配置時，即使已填寫 BANK/PB 也不應允許單筆 AI 提議
        when(ai.configured()).thenReturn(false);
        Overview noAiOverview = clockedService.listOverview();
        assertThat(noAiOverview.items()).allSatisfy(item -> assertThat(item.canPropose()).isFalse());
        verify(ai, never()).propose(any());
    }

    @Test
    void dateTimeServiceControlsCutoffHongKongDateAndMicrosecondPrecision() {
        // UTC 2026-11-19T16:30:00.123456789Z 對應香港時間 2026-11-20 00:30:00
        Instant fixedNow = Instant.parse("2026-11-19T16:30:00.123456789Z");
        IndustryValuationProposalService clockedService = new IndustryValuationProposalService(
                industries, configurations, ai, fixedClock(fixedNow));
        when(ai.configured()).thenReturn(true);
        when(ai.propose(any())).thenReturn(new IndustryValuationAiClient.Candidate(true,
                new BigDecimal("18"), new BigDecimal("22"), List.of(), "依據", "res", "model", "{}"));
        when(configurations.saveAndFlush(any(IndustryValuationConfig.class))).thenAnswer(invocation -> {
            current = invocation.getArgument(0);
            return current;
        });

        IndustryValuationReview.Draft draft = clockedService.propose("I43");
        assertThat(draft.asOf()).isEqualTo(LocalDate.of(2026, 11, 20));

        Instant base = current.getEffectiveAt();
        IndustryValuationReview saved = clockedService.save("I43", update(
                base, "NON_FINANCIAL_OPERATING", "PE", "18", "22", "依據", "https://example.com/research"));

        assertThat(saved.current().effectiveAt()).isEqualTo(Instant.parse("2026-11-19T16:30:00.123456Z"));
        InOrder order = inOrder(industries, configurations);
        order.verify(industries).lockForIndustryImport();
        order.verify(configurations).findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc("I43", fixedNow);
        order.verify(configurations).saveAndFlush(any(IndustryValuationConfig.class));
    }

    private static StockIndustry industryEntity(String code, String name) {
        StockIndustry entity = new StockIndustry();
        entity.setCode(code);
        entity.setIndustry(name);
        return entity;
    }

    private static IndustryActiveStockCount stockCount(String code, long count) {
        return new IndustryActiveStockCount() {
            @Override
            public String getIndustryCode() { return code; }

            @Override
            public long getActiveStockCount() { return count; }
        };
    }

    private static DateTimeService fixedClock(Instant fixedNow) {
        ZoneId hk = ZoneId.of("Asia/Hong_Kong");
        return new DateTimeService() {
            @Override
            public Instant now() { return fixedNow; }

            @Override
            public LocalDateTime nowLocalDateTime() { return fixedNow.atZone(hk).toLocalDateTime(); }

            @Override
            public ZonedDateTime nowZonedDateTime(ZoneId zoneId) { return fixedNow.atZone(zoneId); }

            @Override
            public LocalDate nowLocalDate() { return fixedNow.atZone(hk).toLocalDate(); }
        };
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
