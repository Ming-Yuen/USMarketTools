package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 多業務公司自訂對標組設定與候選對手當日狀態。 */
public record CustomPeerGroupDto(
        String figi,
        String preferredMetric,
        List<String> supportedMetrics,
        List<PeerItem> peers,
        BigDecimal targetMarketCap,
        String targetCurrency,
        LocalDate targetValuationDate,
        String targetPrimaryMetric,
        String targetIssuerKey
) {
    public CustomPeerGroupDto {
        supportedMetrics = supportedMetrics == null ? List.of() : List.copyOf(supportedMetrics);
        peers = peers == null ? List.of() : List.copyOf(peers);
    }

    public CustomPeerGroupDto(String figi, String preferredMetric, List<String> supportedMetrics, List<PeerItem> peers) {
        this(figi, preferredMetric, supportedMetrics, peers, null, null, null, null, figi);
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
            String marketCapTier,
            String issuerKey,
            BigDecimal pe,
            BigDecimal pb,
            BigDecimal ps,
            BigDecimal pfcf,
            BigDecimal evEbitda
    ) {
        public PeerItem(String figi, String symbol, String name, String industryCode, boolean active, boolean tracked,
                        LocalDate valuationDate, String currency, BigDecimal marketCap, String marketCapTier) {
            this(figi, symbol, name, industryCode, active, tracked, valuationDate, currency, marketCap, marketCapTier,
                    figi, null, null, null, null, null);
        }
    }
}
