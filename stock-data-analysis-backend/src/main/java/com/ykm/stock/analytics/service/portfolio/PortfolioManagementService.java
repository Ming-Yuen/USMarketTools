package com.ykm.stock.analytics.service.portfolio;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioItemUpsertRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioItemViewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioOverviewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.SectorExposureDto;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.portfolio.PortfolioSplitAligner.AlignedPosition;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 個人持倉與重點自選股管理服務（CRUD + 輕量最新市值／拆股對齊／板塊曝險彙總）。
 * 僅查詢最新一筆行情與拆股，絕不載入 5 年日線或呼叫任何外部 API。
 */
@Service
@RequiredArgsConstructor
public class PortfolioManagementService {
    private static final MathContext MC = MathContext.DECIMAL128;

    private final PortfolioItemRepository portfolioItemRepository;
    private final StockRepository stockRepository;
    private final StockStatusRepository stockStatusRepository;
    private final StockQuoteRepository stockQuoteRepository;
    private final StockSplitRepository stockSplitRepository;
    private final StockIndustryHistoryRepository industryHistoryRepository;
    private final PortfolioRiskConfigService riskConfigService;
    private final PortfolioSplitAligner splitAligner;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;

    /** 供個股倉位風險計算與組合檢視共用之既有持倉市值快照（純 USD 組合曝險口徑）。 */
    public record PortfolioExposureSnapshot(
            PortfolioRiskConfigDto config,
            Map<String, BigDecimal> holdingMarketValueUsdByFigi,
            Map<String, BigDecimal> sectorMarketValueUsdByIndustry,
            Set<String> unverifiedHoldingFigis,
            int excludedHoldingsCount,
            List<String> coverageWarnings
    ) {
        public PortfolioExposureSnapshot {
            holdingMarketValueUsdByFigi = holdingMarketValueUsdByFigi == null ? Map.of() : Map.copyOf(holdingMarketValueUsdByFigi);
            sectorMarketValueUsdByIndustry = sectorMarketValueUsdByIndustry == null ? Map.of() : Map.copyOf(sectorMarketValueUsdByIndustry);
            unverifiedHoldingFigis = unverifiedHoldingFigis == null ? Set.of() : Set.copyOf(unverifiedHoldingFigis);
            coverageWarnings = coverageWarnings == null ? List.of() : List.copyOf(coverageWarnings);
        }
    }

    private record LoadedOverviewResult(
            PortfolioOverviewDto overview,
            Map<String, BigDecimal> rawHoldingMvUsdByFigi,
            Map<String, BigDecimal> rawSectorMvUsdByIndustry,
            Set<String> unverifiedHoldingFigis
    ) {}

    @Transactional(readOnly = true)
    public PortfolioOverviewDto getOverview() {
        Instant now = dateTimeService.now();
        LocalDate referenceDay = calendar.getLastClosedTradingDay(now);
        PortfolioRiskConfigDto config = riskConfigService.getConfig();
        List<PortfolioItem> items = portfolioItemRepository.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc();
        return buildOverviewFromLoaded(items, config, referenceDay, now).overview();
    }

    @Transactional(readOnly = true)
    public PortfolioExposureSnapshot loadExposureSnapshot(LocalDate referenceDay, Instant asOf) {
        PortfolioRiskConfigDto config = riskConfigService.getConfig();
        List<PortfolioItem> holdings = portfolioItemRepository.findByItemTypeOrderByUpdatedAtDescFigiAsc(PortfolioItem.TYPE_HOLDING);
        if (holdings.isEmpty()) {
            return new PortfolioExposureSnapshot(config, Map.of(), Map.of(), Set.of(), 0, List.of());
        }
        LoadedOverviewResult loaded = buildOverviewFromLoaded(holdings, config, referenceDay, asOf);
        return new PortfolioExposureSnapshot(
                config,
                loaded.rawHoldingMvUsdByFigi(),
                loaded.rawSectorMvUsdByIndustry(),
                loaded.unverifiedHoldingFigis(),
                loaded.overview().excludedHoldingsCount(),
                loaded.overview().coverageWarnings()
        );
    }

    @Transactional
    public PortfolioItemViewDto upsertItem(String figi, PortfolioItemUpsertRequest request) {
        if (figi == null || figi.isBlank() || request == null || request.itemType() == null || request.itemType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PORTFOLIO_ITEM_INVALID");
        }
        String normalizedFigi = figi.trim();
        Stock stock = stockRepository.findById(normalizedFigi)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        String type = request.itemType().trim().toUpperCase(Locale.ROOT);
        if (!PortfolioItem.TYPE_HOLDING.equals(type) && !PortfolioItem.TYPE_WATCHLIST.equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PORTFOLIO_ITEM_TYPE");
        }

        Instant now = dateTimeService.now();
        LocalDate lastClosedDay = calendar.getLastClosedTradingDay(now);
        PortfolioItem existing = portfolioItemRepository.findById(normalizedFigi).orElse(null);

        BigDecimal shares = null;
        BigDecimal avgCost = null;
        LocalDate costBasisDate = null;
        String positionId = null;
        if (PortfolioItem.TYPE_HOLDING.equals(type)) {
            if (request.shares() == null || request.shares().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HOLDING_SHARES");
            }
            if (request.avgCost() == null || request.avgCost().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HOLDING_AVG_COST");
            }
            shares = request.shares().setScale(6, RoundingMode.HALF_UP);
            avgCost = request.avgCost().setScale(6, RoundingMode.HALF_UP);
            if (shares.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HOLDING_SHARES");
            }
            if (avgCost.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HOLDING_AVG_COST");
            }
            if (request.costBasisDate() != null) {
                if (!calendar.isTradingDay(request.costBasisDate())
                        || calendar.getSessionCloseAt(request.costBasisDate()).isAfter(now)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_COST_BASIS_DATE");
                }
                costBasisDate = request.costBasisDate();
            } else if (existing != null
                    && PortfolioItem.TYPE_HOLDING.equals(existing.getItemType())
                    && existing.getShares() != null && existing.getShares().compareTo(shares) == 0
                    && existing.getAvgCost() != null && existing.getAvgCost().compareTo(avgCost) == 0
                    && existing.getCostBasisDate() != null) {
                // 僅修改備註而未更動股數與成本時，保留既有拆股基準日，避免誤移基準日造成重複或漏調拆股
                costBasisDate = existing.getCostBasisDate();
            } else {
                costBasisDate = lastClosedDay;
            }
            // 同次持倉持續更新時保留 positionId；新建或由 WATCHLIST 切換為 HOLDING 時核發新 positionId，防止重開倉誤承接舊止損／目標
            if (existing != null
                    && PortfolioItem.TYPE_HOLDING.equals(existing.getItemType())
                    && existing.getPositionId() != null && !existing.getPositionId().isBlank()) {
                positionId = existing.getPositionId();
            } else {
                positionId = UUID.randomUUID().toString();
            }
        }

        String normalizedNotes = normalizeNotes(request.notes());
        PortfolioItem entity = existing != null ? existing : new PortfolioItem();
        if (existing == null) {
            entity.setFigi(stock.getFigi());
            entity.setCreatedAt(now);
        }
        entity.setItemType(type);
        entity.setShares(shares);
        entity.setAvgCost(avgCost);
        entity.setCostBasisDate(costBasisDate);
        entity.setPositionId(positionId);
        entity.setNotes(normalizedNotes);
        entity.setUpdatedAt(now);
        portfolioItemRepository.saveAndFlush(entity);

        PortfolioRiskConfigDto config = riskConfigService.getConfig();
        PortfolioOverviewDto single = buildOverviewFromLoaded(List.of(entity), config, lastClosedDay, now).overview();
        return single.items().getFirst();
    }

    @Transactional
    public void deleteItem(String figi) {
        if (figi == null || figi.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FIGI_REQUIRED");
        }
        String normalizedFigi = figi.trim();
        if (!portfolioItemRepository.existsById(normalizedFigi)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PORTFOLIO_ITEM_NOT_FOUND");
        }
        portfolioItemRepository.deleteById(normalizedFigi);
    }

    private LoadedOverviewResult buildOverviewFromLoaded(List<PortfolioItem> items,
                                                         PortfolioRiskConfigDto config,
                                                         LocalDate referenceDay,
                                                         Instant asOf) {
        if (items == null || items.isEmpty()) {
            PortfolioOverviewDto empty = new PortfolioOverviewDto(config, List.of(), List.of(), 0, 0,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    null, 0, List.of());
            return new LoadedOverviewResult(empty, Map.of(), Map.of(), Set.of());
        }
        List<String> figis = items.stream().map(PortfolioItem::getFigi).distinct().toList();
        Map<String, Stock> stockByFigi = stockRepository.findAllById(figis).stream()
                .collect(Collectors.toMap(Stock::getFigi, row -> row, (a, b) -> a));
        Map<String, StockStatus> statusByFigi = stockStatusRepository.findAllById(figis).stream()
                .collect(Collectors.toMap(StockStatus::getFigi, row -> row, (a, b) -> a));
        Map<String, StockQuote> latestQuoteByFigi = stockQuoteRepository.findLatestQuotesUpTo(figis, referenceDay).stream()
                .collect(Collectors.toMap(StockQuote::getFigi, row -> row, (a, b) -> a));
        Map<String, List<StockSplit>> splitsByFigi = new HashMap<>();
        for (StockSplit sp : stockSplitRepository.findByFigiInOrderByExecutionDateAsc(figis)) {
            splitsByFigi.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
        }
        Instant sessionCutoff = referenceDay != null ? calendar.getSessionCloseAt(referenceDay) : null;
        Instant industryAsOf = sessionCutoff != null ? sessionCutoff : (asOf != null ? asOf : dateTimeService.now());
        Map<String, StockIndustryHistory> industryByFigi = industryHistoryRepository.findByFigisAt(figis, industryAsOf).stream()
                .collect(Collectors.toMap(StockIndustryHistory::getFigi, row -> row, (a, b) -> a));

        BigDecimal totalCapital = config.totalCapital();
        BigDecimal totalMvUsd = BigDecimal.ZERO;
        BigDecimal totalCostUsd = BigDecimal.ZERO;
        int holdingCount = 0;
        int watchlistCount = 0;
        int excludedHoldingsCount = 0;
        List<String> coverageWarnings = new ArrayList<>();
        Map<String, BigDecimal> rawHoldingMvUsdByFigi = new HashMap<>();
        Map<String, BigDecimal> rawSectorMvUsdByIndustry = new HashMap<>();
        Set<String> unverifiedHoldingFigis = new LinkedHashSet<>();

        Map<String, SectorAccumulator> sectorAccMap = new LinkedHashMap<>();
        List<PortfolioItemViewDto> views = new ArrayList<>(items.size());

        for (PortfolioItem item : items) {
            String figi = item.getFigi();
            Stock stock = stockByFigi.get(figi);
            String symbol = stock != null ? stock.getSymbol() : figi;
            String stockName = stock != null ? stock.getLongName() : null;
            String currency = stock != null ? stock.getCurrency() : null;
            StockIndustryHistory indHist = industryByFigi.get(figi);
            String industryCode = indHist != null && indHist.getIndustry() != null ? indHist.getIndustry().getCode() : null;
            String industryName = indHist != null && indHist.getIndustry() != null ? indHist.getIndustry().getIndustry() : null;

            StockQuote latestQuote = latestQuoteByFigi.get(figi);
            LocalDate quoteDate = latestQuote != null ? latestQuote.getQuoteDate() : null;
            BigDecimal closePrice = latestQuote != null ? latestQuote.getClosePrice() : null;

            boolean isHolding = PortfolioItem.TYPE_HOLDING.equals(item.getItemType());
            if (isHolding) {
                holdingCount++;
            } else {
                watchlistCount++;
            }

            BigDecimal adjShares = null;
            BigDecimal adjAvgCost = null;
            BigDecimal shareFactor = null;
            boolean splitAdjusted = false;
            String splitStatus = null;
            BigDecimal marketValue = null;
            BigDecimal costTotal = null;
            BigDecimal pnlAmount = null;
            BigDecimal pnlPct = null;
            BigDecimal weightPct = null;

            if (isHolding) {
                boolean isUsd = "USD".equalsIgnoreCase(currency);
                if (closePrice == null || closePrice.signum() <= 0 || quoteDate == null) {
                    excludedHoldingsCount++;
                    unverifiedHoldingFigis.add(figi);
                    coverageWarnings.add("QUOTE_MISSING:" + symbol);
                    splitStatus = "QUOTE_MISSING";
                } else {
                    AlignedPosition aligned = splitAligner.alignHolding(
                            item.getShares(),
                            item.getAvgCost(),
                            item.getCostBasisDate(),
                            quoteDate,
                            statusByFigi.get(figi),
                            splitsByFigi.getOrDefault(figi, List.of())
                    );
                    splitStatus = aligned.status();
                    if (!aligned.verified()) {
                        excludedHoldingsCount++;
                        unverifiedHoldingFigis.add(figi);
                        coverageWarnings.add(aligned.status() + ":" + symbol);
                    } else {
                        adjShares = aligned.adjustedShares();
                        adjAvgCost = aligned.adjustedAvgCost();
                        shareFactor = aligned.shareFactor();
                        splitAdjusted = aligned.splitAdjusted();
                        BigDecimal rawMarketValue = aligned.rawAdjustedShares().multiply(closePrice, MC);
                        BigDecimal rawCostTotal = aligned.rawAdjustedShares().multiply(aligned.rawAdjustedAvgCost(), MC);
                        marketValue = rawMarketValue.setScale(2, RoundingMode.HALF_UP);
                        costTotal = rawCostTotal.setScale(2, RoundingMode.HALF_UP);
                        pnlAmount = rawMarketValue.subtract(rawCostTotal, MC).setScale(2, RoundingMode.HALF_UP);
                        pnlPct = rawCostTotal.signum() > 0
                                ? rawMarketValue.subtract(rawCostTotal, MC).divide(rawCostTotal, 6, RoundingMode.HALF_UP) : null;

                        boolean targetDateFresh = referenceDay == null || referenceDay.equals(quoteDate);
                        if (!targetDateFresh) {
                            excludedHoldingsCount++;
                            unverifiedHoldingFigis.add(figi);
                            coverageWarnings.add("TARGET_DATE_QUOTE_MISSING:" + symbol);
                        } else if (isUsd) {
                            totalMvUsd = totalMvUsd.add(rawMarketValue, MC);
                            totalCostUsd = totalCostUsd.add(rawCostTotal, MC);
                            rawHoldingMvUsdByFigi.put(figi, rawMarketValue);
                            weightPct = totalCapital.signum() > 0
                                    ? rawMarketValue.divide(totalCapital, 6, RoundingMode.HALF_UP) : null;
                            if (industryCode != null && !industryCode.isBlank()) {
                                rawSectorMvUsdByIndustry.merge(industryCode, rawMarketValue, (a, b) -> a.add(b, MC));
                                SectorAccumulator acc = sectorAccMap.computeIfAbsent(industryCode,
                                        k -> new SectorAccumulator(industryCode, industryName));
                                acc.holdingCount++;
                                acc.marketValueUsd = acc.marketValueUsd.add(rawMarketValue, MC);
                            } else if (stock == null || !com.ykm.stock.analytics.service.analysis.SecurityTypePolicy.isEtfType(stock.getStockType())) {
                                coverageWarnings.add("INDUSTRY_UNCLASSIFIED:" + symbol);
                            }
                        } else {
                            excludedHoldingsCount++;
                            unverifiedHoldingFigis.add(figi);
                            coverageWarnings.add((currency == null || currency.isBlank() ? "CURRENCY_MISSING:" : "NON_USD_EXCLUDED:") + symbol);
                        }
                    }
                }
            }

            views.add(new PortfolioItemViewDto(
                    figi, symbol, stockName, currency, industryCode, industryName,
                    item.getItemType(),
                    item.getShares(), item.getAvgCost(), item.getCostBasisDate(),
                    adjShares, adjAvgCost, shareFactor, splitAdjusted, splitStatus,
                    quoteDate, closePrice, marketValue, costTotal, pnlAmount, pnlPct, weightPct,
                    item.getNotes(), item.getUpdatedAt()
            ));
        }

        List<SectorExposureDto> exposures = new ArrayList<>();
        for (SectorAccumulator acc : sectorAccMap.values()) {
            BigDecimal exposurePct = totalCapital.signum() > 0
                    ? acc.marketValueUsd.divide(totalCapital, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            boolean exceeded = acc.marketValueUsd.compareTo(totalCapital.multiply(config.maxSectorExposurePct(), MC)) > 0;
            exposures.add(new SectorExposureDto(
                    acc.industryCode,
                    acc.industryName,
                    acc.holdingCount,
                    acc.marketValueUsd.setScale(2, RoundingMode.HALF_UP),
                    exposurePct,
                    config.maxSectorExposurePct(),
                    exceeded
            ));
        }
        exposures.sort(Comparator.comparing(SectorExposureDto::exposurePct, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SectorExposureDto::industryCode));

        BigDecimal totalPnlUsd = totalMvUsd.subtract(totalCostUsd, MC).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPnlPct = totalCostUsd.signum() > 0
                ? totalMvUsd.subtract(totalCostUsd, MC).divide(totalCostUsd, 6, RoundingMode.HALF_UP) : null;

        PortfolioOverviewDto overview = new PortfolioOverviewDto(
                config,
                views,
                exposures,
                holdingCount,
                watchlistCount,
                totalMvUsd.setScale(2, RoundingMode.HALF_UP),
                totalCostUsd.setScale(2, RoundingMode.HALF_UP),
                totalPnlUsd,
                totalPnlPct,
                excludedHoldingsCount,
                coverageWarnings
        );
        return new LoadedOverviewResult(
                overview,
                Map.copyOf(rawHoldingMvUsdByFigi),
                Map.copyOf(rawSectorMvUsdByIndustry),
                Set.copyOf(unverifiedHoldingFigis)
        );
    }

    private String normalizeNotes(String notes) {
        if (notes == null) {
            return null;
        }
        String trimmed = notes.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NOTES_TOO_LONG");
        }
        return trimmed;
    }

    private static final class SectorAccumulator {
        final String industryCode;
        final String industryName;
        int holdingCount;
        BigDecimal marketValueUsd = BigDecimal.ZERO;

        SectorAccumulator(String industryCode, String industryName) {
            this.industryCode = industryCode;
            this.industryName = industryName;
        }
    }
}
