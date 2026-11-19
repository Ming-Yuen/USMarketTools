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

/** 個股近 48 小時新聞與近 3 日 SEC 8-K／6-K（含 Exhibit 99.1 摘錄）去重快取；不對外部新聞站進行全文爬蟲。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stock_news_event_cache",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_news_event_dedup", columnNames = {"figi", "source_type", "external_id"}),
        indexes = {
                @Index(name = "idx_stock_news_figi_published", columnList = "figi,published_at")
        })
@Comment("個股按需抓取之即時新聞與 SEC 8-K／6-K 公告快取表，依來源與穩定外部識別碼去重，作為輕量 AI 或手動摘要之受控輸入。")
public class StockNewsEventCache {
    public static final String SOURCE_ALPACA_NEWS = "ALPACA_NEWS";
    public static final String SOURCE_FINNHUB_NEWS = "FINNHUB_NEWS";
    public static final String SOURCE_MASSIVE_NEWS = "MASSIVE_NEWS";
    public static final String SOURCE_SEC_8K = "SEC_8K";
    public static final String SOURCE_SEC_6K = "SEC_6K";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Comment("新聞或公告快取流水號主鍵。")
    private Long id;

    @Column(name = "figi", nullable = false, length = 32)
    @Comment("關聯股票或 ETF 的 OpenFIGI。")
    private String figi;

    @Column(name = "symbol", nullable = false, length = 32)
    @Comment("抓取當下之股票交易代碼 ticker。")
    private String symbol;

    @Column(name = "source_type", nullable = false, length = 24)
    @Comment("來源類型：ALPACA_NEWS、FINNHUB_NEWS、MASSIVE_NEWS、SEC_8K 或 SEC_6K。")
    private String sourceType;

    @Column(name = "external_id", nullable = false, length = 128)
    @Comment("來源穩定識別碼（新聞為供應商文章 ID 或正規化網址雜湊，SEC 公告為 accessionNumber）。")
    private String externalId;

    @Column(name = "publisher", length = 128)
    @Comment("媒體或公告發佈機構名稱（例如 Benzinga、Reuters、SEC EDGAR）。")
    private String publisher;

    @Column(name = "headline", nullable = false, length = 512)
    @Comment("新聞標題或 SEC 公告摘要標題。")
    private String headline;

    @Column(name = "summary_snippet", columnDefinition = "text")
    @Comment("API 回傳之簡短新聞摘要（絕不爬取外部網站全文）；缺值時為 NULL。")
    private String summarySnippet;

    @Column(name = "url", length = 1024)
    @Comment("新聞原文連結或 SEC EDGAR 公告原文網址。")
    private String url;

    @Column(name = "published_at", nullable = false)
    @Comment("新聞發佈時間或 SEC 公告接受／申報時間（UTC）。")
    private Instant publishedAt;

    @Column(name = "updated_at_source")
    @Comment("來源標示之文章更新時間（UTC）；無修訂時間時為 NULL。")
    private Instant updatedAtSource;

    @Column(name = "sec_form", length = 16)
    @Comment("SEC 公告表單類型（如 8-K、6-K）；一般新聞為 NULL。")
    private String secForm;

    @Column(name = "sec_items", length = 128)
    @Comment("SEC 8-K 條目代碼清單（例如 2.02,9.01）；6-K 或一般新聞為 NULL。")
    private String secItems;

    @Column(name = "exhibit_99_1_url", length = 1024)
    @Comment("8-K／6-K 業績新聞稿（EX-99.1）之 SEC 原文連結；無或無法辨識時為 NULL。")
    private String exhibit991Url;

    @Column(name = "exhibit_99_1_excerpt", columnDefinition = "text")
    @Comment("從官方 EX-99.1 HTML 優先擷取 Outlook／Guidance 相關段落之有界純文字（<= 8,000 字元），供核對官方財測；不可讀時為 NULL。")
    private String exhibit991Excerpt;

    @Column(name = "exhibit_extraction_status", length = 32)
    @Comment("EX-99.1 抽取狀態：EXTRACTED、PDF_UNSUPPORTED、NOT_FOUND、TRUNCATED 或 NOT_APPLICABLE。")
    private String exhibitExtractionStatus;

    @Column(name = "observed_at", nullable = false)
    @Comment("本系統首次寫入此快取列之 UTC 時間。")
    private Instant observedAt;
}
