package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.BriefingRadarItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.BriefingSectorDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.WatchlistOpportunityDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "daily_closing_briefing")
@Comment("每日收盤簡報持久化快照表（每交易日一筆）：集中保存當日大盤市況、強弱板塊、持倉體檢摘要、自選買點與全市場雷達精選前 5 名。")
public class DailyClosingBriefing {
    @Id
    @Column(name = "trade_date", nullable = false)
    @Comment("簡報對應之美東已收市交易日（同日首次完整發布即凍結，避免同日重跑覆寫）。")
    private LocalDate tradeDate;

    @Column(name = "market_regime", length = 32)
    @Comment("當日大盤市況（RISK_ON、NEUTRAL、RECOVERY、CORRECTION、BEAR、PANIC）。")
    private String marketRegime;

    @Column(name = "previous_market_regime", length = 32)
    @Comment("上一份已發布收盤簡報之大盤市況；首次發布時為 NULL。")
    private String previousMarketRegime;

    @Column(name = "previous_trade_date")
    @Comment("上一份已發布收盤簡報之交易日；首次發布時為 NULL。")
    private LocalDate previousTradeDate;

    @Column(name = "regime_changed", nullable = false)
    @Comment("當日大盤市況是否較上一份已發布簡報發生實質切換。")
    private boolean regimeChanged;

    @Column(name = "vix_close", precision = 12, scale = 4)
    @Comment("截至簡報交易日可用之 VIX 收盤水準；缺值為 NULL。")
    private BigDecimal vixClose;

    @Column(name = "treasury_10y", precision = 10, scale = 4)
    @Comment("截至簡報交易日可用之美國 10 年期公債殖利率（百分比數值，如 4.35）；缺值為 NULL。")
    private BigDecimal treasury10y;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_sectors", nullable = false, columnDefinition = "jsonb")
    @Comment("截至當日 20 日量價資金流（CMF20d）最強前 2 大板塊清單。")
    private List<BriefingSectorDto> topSectors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bottom_sectors", nullable = false, columnDefinition = "jsonb")
    @Comment("截至當日 20 日量價資金流（CMF20d）最弱前 2 大板塊清單。")
    private List<BriefingSectorDto> bottomSectors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "portfolio_health", nullable = false, columnDefinition = "jsonb")
    @Comment("當日持倉與自選自動體檢摘要（含拆股對齊損益、止損與目標參考價、分級警示及板塊集中度）。")
    private PortfolioHealthSummaryDto portfolioHealth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "watchlist_opportunities", nullable = false, columnDefinition = "jsonb")
    @Comment("當日重點自選股觸發有效買入觀察區之機會清單（含參考買入區、止損價與建議股數）。")
    private List<WatchlistOpportunityDto> watchlistOpportunities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "radar_top5", nullable = false, columnDefinition = "jsonb")
    @Comment("當日全市場每日機會雷達雙軌精選前 5 名清單（含參考買入區、止損價與建議股數）。")
    private List<BriefingRadarItemDto> radarTop5;

    @Column(name = "critical_count", nullable = false)
    @Comment("當日實際業務警示中 CRITICAL 嚴重度總筆數（不含簡報已就緒通知）。")
    private int criticalCount;

    @Column(name = "warning_count", nullable = false)
    @Comment("當日實際業務警示中 WARNING 嚴重度總筆數。")
    private int warningCount;

    @Column(name = "info_count", nullable = false)
    @Comment("當日實際業務警示中 INFO 嚴重度總筆數（不含簡報已就緒通知）。")
    private int infoCount;

    @Column(name = "observation_cutoff", nullable = false)
    @Comment("本次收盤體檢固定採用之來源資料觀察截點（UTC）。")
    private Instant observationCutoff;

    @Column(name = "generated_at", nullable = false)
    @Comment("本次收盤簡報實際計算並寫入之 UTC 時間。")
    private Instant generatedAt;
}
