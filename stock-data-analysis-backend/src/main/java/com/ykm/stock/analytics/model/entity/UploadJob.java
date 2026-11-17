package com.ykm.stock.analytics.model.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import com.ykm.stock.analytics.enums.ProcessStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.UploadResult;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Data
@EntityListeners(AuditingEntityListener.class)
@Comment("手動 CSV 上傳任務；處理結果持久化供斷線及重新整理後查詢，完成或失敗後移除輸入內容。")
public class UploadJob {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("上傳任務建立時間，用於重啟時識別尚未完成的任務。")
    private LocalDateTime createdAt;

    @Id @Comment("前端產生的上傳識別碼；接收回應遺失時可查詢及重送同一請求。")
    private UUID id;
    @Column(nullable = false, length = 32) @Comment("上傳功能類型，目前為 STOCK_INDUSTRY。")
    private String type;
    @Column(nullable = false, length = 255) @Comment("原始檔名，供使用者識別；不作為檔案儲存路徑。")
    private String fileName;
    @Column(nullable = false, length = 64) @Comment("輸入內容 SHA-256；同一請求 ID 不接受不同檔案。")
    private String inputHash;
    @Column(columnDefinition = "bytea") @Comment("尚未處理完成的 CSV；終止狀態即清空，不永久保存原始檔。")
    private byte[] inputData;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    @Comment("PENDING、PROCESSING、COMPLETED、FAILED；HTTP timeout 不改變背景任務狀態。")
    private ProcessStatus status;
    @Comment("背景處理開始時間。") private Instant startedAt;
    @Comment("完成、失敗或啟動時確認中斷的時間。") private Instant finishedAt;
    @Column(length = 1000) @Comment("可顯示的失敗原因；中斷後不自動續跑。") private String error;
    @Comment("CSV 資料總列數。") private Integer totalRows;
    @Comment("實際更新股票數。") private Integer updated;
    @Comment("產業未變的股票數。") private Integer unchanged;
    @Comment("新增共用產業數。") private Integer createdIndustries;
    @Comment("缺少產業的資料列數。") private Integer missingIndustry;
    @Comment("找不到股票的資料列數。") private Integer unmatched;
    @Comment("股票或產業配對有歧義的資料列數。") private Integer ambiguous;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    @Comment("最多 100 筆可變長度略過明細；統計欄位包含全部資料。")
    private List<UploadResult.Issue> issues;
}
