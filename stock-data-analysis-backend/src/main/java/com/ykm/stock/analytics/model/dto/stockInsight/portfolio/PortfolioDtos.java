package com.ykm.stock.analytics.model.dto.stockInsight.portfolio;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Batch 04 持倉管理、風險參數、每日自動體檢、站內通知與收盤簡報 DTO 定義。 */
public final class PortfolioDtos {
    private PortfolioDtos() {}

    public record PortfolioRiskConfigDto(
            BigDecimal totalCapital,
            BigDecimal riskPerTradePct,
            BigDecimal maxSinglePositionPct,
            BigDecimal maxSectorExposurePct,
            String capitalCurrency
    ) {}

    public record PortfolioRiskConfigUpdateRequest(
            BigDecimal totalCapital,
            BigDecimal riskPerTradePct,
            BigDecimal maxSinglePositionPct,
            BigDecimal maxSectorExposurePct
    ) {}

    public record PortfolioItemUpsertRequest(
            String itemType,
            BigDecimal shares,
            BigDecimal avgCost,
            LocalDate costBasisDate,
            String notes
    ) {}

    public record PortfolioItemViewDto(
            String figi,
            String symbol,
            String stockName,
            String currency,
            String industryCode,
            String industryName,
            String itemType,
            BigDecimal rawShares,
            BigDecimal rawAvgCost,
            LocalDate costBasisDate,
            BigDecimal adjustedShares,
            BigDecimal adjustedAvgCost,
            BigDecimal splitShareFactor,
            boolean splitAdjusted,
            String splitAlignmentStatus,
            LocalDate latestQuoteDate,
            BigDecimal latestClosePrice,
            BigDecimal marketValue,
            BigDecimal costBasisTotal,
            BigDecimal unrealizedPnlAmount,
            BigDecimal unrealizedPnlPct,
            BigDecimal capitalWeightPct,
            String notes,
            Instant updatedAt
    ) {}

    public record SectorExposureDto(
            String industryCode,
            String industryName,
            int holdingCount,
            BigDecimal marketValueUsd,
            BigDecimal exposurePct,
            BigDecimal maxSectorExposurePct,
            boolean concentrationExceeded
    ) {}

    public record PortfolioOverviewDto(
            PortfolioRiskConfigDto config,
            List<PortfolioItemViewDto> items,
            List<SectorExposureDto> sectorExposures,
            int holdingCount,
            int watchlistCount,
            BigDecimal totalHoldingMarketValueUsd,
            BigDecimal totalHoldingCostUsd,
            BigDecimal totalUnrealizedPnlUsd,
            BigDecimal totalUnrealizedPnlPct,
            int excludedHoldingsCount,
            List<String> coverageWarnings
    ) {
        public PortfolioOverviewDto {
            items = items == null ? List.of() : List.copyOf(items);
            sectorExposures = sectorExposures == null ? List.of() : List.copyOf(sectorExposures);
            coverageWarnings = coverageWarnings == null ? List.of() : List.copyOf(coverageWarnings);
        }
    }

    public record PortfolioAlertDto(
            String severity,
            String category,
            String alertCode,
            String figi,
            String symbol,
            String stockName,
            Map<String, Object> payload
    ) {
        public PortfolioAlertDto {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record HoldingHealthItemDto(
            String figi,
            String positionId,
            String symbol,
            String stockName,
            String currency,
            String industryCode,
            String industryName,
            LocalDate evalDate,
            BigDecimal closePrice,
            BigDecimal adjustedShares,
            BigDecimal adjustedAvgCost,
            BigDecimal marketValue,
            BigDecimal unrealizedPnlAmount,
            BigDecimal unrealizedPnlPct,
            String splitAlignmentStatus,
            String verdict,
            String fundamentalBacking,
            String stance,
            String breakoutDiagnosis,
            BigDecimal referenceProtectiveStop,
            String referenceStopSource,
            BigDecimal referenceTarget1,
            BigDecimal referenceTarget2,
            LocalDate referenceTargetsDate,
            LocalDate nextEarningsDate,
            Integer daysToNextEarnings,
            String earningsBufferLevel,
            String highestSeverity,
            List<PortfolioAlertDto> alerts,
            List<String> dataGapReasons
    ) {
        public HoldingHealthItemDto {
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
            dataGapReasons = dataGapReasons == null ? List.of() : List.copyOf(dataGapReasons);
        }
    }

    public record WatchlistOpportunityDto(
            String figi,
            String symbol,
            String stockName,
            String currency,
            String industryCode,
            String industryName,
            String triggerType,
            String verdict,
            String fundamentalBacking,
            String stance,
            BigDecimal closePrice,
            BigDecimal entryLower,
            BigDecimal entryUpper,
            BigDecimal protectiveStop,
            BigDecimal firstTargetPrice,
            PositionSizingDto positionSizing
    ) {}

    public record PortfolioHealthSummaryDto(
            LocalDate tradeDate,
            String sourceMode,
            Instant generatedAt,
            String marketRegime,
            int holdingCount,
            int watchlistCount,
            int alertHoldingCount,
            int criticalCount,
            int warningCount,
            int infoCount,
            boolean structureHealthy,
            boolean completeCoverage,
            int excludedHoldingsCount,
            List<String> coverageWarnings,
            List<HoldingHealthItemDto> holdings,
            List<WatchlistOpportunityDto> watchlistOpportunities,
            List<SectorExposureDto> sectorExposures,
            List<PortfolioAlertDto> sectorAlerts
    ) {
        public PortfolioHealthSummaryDto {
            coverageWarnings = coverageWarnings == null ? List.of() : List.copyOf(coverageWarnings);
            holdings = holdings == null ? List.of() : List.copyOf(holdings);
            watchlistOpportunities = watchlistOpportunities == null ? List.of() : List.copyOf(watchlistOpportunities);
            sectorExposures = sectorExposures == null ? List.of() : List.copyOf(sectorExposures);
            sectorAlerts = sectorAlerts == null ? List.of() : List.copyOf(sectorAlerts);
        }
    }

    public record BriefingSectorDto(
            String industryCode,
            String industryName,
            BigDecimal cmf20d,
            BigDecimal dollarVolume1d,
            BigDecimal breadthAboveSma50Pct,
            String flowState
    ) {}

    public record BriefingRadarItemDto(
            String figi,
            String symbol,
            String stockName,
            String currency,
            String trackType,
            String industryCode,
            String industryName,
            String verdict,
            String stance,
            BigDecimal closePrice,
            BigDecimal discountToMid,
            BigDecimal entryLower,
            BigDecimal entryUpper,
            BigDecimal protectiveStop,
            BigDecimal firstTargetPrice,
            BigDecimal netRiskReward,
            String pullbackAnchor,
            String positionFraction,
            String planStatus,
            boolean planActionable,
            Long suggestedShares,
            BigDecimal positionCapitalPct,
            List<String> sizingTags
    ) {
        public BriefingRadarItemDto {
            sizingTags = sizingTags == null ? List.of() : List.copyOf(sizingTags);
        }
    }

    public record DailyClosingBriefingDto(
            LocalDate tradeDate,
            String marketRegime,
            String previousMarketRegime,
            LocalDate previousTradeDate,
            boolean regimeChanged,
            BigDecimal vixClose,
            BigDecimal treasury10y,
            List<BriefingSectorDto> topSectors,
            List<BriefingSectorDto> bottomSectors,
            PortfolioHealthSummaryDto portfolioHealth,
            List<WatchlistOpportunityDto> watchlistOpportunities,
            List<BriefingRadarItemDto> radarTop5,
            int criticalCount,
            int warningCount,
            int infoCount,
            Instant observationCutoff,
            Instant generatedAt
    ) {
        public DailyClosingBriefingDto {
            topSectors = topSectors == null ? List.of() : List.copyOf(topSectors);
            bottomSectors = bottomSectors == null ? List.of() : List.copyOf(bottomSectors);
            watchlistOpportunities = watchlistOpportunities == null ? List.of() : List.copyOf(watchlistOpportunities);
            radarTop5 = radarTop5 == null ? List.of() : List.copyOf(radarTop5);
        }
    }

    public record InAppNotificationDto(
            Long id,
            String dedupKey,
            LocalDate tradeDate,
            String severity,
            String category,
            String alertCode,
            String figi,
            String symbol,
            String stockName,
            String targetRoute,
            Map<String, Object> payload,
            boolean read,
            Instant readAt,
            Instant createdAt
    ) {
        public InAppNotificationDto {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record UnreadCountDto(long unreadCount) {}
}
