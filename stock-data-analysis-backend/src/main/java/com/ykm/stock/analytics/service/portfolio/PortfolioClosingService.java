package com.ykm.stock.analytics.service.portfolio;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.BriefingRadarItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.BriefingSectorDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.HoldingHealthItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.InAppNotificationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioAlertDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.WatchlistOpportunityDto;
import com.ykm.stock.analytics.model.entity.DailyClosingBriefing;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.InAppNotification;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.repository.jpa.DailyClosingBriefingRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.InAppNotificationRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService;
import com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService.SectorBreadthStatus;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator.SizingInputs;
import com.ykm.stock.analytics.service.portfolio.PortfolioHealthCheckService.HealthEvaluationOutput;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 站內主動提醒中心與每日收盤簡報服務（首版純站內持久化，不接外部 Email／Webhook）。
 * 發布寫入一律採獨立 PROPAGATION_REQUIRES_NEW 短交易，確保異常時僅回滾本批簡報與提醒，絕不影響上游估值與雷達資料。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioClosingService {
    public static final String ALERT_MARKET_REGIME_SHIFT = "MARKET_REGIME_SHIFT";
    public static final String ALERT_HY_OAS_CREDIT_STRESS = "HY_OAS_CREDIT_STRESS";
    public static final String ALERT_RADAR_TOP_OPPORTUNITY = "RADAR_TOP_OPPORTUNITY";
    public static final String ALERT_DAILY_CLOSING_BRIEFING = "DAILY_CLOSING_BRIEFING";

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int RADAR_TOP_TOTAL = 5;
    private static final int RADAR_VALUE_QUOTA = 3;
    private static final int RADAR_STARTER_QUOTA = 2;

    private final PortfolioHealthCheckService healthCheckService;
    private final DailyClosingBriefingRepository briefingRepository;
    private final InAppNotificationRepository notificationRepository;
    private final DailyOpportunityRadarRunRepository radarRunRepository;
    private final DailyOpportunityRadarRepository radarRepository;
    private final SectorMoneyFlowDailyRepository sectorMoneyFlowRepository;
    private final PositionSizingCalculator positionSizingCalculator;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    /**
     * 掛載於 StockValuationDailyTask 步驟 4 尾端執行：
     * 1. 若當日雷達尚未正式發布（例如大盤行情未齊而延後），延後生成簡報，避免凍結殘缺快照；若已發布（含零入選日）則正常發布。
     * 2. 若當日簡報已凍結存在，直接沿用不覆寫既有簡報與通知已讀狀態。
     * 3. 全程透過獨立 REQUIRES_NEW 交易原子提交簡報與提醒。
     */
    public Optional<DailyClosingBriefingDto> publishDailyClosing(LocalDate tradeDate, Instant observationCutoff) {
        if (tradeDate == null) {
            return Optional.empty();
        }
        Optional<DailyClosingBriefing> existingBefore = briefingRepository.findById(tradeDate);
        if (existingBefore.isPresent()) {
            log.info("Daily closing briefing already published | tradeDate={}", tradeDate);
            return Optional.of(toBriefingDto(existingBefore.get()));
        }

        // 區分「雷達正式零入選」與「雷達因大盤未齊延後發布」：無 run 紀錄即代表尚未正式發布，延後簡報以免凍結空榜
        Optional<DailyOpportunityRadarRun> radarRunOpt = radarRunRepository.findById(tradeDate);
        if (radarRunOpt.isEmpty()) {
            log.warn("Daily closing briefing deferred: radar run not published yet | tradeDate={}", tradeDate);
            return Optional.empty();
        }
        DailyOpportunityRadarRun radarRun = radarRunOpt.get();
        Instant obsCutoff = observationCutoff != null ? observationCutoff : dateTimeService.now();

        // 1. 執行持倉與自選自動體檢（純本地唯讀，0 次外部 API）
        HealthEvaluationOutput healthOutput = healthCheckService.evaluate(
                tradeDate, obsCutoff, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT);
        PortfolioHealthSummaryDto healthSummary = healthOutput.summary();

        // 2. 彙整大盤總經狀態（VIX / 10Y / MarketRegime 切換）
        String currentRegime = radarRun.getMarketRegime() != null
                ? radarRun.getMarketRegime() : healthSummary.marketRegime();
        MarketRegime parsedRegime = parseMarketRegime(currentRegime);
        PreloadedMarketData preloaded = healthOutput.preloadedMarket();
        BigDecimal vixClose = preloaded != null && preloaded.vixClose() != null
                && preloaded.vixClose().status() == MetricStatus.AVAILABLE && preloaded.vixClose().value() != null
                ? preloaded.vixClose().value().setScale(4, RoundingMode.HALF_UP) : null;
        BigDecimal treasury10y = preloaded != null && preloaded.latestTreasury10y() != null
                ? preloaded.latestTreasury10y().setScale(4, RoundingMode.HALF_UP) : null;

        Optional<DailyClosingBriefing> previousOpt = briefingRepository.findTopByTradeDateBeforeOrderByTradeDateDesc(tradeDate);
        String previousRegime = previousOpt.map(DailyClosingBriefing::getMarketRegime).orElse(null);
        LocalDate previousTradeDate = previousOpt.map(DailyClosingBriefing::getTradeDate).orElse(null);
        boolean regimeChanged = isMeaningfulRegimeChange(previousRegime, currentRegime);

        // 3. 讀取當日板塊量價資金流（CMF20d 最強／最弱前 2 大，並建立同日產業對照表供雷達倉位與廣度去重共用）
        List<SectorMoneyFlowDaily> sectorFlows = sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(tradeDate);
        Map<String, SectorMoneyFlowDaily> sectorFlowByIndustry = new LinkedHashMap<>();
        if (sectorFlows != null) {
            for (SectorMoneyFlowDaily row : sectorFlows) {
                if (row != null && row.getIndustryCode() != null) {
                    sectorFlowByIndustry.putIfAbsent(row.getIndustryCode(), row);
                }
            }
        }
        List<BriefingSectorDto> topSectors = selectTopSectors(sectorFlows, 2);
        List<BriefingSectorDto> bottomSectors = selectBottomSectors(sectorFlows, topSectors, 2);

        // 4. 讀取當日全市場每日雷達精選前 5 名（主清單最多 3 + 次清單最多 2，缺額遞補）並計算建議買入股數
        List<DailyOpportunityRadar> radarRows = radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(tradeDate);
        List<BriefingRadarItemDto> radarTop5 = selectRadarTop5(radarRows, parsedRegime, healthOutput, sectorFlowByIndustry, tradeDate);

        // 5. 構建待寫入之分級站內提醒（簡報統計僅計實際業務警示，不含簡報已就緒本身）
        Instant generatedAt = dateTimeService.now();
        List<InAppNotification> notifications = new ArrayList<>();
        int criticalBiz = 0;
        int warningBiz = 0;
        int infoBiz = 0;

        // 5a. 大盤總經狀態切換警報
        if (regimeChanged) {
            String shiftSeverity = regimeShiftSeverity(parsedRegime);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("previousRegime", previousRegime);
            payload.put("currentRegime", currentRegime);
            if (previousTradeDate != null) {
                payload.put("previousTradeDate", previousTradeDate.toString());
            }
            if (vixClose != null) {
                payload.put("vixClose", vixClose);
            }
            if (treasury10y != null) {
                payload.put("treasury10y", treasury10y);
            }
            notifications.add(buildNotification(
                    "MACRO_REGIME:" + tradeDate + ":" + previousRegime + "_TO_" + currentRegime,
                    tradeDate, shiftSeverity, InAppNotification.CATEGORY_MACRO_REGIME,
                    ALERT_MARKET_REGIME_SHIFT, null, null, null, payload, generatedAt));
            if (InAppNotification.SEVERITY_CRITICAL.equals(shiftSeverity)) criticalBiz++;
            else if (InAppNotification.SEVERITY_WARNING.equals(shiftSeverity)) warningBiz++;
            else infoBiz++;
        }

        // 5a2. 債市高收益債信用利差（FRED BAMLH0A0HYM2）急擴或突破警戒門檻通知（按觀測日＋狀態冪等去重，避免延遲觀測每日重複轟炸）
        CreditSpreadSummaryDto creditSpread = preloaded == null ? null : preloaded.creditSpread();
        if (creditSpread != null && creditSpread.macroCautionTriggered() && !creditSpread.stale() && creditSpread.observationDate() != null) {
            String csSeverity = "STRESS".equals(creditSpread.status())
                    ? InAppNotification.SEVERITY_CRITICAL : InAppNotification.SEVERITY_WARNING;
            Map<String, Object> csPayload = new LinkedHashMap<>();
            csPayload.put("seriesId", creditSpread.seriesId());
            csPayload.put("status", creditSpread.status());
            csPayload.put("observationDate", creditSpread.observationDate().toString());
            if (creditSpread.oasPct() != null) csPayload.put("oasPct", creditSpread.oasPct());
            if (creditSpread.deltaVsPrior20Bps() != null) csPayload.put("deltaBps", creditSpread.deltaVsPrior20Bps());
            notifications.add(buildNotification(
                    "MACRO_HY_OAS:" + creditSpread.observationDate() + ":" + creditSpread.status(),
                    tradeDate, csSeverity, InAppNotification.CATEGORY_MACRO_REGIME,
                    ALERT_HY_OAS_CREDIT_STRESS, null, null, null, csPayload, generatedAt));
            if (InAppNotification.SEVERITY_CRITICAL.equals(csSeverity)) criticalBiz++;
            else warningBiz++;
        }

        // 5b. 持倉體檢異常警示
        for (HoldingHealthItemDto holding : healthSummary.holdings()) {
            for (PortfolioAlertDto alert : holding.alerts()) {
                notifications.add(buildNotification(
                        "HOLDING:" + tradeDate + ":" + holding.figi() + ":" + alert.alertCode(),
                        tradeDate, alert.severity(), InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        alert.alertCode(), holding.figi(), holding.symbol(), holding.stockName(),
                        alert.payload(), generatedAt));
                if (InAppNotification.SEVERITY_CRITICAL.equals(alert.severity())) criticalBiz++;
                else if (InAppNotification.SEVERITY_WARNING.equals(alert.severity())) warningBiz++;
                else infoBiz++;
            }
        }

        // 5c. 產業板塊警示：集中度超標維持每日提醒；板塊廣度崩壞採無狀態跨波段／換倉抑制（首次進入、確認修復後新波段、或 S_now 與 S_0 完全無交集才發通知）
        for (PortfolioAlertDto sectorAlert : healthSummary.sectorAlerts()) {
            String indCode = String.valueOf(sectorAlert.payload().getOrDefault("industryCode", "UNKNOWN"));
            if (PortfolioHealthCheckService.ALERT_HOLDING_SECTOR_BREADTH_BREAKDOWN.equals(sectorAlert.alertCode())) {
                if (!shouldEmitSectorBreadthNotification(indCode, tradeDate, sectorAlert.payload())) {
                    continue;
                }
                notifications.add(buildNotification(
                        "SECTOR_BREADTH:" + tradeDate + ":" + indCode,
                        tradeDate, sectorAlert.severity(), InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        sectorAlert.alertCode(), null, null, sectorAlert.stockName(),
                        sectorAlert.payload(), generatedAt));
                warningBiz++;
            } else {
                notifications.add(buildNotification(
                        "SECTOR:" + tradeDate + ":" + indCode,
                        tradeDate, sectorAlert.severity(), InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        sectorAlert.alertCode(), null, null, sectorAlert.stockName(),
                        sectorAlert.payload(), generatedAt));
                warningBiz++;
            }
        }

        // 5d. 自選股觸發有效買點通知
        for (WatchlistOpportunityDto opp : healthSummary.watchlistOpportunities()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("triggerType", opp.triggerType());
            payload.put("closePrice", opp.closePrice());
            if (opp.entryLower() != null) payload.put("entryLower", opp.entryLower());
            if (opp.entryUpper() != null) payload.put("entryUpper", opp.entryUpper());
            if (opp.protectiveStop() != null) payload.put("protectiveStop", opp.protectiveStop());
            if (opp.positionSizing() != null && opp.positionSizing().suggestedShares() != null) {
                payload.put("suggestedShares", opp.positionSizing().suggestedShares());
                payload.put("positionCapitalPct", opp.positionSizing().positionCapitalPct());
                payload.put("scaleLabel", opp.positionSizing().scaleLabel());
                if (!opp.positionSizing().sizingTags().isEmpty()) {
                    payload.put("sizingTags", opp.positionSizing().sizingTags());
                }
            }
            notifications.add(buildNotification(
                    "WATCHLIST:" + tradeDate + ":" + opp.figi() + ":" + opp.triggerType(),
                    tradeDate, InAppNotification.SEVERITY_INFO, InAppNotification.CATEGORY_WATCHLIST_ENTRY,
                    opp.triggerType(), opp.figi(), opp.symbol(), opp.stockName(),
                    payload, generatedAt));
            infoBiz++;
        }

        // 5e. 每日機會雷達高優先訊號（非逆風僅觀察之精選前 5 名）
        for (BriefingRadarItemDto r : radarTop5) {
            if ("WAIT_FOR_MARKET_STABILIZATION".equals(r.stance())) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("trackType", r.trackType());
            payload.put("verdict", r.verdict());
            payload.put("closePrice", r.closePrice());
            payload.put("planActionable", r.planActionable());
            if (r.planStatus() != null) payload.put("planStatus", r.planStatus());
            if (r.discountToMid() != null) payload.put("discountToMid", r.discountToMid());
            if (r.protectiveStop() != null) payload.put("protectiveStop", r.protectiveStop());
            if (r.suggestedShares() != null) payload.put("suggestedShares", r.suggestedShares());
            if (r.positionFraction() != null) payload.put("positionFraction", r.positionFraction());
            notifications.add(buildNotification(
                    "RADAR:" + tradeDate + ":" + r.trackType() + ":" + r.figi(),
                    tradeDate, InAppNotification.SEVERITY_INFO, InAppNotification.CATEGORY_RADAR_OPPORTUNITY,
                    ALERT_RADAR_TOP_OPPORTUNITY, r.figi(), r.symbol(), r.stockName(),
                    payload, generatedAt));
            infoBiz++;
        }

        // 5f. 每日收盤簡報已就緒總覽通知（不計入 critical/warning/info 業務警示數）
        Map<String, Object> briefingPayload = new LinkedHashMap<>();
        briefingPayload.put("tradeDate", tradeDate.toString());
        briefingPayload.put("marketRegime", currentRegime);
        briefingPayload.put("criticalCount", criticalBiz);
        briefingPayload.put("warningCount", warningBiz);
        briefingPayload.put("infoCount", infoBiz);
        briefingPayload.put("radarTop5Count", radarTop5.size());
        briefingPayload.put("watchlistCount", healthSummary.watchlistOpportunities().size());
        briefingPayload.put("structureHealthy", healthSummary.structureHealthy());
        notifications.add(buildNotification(
                "BRIEFING:" + tradeDate,
                tradeDate,
                criticalBiz > 0 ? InAppNotification.SEVERITY_CRITICAL
                        : (warningBiz > 0 ? InAppNotification.SEVERITY_WARNING : InAppNotification.SEVERITY_INFO),
                InAppNotification.CATEGORY_CLOSING_BRIEFING,
                ALERT_DAILY_CLOSING_BRIEFING, null, null, null, briefingPayload, generatedAt));

        DailyClosingBriefing briefing = new DailyClosingBriefing();
        briefing.setTradeDate(tradeDate);
        briefing.setMarketRegime(currentRegime);
        briefing.setPreviousMarketRegime(previousRegime);
        briefing.setPreviousTradeDate(previousTradeDate);
        briefing.setRegimeChanged(regimeChanged);
        briefing.setVixClose(vixClose);
        briefing.setTreasury10y(treasury10y);
        briefing.setTopSectors(topSectors);
        briefing.setBottomSectors(bottomSectors);
        briefing.setPortfolioHealth(healthSummary);
        briefing.setWatchlistOpportunities(healthSummary.watchlistOpportunities());
        briefing.setRadarTop5(radarTop5);
        briefing.setCriticalCount(criticalBiz);
        briefing.setWarningCount(warningBiz);
        briefing.setInfoCount(infoBiz);
        briefing.setObservationCutoff(obsCutoff);
        briefing.setGeneratedAt(generatedAt);

        // 6. 獨立 PROPAGATION_REQUIRES_NEW 短交易提交，絕不與上游估值／雷達共用交易邊界；並批次去重與承接併發主鍵競態
        TransactionTemplate requiresNewTx = new TransactionTemplate(transactions.getTransactionManager());
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        DailyClosingBriefing persisted;
        try {
            persisted = requiresNewTx.execute(tx -> {
                Optional<DailyClosingBriefing> concurrent = briefingRepository.findById(tradeDate);
                if (concurrent.isPresent()) {
                    return concurrent.get();
                }
                entityManager.persist(briefing);
                List<String> candidateKeys = notifications.stream()
                        .map(InAppNotification::getDedupKey)
                        .distinct()
                        .toList();
                Set<String> existingKeys = candidateKeys.isEmpty()
                        ? Set.of()
                        : new HashSet<>(notificationRepository.findExistingDedupKeys(candidateKeys));
                Set<String> persistedInBatch = new HashSet<>(existingKeys);
                for (InAppNotification notif : notifications) {
                    if (persistedInBatch.add(notif.getDedupKey())) {
                        entityManager.persist(notif);
                    }
                }
                entityManager.flush();
                return briefing;
            });
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            Optional<DailyClosingBriefing> winner = briefingRepository.findById(tradeDate);
            if (winner.isPresent()) {
                log.info("Daily closing briefing already published concurrently | tradeDate={}", tradeDate);
                return Optional.of(toBriefingDto(winner.get()));
            }
            throw ex;
        }

        log.info("Daily closing briefing published | tradeDate={} regime={} critical={} warning={} info={} radarTop5={}",
                tradeDate, currentRegime, criticalBiz, warningBiz, infoBiz, radarTop5.size());
        return Optional.ofNullable(persisted).map(this::toBriefingDto);
    }

    @Transactional(readOnly = true)
    public List<InAppNotificationDto> listNotifications(String severity, String category, boolean unreadOnly, Integer limit) {
        int effectiveLimit = limit == null ? DEFAULT_LIST_LIMIT : Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        String normSeverity = normalizeFilter(severity);
        String normCategory = normalizeFilter(category);
        return notificationRepository.findFiltered(normSeverity, normCategory, unreadOnly, PageRequest.of(0, effectiveLimit))
                .stream()
                .map(this::toNotificationDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUnread() {
        return notificationRepository.countByReadFalse();
    }

    @Transactional
    public boolean markRead(Long id) {
        if (id == null) {
            return false;
        }
        int updated = notificationRepository.markReadIfUnread(id, dateTimeService.now());
        return updated > 0;
    }

    @Transactional
    public int markAllRead() {
        return notificationRepository.markAllRead(dateTimeService.now());
    }

    @Transactional(readOnly = true)
    public Optional<DailyClosingBriefingDto> getLatestBriefing() {
        return briefingRepository.findTopByOrderByTradeDateDesc().map(this::toBriefingDto);
    }

    /** 取得首頁「持倉與自選股今日體檢警示卡」資料：預設讀取最新已發布收盤簡報快照，live=true 或尚未發布時按目前持倉即時唯讀重算（不產生通知、不呼外部 API）。 */
    public PortfolioHealthSummaryDto getHealthSummary(boolean live) {
        if (!live) {
            Optional<DailyClosingBriefing> latest = briefingRepository.findTopByOrderByTradeDateDesc();
            if (latest.isPresent() && latest.get().getPortfolioHealth() != null) {
                return latest.get().getPortfolioHealth();
            }
        }
        Instant now = dateTimeService.now();
        return healthCheckService.evaluate(null, now, PortfolioHealthCheckService.SOURCE_MODE_LIVE).summary();
    }

    private List<BriefingSectorDto> selectTopSectors(List<SectorMoneyFlowDaily> rows, int limit) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<SectorMoneyFlowDaily> valid = rows.stream()
                .filter(r -> r.getCmf20d() != null)
                .sorted(Comparator.comparing(SectorMoneyFlowDaily::getCmf20d, Comparator.reverseOrder())
                        .thenComparing(SectorMoneyFlowDaily::getDollarVolume1d, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SectorMoneyFlowDaily::getIndustryCode))
                .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = valid.size() >= limit * 2 ? limit : Math.max(1, (valid.size() + 1) / 2);
        return valid.stream()
                .limit(effectiveLimit)
                .map(this::toBriefingSector)
                .toList();
    }

    private List<BriefingSectorDto> selectBottomSectors(List<SectorMoneyFlowDaily> rows,
                                                        List<BriefingSectorDto> topSectors,
                                                        int limit) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<String> topCodes = new LinkedHashSet<>();
        for (BriefingSectorDto s : topSectors) {
            topCodes.add(s.industryCode());
        }
        return rows.stream()
                .filter(r -> r.getCmf20d() != null && !topCodes.contains(r.getIndustryCode()))
                .sorted(Comparator.comparing(SectorMoneyFlowDaily::getCmf20d)
                        .thenComparing(SectorMoneyFlowDaily::getDollarVolume1d, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SectorMoneyFlowDaily::getIndustryCode))
                .limit(limit)
                .map(this::toBriefingSector)
                .toList();
    }

    private BriefingSectorDto toBriefingSector(SectorMoneyFlowDaily s) {
        return new BriefingSectorDto(
                s.getIndustryCode(),
                s.getIndustryName(),
                s.getCmf20d(),
                s.getDollarVolume1d(),
                s.getBreadthAboveSma50Pct(),
                s.getFlowState()
        );
    }

    /** 雙軌雷達精選前 5 名：主清單按折價與 R/R 最多 3 名 + 次清單按回踩距離與 PEG 最多 2 名，任一軌不足由另一軌遞補至最多 5 名。 */
    private List<BriefingRadarItemDto> selectRadarTop5(List<DailyOpportunityRadar> rows,
                                                       MarketRegime regime,
                                                       HealthEvaluationOutput healthOutput,
                                                       Map<String, SectorMoneyFlowDaily> sectorFlowByIndustry,
                                                       LocalDate tradeDate) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DailyOpportunityRadar> valueSorted = rows.stream()
                .filter(r -> DailyOpportunityRadar.TRACK_VALUE_SUPPORT.equals(r.getTrackType()))
                .sorted(Comparator.comparing(DailyOpportunityRadar::getDiscountToMid, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DailyOpportunityRadar::getNetRiskReward, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DailyOpportunityRadar::getSymbol))
                .toList();
        List<DailyOpportunityRadar> starterSorted = rows.stream()
                .filter(r -> DailyOpportunityRadar.TRACK_TREND_PULLBACK_STARTER.equals(r.getTrackType()))
                .sorted(Comparator.comparing(DailyOpportunityRadar::getSupportDistancePct, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DailyOpportunityRadar::getForwardPeg, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(DailyOpportunityRadar::getSymbol))
                .toList();

        List<DailyOpportunityRadar> picked = new ArrayList<>(RADAR_TOP_TOTAL);
        Set<String> seenFigis = new LinkedHashSet<>();

        int vLimit = Math.min(RADAR_VALUE_QUOTA, valueSorted.size());
        for (int i = 0; i < vLimit; i++) {
            DailyOpportunityRadar r = valueSorted.get(i);
            if (seenFigis.add(r.getFigi())) picked.add(r);
        }
        int sLimit = Math.min(RADAR_STARTER_QUOTA, starterSorted.size());
        for (int i = 0; i < sLimit; i++) {
            DailyOpportunityRadar r = starterSorted.get(i);
            if (seenFigis.add(r.getFigi())) picked.add(r);
        }
        // 任一軌不足配額時由另一軌依序遞補至最多 5 名
        for (int i = vLimit; i < valueSorted.size() && picked.size() < RADAR_TOP_TOTAL; i++) {
            DailyOpportunityRadar r = valueSorted.get(i);
            if (seenFigis.add(r.getFigi())) picked.add(r);
        }
        for (int i = sLimit; i < starterSorted.size() && picked.size() < RADAR_TOP_TOTAL; i++) {
            DailyOpportunityRadar r = starterSorted.get(i);
            if (seenFigis.add(r.getFigi())) picked.add(r);
        }

        List<BriefingRadarItemDto> result = new ArrayList<>(picked.size());
        for (DailyOpportunityRadar r : picked) {
            boolean isStarter = DailyOpportunityRadar.TRACK_TREND_PULLBACK_STARTER.equals(r.getTrackType());
            BigDecimal displayEntryLower = isStarter
                    ? r.getPullbackAnchorPrice()
                    : (r.getPlanEntryLower() != null ? r.getPlanEntryLower() : r.getSupportLower());
            BigDecimal displayEntryUpper = isStarter
                    ? r.getPullbackAnchorPrice()
                    : (r.getPlanEntryUpper() != null ? r.getPlanEntryUpper() : r.getSupportUpper());
            // 主清單須採用雷達寫入之實際匹配計畫入場上限（區分區間限價與確認回踩上限），缺該欄位之舊快照省略精確股數以免高估
            BigDecimal sizingEntryUpper = isStarter ? r.getPullbackAnchorPrice() : r.getPlanEntryUpper();
            BigDecimal existingMv = healthOutput.holdingMarketValueUsdByFigi().getOrDefault(r.getFigi(), BigDecimal.ZERO);
            BigDecimal sectorMv = r.getIndustryCode() == null ? BigDecimal.ZERO
                    : healthOutput.sectorMarketValueUsdByIndustry().getOrDefault(r.getIndustryCode(), BigDecimal.ZERO);
            boolean actionable = !r.isHeadwindWatchOnly() && (isStarter || "ACTIVE".equals(r.getPlanStatus()));

            boolean sectorBreadthBreakdown = SectorMoneyFlowService.isSameDayBreadthBreakdown(
                    r.getIndustryCode() == null ? null : sectorFlowByIndustry.get(r.getIndustryCode()),
                    tradeDate);

            PositionSizingDto sizing = null;
            if (healthOutput.unverifiedHoldingFigis().contains(r.getFigi()) && "USD".equalsIgnoreCase(r.getCurrency())) {
                sizing = PositionSizingDto.notApplicable(
                        "EXISTING_HOLDING_VALUE_UNVERIFIED",
                        healthOutput.config().totalCapital(),
                        healthOutput.config().riskPerTradePct(),
                        healthOutput.config().maxSinglePositionPct(),
                        healthOutput.config().maxSectorExposurePct(),
                        r.getIndustryCode());
            } else if (sizingEntryUpper != null && r.getProtectiveStop() != null) {
                sizing = positionSizingCalculator.calculate(new SizingInputs(
                        r.getCurrency(),
                        r.getIndustryCode(),
                        sizingEntryUpper,
                        r.getClosePrice(),
                        r.getProtectiveStop(),
                        actionable,
                        false,
                        isStarter,
                        regime,
                        r.isMacroCautionActive(),
                        sectorBreadthBreakdown,
                        healthOutput.config(),
                        existingMv,
                        sectorMv,
                        healthOutput.excludedHoldingsCount()
                ));
            }
            String effectivePositionFraction = isStarter && sizing != null && "1/6".equals(sizing.scaleLabel())
                    ? "1/6" : r.getPositionFraction();
            result.add(new BriefingRadarItemDto(
                    r.getFigi(),
                    r.getSymbol(),
                    r.getStockName(),
                    r.getCurrency(),
                    r.getTrackType(),
                    r.getIndustryCode(),
                    r.getIndustryName(),
                    r.getVerdict(),
                    r.getStance(),
                    r.getClosePrice(),
                    r.getDiscountToMid(),
                    displayEntryLower,
                    displayEntryUpper,
                    r.getProtectiveStop(),
                    r.getFirstTargetPrice(),
                    r.getNetRiskReward(),
                    r.getPullbackAnchor(),
                    effectivePositionFraction,
                    r.getPlanStatus(),
                    actionable,
                    sizing != null ? sizing.suggestedShares() : null,
                    sizing != null ? sizing.positionCapitalPct() : null,
                    sizing != null ? sizing.sizingTags() : List.of()
            ));
        }
        return result;
    }

    /**
     * 無狀態板塊廣度崩壞通知去重判定：
     * 1. 無前期通知 -> 發送；
     * 2. 上次通知日 T_0 至今日 D 之間存在至少一日通過門檻且未崩壞（確認修復） -> 新波段發送；
     * 3. 同一未修復波段內，當前持倉 S_now 與上次通知快照 S_0 完全無交集（原倉已全數換血） -> 發送；否則靜默。
     */
    private boolean shouldEmitSectorBreadthNotification(String industryCode,
                                                        LocalDate tradeDate,
                                                        Map<String, Object> currentPayload) {
        Optional<InAppNotification> lastPriorOpt = notificationRepository
                .findTopByAlertCodeAndTradeDateLessThanAndDedupKeyEndingWithOrderByTradeDateDescIdDesc(
                        PortfolioHealthCheckService.ALERT_HOLDING_SECTOR_BREADTH_BREAKDOWN,
                        tradeDate,
                        ":" + industryCode);
        if (lastPriorOpt == null || lastPriorOpt.isEmpty() || lastPriorOpt.get().getTradeDate() == null) {
            return true;
        }
        InAppNotification lastPrior = lastPriorOpt.get();
        // 先判完全換倉（零額外 DB 查詢）；若歷史快照無 positionIds 而目前已有新倉亦視為不重疊
        Set<String> priorPositionIds = extractStringSet(lastPrior.getPayload(), "positionIds");
        Set<String> currentPositionIds = extractStringSet(currentPayload, "positionIds");
        if (!currentPositionIds.isEmpty()
                && (priorPositionIds.isEmpty() || Collections.disjoint(currentPositionIds, priorPositionIds))) {
            return true;
        }
        List<SectorMoneyFlowDaily> between = sectorMoneyFlowRepository
                .findByIndustryCodeAndTradeDateGreaterThanAndTradeDateLessThanOrderByTradeDateAsc(
                        industryCode, lastPrior.getTradeDate(), tradeDate);
        if (between != null) {
            for (SectorMoneyFlowDaily row : between) {
                SectorBreadthStatus status = SectorMoneyFlowService.evaluateBreadthBreakdown(row);
                if (status.eligible() && !status.breakdown()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> extractStringSet(Map<String, Object> payload, String key) {
        if (payload == null) {
            return Set.of();
        }
        Object raw = payload.get(key);
        if (!(raw instanceof Collection<?> col)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Object item : col) {
            if (item != null) {
                String value = String.valueOf(item).trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private boolean isMeaningfulRegimeChange(String previousRegime, String currentRegime) {
        if (previousRegime == null || currentRegime == null) {
            return false;
        }
        if ("UNAVAILABLE".equalsIgnoreCase(previousRegime) || "UNAVAILABLE".equalsIgnoreCase(currentRegime)) {
            return false;
        }
        return !previousRegime.equalsIgnoreCase(currentRegime);
    }

    private String regimeShiftSeverity(MarketRegime current) {
        if (current == MarketRegime.BEAR || current == MarketRegime.PANIC) {
            return InAppNotification.SEVERITY_CRITICAL;
        }
        if (current == MarketRegime.CORRECTION) {
            return InAppNotification.SEVERITY_WARNING;
        }
        return InAppNotification.SEVERITY_INFO;
    }

    private MarketRegime parseMarketRegime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MarketRegime.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private InAppNotification buildNotification(String dedupKey,
                                                LocalDate tradeDate,
                                                String severity,
                                                String category,
                                                String alertCode,
                                                String figi,
                                                String symbol,
                                                String stockName,
                                                Map<String, Object> payload,
                                                Instant createdAt) {
        InAppNotification entity = new InAppNotification();
        entity.setDedupKey(dedupKey);
        entity.setTradeDate(tradeDate);
        entity.setSeverity(severity);
        entity.setCategory(category);
        entity.setAlertCode(alertCode);
        entity.setFigi(figi);
        entity.setSymbol(symbol);
        entity.setStockName(stockName);
        entity.setPayload(payload == null ? Map.of() : payload);
        entity.setRead(false);
        entity.setReadAt(null);
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private DailyClosingBriefingDto toBriefingDto(DailyClosingBriefing b) {
        return new DailyClosingBriefingDto(
                b.getTradeDate(),
                b.getMarketRegime(),
                b.getPreviousMarketRegime(),
                b.getPreviousTradeDate(),
                b.isRegimeChanged(),
                b.getVixClose(),
                b.getTreasury10y(),
                b.getTopSectors(),
                b.getBottomSectors(),
                b.getPortfolioHealth(),
                b.getWatchlistOpportunities(),
                b.getRadarTop5(),
                b.getCriticalCount(),
                b.getWarningCount(),
                b.getInfoCount(),
                b.getObservationCutoff(),
                b.getGeneratedAt()
        );
    }

    private InAppNotificationDto toNotificationDto(InAppNotification n) {
        String targetRoute = n.getFigi() != null && !n.getFigi().isBlank()
                ? "/stockAnalysis/" + n.getFigi() : "/";
        return new InAppNotificationDto(
                n.getId(),
                n.getDedupKey(),
                n.getTradeDate(),
                n.getSeverity(),
                n.getCategory(),
                n.getAlertCode(),
                n.getFigi(),
                n.getSymbol(),
                n.getStockName(),
                targetRoute,
                n.getPayload(),
                n.isRead(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
