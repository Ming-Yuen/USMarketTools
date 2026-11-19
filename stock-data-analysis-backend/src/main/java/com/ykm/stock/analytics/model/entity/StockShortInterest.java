package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.StockShortInterestId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 雙週官方 FINRA 口徑做空股數與回補天數原始紀錄；佔流通股比例於查詢時結合核實單一股類股數即時計算。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@IdClass(StockShortInterestId.class)
@Table(name = "stock_short_interest", indexes = {
        @Index(name = "idx_stock_short_interest_settlement", columnList = "settlement_date")
})
@Comment("美股雙週做空餘額（Short Interest）紀錄表，保存官方 FINRA 結算日做空股數、來源均量與回補天數。")
public class StockShortInterest {
    public static final String SOURCE_MASSIVE_FINRA = "MASSIVE_FINRA";
    public static final String SOURCE_FINRA_PUBLIC = "FINRA_PUBLIC";

    @Id
    @Column(name = "figi", nullable = false, length = 32)
    @Comment("股票或 ETF 的 OpenFIGI 主鍵。")
    private String figi;

    @Id
    @Column(name = "settlement_date", nullable = false)
    @Comment("FINRA 官方雙週做空餘額結算日期。")
    private LocalDate settlementDate;

    @Column(name = "symbol", nullable = false, length = 32)
    @Comment("結算同步當時之股票交易代碼 ticker。")
    private String symbol;

    @Column(name = "short_interest_shares", nullable = false)
    @Comment("截至結算日尚未回補之總做空股數。")
    private Long shortInterestShares;

    @Column(name = "avg_daily_volume_source")
    @Comment("來源官方口徑提供之同期日均成交量（股）；缺值時為 NULL。")
    private Long avgDailyVolumeSource;

    @Column(name = "days_to_cover_source", precision = 12, scale = 4)
    @Comment("來源官方口徑計算之回補天數（Days to Cover）；缺值時為 NULL。")
    private BigDecimal daysToCoverSource;

    @Column(name = "source", nullable = false, length = 32)
    @Comment("資料來源識別：MASSIVE_FINRA 或 FINRA_PUBLIC。")
    private String source;

    @Column(name = "observed_at", nullable = false)
    @Comment("本系統首次同步並可見此筆雙週做空數據的 UTC 時間。")
    private Instant observedAt;
}
