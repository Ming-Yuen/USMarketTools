package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.enums.ValuationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stock_valuation_latest", indexes = {
        @Index(name = "idx_stock_valuation_latest_industry", columnList = "industry_code"),
        @Index(name = "idx_stock_valuation_latest_date", columnList = "valuation_date"),
        @Index(name = "idx_stock_valuation_latest_tier", columnList = "market_cap_tier"),
        @Index(name = "idx_stock_valuation_latest_verdict", columnList = "verdict")
})
@Comment("最新收市交易日估值快照表，以 FIGI 為主鍵保存五項市場倍數、狀態與原因碼，供全市場選股與同業比較直接查詢。")
public class StockValuationLatest {
    @Id
    @Column(name = "figi", nullable = false, length = 32)
    @Comment("股票 FIGI 識別碼；每檔追蹤股票保存一筆最新估值快照。")
    private String figi;

    @Column(name = "valuation_date", nullable = false)
    @Comment("快照對應的最新已收市美股交易日。")
    private LocalDate valuationDate;

    @Column(name = "analysis_as_of", nullable = false)
    @Comment("該估值交易日的正規交易時段收市截點（UTC）。")
    private Instant analysisAsOf;

    @Column(name = "calculated_at", nullable = false)
    @Comment("本次估值快照的計算與寫入時間（UTC）。")
    private Instant calculatedAt;

    @Column(name = "financial_observed_at")
    @Comment("所採用財季分母的財務資料最近入庫／觀察時間（UTC）。")
    private Instant financialObservedAt;

    @Column(name = "shares_observed_at")
    @Comment("市值計算採用的股數觀察或申報股數取得時間（UTC）。")
    private Instant sharesObservedAt;

    @Column(name = "market_cap_source", length = 32)
    @Comment("市值股數來源（FUTU_CLOSE_SNAPSHOT、OBSERVED_SHARES 或 SEC_FILING_SHARES）。")
    private String marketCapSource;

    @Column(name = "sec_financial_available_from_date")
    @Comment("若採用 SEC 財務事實，其最晚通過披露時間門的美東交易日。")
    private LocalDate secFinancialAvailableFromDate;

    @Column(name = "financial_source", length = 64)
    @Comment("整體財務資料來源摘要（FINANCIAL_REPORT_AS_OBSERVED 或 FUTU_AND_SEC_FINANCIAL_FACTS）。")
    private String financialSource;

    @Column(name = "currency", length = 16)
    @Comment("股票報價幣別代碼（如 USD）。")
    private String currency;

    @Column(name = "close_price", precision = 20, scale = 6)
    @Comment("估值交易日未復權收盤價。")
    private BigDecimal closePrice;

    @Column(name = "industry_code", length = 64)
    @Comment("估值交易日對應的產業分類代碼。")
    private String industryCode;

    @Column(name = "model_code", length = 64)
    @Comment("適用的產業估值模型代碼（NON_FINANCIAL_OPERATING、BANK、INSURANCE、REIT 等）。")
    private String modelCode;

    @Column(name = "primary_metric", length = 32)
    @Comment("本次選定的單一主估值指標（PE、PB、PS、PFCF 或 EV_EBITDA）。")
    private String primaryMetric;

    @Column(name = "status", nullable = false, length = 32)
    @Comment("整體估值狀態；至少一項倍數可用時為 AVAILABLE。")
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 64)
    @Comment("整體估值不可用時的原因碼；可用時為 NULL。")
    private ValuationReason reasonCode;

    @Column(name = "pe", precision = 20, scale = 4)
    @Comment("TTM 市盈率（P/E）；非正盈利或不可用時為 NULL。")
    private BigDecimal pe;

    @Column(name = "pe_status", length = 32)
    @Comment("P/E 指標狀態。")
    private String peStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "pe_reason", length = 64)
    @Comment("P/E 不可用或不適用原因碼。")
    private ValuationReason peReason;

    @Column(name = "pe_financial_source", length = 32)
    @Comment("P/E 財務分母來源（FUTU、SEC 或 FUTU_AND_SEC）。")
    private String peFinancialSource;

    @Column(name = "pe_period_start")
    @Comment("P/E TTM 分母期間起始日。")
    private LocalDate pePeriodStart;

    @Column(name = "pe_period_end")
    @Comment("P/E TTM 分母期間期末日。")
    private LocalDate pePeriodEnd;

    @Column(name = "pb", precision = 20, scale = 4)
    @Comment("市帳率（P/B）；非正普通股權益或不可用時為 NULL。")
    private BigDecimal pb;

    @Column(name = "pb_status", length = 32)
    @Comment("P/B 指標狀態。")
    private String pbStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "pb_reason", length = 64)
    @Comment("P/B 不可用或不適用原因碼。")
    private ValuationReason pbReason;

    @Column(name = "pb_financial_source", length = 32)
    @Comment("P/B 財務分母來源。")
    private String pbFinancialSource;

    @Column(name = "pb_period_end")
    @Comment("P/B 資產負債表期末日。")
    private LocalDate pbPeriodEnd;

    @Column(name = "ps", precision = 20, scale = 4)
    @Comment("TTM 市銷率（P/S）；非正營收或不可用時為 NULL。")
    private BigDecimal ps;

    @Column(name = "ps_status", length = 32)
    @Comment("P/S 指標狀態。")
    private String psStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "ps_reason", length = 64)
    @Comment("P/S 不可用或不適用原因碼。")
    private ValuationReason psReason;

    @Column(name = "ps_financial_source", length = 32)
    @Comment("P/S 財務分母來源。")
    private String psFinancialSource;

    @Column(name = "ps_period_start")
    @Comment("P/S TTM 分母期間起始日。")
    private LocalDate psPeriodStart;

    @Column(name = "ps_period_end")
    @Comment("P/S TTM 分母期間期末日。")
    private LocalDate psPeriodEnd;

    @Column(name = "pfcf", precision = 20, scale = 4)
    @Comment("TTM 市現率（P/FCF）；非正自由現金流、金融業不適用或缺值時為 NULL。")
    private BigDecimal pfcf;

    @Column(name = "pfcf_status", length = 32)
    @Comment("P/FCF 指標狀態。")
    private String pfcfStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "pfcf_reason", length = 64)
    @Comment("P/FCF 不可用或不適用原因碼。")
    private ValuationReason pfcfReason;

    @Column(name = "pfcf_financial_source", length = 32)
    @Comment("P/FCF 財務分母來源。")
    private String pfcfFinancialSource;

    @Column(name = "pfcf_period_start")
    @Comment("P/FCF TTM 分母期間起始日。")
    private LocalDate pfcfPeriodStart;

    @Column(name = "pfcf_period_end")
    @Comment("P/FCF TTM 分母期間期末日。")
    private LocalDate pfcfPeriodEnd;

    @Column(name = "ev_ebitda", precision = 20, scale = 4)
    @Comment("企業價值倍數（EV/EBITDA）；非正 EBITDA、金融業不適用或缺值時為 NULL。")
    private BigDecimal evEbitda;

    @Column(name = "ev_ebitda_status", length = 32)
    @Comment("EV/EBITDA 指標狀態。")
    private String evEbitdaStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "ev_ebitda_reason", length = 64)
    @Comment("EV/EBITDA 不可用或不適用原因碼。")
    private ValuationReason evEbitdaReason;

    @Column(name = "ev_ebitda_financial_source", length = 32)
    @Comment("EV/EBITDA 財務分母來源。")
    private String evEbitdaFinancialSource;

    @Column(name = "ev_ebitda_period_start")
    @Comment("EV/EBITDA TTM 分母期間起始日。")
    private LocalDate evEbitdaPeriodStart;

    @Column(name = "ev_ebitda_period_end")
    @Comment("EV/EBITDA TTM 分母期間期末日。")
    private LocalDate evEbitdaPeriodEnd;

    @Column(name = "market_cap", precision = 24, scale = 4)
    @Comment("估值交易日總市值（以股票報價幣別計）；缺少已核實流通股數或有效收盤價時為 NULL。")
    private BigDecimal marketCap;

    @Column(name = "market_cap_tier", length = 16)
    @Comment("市值量級（MEGA_LARGE >= 100 億美元、MID 20–100 億美元、SMALL < 20 億美元）；僅 currency 為 USD 且 market_cap > 0 時計算，否則為 NULL。")
    private String marketCapTier;

    @Column(name = "revenue_growth_yoy", precision = 16, scale = 6)
    @Comment("當期配對財季之 TTM 營業收入年增率（小數比例，如 0.15 代表 +15%）；缺少可比報期或基期非正時為 NULL。")
    private BigDecimal revenueGrowthYoy;

    @Column(name = "roe", precision = 16, scale = 6)
    @Comment("當期配對財季之普通股權益報酬率 ROE（小數比例）；普通股權益非正或缺值時為 NULL。")
    private BigDecimal roe;

    @Column(name = "fcf_margin", precision = 16, scale = 6)
    @Comment("當期配對財季之 TTM 自由現金流利潤率（小數比例）；銀行與保險業不適用或缺值時固定為 NULL。")
    private BigDecimal fcfMargin;

    @Column(name = "historical_percentile", precision = 8, scale = 2)
    @Comment("當日主估值指標於過去五年有效月末樣本中的經驗分位排名（0–100 mid-rank）；樣本未滿 6 個月或當期倍數非正時為 NULL。")
    private BigDecimal historicalPercentile;

    @Column(name = "verdict", length = 32)
    @Comment("單軌客觀市場參考帶 [L, M, H] 估值位置結論（DEEP_UNDERVALUED 至 OVERVALUED_RISK，或 INSUFFICIENT_EVIDENCE）。")
    private String verdict;

    public boolean hasAvailableMetric() {
        return pe != null || pb != null || ps != null || pfcf != null || evEbitda != null;
    }
}
