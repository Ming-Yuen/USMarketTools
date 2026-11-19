package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.entity.key.StockFinancialPeriodId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@IdClass(StockFinancialPeriodId.class)
@Table(name = "stock_financial_period", indexes = {
        @Index(name = "idx_stock_financial_period_lookup", columnList = "figi, period_end, period_start")
})
@Comment("標準化財季分母與 14 項核心財務原料表，供最新估值與歷史交易日即時估值配對相除。")
public class StockFinancialPeriod {
    @Id
    @Column(name = "figi", nullable = false, length = 32)
    @Comment("股票 FIGI 識別碼。")
    private String figi;

    @Id
    @Column(name = "period_end", nullable = false)
    @Comment("標準化報表期末日；配對 SEC 時採 SEC 實際結算日，無 SEC 時採富途結算日。")
    private LocalDate periodEnd;

    @Column(name = "period_start")
    @Comment("該財季／單一報期的起始日，供歷史交易日按 period_start <= D <= period_end 配對。")
    private LocalDate periodStart;

    @Column(name = "fiscal_year")
    @Comment("所屬會計財年編號；非曆年制保留公司財年標籤，無法確認時為 NULL。")
    private Integer fiscalYear;

    @Column(name = "fiscal_quarter")
    @Comment("所屬會計財季（1 至 4）；無法確認季度時為 NULL。")
    private Integer fiscalQuarter;

    @Column(name = "reporting_currency", length = 16)
    @Comment("財報原幣代碼（如 USD、CAD、CNY、TWD）；非美元財報於估值計算邊界按官方匯率換算為 USD。")
    private String reportingCurrency;

    @Column(name = "accounting_standards", length = 32)
    @Comment("正規化會計準則（US_GAAP 或 IFRS），未知準則為 NULL。")
    private String accountingStandards;

    @Column(name = "available_at")
    @Comment("本期採用的財務資料最近入庫／觀察時間（UTC）。")
    private Instant availableAt;

    @Column(name = "sec_available_from_date")
    @Comment("本期若採用 SEC 披露事實，其最晚通過披露時間門的美東交易日。")
    private LocalDate secAvailableFromDate;

    @Column(name = "sec_periodic_observed_at")
    @Comment("本期最早具備定期申報證據之 SEC 財務事實入庫時間（UTC），供判定該財期是否已公開。")
    private Instant secPeriodicObservedAt;

    @Column(name = "sec_periodic_available_from_date")
    @Comment("本期最早具備定期申報證據之 SEC 財務事實生效交易日（美東），不受同季後續 10-Q/A 或補披露項目推遲。")
    private LocalDate secPeriodicAvailableFromDate;

    @Column(name = "sec_cik", length = 16)
    @Comment("本期補充或計算所使用的 SEC 十位數 CIK；純富途來源時為 NULL。")
    private String secCik;

    // 1. TTM 營業收入
    @Column(name = "revenue_ttm", precision = 24, scale = 4)
    @Comment("TTM 營業收入金額（原幣）。")
    private BigDecimal revenueTtm;

    @Column(name = "revenue_ttm_status", length = 32)
    @Comment("TTM 營業收入狀態（AVAILABLE、NOT_APPLICABLE、UNVERIFIED、INSUFFICIENT_DATA）。")
    private String revenueTtmStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "revenue_ttm_reason", length = 64)
    @Comment("TTM 營業收入不可用原因碼；可用時為 NULL。")
    private ValuationReason revenueTtmReason;

    @Column(name = "revenue_ttm_source", length = 32)
    @Comment("TTM 營業收入來源（FUTU、SEC 或 FUTU_AND_SEC）。")
    private String revenueTtmSource;

    @Column(name = "revenue_ttm_calculation", length = 256)
    @Comment("TTM 營業收入計算口徑說明。")
    private String revenueTtmCalculation;

    @Column(name = "revenue_ttm_period_start")
    @Comment("TTM 營業收入涵蓋十二個月期間的起始日。")
    private LocalDate revenueTtmPeriodStart;

    @Column(name = "revenue_ttm_available_at")
    @Comment("TTM 營業收入輸入資料的取得時間（UTC）。")
    private Instant revenueTtmAvailableAt;

    // 2. TTM 普通股淨利
    @Column(name = "net_income_common_ttm", precision = 24, scale = 4)
    @Comment("TTM 歸屬普通股股東淨利金額（原幣，保留虧損負值）。")
    private BigDecimal netIncomeCommonTtm;

    @Column(name = "net_income_common_ttm_status", length = 32)
    @Comment("TTM 普通股淨利狀態。")
    private String netIncomeCommonTtmStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "net_income_common_ttm_reason", length = 64)
    @Comment("TTM 普通股淨利不可用原因碼。")
    private ValuationReason netIncomeCommonTtmReason;

    @Column(name = "net_income_common_ttm_source", length = 32)
    @Comment("TTM 普通股淨利來源。")
    private String netIncomeCommonTtmSource;

    @Column(name = "net_income_common_ttm_calculation", length = 256)
    @Comment("TTM 普通股淨利計算口徑說明。")
    private String netIncomeCommonTtmCalculation;

    @Column(name = "net_income_common_ttm_period_start")
    @Comment("TTM 普通股淨利涵蓋十二個月期間的起始日。")
    private LocalDate netIncomeCommonTtmPeriodStart;

    @Column(name = "net_income_common_ttm_available_at")
    @Comment("TTM 普通股淨利輸入資料的取得時間（UTC）。")
    private Instant netIncomeCommonTtmAvailableAt;

    // 3. TTM 每股稀釋盈利與股份基準日
    @Column(name = "eps_ttm", precision = 20, scale = 6)
    @Comment("TTM 每股稀釋盈利（原幣／股，對應 eps_share_basis_date 股份基準，保留負值）。")
    private BigDecimal epsTtm;

    @Column(name = "eps_ttm_status", length = 32)
    @Comment("TTM 每股盈利狀態。")
    private String epsTtmStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "eps_ttm_reason", length = 64)
    @Comment("TTM 每股盈利不可用原因碼。")
    private ValuationReason epsTtmReason;

    @Column(name = "eps_ttm_source", length = 32)
    @Comment("TTM 每股盈利來源。")
    private String epsTtmSource;

    @Column(name = "eps_ttm_calculation", length = 256)
    @Comment("TTM 每股盈利計算口徑說明。")
    private String epsTtmCalculation;

    @Column(name = "eps_ttm_period_start")
    @Comment("TTM 每股盈利涵蓋十二個月期間的起始日。")
    private LocalDate epsTtmPeriodStart;

    @Column(name = "eps_ttm_available_at")
    @Comment("TTM 每股盈利輸入資料的取得時間（UTC）。")
    private Instant epsTtmAvailableAt;

    @Column(name = "eps_ttm_sec_available_from_date")
    @Comment("TTM 每股盈利若採用 SEC 披露事實，其構成項目最晚通過披露時間門的美東交易日；純富途來源時為 NULL。")
    private LocalDate epsTtmSecAvailableFromDate;

    @Column(name = "eps_share_basis_date")
    @Comment("eps_ttm 所對應的已核實股份拆股基準日；查詢交易日 D 時依此日轉換拆股因子。")
    private LocalDate epsShareBasisDate;

    // 4. TTM 營運現金流
    @Column(name = "operating_cash_flow_ttm", precision = 24, scale = 4)
    @Comment("TTM 營運活動現金流金額（原幣，保留負值）。")
    private BigDecimal operatingCashFlowTtm;

    @Column(name = "operating_cash_flow_ttm_status", length = 32)
    @Comment("TTM 營運現金流狀態。")
    private String operatingCashFlowTtmStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "operating_cash_flow_ttm_reason", length = 64)
    @Comment("TTM 營運現金流不可用原因碼。")
    private ValuationReason operatingCashFlowTtmReason;

    @Column(name = "operating_cash_flow_ttm_source", length = 32)
    @Comment("TTM 營運現金流來源。")
    private String operatingCashFlowTtmSource;

    @Column(name = "operating_cash_flow_ttm_calculation", length = 256)
    @Comment("TTM 營運現金流計算口徑說明。")
    private String operatingCashFlowTtmCalculation;

    @Column(name = "operating_cash_flow_ttm_period_start")
    @Comment("TTM 營運現金流涵蓋十二個月期間的起始日。")
    private LocalDate operatingCashFlowTtmPeriodStart;

    @Column(name = "operating_cash_flow_ttm_available_at")
    @Comment("TTM 營運現金流輸入資料的取得時間（UTC）。")
    private Instant operatingCashFlowTtmAvailableAt;

    // 5. TTM 資本支出
    @Column(name = "capital_expenditure_ttm", precision = 24, scale = 4)
    @Comment("TTM 完整資本支出金額（原幣，統一以正數表示現金流出規模）。")
    private BigDecimal capitalExpenditureTtm;

    @Column(name = "capital_expenditure_ttm_status", length = 32)
    @Comment("TTM 資本支出狀態。")
    private String capitalExpenditureTtmStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "capital_expenditure_ttm_reason", length = 64)
    @Comment("TTM 資本支出不可用原因碼。")
    private ValuationReason capitalExpenditureTtmReason;

    @Column(name = "capital_expenditure_ttm_source", length = 32)
    @Comment("TTM 資本支出一覽來源。")
    private String capitalExpenditureTtmSource;

    @Column(name = "capital_expenditure_ttm_calculation", length = 256)
    @Comment("TTM 資本支出計算口徑說明。")
    private String capitalExpenditureTtmCalculation;

    @Column(name = "capital_expenditure_ttm_period_start")
    @Comment("TTM 資本支出涵蓋十二個月期間的起始日。")
    private LocalDate capitalExpenditureTtmPeriodStart;

    @Column(name = "capital_expenditure_ttm_available_at")
    @Comment("TTM 資本支出輸入資料的取得時間（UTC）。")
    private Instant capitalExpenditureTtmAvailableAt;

    // 6. TTM 自由現金流
    @Column(name = "free_cash_flow_ttm", precision = 24, scale = 4)
    @Comment("TTM 自由現金流金額（原幣，保留負值供畫面顯示，計算倍數時非正標為 N.M.）。")
    private BigDecimal freeCashFlowTtm;

    @Column(name = "free_cash_flow_ttm_status", length = 32)
    @Comment("TTM 自由現金流狀態。")
    private String freeCashFlowTtmStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "free_cash_flow_ttm_reason", length = 64)
    @Comment("TTM 自由現金流不可用原因碼。")
    private ValuationReason freeCashFlowTtmReason;

    @Column(name = "free_cash_flow_ttm_source", length = 32)
    @Comment("TTM 自由現金流來源。")
    private String freeCashFlowTtmSource;

    @Column(name = "free_cash_flow_ttm_calculation", length = 256)
    @Comment("TTM 自由現金流計算口徑說明。")
    private String freeCashFlowTtmCalculation;

    @Column(name = "free_cash_flow_ttm_period_start")
    @Comment("TTM 自由現金流涵蓋十二個月期間的起始日。")
    private LocalDate freeCashFlowTtmPeriodStart;

    @Column(name = "free_cash_flow_ttm_available_at")
    @Comment("TTM 自由現金流輸入資料的取得時間（UTC）。")
    private Instant freeCashFlowTtmAvailableAt;

    // 7. TTM 營運 EBITDA
    @Column(name = "ebitda_ttm", precision = 24, scale = 4)
    @Comment("TTM 營運口徑 EBITDA（Operating Profit + DD&A，原幣，保留負值）。")
    private BigDecimal ebitdaTtm;

    @Column(name = "ebitda_ttm_status", length = 32)
    @Comment("TTM EBITDA 狀態。")
    private String ebitdaTtmStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "ebitda_ttm_reason", length = 64)
    @Comment("TTM EBITDA 不可用原因碼。")
    private ValuationReason ebitdaTtmReason;

    @Column(name = "ebitda_ttm_source", length = 32)
    @Comment("TTM EBITDA 來源。")
    private String ebitdaTtmSource;

    @Column(name = "ebitda_ttm_calculation", length = 256)
    @Comment("TTM EBITDA 計算口徑說明（標註損益或現金流 DD&A 標籤）。")
    private String ebitdaTtmCalculation;

    @Column(name = "ebitda_ttm_period_start")
    @Comment("TTM EBITDA 涵蓋十二個月期間的起始日。")
    private LocalDate ebitdaTtmPeriodStart;

    @Column(name = "ebitda_ttm_available_at")
    @Comment("TTM EBITDA 輸入資料的取得時間（UTC）。")
    private Instant ebitdaTtmAvailableAt;

    // 8. 普通股權益
    @Column(name = "common_equity", precision = 24, scale = 4)
    @Comment("期末普通股股東權益（扣除優先股帳面值後，原幣，保留負權益值）。")
    private BigDecimal commonEquity;

    @Column(name = "common_equity_status", length = 32)
    @Comment("期末普通股權益狀態。")
    private String commonEquityStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "common_equity_reason", length = 64)
    @Comment("期末普通股權益不可用原因碼。")
    private ValuationReason commonEquityReason;

    @Column(name = "common_equity_source", length = 32)
    @Comment("期末普通股權益來源。")
    private String commonEquitySource;

    @Column(name = "common_equity_calculation", length = 256)
    @Comment("期末普通股權益計算口徑說明。")
    private String commonEquityCalculation;

    @Column(name = "common_equity_available_at")
    @Comment("期末普通股權益輸入資料的取得時間（UTC）。")
    private Instant commonEquityAvailableAt;

    // 9. 期末流通在外股數基準
    @Column(name = "shares_outstanding", precision = 24, scale = 4)
    @Comment("期末或對應觀察基準之流通在外股數（扣除庫藏股後，對應 shares_basis_date 單位）。")
    private BigDecimal sharesOutstanding;

    @Column(name = "shares_outstanding_status", length = 32)
    @Comment("流通股數基準狀態。")
    private String sharesOutstandingStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "shares_outstanding_reason", length = 64)
    @Comment("流通股數基準不可用原因碼。")
    private ValuationReason sharesOutstandingReason;

    @Column(name = "shares_outstanding_source", length = 32)
    @Comment("流通股數來源（SEC_FILING_SHARES 或 OBSERVED_SHARES）。")
    private String sharesOutstandingSource;

    @Column(name = "shares_outstanding_calculation", length = 256)
    @Comment("流通股數基準說明。")
    private String sharesOutstandingCalculation;

    @Column(name = "shares_outstanding_available_at")
    @Comment("流通股數基準的取得時間（UTC）。")
    private Instant sharesOutstandingAvailableAt;

    @Column(name = "shares_sec_available_from_date")
    @Comment("期末股數若取自 SEC 申報，該筆股數事實通過披露時間門的美東生效交易日；觀察股數來源時為 NULL。")
    private LocalDate sharesSecAvailableFromDate;

    @Column(name = "shares_basis_date")
    @Comment("shares_outstanding 對應的股份拆股基準日。")
    private LocalDate sharesBasisDate;

    @Column(name = "sec_share_accession", length = 32)
    @Comment("若期末股數取自 SEC 申報，記錄對應的申報編號（accession）。")
    private String secShareAccession;

    // 10. 每股帳面值
    @Column(name = "book_value_per_share", precision = 20, scale = 6)
    @Comment("期末每股普通股帳面值（common_equity / shares_outstanding，對應 shares_basis_date 單位）。")
    private BigDecimal bookValuePerShare;

    @Column(name = "book_value_per_share_status", length = 32)
    @Comment("每股帳面值狀態。")
    private String bookValuePerShareStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "book_value_per_share_reason", length = 64)
    @Comment("每股帳面值不可用原因碼。")
    private ValuationReason bookValuePerShareReason;

    @Column(name = "book_value_per_share_source", length = 32)
    @Comment("每股帳面值來源。")
    private String bookValuePerShareSource;

    @Column(name = "book_value_per_share_calculation", length = 256)
    @Comment("每股帳面值計算口徑說明。")
    private String bookValuePerShareCalculation;

    @Column(name = "book_value_per_share_available_at")
    @Comment("每股帳面值輸入資料的取得時間（UTC）。")
    private Instant bookValuePerShareAvailableAt;

    // 11. 有息債務（含準則對應之租賃負債）
    @Column(name = "total_debt", precision = 24, scale = 4)
    @Comment("期末 EV 計入之有息債務（US_GAAP 排除營運租賃或採純借款；IFRS 含全部租賃負債）。")
    private BigDecimal totalDebt;

    @Column(name = "total_debt_status", length = 32)
    @Comment("期末有息債務狀態。")
    private String totalDebtStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "total_debt_reason", length = 64)
    @Comment("期末有息債務不可用原因碼。")
    private ValuationReason totalDebtReason;

    @Column(name = "total_debt_source", length = 32)
    @Comment("期末有息債務來源。")
    private String totalDebtSource;

    @Column(name = "total_debt_calculation", length = 256)
    @Comment("期末有息債務計算口徑說明。")
    private String totalDebtCalculation;

    @Column(name = "total_debt_available_at")
    @Comment("期末有息債務輸入資料的取得時間（UTC）。")
    private Instant totalDebtAvailableAt;

    // 12. 現金及約當現金
    @Column(name = "cash_like_assets", precision = 24, scale = 4)
    @Comment("期末 EV 扣減之獨立現金及約當現金（原幣）。")
    private BigDecimal cashLikeAssets;

    @Column(name = "cash_like_assets_status", length = 32)
    @Comment("期末現金及約當現金狀態。")
    private String cashLikeAssetsStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "cash_like_assets_reason", length = 64)
    @Comment("期末現金及約當現金不可用原因碼。")
    private ValuationReason cashLikeAssetsReason;

    @Column(name = "cash_like_assets_source", length = 32)
    @Comment("期末現金及約當現金來源。")
    private String cashLikeAssetsSource;

    @Column(name = "cash_like_assets_calculation", length = 256)
    @Comment("期末現金及約當現金計算口徑說明。")
    private String cashLikeAssetsCalculation;

    @Column(name = "cash_like_assets_available_at")
    @Comment("期末現金及約當現金輸入資料的取得時間（UTC）。")
    private Instant cashLikeAssetsAvailableAt;

    // 13. 優先股權益帳面值
    @Column(name = "preferred_equity", precision = 24, scale = 4)
    @Comment("期末優先股帳面值（原幣；未列示時於已錨定資產負債表視為 0）。")
    private BigDecimal preferredEquity;

    @Column(name = "preferred_equity_status", length = 32)
    @Comment("期末優先股權益狀態。")
    private String preferredEquityStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_equity_reason", length = 64)
    @Comment("期末優先股權益不可用原因碼。")
    private ValuationReason preferredEquityReason;

    @Column(name = "preferred_equity_source", length = 32)
    @Comment("期末優先股權益來源。")
    private String preferredEquitySource;

    @Column(name = "preferred_equity_calculation", length = 256)
    @Comment("期末優先股權益計算口徑說明。")
    private String preferredEquityCalculation;

    @Column(name = "preferred_equity_available_at")
    @Comment("期末優先股權益輸入資料的取得時間（UTC）。")
    private Instant preferredEquityAvailableAt;

    // 14. 非控制權益（少數股東權益）帳面值
    @Column(name = "noncontrolling_interest", precision = 24, scale = 4)
    @Comment("期末少數股東／非控制權益帳面值（原幣；未列示時於已錨定資產負債表視為 0）。")
    private BigDecimal noncontrollingInterest;

    @Column(name = "noncontrolling_interest_status", length = 32)
    @Comment("期末非控制權益狀態。")
    private String noncontrollingInterestStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "noncontrolling_interest_reason", length = 64)
    @Comment("期末非控制權益不可用原因碼。")
    private ValuationReason noncontrollingInterestReason;

    @Column(name = "noncontrolling_interest_source", length = 32)
    @Comment("期末非控制權益來源。")
    private String noncontrollingInterestSource;

    @Column(name = "noncontrolling_interest_calculation", length = 256)
    @Comment("期末非控制權益計算口徑說明。")
    private String noncontrollingInterestCalculation;

    @Column(name = "noncontrolling_interest_available_at")
    @Comment("期末非控制權益輸入資料的取得時間（UTC）。")
    private Instant noncontrollingInterestAvailableAt;

    // EV 非市值調整淨額（total_debt + preferred_equity + noncontrolling_interest - cash_like_assets）
    @Column(name = "ev_adjustment", precision = 24, scale = 4)
    @Comment("EV 非市值調整項淨額（有息債務＋優先股＋非控制權益－現金），供即時與當日市值相加得出 EV。")
    private BigDecimal evAdjustment;

    @Column(name = "ev_adjustment_status", length = 32)
    @Comment("EV 非市值調整項狀態（含準則與報期一致性核對結果）。")
    private String evAdjustmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "ev_adjustment_reason", length = 64)
    @Comment("EV 非市值調整項不可用原因碼。")
    private ValuationReason evAdjustmentReason;

    @Column(name = "ev_adjustment_source", length = 32)
    @Comment("EV 非市值調整項來源（FUTU、SEC 或 FUTU_AND_SEC）。")
    private String evAdjustmentSource;

    // 15. 財務品質深化擴充（原幣 TTM 流量與期末資產負債原料，供毛利率／ROIC／SBC／TBV／償債覆蓋計算）
    @Column(name = "gross_profit_ttm", precision = 24, scale = 4)
    @Comment("TTM 毛利金額（原幣）。")
    private BigDecimal grossProfitTtm;

    @Column(name = "gross_profit_ttm_source", length = 32)
    @Comment("TTM 毛利來源（FUTU、SEC 或 FUTU_AND_SEC）。")
    private String grossProfitTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "gross_profit_ttm_reason", length = 64)
    @Comment("TTM 毛利不可用原因碼；可用時為 NULL。")
    private ValuationReason grossProfitTtmReason;

    @Column(name = "operating_profit_ttm", precision = 24, scale = 4)
    @Comment("TTM 營業利益金額（原幣，保留負值）。")
    private BigDecimal operatingProfitTtm;

    @Column(name = "operating_profit_ttm_source", length = 32)
    @Comment("TTM 營業利益來源（FUTU、SEC 或 FUTU_AND_SEC）。")
    private String operatingProfitTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "operating_profit_ttm_reason", length = 64)
    @Comment("TTM 營業利益不可用原因碼；可用時為 NULL。")
    private ValuationReason operatingProfitTtmReason;

    @Column(name = "pretax_profit_ttm", precision = 24, scale = 4)
    @Comment("TTM 稅前盈利金額（原幣，保留負值，供計算有效稅率與 NOPAT）。")
    private BigDecimal pretaxProfitTtm;

    @Column(name = "pretax_profit_ttm_source", length = 32)
    @Comment("TTM 稅前盈利來源。")
    private String pretaxProfitTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "pretax_profit_ttm_reason", length = 64)
    @Comment("TTM 稅前盈利不可用原因碼；可用時為 NULL。")
    private ValuationReason pretaxProfitTtmReason;

    @Column(name = "income_tax_expense_ttm", precision = 24, scale = 4)
    @Comment("TTM 所得稅費用金額（原幣，正數為稅費、負數為稅益）。")
    private BigDecimal incomeTaxExpenseTtm;

    @Column(name = "income_tax_expense_ttm_source", length = 32)
    @Comment("TTM 所得稅費用來源。")
    private String incomeTaxExpenseTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "income_tax_expense_ttm_reason", length = 64)
    @Comment("TTM 所得稅費用不可用原因碼；可用時為 NULL。")
    private ValuationReason incomeTaxExpenseTtmReason;

    @Column(name = "interest_expense_ttm", precision = 24, scale = 4)
    @Comment("TTM 利息費用金額（原幣，正規化為正數規模，供計算利息保障倍數）。")
    private BigDecimal interestExpenseTtm;

    @Column(name = "interest_expense_ttm_source", length = 32)
    @Comment("TTM 利息費用來源。")
    private String interestExpenseTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_expense_ttm_reason", length = 64)
    @Comment("TTM 利息費用不可用原因碼；可用時為 NULL。")
    private ValuationReason interestExpenseTtmReason;

    @Column(name = "sbc_ttm", precision = 24, scale = 4)
    @Comment("TTM 股票薪酬費用 SBC（原幣，取自 SEC 標準標籤 ShareBasedCompensation／AllocatedShareBasedCompensationExpense）。")
    private BigDecimal sbcTtm;

    @Column(name = "sbc_ttm_source", length = 32)
    @Comment("TTM 股票薪酬費用來源。")
    private String sbcTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "sbc_ttm_reason", length = 64)
    @Comment("TTM 股票薪酬費用不可用原因碼；可用時為 NULL。")
    private ValuationReason sbcTtmReason;

    @Column(name = "cash_dividends_paid_ttm", precision = 24, scale = 4)
    @Comment("TTM 普通股現金股息流出（原幣，正規化為非負流出規模）。")
    private BigDecimal cashDividendsPaidTtm;

    @Column(name = "cash_dividends_paid_ttm_source", length = 32)
    @Comment("TTM 現金股息來源。")
    private String cashDividendsPaidTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "cash_dividends_paid_ttm_reason", length = 64)
    @Comment("TTM 現金股息不可用原因碼；可用時為 NULL。")
    private ValuationReason cashDividendsPaidTtmReason;

    @Column(name = "net_common_stock_issuance_ttm", precision = 24, scale = 4)
    @Comment("TTM 普通股淨發行現金流（原幣；正數表淨發行稀釋、負數表淨回購回饋股東，保留正負號不套用 abs）。")
    private BigDecimal netCommonStockIssuanceTtm;

    @Column(name = "net_common_stock_issuance_ttm_source", length = 32)
    @Comment("TTM 普通股淨發行來源（Futu Net Common Stock Issuance）。")
    private String netCommonStockIssuanceTtmSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "net_common_stock_issuance_ttm_reason", length = 64)
    @Comment("TTM 普通股淨發行不可用原因碼；可用時為 NULL。")
    private ValuationReason netCommonStockIssuanceTtmReason;

    @Column(name = "goodwill_and_intangibles", precision = 24, scale = 4)
    @Comment("期末商譽及無形資產合計（原幣，優先採權威合計項，缺合計時以商譽加不含商譽無形資產合成，供計算 P/TBV）。")
    private BigDecimal goodwillAndIntangibles;

    @Column(name = "goodwill_and_intangibles_source", length = 32)
    @Comment("期末商譽及無形資產來源。")
    private String goodwillAndIntangiblesSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "goodwill_and_intangibles_reason", length = 64)
    @Comment("期末商譽及無形資產不可用原因碼；可用時為 NULL。")
    private ValuationReason goodwillAndIntangiblesReason;

    @Column(name = "current_debt", precision = 24, scale = 4)
    @Comment("期末一年內到期計息債務（原幣；明示為零表無短債，缺披露時為 NULL 不預設補零）。")
    private BigDecimal currentDebt;

    @Column(name = "current_debt_source", length = 32)
    @Comment("期末短期有息債務來源。")
    private String currentDebtSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_debt_reason", length = 64)
    @Comment("期末短期有息債務不可用原因碼；可用時為 NULL。")
    private ValuationReason currentDebtReason;

    @Column(name = "updated_at", nullable = false)
    @Comment("本列財季分母最近重建時間（UTC）。")
    private Instant updatedAt;
}
