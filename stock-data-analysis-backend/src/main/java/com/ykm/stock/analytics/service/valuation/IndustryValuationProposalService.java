package com.ykm.stock.analytics.service.valuation;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Current;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Draft;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Overview;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.OverviewItem;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Update;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository.IndustryActiveStockCount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class IndustryValuationProposalService {
    public static final String MODEL_NON_FINANCIAL = "NON_FINANCIAL_OPERATING";
    public static final String MODEL_BANK = "BANK";
    public static final String MODEL_INSURANCE = "INSURANCE";
    private static final ZoneId HONG_KONG_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final Set<String> BANK_PLATES = Set.of("US.LIST2481", "US.LIST2456");
    private static final Set<String> INSURANCE_PLATES = Set.of("US.LIST2429", "US.LIST2512", "US.LIST2484", "US.LIST2467", "US.LIST2465");
    private static final Set<String> AMBIGUOUS_FINANCIAL_PLATES = Set.of("US.LIST2260", "US.LIST2261", "US.LIST2479", "US.LIST2495");
    private static final Map<String, List<String>> SUPPORTED_METRICS = supportedMetrics();
    private final StockIndustryRepository industries;
    private final IndustryValuationConfigRepository configurations;
    private final IndustryValuationAiClient ai;
    private final DateTimeService dateTimeService;

    @Autowired
    public IndustryValuationProposalService(StockIndustryRepository industries,
                                            IndustryValuationConfigRepository configurations,
                                            IndustryValuationAiClient ai,
                                            DateTimeService dateTimeService) {
        this.industries = industries;
        this.configurations = configurations;
        this.ai = ai;
        this.dateTimeService = Objects.requireNonNull(dateTimeService, "dateTimeService");
    }

    IndustryValuationProposalService(StockIndustryRepository industries,
                                     IndustryValuationConfigRepository configurations,
                                     IndustryValuationAiClient ai) {
        this(industries, configurations, ai, defaultClock());
    }

    /** 唯讀聚合全平台產業與截至當下已生效之最新估值設定；最多三次批量查詢、絕不觸發 AI 提議。 */
    @Transactional(readOnly = true)
    public Overview listOverview() {
        List<StockIndustry> allIndustries = industries.findAll();
        if (allIndustries.isEmpty()) {
            return new Overview(List.of(), SUPPORTED_METRICS);
        }
        Instant now = dateTimeService.now();
        List<String> codes = new ArrayList<>(allIndustries.size());
        for (StockIndustry industry : allIndustries) {
            codes.add(industry.getCode());
        }
        Map<String, Long> activeStockCounts = new HashMap<>();
        for (IndustryActiveStockCount row : industries.countActiveStocksByIndustry()) {
            activeStockCounts.put(row.getIndustryCode(), row.getActiveStockCount());
        }
        Map<String, IndustryValuationConfig> latestConfigs = new HashMap<>();
        for (IndustryValuationConfig config : configurations.findByIndustryCodesAt(codes, now)) {
            latestConfigs.put(config.getIndustryCode(), config);
        }
        boolean aiConfigured = ai.configured();
        List<OverviewItem> items = new ArrayList<>(allIndustries.size());
        for (StockIndustry industry : allIndustries) {
            String code = industry.getCode();
            IndustryValuationConfig config = latestConfigs.get(code);
            boolean configured = isConfigured(config);
            boolean canPropose = config != null && supported(config.getModelCode(), config.getPrimaryMetric()) && aiConfigured;
            long activeStockCount = activeStockCounts.getOrDefault(code, 0L);
            items.add(new OverviewItem(
                    code,
                    industry.getIndustry(),
                    config == null ? null : config.getModelCode(),
                    config == null ? null : config.getPrimaryMetric(),
                    config == null ? null : config.getAssumedMultipleLow(),
                    config == null ? null : config.getAssumedMultipleHigh(),
                    config == null ? null : config.getEffectiveAt(),
                    config == null ? null : config.getSourceUrl(),
                    configured,
                    canPropose,
                    activeStockCount
            ));
        }
        items.sort(Comparator.comparing(OverviewItem::configured)
                .thenComparing(Comparator.comparingLong(OverviewItem::activeStockCount).reversed())
                .thenComparing(OverviewItem::industryCode));
        return new Overview(items, SUPPORTED_METRICS);
    }

    /** 只讀分類及當前正式設定，不在查看畫面時搜尋或產生 AI 假設。 */
    @Transactional(readOnly = true)
    public IndustryValuationReview get(String code) {
        StockIndustry industry = industry(code);
        IndustryValuationConfig config = current(code, dateTimeService.now());
        boolean canPropose = config != null && supported(config.getModelCode(), config.getPrimaryMetric()) && ai.configured();
        return new IndustryValuationReview(code, industry.getIndustry(), toCurrent(config), SUPPORTED_METRICS, canPropose);
    }

    /** 使用者主動要求才取得提議；回傳待確認數值，不存 raw response、hash 或另一張草稿表。 */
    public Draft propose(String code) {
        StockIndustry industry = industry(code);
        Instant now = dateTimeService.now();
        IndustryValuationConfig config = current(code, now);
        if (config == null || !supported(config.getModelCode(), config.getPrimaryMetric()))
            throw failure(HttpStatus.CONFLICT, "請先設定這個產業可用的估值模型及主要指標。");
        if (!ai.configured()) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "尚未設定 AI API，不能產生提議。");
        LocalDate asOf = now.atZone(HONG_KONG_ZONE).toLocalDate();
        IndustryValuationAiClient.Candidate candidate = ai.propose(new IndustryValuationAiClient.Input(
                code, industry.getIndustry(), config.getModelCode(), config.getPrimaryMetric(), asOf));
        if (!candidate.proposed()) throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "未能取得可用假設：" + candidate.rationale());
        return new Draft(config.getModelCode(), config.getPrimaryMetric(), candidate.low(), candidate.high(),
                asOf, candidate.rationale(), candidate.sources());
    }

    /** 人工確認後才新增正式設定；生效時間是現在，不把新假設回填成歷史已知。 */
    @Transactional
    public IndustryValuationReview save(String code, Update input) {
        industries.lockForIndustryImport();
        industry(code);
        Instant now = dateTimeService.now();
        IndustryValuationConfig previous = current(code, now);
        Instant expected = previous == null ? null : previous.getEffectiveAt();
        if (!Objects.equals(expected, input.baseEffectiveAt()))
            throw failure(HttpStatus.CONFLICT, "正式設定已改變，請重新載入後確認。");
        if (!supported(input.modelCode(), input.primaryMetric()))
            throw failure(HttpStatus.BAD_REQUEST, "模型不支援所選主要估值指標。");
        validateRange(input.assumedMultipleLow(), input.assumedMultipleHigh());
        if (input.assumedMultipleLow() != null && (blank(input.rationale()) || blank(input.sourceUrl())))
            throw failure(HttpStatus.BAD_REQUEST, "合理倍數假設須有說明及主要來源網址。");
        if (!blank(input.sourceUrl())) {
            try {
                URI source = URI.create(input.sourceUrl());
                if (!List.of("https", "http").contains(source.getScheme()) || source.getHost() == null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw failure(HttpStatus.BAD_REQUEST, "來源網址必須是有效的 HTTP 或 HTTPS 網址。");
            }
        }
        IndustryValuationConfig next = new IndustryValuationConfig();
        next.setIndustryCode(code);
        next.setEffectiveAt(now.truncatedTo(ChronoUnit.MICROS));
        next.setModelCode(input.modelCode());
        next.setPrimaryMetric(input.primaryMetric());
        next.setAssumedMultipleLow(input.assumedMultipleLow());
        next.setAssumedMultipleHigh(input.assumedMultipleHigh());
        next.setRationale(input.rationale());
        next.setSourceUrl(input.sourceUrl());
        configurations.saveAndFlush(next);
        return get(code);
    }

    private IndustryValuationConfig current(String code, Instant cutoff) {
        return configurations.findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(code, cutoff).orElse(null);
    }

    private static boolean isConfigured(IndustryValuationConfig config) {
        return config != null
                && config.getModelCode() != null
                && config.getPrimaryMetric() != null
                && config.getAssumedMultipleLow() != null
                && config.getAssumedMultipleHigh() != null;
    }

    private static DateTimeService defaultClock() {
        return new DateTimeService() {
            @Override
            public Instant now() { return Instant.now(); }

            @Override
            public LocalDateTime nowLocalDateTime() { return ZonedDateTime.now(HONG_KONG_ZONE).toLocalDateTime(); }

            @Override
            public ZonedDateTime nowZonedDateTime(ZoneId zoneId) { return ZonedDateTime.now(zoneId); }

            @Override
            public LocalDate nowLocalDate() { return LocalDate.now(HONG_KONG_ZONE); }
        };
    }

    private StockIndustry industry(String code) {
        return industries.findById(code).orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "找不到指定產業。"));
    }

    private Current toCurrent(IndustryValuationConfig value) {
        return value == null ? null : new Current(value.getEffectiveAt(), value.getModelCode(), value.getPrimaryMetric(),
                value.getAssumedMultipleLow(), value.getAssumedMultipleHigh(), value.getRationale(), value.getSourceUrl());
    }

    private static boolean supported(String model, String metric) {
        return model != null && metric != null && SUPPORTED_METRICS.getOrDefault(model, List.of()).contains(metric);
    }

    private static void validateRange(BigDecimal low, BigDecimal high) {
        if ((low == null) != (high == null) || (low != null && (low.signum() <= 0 || high.compareTo(low) < 0)))
            throw failure(HttpStatus.BAD_REQUEST, "合理倍數上下限須同時提供、為正數，且下限不得高於上限。");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ResponseStatusException failure(HttpStatus status, String message) { return new ResponseStatusException(status, message); }

    /** 統一判定產業估值模型：優先採用已生效設定，其次依富途銀行／保險板塊與 SEC 4 碼 SIC 判定銀行、保險與證券經紀業。 */
    public static String resolveModelCode(String configuredModelCode, String sourcePlateCode, String sic) {
        if (MODEL_BANK.equals(configuredModelCode) || MODEL_INSURANCE.equals(configuredModelCode)
                || MODEL_NON_FINANCIAL.equals(configuredModelCode)) {
            return configuredModelCode;
        }
        if (sourcePlateCode != null && BANK_PLATES.contains(sourcePlateCode)) return MODEL_BANK;
        if (sourcePlateCode != null && INSURANCE_PLATES.contains(sourcePlateCode)) return MODEL_INSURANCE;
        if (sourcePlateCode == null || sourcePlateCode.isBlank() || AMBIGUOUS_FINANCIAL_PLATES.contains(sourcePlateCode)) {
            int code = parseSic(sic);
            if ((code >= 6000 && code <= 6199) || code == 6712 || (code >= 6200 && code <= 6219)) return MODEL_BANK;
            if (code >= 6300 && code <= 6399) return MODEL_INSURANCE;
        }
        return MODEL_NON_FINANCIAL;
    }

    public static boolean isFinancialInstitutionModel(String modelCode) {
        return MODEL_BANK.equals(modelCode) || MODEL_INSURANCE.equals(modelCode);
    }

    /** SEC 合約收入排除範圍：銀行（60xx-61xx、6712）、證券經紀與投資業（62xx）、保險（63xx-64xx）。 */
    public static boolean isFinancialSicExcludedFromContractRevenue(String sic) {
        int code = parseSic(sic);
        return (code >= 6000 && code <= 6499) || code == 6712;
    }

    private static int parseSic(String sic) {
        if (sic == null || sic.length() != 4) return -1;
        for (int i = 0; i < 4; i++) {
            if (!Character.isDigit(sic.charAt(i))) return -1;
        }
        return Integer.parseInt(sic);
    }

    private static Map<String, List<String>> supportedMetrics() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put(MODEL_NON_FINANCIAL, List.of("PE", "PB", "PS", "PFCF", "EV_EBITDA"));
        result.put(MODEL_BANK, List.of("PE", "PB"));
        result.put(MODEL_INSURANCE, List.of("PE", "PB"));
        return java.util.Collections.unmodifiableMap(result);
    }
}
