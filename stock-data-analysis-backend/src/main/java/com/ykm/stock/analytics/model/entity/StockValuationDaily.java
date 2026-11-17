package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.StockValuationDailyId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 以交易日收盤價及其所屬財季於本輪已取得的最新財務數字計算市場倍數。 */
@Entity
@Getter
@Setter
@Table(name = "stock_valuation_daily")
@IdClass(StockValuationDailyId.class)
@Check(name = "ck_stock_valuation_market_cap_source", constraints = "market_cap_source in ('OBSERVED_SHARES','FUTU_CLOSE_SNAPSHOT')")
public class StockValuationDaily {
    @Id
    @Column(length = 32, nullable = false)
    @Comment("估值所屬證券 FIGI。")
    private String figi;
    @Id
    @Column(name = "valuation_date", nullable = false)
    @Comment("實際使用未復權收盤價的交易日，不是計算執行日期。")
    private LocalDate valuationDate;
    @Column(nullable = false, columnDefinition = "timestamp")
    @Comment("本系統最近計算或撤銷已失效估值資格的時間。")
    private Instant calculatedAt;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Comment("本列最近寫入估值結果的香港時間；舊列尚未更新時為 NULL。")
    private LocalDateTime updatedAt;
    @Column(nullable = false, columnDefinition = "timestamp")
    @Comment("估值交易日美股收市時刻；供價格及股數基準使用，不限制財報回算的取得時間。")
    private Instant valuationCutoff;
    @Column(columnDefinition = "timestamp")
    @Comment("本輪財務輸入最遲實際取得時間；Futu／SEC 均可晚於股價日，不是公告日。")
    private Instant financialObservedAt;
    @Column(columnDefinition = "timestamp")
    @Comment("Futu 財報輸入範圍的最遲取得時間；與 SEC 時間分開，避免 SEC 更新誤判其他指標原料已變。")
    private Instant futuFinancialObservedAt;
    @Column(columnDefinition = "timestamp")
    @Comment("股數實際取得時間；富途收市快照可晚於收市，SEC 股數另按原始披露日判斷。")
    private Instant sharesObservedAt;
    @Comment("SEC 流通普通股股數的資產負債表日期；富途快照或股數未採用時為 NULL。")
    private LocalDate secShareBasisDate;
    @Column(length = 20)
    @Comment("用於市值的 SEC 原始股數申報 accession；富途股數或無股數時為 NULL。")
    private String secShareAccession;
    @Column(length = 32)
    @Comment("FUTU_CLOSE_SNAPSHOT 為同收盤價快照市值估算；OBSERVED_SHARES 為收盤價乘已知股數；NULL 表示不可用或舊資料未記錄。")
    private String marketCapSource;
    @Column(length = 16)
    @Comment("PS 財務分母來源 FUTU 或 SEC；舊列 NULL 沿用原富途來源，不代表市值來源。")
    private String psFinancialSource;
    @Comment("SEC 收入 TTM 真實起日；富途未提供精確起日則為 NULL。")
    private LocalDate psPeriodStart;
    @Comment("PS 收入期末，與估值交易日及利潤表日期分開。")
    private LocalDate psPeriodEnd;
    @Column(length = 16)
    @Comment("P/FCF 分母來源 FUTU、SEC 或 FUTU_AND_SEC；補缺採 CFO 減同期間資本支出。")
    private String pfcfFinancialSource;
    @Comment("SEC FCF TTM 真實起日；富途未提供精確起日則為 NULL。")
    private LocalDate pfcfPeriodStart;
    @Comment("P/FCF 現金流期末，不是執行日。")
    private LocalDate pfcfPeriodEnd;
    @Comment("所選 SEC 財務輸入的最遲可用交易日；歷史回算可晚於股價日，沒有 SEC 輸入時為 NULL。")
    private LocalDate secFinancialAvailableFromDate;
    @Column(length = 10)
    @Comment("本列選用 SEC 財務輸入的 CIK；重算沿用此日已建立的發行人識別，不把目前 CIK 回推其他歷史日期。")
    private String secFinancialCik;
    @Comment("PE 選用的利潤表期末日，與估值交易日分開。")
    private LocalDate incomePeriodEnd;
    @Column(length = 16)
    @Comment("PE 稀釋 EPS 來源。")
    private String peFinancialSource;
    @Column(length = 16)
    @Comment("PB 普通股帳面權益來源。")
    private String pbFinancialSource;
    @Comment("PB 帳面權益期末。")
    private LocalDate balancePeriodEnd;
    @Column(length = 16)
    @Comment("EV/EBITDA 財務原料來源 FUTU、SEC 或 FUTU_AND_SEC。")
    private String evEbitdaFinancialSource;
    @Comment("EV/EBITDA 分母 EBITDA 的 TTM 期末。")
    private LocalDate evEbitdaPeriodEnd;
    @Comment("EPS 所用最早單季期末；直接用年度 EPS 時為年度期末，用於檢查期間內拆股基準。")
    private LocalDate epsBasisFromDate;
    @Column(length = 16)
    @Comment("行情交易幣別；財務金額未經匯兌，只使用同幣別輸入。")
    private String currency;
    @Column(precision = 19, scale = 8)
    @Comment("估值交易日的未復權收盤價。")
    private BigDecimal closePrice;
    @Column(columnDefinition = "numeric")
    @Comment("收盤價除以已核實的稀釋 EPS TTM；不足時為 NULL，見 pe_reason。")
    private BigDecimal pe;
    @Column(length = 160)
    @Comment("PE 未取得有效輸入的原因；有數值時為 NULL。")
    private String peReason;
    @Column(columnDefinition = "numeric")
    @Comment("市值除以普通股帳面權益；不足時為 NULL，不能直接使用總權益。")
    private BigDecimal pb;
    @Column(length = 160)
    @Comment("PB 的輸入不足或口徑未確認原因。")
    private String pbReason;
    @Column(columnDefinition = "numeric")
    @Comment("市值除以同口徑收入 TTM；不足時為 NULL。")
    private BigDecimal ps;
    @Column(length = 160)
    @Comment("PS 的輸入不足或口徑未確認原因。")
    private String psReason;
    @Column(columnDefinition = "numeric")
    @Comment("市值除以已核實口徑 FCF TTM；不足時為 NULL。")
    private BigDecimal pfcf;
    @Column(length = 160)
    @Comment("P/FCF 的輸入不足或口徑未確認原因。")
    private String pfcfReason;
    @Column(columnDefinition = "numeric")
    @Comment("企業價值除以 EBITDA TTM；來源與期末另列，範圍不足則留空。")
    private BigDecimal evEbitda;
    @Column(length = 160)
    @Comment("EV/EBITDA 的輸入不足或口徑未確認原因。")
    private String evEbitdaReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "figi", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_stock_valuation_stock"))
    private Stock stock;

    public boolean hasAvailableMetric() {
        return pe != null || (pb != null && pb.signum() > 0) || ps != null || pfcf != null || evEbitda != null;
    }

    /** 偵測舊指標將變為缺值，由排程分辨暫缺與已確認資格失效。 */
    public boolean losesAvailableMetric(StockValuationDaily previous) {
        return previous.pe != null && pe == null || previous.pb != null && pb == null || previous.ps != null && ps == null
                || previous.pfcf != null && pfcf == null || previous.evEbitda != null && evEbitda == null;
    }
}
