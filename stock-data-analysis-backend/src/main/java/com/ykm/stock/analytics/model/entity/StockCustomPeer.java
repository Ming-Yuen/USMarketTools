package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.StockCustomPeerId;
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

import java.time.Instant;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@IdClass(StockCustomPeerId.class)
@Table(name = "stock_custom_peer", indexes = {
        @Index(name = "idx_stock_custom_peer_target", columnList = "figi, sort_order"),
        @Index(name = "idx_stock_custom_peer_member", columnList = "peer_figi")
})
@Comment("多業務公司自訂同業對標組設定表，以 (figi, peer_figi) 為主鍵保存人工指定的對標對手與整組覆寫指標。")
public class StockCustomPeer {
    @Id
    @Column(name = "figi", nullable = false, length = 32)
    @Comment("目標股票 FIGI 識別碼。")
    private String figi;

    @Id
    @Column(name = "peer_figi", nullable = false, length = 32)
    @Comment("自訂對標對手股票 FIGI 識別碼；禁止指向目標股自身或同一發行人兄弟股。")
    private String peerFigi;

    @Column(name = "preferred_metric", length = 32)
    @Comment("此自訂對標組選填覆寫的主估值指標（PE、PB、PS、PFCF 或 EV_EBITDA）；同一 figi 整組一致，NULL 代表自動跟隨目標股當日主指標。")
    private String preferredMetric;

    @Column(name = "sort_order", nullable = false)
    @Comment("自訂對標名單展示順序（由 0 遞增）。")
    private int sortOrder;

    @Column(name = "updated_at", nullable = false)
    @Comment("最後更新時間（UTC）。")
    private Instant updatedAt;
}
