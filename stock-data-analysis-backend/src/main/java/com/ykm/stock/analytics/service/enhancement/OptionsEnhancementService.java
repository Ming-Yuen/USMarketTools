package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.OptionsSummaryDto;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockOptionsSummary;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockOptionsSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.AlpacaService;
import com.ykm.stock.analytics.service.stock.AlpacaService.OptionContractRecord;
import com.ykm.stock.analytics.service.stock.AlpacaService.OptionSnapshotRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 2.1 輕量期權輔助指標服務（Alpaca Contracts + Snapshots，零額外付費訂閱）。
 * 規則邊界：
 * - 僅針對持倉、自選、當日雷達與手動觸發標的按需同步，絕不掃描全市場 5,000 股。
 * - 財報 Implied Move 嚴格選取覆蓋公告時刻之首個有效到期 ATM 跨式（週五盤後財報不可用當日到期）；ETF 標示 NOT_APPLICABLE_FOR_ETF 但完整輸出 OI Wall、Put/Call 與 Skew。
 * - OI 牆強制附帶非方向性防呆標記（OI_WALL_NON_DIRECTIONAL_DISCLAIMER），嚴禁將 Call OI 多直接解讀為看漲。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OptionsEnhancementService {
    public static final String SOURCE_ALPACA = "ALPACA";
    public static final String OI_DISCLAIMER_CODE = "OI_WALL_NON_DIRECTIONAL_DISCLAIMER";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal TARGET_OTM_DELTA = new BigDecimal("0.25");

    private final AlpacaService alpacaService;
    private final StockOptionsSummaryRepository optionsRepository;
    private final StockQuoteRepository stockQuoteRepository;
    private final MarketEventRepository marketEventRepository;
    private final QuoteNormalizationService quoteNormalizationService;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactionTemplate;

    /** 按需同步單股／ETF 之輕量期權摘要並落庫（網路呼叫在 DB 交易外執行）。 */
    public OptionsSummaryDto refreshStock(Stock stock) {
        if (stock == null || stock.getSymbol() == null || stock.getSymbol().isBlank()) {
            return OptionsSummaryDto.unavailable(stock == null ? null : stock.getFigi(),
                    stock == null ? null : stock.getSymbol(), StockOptionsSummary.STATUS_INSUFFICIENT_DATA, "STOCK_INVALID");
        }
        if (!alpacaService.isConfigured()) {
            throw new IllegalStateException("ALPACA_API_CREDENTIALS_REQUIRED");
        }
        Instant now = dateTimeService.now();
        // 美東交易日盤中刷新使用當日美東交易日，避免覆寫前一已收市交易日之結算快照；休市日／盤前沿用最近已收市交易日
        LocalDate etToday = now.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        boolean intradayOrUnsettledToday = calendar.isTradingDay(etToday) && !now.isBefore(calendar.getSessionOpenAt(etToday));
        LocalDate snapshotDate = intradayOrUnsettledToday ? etToday : lastClosed;
        boolean isEtf = SecurityTypePolicy.isEtfType(stock.getStockType());

        PriceContext priceCtx = loadSpotAndHv20(stock, snapshotDate, intradayOrUnsettledToday, now);
        if (priceCtx.spotPrice() == null || priceCtx.spotPrice().signum() <= 0) {
            StockOptionsSummary missing = buildUnavailableEntity(stock, snapshotDate, now,
                    StockOptionsSummary.STATUS_INSUFFICIENT_DATA,
                    intradayOrUnsettledToday ? "INTRADAY_SPOT_PRICE_UNAVAILABLE" : "SPOT_PRICE_UNAVAILABLE", isEtf);
            saveSummary(missing);
            return toDto(missing, List.of(), null, snapshotDate);
        }

        MarketEvent nextEarnings = isEtf ? null : findNextUpcomingEarnings(stock.getFigi(), snapshotDate, now);
        LocalDate earningsDate = nextEarnings == null ? null : nextEarnings.getEventDate();
        LocalDate expFrom = snapshotDate;
        LocalDate expThrough = snapshotDate.plusDays(60);
        if (earningsDate != null && earningsDate.plusDays(14).isAfter(expThrough)) {
            expThrough = earningsDate.plusDays(14);
        }

        List<OptionContractRecord> contracts = alpacaService.getOptionContracts(stock.getSymbol(), expFrom, expThrough, 8);
        if (contracts.isEmpty()) {
            StockOptionsSummary noChain = buildUnavailableEntity(stock, snapshotDate, now,
                    StockOptionsSummary.STATUS_NOT_APPLICABLE, "NO_ACTIVE_OPTIONS_CHAIN", isEtf);
            noChain.setSpotPrice(priceCtx.spotPrice());
            noChain.setHv20(priceCtx.hv20());
            saveSummary(noChain);
            return toDto(noChain, List.of(), null, snapshotDate);
        }

        TreeMap<LocalDate, List<OptionContractRecord>> byExpiry = new TreeMap<>();
        for (OptionContractRecord c : contracts) {
            byExpiry.computeIfAbsent(c.expirationDate(), k -> new ArrayList<>()).add(c);
        }

        // 1. 選定主力 OI 到期日（近 45 日內總未平倉量最大之到期日；若全無 OI 則取最近有效到期日）
        LocalDate oiWallExp = selectPrimaryOiExpiration(byExpiry, snapshotDate);
        List<OptionContractRecord> oiContracts = byExpiry.getOrDefault(oiWallExp, List.of());

        // 2. 選定跨式 Implied Move 到期日（財報情境嚴格覆蓋公告時刻；ETF 選主力近月僅作一般波動參考）
        LocalDate straddleExp = selectStraddleExpiration(byExpiry, nextEarnings, snapshotDate, isEtf);
        LocalDate snapshotExpFrom = oiWallExp;
        LocalDate snapshotExpThrough = oiWallExp;
        if (straddleExp != null) {
            if (snapshotExpFrom == null || straddleExp.isBefore(snapshotExpFrom)) snapshotExpFrom = straddleExp;
            if (snapshotExpThrough == null || straddleExp.isAfter(snapshotExpThrough)) snapshotExpThrough = straddleExp;
        }

        BigDecimal strikeMin = priceCtx.spotPrice().multiply(new BigDecimal("0.70"), MC).setScale(2, RoundingMode.HALF_UP);
        BigDecimal strikeMax = priceCtx.spotPrice().multiply(new BigDecimal("1.30"), MC).setScale(2, RoundingMode.HALF_UP);
        Map<String, OptionSnapshotRecord> snapshots = snapshotExpFrom == null
                ? Map.of()
                : alpacaService.getOptionSnapshots(stock.getSymbol(), snapshotExpFrom, snapshotExpThrough, strikeMin, strikeMax, 6);

        StockOptionsSummary entity = computeSummary(
                stock, snapshotDate, now, isEtf, priceCtx, nextEarnings,
                oiWallExp, oiContracts, straddleExp,
                straddleExp == null ? List.of() : byExpiry.getOrDefault(straddleExp, List.of()),
                snapshots);
        saveSummary(entity);
        List<StockOptionsSummary> history = optionsRepository.findByFigiAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                stock.getFigi(), snapshotDate.minusDays(365), snapshotDate);
        return toDto(entity, history, null, snapshotDate);
    }

    public void saveSummary(StockOptionsSummary entity) {
        transactionTemplate.executeWithoutResult(txStatus -> optionsRepository.save(entity));
    }

    /** 純資料庫唯讀查詢單股最新（截至 asOfDate）期權摘要，並與保護止損距離對照計算財報跳空打穿風險。 */
    @Transactional(readOnly = true)
    public OptionsSummaryDto readAt(Stock stock, LocalDate asOfDate, BigDecimal protectiveStopPrice, BigDecimal currentClosePrice) {
        if (stock == null || asOfDate == null) {
            return OptionsSummaryDto.unavailable(stock == null ? null : stock.getFigi(),
                    stock == null ? null : stock.getSymbol(), StockOptionsSummary.STATUS_INSUFFICIENT_DATA, "STOCK_OR_DATE_MISSING");
        }
        Optional<StockOptionsSummary> found = optionsRepository.findTopByFigiAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                stock.getFigi(), asOfDate);
        if (found.isEmpty()) {
            boolean isEtf = SecurityTypePolicy.isEtfType(stock.getStockType());
            OptionsSummaryDto emptyDto = OptionsSummaryDto.unavailable(stock.getFigi(), stock.getSymbol(),
                    StockOptionsSummary.STATUS_INSUFFICIENT_DATA, "OPTIONS_NOT_SYNCED");
            if (!isEtf) {
                return emptyDto;
            }
            return new OptionsSummaryDto(
                    emptyDto.figi(), emptyDto.symbol(), null, null, null,
                    StockOptionsSummary.STATUS_INSUFFICIENT_DATA, "OPTIONS_NOT_SYNCED",
                    null, null, null, null, null, null, null, null, null,
                    StockOptionsSummary.STATUS_NOT_APPLICABLE, "NOT_APPLICABLE_FOR_ETF",
                    null, false, null, null, null, 0, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    OI_DISCLAIMER_CODE, null
            );
        }
        StockOptionsSummary entity = found.get();
        List<StockOptionsSummary> history = optionsRepository.findByFigiAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                stock.getFigi(), entity.getSnapshotDate().minusDays(365), entity.getSnapshotDate());
        BigDecimal stopDistPct = computeStopDistancePct(currentClosePrice != null ? currentClosePrice : entity.getSpotPrice(), protectiveStopPrice);
        return toDto(entity, history, stopDistPct, asOfDate);
    }

    StockOptionsSummary computeSummary(
            Stock stock,
            LocalDate snapshotDate,
            Instant now,
            boolean isEtf,
            PriceContext priceCtx,
            MarketEvent nextEarnings,
            LocalDate oiWallExp,
            List<OptionContractRecord> oiContracts,
            LocalDate straddleExp,
            List<OptionContractRecord> straddleContracts,
            Map<String, OptionSnapshotRecord> snapshots) {
        BigDecimal spot = priceCtx.spotPrice();
        StockOptionsSummary entity = new StockOptionsSummary();
        entity.setFigi(stock.getFigi());
        entity.setSnapshotDate(snapshotDate);
        entity.setSymbol(stock.getSymbol());
        entity.setSource(SOURCE_ALPACA);
        entity.setFeed(alpacaService.getOptionsFeed());
        entity.setSpotPrice(spot.setScale(6, RoundingMode.HALF_UP));
        entity.setHv20(priceCtx.hv20());
        entity.setNextEarningsDate(nextEarnings == null ? null : nextEarnings.getEventDate());
        entity.setOiWallExpiration(oiWallExp);
        entity.setStraddleExpiration(straddleExp);
        entity.setObservedAt(now);

        // 1. 計算主力到期日完整行權價之 Call/Put OI 牆與 Put/Call 比例
        long totalCallOi = 0L;
        long totalPutOi = 0L;
        long totalCallVol = 0L;
        long totalPutVol = 0L;
        BigDecimal maxCallStrike = null;
        Long maxCallOi = null;
        BigDecimal maxPutStrike = null;
        Long maxPutOi = null;
        LocalDate latestOiDate = null;
        LocalDate latestVolDate = null;
        Instant latestQuoteTs = null;

        for (OptionContractRecord c : oiContracts) {
            long oi = c.openInterest() != null && c.openInterest() >= 0 ? c.openInterest() : 0L;
            if (c.openInterestDate() != null && (latestOiDate == null || c.openInterestDate().isAfter(latestOiDate))) {
                latestOiDate = c.openInterestDate();
            }
            OptionSnapshotRecord snap = snapshots.get(c.contractSymbol());
            long vol = snap != null && snap.dailyVolume() != null && snap.dailyVolume() > 0 ? snap.dailyVolume() : 0L;
            if (snap != null && snap.volumeSessionDate() != null && (latestVolDate == null || snap.volumeSessionDate().isAfter(latestVolDate))) {
                latestVolDate = snap.volumeSessionDate();
            }
            if (snap != null && snap.quoteTimestamp() != null && (latestQuoteTs == null || snap.quoteTimestamp().isAfter(latestQuoteTs))) {
                latestQuoteTs = snap.quoteTimestamp();
            }
            if ("call".equalsIgnoreCase(c.type())) {
                totalCallOi += oi;
                totalCallVol += vol;
                if (oi > 0 && (maxCallOi == null || oi > maxCallOi)) {
                    maxCallOi = oi;
                    maxCallStrike = c.strikePrice();
                }
            } else if ("put".equalsIgnoreCase(c.type())) {
                totalPutOi += oi;
                totalPutVol += vol;
                if (oi > 0 && (maxPutOi == null || oi > maxPutOi)) {
                    maxPutOi = oi;
                    maxPutStrike = c.strikePrice();
                }
            }
        }
        entity.setTotalCallOi(totalCallOi > 0 ? totalCallOi : null);
        entity.setTotalPutOi(totalPutOi > 0 ? totalPutOi : null);
        entity.setMaxCallOiStrike(maxCallStrike);
        entity.setMaxCallOi(maxCallOi);
        entity.setMaxPutOiStrike(maxPutStrike);
        entity.setMaxPutOi(maxPutOi);
        entity.setOpenInterestDate(latestOiDate);
        entity.setVolumeSessionDate(latestVolDate);
        entity.setQuoteTimestamp(latestQuoteTs);
        if (totalCallOi > 0 && totalPutOi > 0) {
            entity.setPutCallOiRatio(BigDecimal.valueOf(totalPutOi).divide(BigDecimal.valueOf(totalCallOi), 4, RoundingMode.HALF_UP));
        }
        if (totalCallVol > 0 && totalPutVol > 0) {
            entity.setPutCallVolRatio(BigDecimal.valueOf(totalPutVol).divide(BigDecimal.valueOf(totalCallVol), 4, RoundingMode.HALF_UP));
        }

        // 2. 計算 ATM 跨式 Implied Move 與 ATM IV（同到期同履約價 Call + Put）
        if (isEtf) {
            entity.setImpliedMoveStatus(StockOptionsSummary.STATUS_NOT_APPLICABLE);
            entity.setImpliedMoveReason("NOT_APPLICABLE_FOR_ETF");
        } else if (straddleExp == null || straddleContracts.isEmpty()) {
            entity.setImpliedMoveStatus(StockOptionsSummary.STATUS_INSUFFICIENT_DATA);
            entity.setImpliedMoveReason(nextEarnings != null ? "EARNINGS_COVERING_EXPIRY_MISSING" : "NO_STRADDLE_EXPIRY");
        }

        BigDecimal atmStrike = findAtmStrikeWithBothLegs(straddleContracts.isEmpty() ? oiContracts : straddleContracts, spot);
        List<OptionContractRecord> atmPool = straddleContracts.isEmpty() ? oiContracts : straddleContracts;
        if (atmStrike != null) {
            entity.setAtmStrike(atmStrike);
            OptionContractRecord atmCall = findLeg(atmPool, atmStrike, "call");
            OptionContractRecord atmPut = findLeg(atmPool, atmStrike, "put");
            OptionSnapshotRecord callSnap = atmCall == null ? null : snapshots.get(atmCall.contractSymbol());
            OptionSnapshotRecord putSnap = atmPut == null ? null : snapshots.get(atmPut.contractSymbol());

            BigDecimal callPrice = resolveOptionMidOrClose(atmCall, callSnap);
            BigDecimal putPrice = resolveOptionMidOrClose(atmPut, putSnap);
            entity.setAtmCallPrice(callPrice);
            entity.setAtmPutPrice(putPrice);

            BigDecimal callIv = callSnap == null ? null : validIv(callSnap.impliedVolatility());
            BigDecimal putIv = putSnap == null ? null : validIv(putSnap.impliedVolatility());
            BigDecimal atmIv = averagePositive(callIv, putIv);
            entity.setAtmIv(atmIv);
            if (atmIv != null && priceCtx.hv20() != null && priceCtx.hv20().signum() > 0) {
                entity.setIvToHvRatio(atmIv.divide(priceCtx.hv20(), 4, RoundingMode.HALF_UP));
            }

            if (!isEtf && straddleExp != null) {
                if (callPrice != null && putPrice != null && callPrice.signum() > 0 && putPrice.signum() > 0) {
                    BigDecimal impliedMove = callPrice.add(putPrice, MC).divide(spot, 6, RoundingMode.HALF_UP);
                    entity.setImpliedMovePct(impliedMove);
                    entity.setImpliedMoveStatus(StockOptionsSummary.STATUS_AVAILABLE);
                    entity.setImpliedMoveReason(nextEarnings != null ? "EARNINGS_STRADDLE" : "NEAREST_EXPIRY_REFERENCE");
                } else {
                    entity.setImpliedMoveStatus(StockOptionsSummary.STATUS_INSUFFICIENT_DATA);
                    entity.setImpliedMoveReason("ATM_TWO_SIDED_QUOTE_MISSING");
                }
            }
        } else if (entity.getImpliedMoveStatus() == null) {
            entity.setImpliedMoveStatus(isEtf ? StockOptionsSummary.STATUS_NOT_APPLICABLE : StockOptionsSummary.STATUS_INSUFFICIENT_DATA);
            entity.setImpliedMoveReason(isEtf ? "NOT_APPLICABLE_FOR_ETF" : "ATM_STRIKE_PAIR_MISSING");
        }
        if (entity.getImpliedMoveStatus() == null) {
            entity.setImpliedMoveStatus(isEtf ? StockOptionsSummary.STATUS_NOT_APPLICABLE : StockOptionsSummary.STATUS_INSUFFICIENT_DATA);
            entity.setImpliedMoveReason(isEtf ? "NOT_APPLICABLE_FOR_ETF" : "NO_STRADDLE_EXPIRY");
        }

        // 3. 計算同到期等距虛值 Put IV vs Call IV 偏度（Skew：優先約 -25Δ vs +25Δ，缺 delta 時退約 5%–10% 等距虛值）
        SkewResult skew = computeOtmSkew(oiContracts, snapshots, spot);
        entity.setOtmPutIv(skew.otmPutIv());
        entity.setOtmCallIv(skew.otmCallIv());
        entity.setIvSkewPutMinusCall(skew.skew());

        boolean hasAnyCoreMetric = entity.getMaxCallOiStrike() != null
                || entity.getMaxPutOiStrike() != null
                || entity.getImpliedMovePct() != null
                || entity.getAtmIv() != null;
        if (hasAnyCoreMetric) {
            entity.setStatus(StockOptionsSummary.STATUS_AVAILABLE);
            entity.setReasonCode(isEtf ? "ETF_OPTIONS_SUMMARY_ONLY" : null);
        } else {
            entity.setStatus(StockOptionsSummary.STATUS_INSUFFICIENT_DATA);
            entity.setReasonCode("OPTIONS_METRICS_INCOMPLETE");
        }
        return entity;
    }

    private SkewResult computeOtmSkew(List<OptionContractRecord> contracts, Map<String, OptionSnapshotRecord> snapshots, BigDecimal spot) {
        OptionContractRecord bestPut = null;
        BigDecimal bestPutDist = null;
        BigDecimal bestPutIv = null;
        OptionContractRecord bestCall = null;
        BigDecimal bestCallDist = null;
        BigDecimal bestCallIv = null;

        // 優先按 Greeks delta 找最接近 -0.25Δ 的 OTM Put 與 +0.25Δ 的 OTM Call
        for (OptionContractRecord c : contracts) {
            OptionSnapshotRecord snap = snapshots.get(c.contractSymbol());
            if (snap == null || validIv(snap.impliedVolatility()) == null || snap.delta() == null) {
                continue;
            }
            if ("put".equalsIgnoreCase(c.type()) && c.strikePrice().compareTo(spot) < 0 && snap.delta().signum() < 0) {
                BigDecimal dist = snap.delta().abs().subtract(TARGET_OTM_DELTA, MC).abs();
                if (dist.compareTo(new BigDecimal("0.15")) <= 0 && (bestPutDist == null || dist.compareTo(bestPutDist) < 0)) {
                    bestPut = c;
                    bestPutDist = dist;
                    bestPutIv = validIv(snap.impliedVolatility());
                }
            } else if ("call".equalsIgnoreCase(c.type()) && c.strikePrice().compareTo(spot) > 0 && snap.delta().signum() > 0) {
                BigDecimal dist = snap.delta().subtract(TARGET_OTM_DELTA, MC).abs();
                if (dist.compareTo(new BigDecimal("0.15")) <= 0 && (bestCallDist == null || dist.compareTo(bestCallDist) < 0)) {
                    bestCall = c;
                    bestCallDist = dist;
                    bestCallIv = validIv(snap.impliedVolatility());
                }
            }
        }
        // 若缺少 Greeks delta，以 5%–10% 等距虛值行權價配對（約 7.5% OTM）
        if (bestPut == null || bestCall == null) {
            BigDecimal targetPutStrike = spot.multiply(new BigDecimal("0.925"), MC);
            BigDecimal targetCallStrike = spot.multiply(new BigDecimal("1.075"), MC);
            bestPutDist = null;
            bestCallDist = null;
            bestPutIv = null;
            bestCallIv = null;
            for (OptionContractRecord c : contracts) {
                OptionSnapshotRecord snap = snapshots.get(c.contractSymbol());
                BigDecimal iv = snap == null ? null : validIv(snap.impliedVolatility());
                if (iv == null) continue;
                if ("put".equalsIgnoreCase(c.type()) && c.strikePrice().compareTo(spot) < 0) {
                    BigDecimal dist = c.strikePrice().subtract(targetPutStrike, MC).abs();
                    if (bestPutDist == null || dist.compareTo(bestPutDist) < 0) {
                        bestPutDist = dist;
                        bestPutIv = iv;
                    }
                } else if ("call".equalsIgnoreCase(c.type()) && c.strikePrice().compareTo(spot) > 0) {
                    BigDecimal dist = c.strikePrice().subtract(targetCallStrike, MC).abs();
                    if (bestCallDist == null || dist.compareTo(bestCallDist) < 0) {
                        bestCallDist = dist;
                        bestCallIv = iv;
                    }
                }
            }
        }
        if (bestPutIv == null || bestCallIv == null) {
            return new SkewResult(bestPutIv, bestCallIv, null);
        }
        return new SkewResult(
                bestPutIv.setScale(6, RoundingMode.HALF_UP),
                bestCallIv.setScale(6, RoundingMode.HALF_UP),
                bestPutIv.subtract(bestCallIv, MC).setScale(6, RoundingMode.HALF_UP)
        );
    }

    private record SkewResult(BigDecimal otmPutIv, BigDecimal otmCallIv, BigDecimal skew) {}

    private LocalDate selectPrimaryOiExpiration(TreeMap<LocalDate, List<OptionContractRecord>> byExpiry, LocalDate snapshotDate) {
        LocalDate maxWindow = snapshotDate.plusDays(45);
        LocalDate bestExp = null;
        long bestTotalOi = -1L;
        for (Map.Entry<LocalDate, List<OptionContractRecord>> entry : byExpiry.entrySet()) {
            if (entry.getKey().isAfter(maxWindow)) {
                continue;
            }
            long sumOi = 0L;
            for (OptionContractRecord c : entry.getValue()) {
                if (c.openInterest() != null && c.openInterest() > 0) {
                    sumOi += c.openInterest();
                }
            }
            if (sumOi > bestTotalOi) {
                bestTotalOi = sumOi;
                bestExp = entry.getKey();
            }
        }
        return bestExp != null ? bestExp : (byExpiry.isEmpty() ? null : byExpiry.firstKey());
    }

    /**
     * 財報跨式到期選取：
     * - 盤後財報（AFTER_CLOSE 或未明確盤前）：到期日必須嚴格晚於財報日（避免週五盤後財報誤選當日已到期合約）。
     * - 盤前財報（BEFORE_OPEN）：到期日可為財報當日或之後首個有效到期日。
     */
    LocalDate selectStraddleExpiration(TreeMap<LocalDate, List<OptionContractRecord>> byExpiry,
                                       MarketEvent nextEarnings,
                                       LocalDate snapshotDate,
                                       boolean isEtf) {
        if (byExpiry.isEmpty()) {
            return null;
        }
        if (isEtf || nextEarnings == null || nextEarnings.getEventDate() == null) {
            // 無已知財報時取 >= snapshotDate + 5 天的最近有效到期（避開 0DTE 雜訊）
            LocalDate minExp = snapshotDate.plusDays(5);
            for (LocalDate exp : byExpiry.keySet()) {
                if (!exp.isBefore(minExp)) {
                    return exp;
                }
            }
            return byExpiry.firstKey();
        }
        LocalDate earningsDate = nextEarnings.getEventDate();
        boolean beforeOpen = "BEFORE_OPEN".equalsIgnoreCase(nextEarnings.getTimePrecision());
        for (LocalDate exp : byExpiry.keySet()) {
            if (beforeOpen ? !exp.isBefore(earningsDate) : exp.isAfter(earningsDate)) {
                return exp;
            }
        }
        return null;
    }

    private BigDecimal findAtmStrikeWithBothLegs(List<OptionContractRecord> contracts, BigDecimal spot) {
        Map<BigDecimal, Integer> maskByStrike = new LinkedHashMap<>();
        for (OptionContractRecord c : contracts) {
            int bit = "call".equalsIgnoreCase(c.type()) ? 1 : ("put".equalsIgnoreCase(c.type()) ? 2 : 0);
            maskByStrike.merge(c.strikePrice(), bit, (a, b) -> a | b);
        }
        BigDecimal best = null;
        BigDecimal bestDist = null;
        for (Map.Entry<BigDecimal, Integer> e : maskByStrike.entrySet()) {
            if (e.getValue() != 3) continue;
            BigDecimal dist = e.getKey().subtract(spot, MC).abs();
            if (bestDist == null || dist.compareTo(bestDist) < 0) {
                bestDist = dist;
                best = e.getKey();
            }
        }
        return best;
    }

    private OptionContractRecord findLeg(List<OptionContractRecord> contracts, BigDecimal strike, String type) {
        for (OptionContractRecord c : contracts) {
            if (c.strikePrice().compareTo(strike) == 0 && type.equalsIgnoreCase(c.type())) {
                return c;
            }
        }
        return null;
    }

    private BigDecimal resolveOptionMidOrClose(OptionContractRecord contract, OptionSnapshotRecord snap) {
        if (snap != null && snap.bidPrice() != null && snap.askPrice() != null
                && snap.bidPrice().signum() > 0 && snap.askPrice().compareTo(snap.bidPrice()) >= 0) {
            return snap.bidPrice().add(snap.askPrice(), MC).divide(TWO, 6, RoundingMode.HALF_UP);
        }
        if (snap != null && snap.dailyClose() != null && snap.dailyClose().signum() > 0) {
            return snap.dailyClose().setScale(6, RoundingMode.HALF_UP);
        }
        if (snap != null && snap.lastTradePrice() != null && snap.lastTradePrice().signum() > 0) {
            return snap.lastTradePrice().setScale(6, RoundingMode.HALF_UP);
        }
        if (contract != null && contract.closePrice() != null && contract.closePrice().signum() > 0) {
            return contract.closePrice().setScale(6, RoundingMode.HALF_UP);
        }
        return null;
    }

    private BigDecimal validIv(BigDecimal iv) {
        if (iv == null || iv.signum() <= 0 || iv.compareTo(BigDecimal.valueOf(5)) > 0) {
            return null;
        }
        return iv.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal averagePositive(BigDecimal a, BigDecimal b) {
        if (a != null && b != null) {
            return a.add(b, MC).divide(TWO, 6, RoundingMode.HALF_UP);
        }
        return a != null ? a : b;
    }

    private MarketEvent findNextUpcomingEarnings(String figi, LocalDate fromDate, Instant cutoff) {
        List<MarketEvent> events = marketEventRepository.findVisible(figi, fromDate, fromDate.plusDays(90), cutoff);
        return events.stream()
                .filter(e -> "EARNINGS".equals(e.getEventType()) && figi.equals(e.getFigi()) && e.getEventDate() != null)
                .min(Comparator.comparing(MarketEvent::getEventDate))
                .orElse(null);
    }

    record PriceContext(BigDecimal spotPrice, BigDecimal hv20) {}

    private PriceContext loadSpotAndHv20(Stock stock, LocalDate snapshotDate, boolean intradayOrUnsettledToday, Instant now) {
        String figi = stock.getFigi();
        List<StockQuote> storedQuotes = stockQuoteRepository.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(
                figi, snapshotDate.minusDays(60), snapshotDate);
        BigDecimal hv20 = null;
        BigDecimal localClose = null;
        LocalDate latestQuoteDate = null;
        if (storedQuotes != null && !storedQuotes.isEmpty()) {
            StockQuote latestQuote = storedQuotes.getLast();
            latestQuoteDate = latestQuote.getQuoteDate();
            AnalysisContext ctx = new AnalysisContext(figi, calendar.getSessionCloseAt(latestQuoteDate), now, AnalysisHorizon.SWING);
            MetricResult<NormalizedPriceSeriesDto> norm = quoteNormalizationService.normalize(ctx, latestQuoteDate.minusDays(60), latestQuoteDate);
            if (norm != null && norm.status() == MetricStatus.AVAILABLE && norm.value() != null && !norm.value().bars().isEmpty()) {
                List<NormalizedBarDto> bars = norm.value().bars();
                localClose = bars.getLast().close();
                hv20 = computeAnnualizedHv20(bars);
            } else if (latestQuote.getClosePrice() != null && latestQuote.getClosePrice().signum() > 0) {
                localClose = latestQuote.getClosePrice();
            }
        } else {
            localClose = quoteNormalizationService.findStoredClosePrice(figi, snapshotDate);
            if (localClose != null) {
                latestQuoteDate = snapshotDate;
            }
        }

        // 盤中或當日日線尚未同步時，絕不拿昨日收盤價配對今日即時期權報價；改取當日開市後 Alpaca 即時成交價，取不到則標為資料不足
        if (intradayOrUnsettledToday && !snapshotDate.equals(latestQuoteDate)) {
            BigDecimal liveSpot = alpacaService.getLatestStockPrice(stock.getSymbol(), calendar.getSessionOpenAt(snapshotDate));
            return new PriceContext(liveSpot, hv20);
        }
        return new PriceContext(localClose, hv20);
    }

    BigDecimal computeAnnualizedHv20(List<NormalizedBarDto> bars) {
        if (bars == null || bars.size() < 21) {
            return null;
        }
        int start = bars.size() - 21;
        double[] logReturns = new double[20];
        double sum = 0.0;
        for (int i = 0; i < 20; i++) {
            BigDecimal prev = bars.get(start + i).close();
            BigDecimal curr = bars.get(start + i + 1).close();
            if (prev == null || curr == null || prev.signum() <= 0 || curr.signum() <= 0) {
                return null;
            }
            double lr = Math.log(curr.doubleValue() / prev.doubleValue());
            logReturns[i] = lr;
            sum += lr;
        }
        double mean = sum / 20.0;
        double sq = 0.0;
        for (double lr : logReturns) {
            double diff = lr - mean;
            sq += diff * diff;
        }
        double dailyStd = Math.sqrt(sq / 19.0);
        double annualized = dailyStd * Math.sqrt(252.0);
        if (!Double.isFinite(annualized) || annualized <= 0.0) {
            return null;
        }
        return BigDecimal.valueOf(annualized).setScale(6, RoundingMode.HALF_UP);
    }

    private StockOptionsSummary buildUnavailableEntity(Stock stock, LocalDate snapshotDate, Instant now, String status, String reason, boolean isEtf) {
        StockOptionsSummary entity = new StockOptionsSummary();
        entity.setFigi(stock.getFigi());
        entity.setSnapshotDate(snapshotDate);
        entity.setSymbol(stock.getSymbol());
        entity.setSource(SOURCE_ALPACA);
        entity.setFeed(alpacaService.getOptionsFeed());
        entity.setImpliedMoveStatus(isEtf ? StockOptionsSummary.STATUS_NOT_APPLICABLE : status);
        entity.setImpliedMoveReason(isEtf ? "NOT_APPLICABLE_FOR_ETF" : reason);
        entity.setStatus(status);
        entity.setReasonCode(reason);
        entity.setObservedAt(now);
        return entity;
    }

    private BigDecimal computeStopDistancePct(BigDecimal price, BigDecimal stop) {
        if (price == null || stop == null || price.signum() <= 0 || stop.signum() <= 0 || stop.compareTo(price) >= 0) {
            return null;
        }
        return price.subtract(stop, MC).divide(price, 6, RoundingMode.HALF_UP);
    }

    private OptionsSummaryDto toDto(StockOptionsSummary e, List<StockOptionsSummary> history, BigDecimal stopDistancePct, LocalDate evaluationDate) {
        LocalDate refDate = evaluationDate != null ? evaluationDate : e.getSnapshotDate();
        Integer daysToEarnings = null;
        if (e.getNextEarningsDate() != null && refDate != null && !e.getNextEarningsDate().isBefore(refDate)) {
            daysToEarnings = (int) ChronoUnit.DAYS.between(refDate, e.getNextEarningsDate());
        }
        // 僅在財報尚未過去、距評估日 <= 14 天、跨式到期日仍覆蓋該財報且快照未過期時才亮出財報跳空打穿止損警示
        boolean snapshotFresh = e.getSnapshotDate() != null && refDate != null
                && !e.getSnapshotDate().isBefore(refDate.minusDays(7));
        boolean straddleCoversEarnings = e.getStraddleExpiration() != null && e.getNextEarningsDate() != null
                && !e.getStraddleExpiration().isBefore(e.getNextEarningsDate());
        boolean gapRisk = snapshotFresh && straddleCoversEarnings
                && daysToEarnings != null && daysToEarnings >= 0 && daysToEarnings <= 14
                && e.getImpliedMovePct() != null && stopDistancePct != null
                && e.getImpliedMovePct().compareTo(stopDistancePct) > 0;

        // 誠實呈現短視窗歷史 IV 分位與實際樣本筆數（不把 5–20 筆歷史偽裝成 252 日 IV Rank）
        List<BigDecimal> ivSamples = history == null ? List.of()
                : history.stream().map(StockOptionsSummary::getAtmIv).filter(v -> v != null && v.signum() > 0).toList();
        BigDecimal shortPctile = null;
        if (e.getAtmIv() != null && ivSamples.size() >= 5) {
            BigDecimal minIv = ivSamples.stream().min(BigDecimal::compareTo).orElse(null);
            BigDecimal maxIv = ivSamples.stream().max(BigDecimal::compareTo).orElse(null);
            if (minIv != null && maxIv != null && maxIv.compareTo(minIv) > 0) {
                shortPctile = e.getAtmIv().subtract(minIv, MC)
                        .divide(maxIv.subtract(minIv, MC), 4, RoundingMode.HALF_UP);
            }
        }

        return new OptionsSummaryDto(
                e.getFigi(),
                e.getSymbol(),
                e.getSnapshotDate(),
                e.getSource(),
                e.getFeed(),
                e.getStatus(),
                e.getReasonCode(),
                e.getSpotPrice(),
                e.getQuoteTimestamp(),
                e.getNextEarningsDate(),
                daysToEarnings,
                e.getStraddleExpiration(),
                e.getAtmStrike(),
                e.getAtmCallPrice(),
                e.getAtmPutPrice(),
                e.getImpliedMovePct(),
                e.getImpliedMoveStatus(),
                e.getImpliedMoveReason(),
                stopDistancePct,
                gapRisk,
                e.getAtmIv(),
                e.getHv20(),
                e.getIvToHvRatio(),
                ivSamples.size(),
                shortPctile,
                e.getPutCallOiRatio(),
                e.getPutCallVolRatio(),
                e.getOtmPutIv(),
                e.getOtmCallIv(),
                e.getIvSkewPutMinusCall(),
                e.getOiWallExpiration(),
                e.getMaxCallOiStrike(),
                e.getMaxCallOi(),
                e.getMaxPutOiStrike(),
                e.getMaxPutOi(),
                e.getTotalCallOi(),
                e.getTotalPutOi(),
                e.getOpenInterestDate(),
                e.getVolumeSessionDate(),
                OI_DISCLAIMER_CODE,
                e.getObservedAt()
        );
    }
}
