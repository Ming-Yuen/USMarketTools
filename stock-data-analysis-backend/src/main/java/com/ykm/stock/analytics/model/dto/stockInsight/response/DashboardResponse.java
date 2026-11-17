package com.ykm.stock.analytics.model.dto.stockInsight.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
