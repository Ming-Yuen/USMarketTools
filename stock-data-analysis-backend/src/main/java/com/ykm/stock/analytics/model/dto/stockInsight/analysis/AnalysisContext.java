package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.Instant;

/** analysisAsOf 是財務/價格分析截止；knowledgeAsOf 是補充觀察可讀取截止（最新報告為現在，指定歷史報告為當日收市）。 */
public record AnalysisContext(String figi, Instant analysisAsOf, Instant knowledgeAsOf, AnalysisHorizon horizon) {
    public AnalysisContext {
        if (figi == null || figi.isBlank()) throw new IllegalArgumentException("figi is required");
        if (analysisAsOf == null) throw new IllegalArgumentException("analysisAsOf is required");
        if (knowledgeAsOf == null) throw new IllegalArgumentException("knowledgeAsOf is required");
        if (knowledgeAsOf.isBefore(analysisAsOf)) throw new IllegalArgumentException("knowledgeAsOf cannot be before analysisAsOf");
        if (horizon == null) throw new IllegalArgumentException("horizon is required");
    }

    /** 事件、第三方倍數與預測的知悉截止；交易日事實數值另依其業務日期選取。 */
    public Instant observationCutoff() { return knowledgeAsOf; }

    /** 改分析日期不隱含改變補充資料的截止設定。 */
    public AnalysisContext withAnalysisAsOf(Instant cutoff) {
        return new AnalysisContext(figi, cutoff, knowledgeAsOf, horizon);
    }

    /** 同一分析 run 切換基準證券時保留時間規則。 */
    public AnalysisContext withFigi(String childFigi) {
        return new AnalysisContext(childFigi, analysisAsOf, knowledgeAsOf, horizon);
    }
}
