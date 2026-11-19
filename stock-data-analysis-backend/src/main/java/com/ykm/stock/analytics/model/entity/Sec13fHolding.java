package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** SEC 13F-HR 精選長線／對沖機構季度持倉聚合明細；季度變動與拆股對齊於讀取時即時比對相鄰兩季，避免修訂前季殘留錯誤衍生欄。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "sec_13f_holding",
        uniqueConstraints = @UniqueConstraint(name = "uk_sec_13f_manager_period_pos",
                columnNames = {"manager_cik", "report_period", "cusip", "share_type", "put_call"}),
        indexes = {
                @Index(name = "idx_sec_13f_figi_period", columnList = "figi,report_period"),
                @Index(name = "idx_sec_13f_manager_period", columnList = "manager_cik,report_period")
        })
@Comment("SEC 13F-HR 機構季度持倉聚合表，按機構、季末日、CUSIP、股數／本金類型與期權方向保存已聚合之持倉口徑。")
public class Sec13fHolding {
    public static final String PUT_CALL_NONE = "NONE";
    public static final String PUT_CALL_CALL = "CALL";
    public static final String PUT_CALL_PUT = "PUT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Comment("13F 機構持倉紀錄流水號主鍵。")
    private Long id;

    @Column(name = "manager_cik", nullable = false, length = 10)
    @Comment("申報機構之 SEC 10 碼 CIK。")
    private String managerCik;

    @Column(name = "manager_name", nullable = false, length = 160)
    @Comment("申報機構標準顯示名稱（例如 Berkshire Hathaway）。")
    private String managerName;

    @Column(name = "report_period", nullable = false)
    @Comment("13F 持倉所屬季度結算日（例如 2026-09-30），具最高 45 天披露延遲。")
    private LocalDate reportPeriod;

    @Column(name = "filing_date", nullable = false)
    @Comment("SEC 13F-HR 或修訂版實際申報日期。")
    private LocalDate filingDate;

    @Column(name = "accession", nullable = false, length = 24)
    @Comment("本季採用之有效 SEC 13F 申報編號。")
    private String accession;

    @Column(name = "form_type", nullable = false, length = 16)
    @Comment("申報表單類型，例如 13F-HR 或 13F-HR/A。")
    private String formType;

    @Column(name = "cusip", nullable = false, length = 12)
    @Comment("13F XML 原始 9 碼證券識別碼 CUSIP。")
    private String cusip;

    @Column(name = "issuer_name", nullable = false, length = 255)
    @Comment("13F XML 原始發行人名稱（nameOfIssuer）。")
    private String issuerName;

    @Column(name = "title_of_class", length = 128)
    @Comment("13F XML 原始證券類別描述（titleOfClass）。")
    private String titleOfClass;

    @Column(name = "filing_figi", length = 32)
    @Comment("13F XML 可選填之 share-class FIGI 原值；申報未提供時為 NULL。")
    private String filingFigi;

    @Column(name = "figi", length = 32)
    @Comment("經 shareClassFigi、Massive CUSIP 或 OpenFIGI 唯一核實對應之本地 stock.figi；無法唯一核實時維持 NULL。")
    private String figi;

    @Column(name = "symbol", length = 32)
    @Comment("對應到本地主檔時之最新交易代碼 ticker；未唯一匹配時為 NULL。")
    private String symbol;

    @Column(name = "share_type", nullable = false, length = 8)
    @Comment("13F 數量單位類型：SH（股數）或 PRN（債券本金金額）。")
    private String shareType;

    @Column(name = "put_call", nullable = false, length = 8)
    @Comment("期權部位標記：NONE（現股）、CALL 或 PUT，嚴格與現股分開統計。")
    private String putCall;

    @Column(name = "shares", nullable = false, precision = 24, scale = 4)
    @Comment("同機構同季同 CUSIP 聚合後之期末持有股數或本金數量。")
    private BigDecimal shares;

    @Column(name = "market_value_usd", nullable = false, precision = 24, scale = 2)
    @Comment("13F 申報期末持倉市值（USD 整數元，依 SEC 2023 年起新制口徑）。")
    private BigDecimal marketValueUsd;

    @Column(name = "observed_at", nullable = false)
    @Comment("本系統同步並寫入此筆 13F 紀錄之 UTC 時間。")
    private Instant observedAt;
}
