package com.ykm.stock.analytics.model.dto.stockInsight.response;

import com.ykm.stock.analytics.service.analysis.SignalTrackingService.SignalTrackingStatsSummary;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse {

    private SnapshotMeta snapshotMeta;
    private List<TapeRow> tapeRows;
    private List<IndexRow> indexRows;
    private List<EtfRow> etfRows;
    private List<MessageItem> alerts;
    private List<EventItem> events;
    private List<SignalRow> signalRows;
    private List<FearGreedComponent> fearGreedComponents;
    private MarketSummary marketSummary;
    private ExecutionBias executionBias;
    private MarketRegimeBanner marketRegime;
    private List<SectorEtfRow> sectorEtfs;
    private SectorMoneyFlowSummary sectorMoneyFlow;
    private OpportunityRadarSummary opportunityRadar;
    private SignalTrackingStatsSummary signalTrackingStats;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SnapshotMeta {
        private String marketDate;
        private List<String> sources;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TapeRow {
        private String symbol;
        private String value;
        private String change;
        private boolean positive;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndexRow {
        private String nameCode;
        private String symbol;
        private String value;
        private String day;
        private String trend;
        private String noteCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EtfRow {
        private String ticker;
        private String price;
        private String day;
        private String rsi;
        private String biasCode;
        private String ma20Deviation;
        private String volumeRatio5d;
        private String noteCode;
        private String sourceCode;
        private String time;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventItem {
        private String id;
        private String title;
        private String titleCode;
        private String categoryCode;
        private String startsAt;
        private String levelCode;
        private String symbol;
        private String source;
        private String sourceUrl;
        private String statusCode;
        private boolean allDay;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignalRow {
        private String code;
        private String value;
        private String statusCode;
        private String sourceCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FearGreedComponent {
        private String code;
        private String value;
        private String statusCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketSummary {
        private String titleCode;
        private MessageItem description;
        private MessageItem tag;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionBias {
        private String titleCode;
        private String descriptionCode;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageItem {
        private String code;
        private Map<String, Object> params;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketRegimeBanner {
        private String regime;
        private BigDecimal vixClose;
        private boolean headwindActive;
        private boolean correctionActive;
        private String asOfDate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectorEtfRow {
        private String symbol;
        private String sectorCode;
        private BigDecimal closePrice;
        private BigDecimal return5d;
        private BigDecimal return20d;
        private BigDecimal return60d;
        private BigDecimal rvol20;
        private BigDecimal excessVsSpy5d;
        private BigDecimal excessVsSpy20d;
        private BigDecimal excessVsSpy60d;
        private String tradeDate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectorMoneyFlowSummary {
        private String tradeDate;
        private int marketTotalStockCount;
        private BigDecimal classifiedCoveragePct;
        private List<SectorMoneyFlowRow> rows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectorMoneyFlowRow {
        private String industryCode;
        private String industryName;
        private int stockCount;
        private BigDecimal dollarVolume1d;
        private BigDecimal dollarVolume5d;
        private BigDecimal marketVolumeShare1d;
        private BigDecimal marketVolumeShare5d;
        private BigDecimal marketVolumeShare20d;
        private BigDecimal volumeShareDelta5dVs20d;
        private BigDecimal cmf5d;
        private BigDecimal cmf20d;
        private int breadthAboveSma50Count;
        private int breadthSma50Total;
        private BigDecimal breadthAboveSma50Pct;
        private int breadthAboveSma200Count;
        private int breadthSma200Total;
        private BigDecimal breadthAboveSma200Pct;
        private BigDecimal return20dMedian;
        private BigDecimal return60dMedian;
        private String flowState;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpportunityRadarSummary {
        private String radarDate;
        private String marketRegime;
        private boolean headwindActive;
        private int valueCount;
        private int starterCount;
        private List<OpportunityRadarItem> valueOpportunities;
        private List<OpportunityRadarItem> starterOpportunities;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpportunityRadarItem {
        private String figi;
        private String symbol;
        private String stockName;
        private String currency;
        private String trackType;
        private String industryCode;
        private String industryName;
        private String marketCapTier;
        private String verdict;
        private String fundamentalBacking;
        private String stance;
        private boolean headwindWatchOnly;
        private boolean macroCautionActive;
        private BigDecimal closePrice;
        private BigDecimal fairValueLow;
        private BigDecimal fairValueMid;
        private BigDecimal fairValueHigh;
        private BigDecimal discountToMid;
        private BigDecimal forwardPeg;
        private BigDecimal forwardPe;
        private BigDecimal revenueGrowthYoy;
        private String forwardGrowthBasis;
        private BigDecimal supportLower;
        private BigDecimal supportUpper;
        private BigDecimal supportDistancePct;
        private String pullbackAnchor;
        private BigDecimal pullbackAnchorPrice;
        private BigDecimal protectiveStop;
        private BigDecimal firstTargetPrice;
        private BigDecimal netRiskReward;
        private String planStatus;
        private String positionFraction;
        private String nextEarningsDate;
        private Integer daysToNextEarnings;
        private BigDecimal sectorCmf20d;
        private String sectorFlowState;
    }
}
