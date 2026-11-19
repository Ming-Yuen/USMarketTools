package com.ykm.stock.analytics.model.dto.stockInsight.enhancement;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 06_Enhancement 籌碼、事件、信用利差與自訂標籤顯式映射統一傳輸物件集合。 */
public final class EnhancementDtos {

    private EnhancementDtos() {}

    public record CreditSpreadSummaryDto(
            String seriesId,
            BigDecimal oasPct,
            LocalDate observationDate,
            BigDecimal prior20MeanPct,
            BigDecimal deltaVsPrior20Bps,
            String status,
            boolean macroCautionTriggered,
            boolean stale,
            Instant observedAt,
            @JsonIgnore List<String> resolvedTriggerCodes
    ) {
        public CreditSpreadSummaryDto(
                String seriesId,
                BigDecimal oasPct,
                LocalDate observationDate,
                BigDecimal prior20MeanPct,
                BigDecimal deltaVsPrior20Bps,
                String status,
                boolean macroCautionTriggered,
                boolean stale,
                Instant observedAt) {
            this(seriesId, oasPct, observationDate, prior20MeanPct, deltaVsPrior20Bps, status, macroCautionTriggered, stale, observedAt, List.of());
        }

        public static CreditSpreadSummaryDto unavailable(String reason) {
            return new CreditSpreadSummaryDto("BAMLH0A0HYM2", null, null, null, null, reason, false, false, null, List.of());
        }
    }

    public record OptionsSummaryDto(
            String figi,
            String symbol,
            LocalDate snapshotDate,
            String source,
            String feed,
            String status,
            String reasonCode,
            BigDecimal spotPrice,
            Instant quoteTimestamp,
            LocalDate nextEarningsDate,
            Integer daysToNextEarnings,
            LocalDate straddleExpiration,
            BigDecimal atmStrike,
            BigDecimal atmCallPrice,
            BigDecimal atmPutPrice,
            BigDecimal impliedMovePct,
            String impliedMoveStatus,
            String impliedMoveReason,
            BigDecimal protectiveStopDistancePct,
            boolean earningsGapExceedsStopRisk,
            BigDecimal atmIv,
            BigDecimal hv20,
            BigDecimal ivToHvRatio,
            Integer ivHistorySampleCount,
            BigDecimal ivShortWindowPercentile,
            BigDecimal putCallOiRatio,
            BigDecimal putCallVolRatio,
            BigDecimal otmPutIv,
            BigDecimal otmCallIv,
            BigDecimal ivSkewPutMinusCall,
            LocalDate oiWallExpiration,
            BigDecimal maxCallOiStrike,
            Long maxCallOi,
            BigDecimal maxPutOiStrike,
            Long maxPutOi,
            Long totalCallOi,
            Long totalPutOi,
            LocalDate openInterestDate,
            LocalDate volumeSessionDate,
            String nonDirectionalDisclaimerCode,
            Instant observedAt
    ) {
        public static OptionsSummaryDto unavailable(String figi, String symbol, String status, String reasonCode) {
            return new OptionsSummaryDto(figi, symbol, null, null, null, status, reasonCode,
                    null, null, null, null, null, null, null, null, null, status, reasonCode,
                    null, false, null, null, null, 0, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    "OI_WALL_NON_DIRECTIONAL_DISCLAIMER", null);
        }
    }

    public record Form4TransactionDto(
            Long id,
            String accession,
            String formType,
            LocalDate filingDate,
            Instant acceptedAt,
            LocalDate transactionDate,
            String securityTitle,
            String primaryOwnerName,
            String primaryOwnerTitle,
            int reportingOwnerCount,
            boolean officer,
            boolean director,
            boolean tenPercentOwner,
            String transactionCode,
            String acquiredDisposed,
            Boolean aff10b5One,
            BigDecimal shares,
            BigDecimal pricePerShare,
            BigDecimal transactionValueUsd,
            BigDecimal sharesOwnedAfter,
            boolean openMarketBuyCodeP,
            boolean routineOrPlanTrade,
            String filingUrl
    ) {}

    public record InsiderSummaryDto(
            String status,
            String reasonCode,
            String cik,
            boolean insiderBuyUndervaluedConfirm,
            int codePBuyCount60d,
            int codePDistinctInsiders60d,
            BigDecimal codePTotalValueUsd60d,
            int routineOrSaleCount60d,
            LocalDate latestFilingDate,
            List<Form4TransactionDto> recentTransactions
    ) {
        public static InsiderSummaryDto unavailable(String status, String reasonCode, String cik) {
            return new InsiderSummaryDto(status, reasonCode, cik, false, 0, 0, null, 0, null, List.of());
        }
    }

    public record Institutional13fRowDto(
            String managerCik,
            String managerName,
            LocalDate reportPeriod,
            LocalDate filingDate,
            String formType,
            String cusip,
            String shareType,
            String putCall,
            BigDecimal shares,
            BigDecimal prevShares,
            BigDecimal shareChangePct,
            BigDecimal marketValueUsd,
            String actionType
    ) {}

    public record Institutional13fSummaryDto(
            String status,
            String reasonCode,
            LocalDate latestReportPeriod,
            int trackedManagersHoldingCount,
            int managersAddedOrNewCount,
            int managersReducedOrExitedCount,
            List<Institutional13fRowDto> holdings
    ) {
        public static Institutional13fSummaryDto unavailable(String status, String reasonCode) {
            return new Institutional13fSummaryDto(status, reasonCode, null, 0, 0, 0, List.of());
        }
    }

    public record ShortInterestSummaryDto(
            String status,
            String reasonCode,
            LocalDate settlementDate,
            Long shortInterestShares,
            BigDecimal shortPctOfSharesOutstanding,
            String shortPctStatus,
            String shortPctReason,
            Long avgDailyVolumeSource,
            BigDecimal daysToCoverSource,
            BigDecimal daysToCover20d,
            LocalDate avgVolume20dAsOf,
            BigDecimal shortChangeVsPriorPct,
            String signalCode,
            String source,
            Instant observedAt
    ) {
        public static ShortInterestSummaryDto unavailable(String status, String reasonCode) {
            return new ShortInterestSummaryDto(status, reasonCode, null, null, null, status, reasonCode,
                    null, null, null, null, null, "UNAVAILABLE", null, null);
        }
    }

    public record StockNewsEventDto(
            Long id,
            String sourceType,
            String externalId,
            String publisher,
            String headline,
            String summarySnippet,
            String url,
            Instant publishedAt,
            String secForm,
            String secItems,
            String exhibit991Url,
            String exhibitExtractionStatus
    ) {}

    public record GuidanceExtractDto(
            String periodLabel,
            String action,
            String basis,
            String currency,
            String unitScale,
            BigDecimal revenueLow,
            BigDecimal revenueHigh,
            BigDecimal epsLow,
            BigDecimal epsHigh,
            String sourceFilingUrl,
            String quoteExcerpt
    ) {}

    public record EventAiSummaryDto(
            Long id,
            LocalDate triggerDate,
            String triggerReason,
            String status,
            String aiProvider,
            String modelName,
            String eventNature,
            List<String> bulletPoints,
            GuidanceExtractDto guidance,
            List<String> sourceUrls,
            String inputFingerprint,
            String errorMessage,
            Instant createdAt
    ) {}

    public record ManualPromptDto(
            String taskType,
            String inputFingerprint,
            String promptText,
            String expectedJsonSchema,
            int sourceItemCount
    ) {}

    public record ManualJsonImportRequest(
            String rawJson,
            String inputFingerprint
    ) {}

    public record CustomTagCandidateDto(
            String sourceType,
            String taxonomy,
            String customTag,
            String unit,
            String labelEn,
            BigDecimal latestValue,
            LocalDate latestPeriodEnd,
            String sampleForm,
            boolean alreadyMapped,
            String existingTargetConcept
    ) {}

    public record CustomTagProposalItemDto(
            String sourceType,
            String taxonomy,
            String customTag,
            String unit,
            String labelEn,
            String targetConcept,
            int signMultiplier,
            String rationale
    ) {}

    public record CustomTagProposalPreviewDto(
            String cik,
            String figi,
            String symbol,
            String provider,
            String modelName,
            boolean persistedToDatabase,
            List<String> allowedTargetConcepts,
            List<CustomTagProposalItemDto> proposals
    ) {}

    public record CustomTagConfirmItemRequest(
            String sourceType,
            String taxonomy,
            String customTag,
            String unit,
            String targetConcept,
            Integer signMultiplier,
            String rationale
    ) {}

    public record CustomTagConfirmRequest(
            String proposalProvider,
            List<CustomTagConfirmItemRequest> items
    ) {}

    public record CustomTagMappingDto(
            Long id,
            String cik,
            String sourceType,
            String taxonomy,
            String customTag,
            String unit,
            String labelEn,
            String targetConcept,
            int signMultiplier,
            String status,
            String proposalProvider,
            String rationale,
            Instant confirmedAt,
            Instant updatedAt
    ) {}

    public record CustomTagConfirmResultDto(
            String cik,
            int confirmedMappingCount,
            int backfilledFactCount,
            int rebuiltStockCount,
            List<CustomTagMappingDto> mappings
    ) {}

    public record AiChannelStatusDto(
            String provider,
            boolean automatedAvailable,
            String activeModel,
            String reasoningEffort,
            boolean manualModeAlwaysAvailable
    ) {}

    public record StockEnhancementOverviewDto(
            String figi,
            String symbol,
            String stockType,
            String cik,
            boolean etf,
            AiChannelStatusDto aiChannel,
            CreditSpreadSummaryDto creditSpread,
            OptionsSummaryDto options,
            InsiderSummaryDto insiders,
            Institutional13fSummaryDto institutional13f,
            ShortInterestSummaryDto shortInterest,
            List<StockNewsEventDto> recentNewsEvents,
            List<EventAiSummaryDto> eventSummaries,
            List<CustomTagMappingDto> confirmedCustomTags,
            List<String> allowedCustomTagConcepts
    ) {}

    /** 掛載於個股分析主報告（POST /analysis/report）的純資料庫唯讀籌碼亮點摘要，0 次新增外部 API 請求。 */
    public record ReportEnhancementHighlightsDto(
            CreditSpreadSummaryDto creditSpread,
            OptionsSummaryDto options,
            boolean insiderBuyUndervaluedConfirm,
            int codePBuyCount60d,
            int codePDistinctInsiders60d,
            BigDecimal codePTotalValueUsd60d,
            ShortInterestSummaryDto shortInterest
    ) {}
}
