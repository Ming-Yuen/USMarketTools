package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 多業務公司自訂對標組設定與候選對手當日狀態。 */
public record CustomPeerGroupDto(
        String figi,
        String preferredMetric,
        List<String> supportedMetrics,
        List<PeerItem> peers
) {
    public CustomPeerGroupDto {
        supportedMetrics = supportedMetrics == null ? List.of() : List.copyOf(supportedMetrics);
        peers = peers == null ? List.of() : List.copyOf(peers);
    }

    public record PeerItem(
            String figi,
            String symbol,
            String name,
            String industryCode,
            boolean active,
            boolean tracked,
            LocalDate valuationDate,
            String currency,
            BigDecimal marketCap,
            String marketCapTier
    ) {}
}
