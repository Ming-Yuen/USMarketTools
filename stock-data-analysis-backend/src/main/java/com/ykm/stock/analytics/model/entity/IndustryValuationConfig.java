package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.IndustryValuationConfigId;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@IdClass(IndustryValuationConfigId.class)
@Table(name = "industry_valuation_config")
@Comment("產業估值設定；依實際生效時間選取，合理倍數假設不是市場觀測值。")
public class IndustryValuationConfig {
    @Id
    @Column(name = "industry_code", nullable = false, length = 96)
    @Comment("設定適用的產業代碼，參照股票產業字典。")
    private String industryCode;
    @Id
    @Column(name = "effective_at", nullable = false)
    @Comment("產業估值政策實際生效時刻；歷史分析不得讀取未生效設定。")
    private Instant effectiveAt;
    @Column(name = "model_code", nullable = false, length = 32)
    @Comment("產業採用的估值模型，例如營運企業與銀行模型。")
    private String modelCode;
    @Column(name = "primary_metric", length = 24)
    @Comment("產業偏好的主估值指標；可為空表示尚未確認。")
    private String primaryMetric;
    @Column(name = "assumed_multiple_low", columnDefinition = "numeric")
    @Comment("經人工確認的合理估值倍數下界；未知保留 NULL。")
    private BigDecimal assumedMultipleLow;
    @Column(name = "assumed_multiple_high", columnDefinition = "numeric")
    @Comment("經人工確認的合理估值倍數上界；未知保留 NULL。")
    private BigDecimal assumedMultipleHigh;
    @Column(columnDefinition = "text")
    @Comment("人工合理估值假設的理由及適用條件。")
    private String rationale;
    @Column(name = "source_url", columnDefinition = "text")
    @Comment("人工估值假設所參考的可查閱來源網址。")
    private String sourceUrl;
}
