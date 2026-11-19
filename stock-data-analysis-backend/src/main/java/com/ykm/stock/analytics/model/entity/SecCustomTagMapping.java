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

import java.time.Instant;

/** 經人工於前端顯式點選確認之 SEC 自訂／非白名單 XBRL 標籤映射規則；未確認前絕不寫入本表，確認後日常計算保持 0 AI 消耗。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "sec_custom_tag_mapping",
        uniqueConstraints = @UniqueConstraint(name = "uk_sec_custom_tag_source", columnNames = {"cik", "taxonomy", "custom_tag", "unit"}),
        indexes = {
                @Index(name = "idx_sec_custom_tag_cik_status", columnList = "cik,status")
        })
@Comment("SEC 自訂與非預設標準 XBRL 標籤顯式映射表，僅保存人工確認之規則供後端確定性回補缺漏財務分母（不覆寫既有標準事實）。")
public class SecCustomTagMapping {
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final String SOURCE_XBRL_INSTANCE = "XBRL_INSTANCE";
    public static final String SOURCE_COMPANYFACTS = "COMPANYFACTS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Comment("自訂標籤映射規則流水號主鍵。")
    private Long id;

    @Column(name = "cik", nullable = false, length = 10)
    @Comment("SEC 發行人 10 碼 CIK；同發行人多股類共用此顯式映射。")
    private String cik;

    @Column(name = "figi", length = 32)
    @Comment("確認當時關聯之主要 OpenFIGI，供前端導覽展示用。")
    private String figi;

    @Column(name = "symbol", length = 32)
    @Comment("確認當時關聯之股票交易代碼 ticker。")
    private String symbol;

    @Column(name = "source_type", nullable = false, length = 24)
    @Comment("標籤抽取來源：XBRL_INSTANCE（公司申報 *_htm.xml 擴展標籤）或 COMPANYFACTS（本地 companyfacts.zip 未納入預設白名單之標籤）。")
    private String sourceType;

    @Column(name = "taxonomy", nullable = false, length = 64)
    @Comment("原始 XBRL namespace／taxonomy 名稱（例如公司擴展前綴、ifrs-full 或 us-gaap）。")
    private String taxonomy;

    @Column(name = "custom_tag", nullable = false, length = 160)
    @Comment("原始 XBRL 標籤名稱（不含 namespace 前綴）。")
    private String customTag;

    @Column(name = "unit", nullable = false, length = 64)
    @Comment("原始 XBRL 單位（例如 USD、EUR、shares），回補時嚴格比對單位一致性。")
    private String unit;

    @Column(name = "label_en", length = 512)
    @Comment("SEC 官方標籤連結庫（*_lab.xml）或 companyfacts 內原始英文說明。")
    private String labelEn;

    @Column(name = "target_concept", nullable = false, length = 48)
    @Comment("受控白名單目標財務概念代碼（如 REVENUE、GROSS_PROFIT、OPERATING_INCOME、OPERATING_CASH_FLOW、CAPITAL_EXPENDITURE、SBC 等）。")
    private String targetConcept;

    @Column(name = "sign_multiplier", nullable = false)
    @Comment("確定性符號倍數（僅允許 1 或 -1，供資本支出或費用正負號口徑對齊）。")
    private int signMultiplier;

    @Column(name = "status", nullable = false, length = 16)
    @Comment("映射生效狀態：CONFIRMED（人工確認生效）或 DISABLED（已停用並撤銷回補貢獻）。")
    private String status;

    @Column(name = "proposal_provider", nullable = false, length = 24)
    @Comment("此規則最初提議通道：OPENAI_API、CODEX_CLI 或 MANUAL_IMPORT。")
    private String proposalProvider;

    @Column(name = "rationale", length = 1000)
    @Comment("繁體中文映射依據與核對說明。")
    private String rationale;

    @Column(name = "confirmed_at", nullable = false)
    @Comment("使用者於前端人工點選確認存檔之 UTC 時間。")
    private Instant confirmedAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("規則最後異動之 UTC 時間。")
    private Instant updatedAt;
}
