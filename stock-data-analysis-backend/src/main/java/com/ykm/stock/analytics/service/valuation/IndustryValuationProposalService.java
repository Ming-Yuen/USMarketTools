package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Current;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Draft;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Update;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class IndustryValuationProposalService {
    private static final Map<String, List<String>> SUPPORTED_METRICS = supportedMetrics();
    private final StockIndustryRepository industries;
    private final IndustryValuationConfigRepository configurations;
    private final IndustryValuationAiClient ai;

    /** 只讀分類及當前正式設定，不在查看畫面時搜尋或產生 AI 假設。 */
    @Transactional(readOnly = true)
    public IndustryValuationReview get(String code) {
        StockIndustry industry = industry(code);
        IndustryValuationConfig config = current(code);
        boolean canPropose = config != null && supported(config.getModelCode(), config.getPrimaryMetric()) && ai.configured();
        return new IndustryValuationReview(code, industry.getIndustry(), toCurrent(config), SUPPORTED_METRICS, canPropose);
    }

    /** 使用者主動要求才取得提議；回傳待確認數值，不存 raw response、hash 或另一張草稿表。 */
    public Draft propose(String code) {
        StockIndustry industry = industry(code);
        IndustryValuationConfig config = current(code);
        if (config == null || !supported(config.getModelCode(), config.getPrimaryMetric()))
            throw failure(HttpStatus.CONFLICT, "請先設定這個產業可用的估值模型及主要指標。");
        if (!ai.configured()) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "尚未設定 AI API，不能產生提議。");
        LocalDate asOf = LocalDate.now(ZoneId.of("Asia/Hong_Kong"));
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
        IndustryValuationConfig previous = current(code);
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
        next.setEffectiveAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        next.setModelCode(input.modelCode());
        next.setPrimaryMetric(input.primaryMetric());
        next.setAssumedMultipleLow(input.assumedMultipleLow());
        next.setAssumedMultipleHigh(input.assumedMultipleHigh());
        next.setRationale(input.rationale());
        next.setSourceUrl(input.sourceUrl());
        configurations.saveAndFlush(next);
        return get(code);
    }

    private IndustryValuationConfig current(String code) {
        return configurations.findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(code, Instant.now()).orElse(null);
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

    private static Map<String, List<String>> supportedMetrics() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("NON_FINANCIAL_OPERATING", List.of("PE", "PS", "PFCF", "EV_EBITDA"));
        result.put("BANK", List.of("PE", "PB"));
        result.put("INSURANCE", List.of("PE", "PB"));
        return java.util.Collections.unmodifiableMap(result);
    }
}
