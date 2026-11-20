package com.ykm.stock.analytics.model.dto.stockInsight.report;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 08_Enhancement：產業與自選股「建議買入排行榜」報表與一鍵下載前後端契約 DTO。
 */
public final class BuyRankingDtos {
    private BuyRankingDtos() {}

    public static final String SCOPE_SECTOR = "SECTOR";
    public static final String SCOPE_PORTFOLIO = "PORTFOLIO";
    public static final String SCOPE_CUSTOM = "CUSTOM";

    public static final String ASSET_COMMON_STOCK = "COMMON_STOCK";
    public static final String ASSET_ADR = "ADR";
    public static final String ASSET_ETF = "ETF";
    public static final String ASSET_REIT = "REIT";

    public static final String TIER_1 = "TIER_1";
    public static final String TIER_2 = "TIER_2";
    public static final String TIER_3 = "TIER_3";
    public static final String TIER_4 = "TIER_4";

    public static final String GRADE_ALL = "ALL";
    public static final String GRADE_MIXED = "MIXED";
    public static final String GRADE_STRONG = "STRONG";

    public static final String SORT_TIER_COMPOSITE = "TIER_COMPOSITE";
    public static final String SORT_NET_RR_DESC = "NET_RR_DESC";
    public static final String SORT_DISCOUNT_DESC = "DISCOUNT_DESC";
    public static final String SORT_DISTANCE_TO_BUY_ZONE_ASC = "DISTANCE_TO_BUY_ZONE_ASC";
    public static final String SORT_ROIC_DESC = "ROIC_DESC";
    public static final String SORT_SYMBOL_ASC = "SYMBOL_ASC";

    public static final String EXPORT_XLSX = "XLSX";
    public static final String EXPORT_CSV = "CSV";

    public static final int MAX_CANDIDATES = 300;

    /** 同時接受 JSON 字串（例如 "NVDA, TSM"）或字串陣列（例如 ["NVDA", "TSM"]），方便前端文字框與 API 呼叫端。 */
    public static final class FlexibleStringListDeserializer extends JsonDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (node == null || node.isNull()) {
                return null;
            }
            List<String> out = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode child : node) {
                    if (child != null && !child.isNull()) {
                        out.add(child.asText());
                    }
                }
                return out;
            }
            if (node.isTextual()) {
                out.add(node.asText());
                return out;
            }
            throw ctxt.weirdNativeValueException(node, List.class);
        }
    }

    public record BuyRankingRequest(
            String scopeMode,
            List<String> industryCodes,
            List<String> sectors,
            List<String> portfolioTypes,
            @JsonDeserialize(using = FlexibleStringListDeserializer.class)
            List<String> customSymbols,
            List<String> assetTypes,
            Boolean excludeReits,
            List<String> tiers,
            String minFundamentalGrade,
            BigDecimal minNetRiskReward,
            Integer excludeEarningsWithinDays,
            String sortBy,
            String horizon,
            String locale,
            Integer page,
            Integer pageSize
    ) {}

    public record BuyRankingOptionsDto(
            List<String> scopeModes,
            List<String> portfolioTypes,
            List<String> assetTypes,
            List<String> tiers,
            List<String> fundamentalGrades,
            List<String> sortOptions,
            List<String> horizons,
            int maxCandidates
    ) {}

    public record MarketBackgroundDto(
            String marketRegime,
            BigDecimal vixClose,
            BigDecimal tenYearYieldPct,
            BigDecimal hyOasPct,
            BigDecimal hyOasDeltaBps,
            String hyOasStatus,
            boolean macroCautionActive,
            boolean marketDataReady,
            LocalDate marketDataDate,
            LocalDate sectorMoneyFlowDate,
            LocalDate lastClosedTradingDay,
            boolean staleDateFallback
    ) {
        public MarketBackgroundDto(
                String marketRegime,
                BigDecimal vixClose,
                BigDecimal tenYearYieldPct,
                BigDecimal hyOasPct,
                BigDecimal hyOasDeltaBps,
                String hyOasStatus,
                boolean macroCautionActive,
                boolean marketDataReady,
                LocalDate marketDataDate,
                LocalDate sectorMoneyFlowDate) {
            this(marketRegime, vixClose, tenYearYieldPct, hyOasPct, hyOasDeltaBps, hyOasStatus,
                    macroCautionActive, marketDataReady, marketDataDate, sectorMoneyFlowDate, marketDataDate, false);
        }
    }

    public record ScopeSummaryDto(
            String scopeMode,
            String scopeLabel,
            List<String> industryCodes,
            List<String> industryNames,
            List<String> portfolioTypes,
            int matchedTotalCount,
            int evaluatedCount,
            int filteredTotalCount,
            int maxCandidates,
            boolean truncated,
            List<String> unmatchedSymbols,
            List<String> untrackedSymbols,
            List<String> excludedByAssetTypeSymbols
    ) {}

    public record AppliedFiltersDto(
            String scopeMode,
            List<String> industryCodes,
            List<String> portfolioTypes,
            List<String> customSymbols,
            List<String> assetTypes,
            boolean excludeReits,
            List<String> tiers,
            String minFundamentalGrade,
            BigDecimal minNetRiskReward,
            Integer excludeEarningsWithinDays,
            String sortBy,
            String horizon
    ) {}

    public record BuyZoneDto(
            BigDecimal low,
            BigDecimal mid,
            BigDecimal high
    ) {}

    public record BuyRankingItemDto(
            int rank,
            int recommendedRank,
            String tier,
            BigDecimal tierScore,
            String figi,
            String symbol,
            String stockName,
            String currency,
            String industryCode,
            String sector,
            String assetType,
            String rawStockType,
            String verdict,
            String fundamentalBacking,
            String stance,
            String breakoutDiagnosis,
            BigDecimal currentPrice,
            BuyZoneDto buyZone,
            BigDecimal distanceToBuyZonePct,
            boolean nearSupportWithin3Pct,
            String buyZoneSource,
            BigDecimal stopLossPrice,
            BigDecimal stopLossDistancePct,
            BigDecimal firstTargetPrice,
            BigDecimal netRiskReward,
            BigDecimal planNetRiskReward,
            Long suggestedShares,
            String positionScaleTag,
            String positionSizingStatus,
            boolean positionSizingActionable,
            String positionSizingReasonCode,
            boolean sectorConcentrationWarning,
            List<String> positionSizingTags,
            BigDecimal valuationDiscountPct,
            BigDecimal forwardPeg,
            BigDecimal forwardPe,
            BigDecimal roic,
            BigDecimal adjFcfTtm,
            BigDecimal reverseDcfImpliedGrowth,
            String reverseDcfBasis,
            String reverseDcfStatus,
            BigDecimal etfTrendScore,
            BigDecimal etfCmf20d,
            BigDecimal etfRs60dVsSpy,
            LocalDate nextEarningsDate,
            Integer daysToEarnings,
            String sectorFlowState,
            BigDecimal sectorCmf20d,
            String reasonCode,
            Map<String, Object> reasonParams,
            String actionReason
    ) {}

    public record BuyRankingResponse(
            String reportId,
            LocalDate asOfDate,
            String generatedAtHk,
            Instant observationCutoff,
            String resolvedLocale,
            String marketRegime,
            MarketBackgroundDto marketContext,
            ScopeSummaryDto scopeSummary,
            int totalCount,
            int page,
            int pageSize,
            Map<String, Integer> tierCounts,
            AppliedFiltersDto appliedFilters,
            List<BuyRankingItemDto> items
    ) {}
}
