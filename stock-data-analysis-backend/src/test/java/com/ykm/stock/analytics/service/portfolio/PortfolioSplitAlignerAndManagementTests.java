package com.ykm.stock.analytics.service.portfolio;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioItemUpsertRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioItemViewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigUpdateRequest;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService.PortfolioExposureSnapshot;
import com.ykm.stock.analytics.service.portfolio.PortfolioSplitAligner.AlignedPosition;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioSplitAlignerAndManagementTests {
    private final PortfolioSplitAligner aligner = new PortfolioSplitAligner();

    /** Scenario：100 股 × 80 跨 2 拆 1 → 200 股 × 40；生效日當天建檔不重複調整；反向拆股與小數不提早取整；同步失敗拒絕混算。 */
    @Test
    void splitAlignmentHandlesForwardReverseExecutionDayAndSyncError() {
        StockStatus okStatus = new StockStatus();
        okStatus.setFigi("F_SPLT");
        okStatus.setSplitFromDate(LocalDate.of(2020, 1, 1));
        okStatus.setSplitDate(LocalDate.of(2026, 11, 18));

        StockSplit split2For1 = new StockSplit();
        split2For1.setFigi("F_SPLT");
        split2For1.setSourceEventId("SPLT-2-1");
        split2For1.setExecutionDate(LocalDate.of(2026, 6, 10));
        split2For1.setSplitFrom(BigDecimal.ONE);
        split2For1.setSplitTo(new BigDecimal("2"));

        AlignedPosition forward = aligner.alignHolding(
                new BigDecimal("100"), new BigDecimal("80"),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 11, 18),
                okStatus, List.of(split2For1));
        assertThat(forward.verified()).isTrue();
        assertThat(forward.splitAdjusted()).isTrue();
        assertThat(forward.adjustedShares()).isEqualByComparingTo("200.000000");
        assertThat(forward.adjustedAvgCost()).isEqualByComparingTo("40.000000");

        // 拆股生效當日才建檔：區間 (2026-06-10, 2026-11-18] 不應重複乘以 2
        AlignedPosition onExecutionDay = aligner.alignHolding(
                new BigDecimal("200"), new BigDecimal("40"),
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 11, 18),
                okStatus, List.of(split2For1));
        assertThat(onExecutionDay.verified()).isTrue();
        assertThat(onExecutionDay.splitAdjusted()).isFalse();
        assertThat(onExecutionDay.adjustedShares()).isEqualByComparingTo("200.000000");
        assertThat(onExecutionDay.adjustedAvgCost()).isEqualByComparingTo("40.000000");

        // 反向拆股（10 併 1）保留小數精度
        StockSplit reverse10To1 = new StockSplit();
        reverse10To1.setFigi("F_SPLT");
        reverse10To1.setSourceEventId("REV-10-1");
        reverse10To1.setExecutionDate(LocalDate.of(2026, 7, 1));
        reverse10To1.setSplitFrom(BigDecimal.TEN);
        reverse10To1.setSplitTo(BigDecimal.ONE);
        AlignedPosition reversed = aligner.alignHolding(
                new BigDecimal("15"), new BigDecimal("2.50"),
                LocalDate.of(2026, 6, 20), LocalDate.of(2026, 11, 18),
                okStatus, List.of(reverse10To1));
        assertThat(reversed.verified()).isTrue();
        assertThat(reversed.adjustedShares()).isEqualByComparingTo("1.500000");
        assertThat(reversed.adjustedAvgCost()).isEqualByComparingTo("25.000000");

        // 3 拆 1 止損價比對：100.0001 / 3 ≈ 33.33336667 < 33.3334，rawAdjustedPrice 保留精度避免取整後誤觸止損
        StockSplit split3For1 = new StockSplit();
        split3For1.setFigi("F_SPLT");
        split3For1.setSourceEventId("SPLT-3-1");
        split3For1.setExecutionDate(LocalDate.of(2026, 8, 1));
        split3For1.setSplitFrom(BigDecimal.ONE);
        split3For1.setSplitTo(new BigDecimal("3"));
        PortfolioSplitAligner.AlignedPrice alignedStop3 = aligner.alignPrice(
                new BigDecimal("100.0001"), LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 1),
                okStatus, List.of(split3For1));
        assertThat(alignedStop3.verified()).isTrue();
        assertThat(new BigDecimal("33.3334").compareTo(alignedStop3.rawAdjustedPrice())).isGreaterThan(0);

        // 實質同步錯誤拒絕估算；STORED_EVENT_NOT_RETURNED 維持既有豁免
        okStatus.setSplitMessage("ERROR:FUTU_TIMEOUT");
        AlignedPosition err = aligner.alignHolding(
                new BigDecimal("100"), new BigDecimal("80"),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 11, 18),
                okStatus, List.of(split2For1));
        assertThat(err.verified()).isFalse();
        assertThat(err.status()).isEqualTo(PortfolioSplitAligner.STATUS_SPLIT_SYNC_ERROR);

        okStatus.setSplitMessage("ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:2026-06-10");
        AlignedPosition exempted = aligner.alignHolding(
                new BigDecimal("100"), new BigDecimal("80"),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 11, 18),
                okStatus, List.of(split2For1));
        assertThat(exempted.verified()).isTrue();
        assertThat(exempted.adjustedShares()).isEqualByComparingTo("200.000000");
    }

    /** Scenario：僅修改備註保留原 costBasisDate；轉自選清空三欄；無效股數回 400。 */
    @Test
    void upsertPreservesCostBasisDateOnNotesOnlyUpdateAndClearsOnWatchlistSwitch() {
        PortfolioItemRepository itemRepo = mock(PortfolioItemRepository.class);
        StockRepository stockRepo = mock(StockRepository.class);
        StockStatusRepository statusRepo = mock(StockStatusRepository.class);
        StockQuoteRepository quoteRepo = mock(StockQuoteRepository.class);
        StockSplitRepository splitRepo = mock(StockSplitRepository.class);
        StockIndustryHistoryRepository industryRepo = mock(StockIndustryHistoryRepository.class);
        PortfolioRiskConfigService configService = mock(PortfolioRiskConfigService.class);
        MarketStatusService calendar = mock(MarketStatusService.class);
        DateTimeService clock = mock(DateTimeService.class);

        Instant now = Instant.parse("2026-11-18T22:00:00Z");
        LocalDate nov18 = LocalDate.of(2026, 11, 18);
        LocalDate may01 = LocalDate.of(2026, 5, 1);
        when(clock.now()).thenReturn(now);
        when(calendar.getLastClosedTradingDay(now)).thenReturn(nov18);
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getSessionCloseAt(any())).thenReturn(now.minusSeconds(3600));
        when(configService.getConfig()).thenReturn(new PortfolioRiskConfigDto(
                new BigDecimal("100000.00"), new BigDecimal("0.0100"),
                new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD"));

        Stock stock = new Stock();
        stock.setFigi("F_NVDA");
        stock.setSymbol("NVDA");
        stock.setLongName("NVIDIA Corp");
        stock.setCurrency("USD");
        when(stockRepo.findById("F_NVDA")).thenReturn(Optional.of(stock));
        when(stockRepo.findAllById(anyCollection())).thenReturn(List.of(stock));

        StockStatus st = new StockStatus();
        st.setFigi("F_NVDA");
        st.setSplitFromDate(LocalDate.of(2020, 1, 1));
        st.setSplitDate(nov18);
        when(statusRepo.findAllById(anyCollection())).thenReturn(List.of(st));

        StockQuote q = new StockQuote();
        q.setFigi("F_NVDA");
        q.setQuoteDate(nov18);
        q.setClosePrice(new BigDecimal("42.00"));
        when(quoteRepo.findLatestQuotesUpTo(anyCollection(), any())).thenReturn(List.of(q));

        StockSplit split2 = new StockSplit();
        split2.setFigi("F_NVDA");
        split2.setSourceEventId("NVDA-2-1");
        split2.setExecutionDate(LocalDate.of(2026, 6, 10));
        split2.setSplitFrom(BigDecimal.ONE);
        split2.setSplitTo(new BigDecimal("2"));
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split2));
        when(industryRepo.findByFigisAt(anyCollection(), any())).thenReturn(List.of());

        PortfolioItem existing = new PortfolioItem();
        existing.setFigi("F_NVDA");
        existing.setItemType(PortfolioItem.TYPE_HOLDING);
        existing.setShares(new BigDecimal("100.000000"));
        existing.setAvgCost(new BigDecimal("80.000000"));
        existing.setCostBasisDate(may01);
        existing.setPositionId("POS-INITIAL-1");
        existing.setNotes("old");
        when(itemRepo.findById("F_NVDA")).thenReturn(Optional.of(existing));
        when(itemRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        PortfolioManagementService service = new PortfolioManagementService(
                itemRepo, stockRepo, statusRepo, quoteRepo, splitRepo, industryRepo,
                configService, aligner, calendar, clock);

        // 1) 僅改 notes（未傳 costBasisDate，股數與均價相同）→ 保留 may01 與原 positionId
        PortfolioItemViewDto updatedNotes = service.upsertItem("F_NVDA", new PortfolioItemUpsertRequest(
                "HOLDING", new BigDecimal("100"), new BigDecimal("80"), null, "long term core"));
        assertThat(updatedNotes.costBasisDate()).isEqualTo(may01);
        assertThat(existing.getPositionId()).isEqualTo("POS-INITIAL-1");
        assertThat(updatedNotes.adjustedShares()).isEqualByComparingTo("200.000000");
        assertThat(updatedNotes.adjustedAvgCost()).isEqualByComparingTo("40.000000");
        assertThat(updatedNotes.marketValue()).isEqualByComparingTo("8400.00");
        assertThat(updatedNotes.unrealizedPnlAmount()).isEqualByComparingTo("400.00");
        assertThat(updatedNotes.unrealizedPnlPct()).isEqualByComparingTo("0.050000");

        // 2) 轉為 WATCHLIST → 三個成本欄位與 positionId 強制清空為 NULL
        PortfolioItemViewDto switched = service.upsertItem("F_NVDA", new PortfolioItemUpsertRequest(
                "WATCHLIST", new BigDecimal("100"), new BigDecimal("80"), may01, "watch pullback"));
        assertThat(switched.itemType()).isEqualTo(PortfolioItem.TYPE_WATCHLIST);
        assertThat(switched.rawShares()).isNull();
        assertThat(switched.rawAvgCost()).isNull();
        assertThat(switched.costBasisDate()).isNull();
        assertThat(existing.getPositionId()).isNull();

        // 2b) 由 WATCHLIST 重新轉為 HOLDING → 核發全新 positionId（絕不沿用舊持倉身分）
        service.upsertItem("F_NVDA", new PortfolioItemUpsertRequest(
                "HOLDING", new BigDecimal("50"), new BigDecimal("42"), nov18, "reopened"));
        assertThat(existing.getPositionId()).isNotBlank();
        assertThat(existing.getPositionId()).isNotEqualTo("POS-INITIAL-1");

        // 3) HOLDING 缺少合法股數或四捨五入後為 0（0.0000004）拋 400
        assertThatThrownBy(() -> service.upsertItem("F_NVDA", new PortfolioItemUpsertRequest(
                "HOLDING", BigDecimal.ZERO, new BigDecimal("80"), may01, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.upsertItem("F_NVDA", new PortfolioItemUpsertRequest(
                "HOLDING", new BigDecimal("0.0000004"), new BigDecimal("80"), may01, null)))
                .isInstanceOf(ResponseStatusException.class);

        // 4) 若既有 USD 持倉缺最新行情，loadExposureSnapshot 必須納入 unverifiedHoldingFigis 以阻斷不安全加倉建議
        when(itemRepo.findByItemTypeOrderByUpdatedAtDescFigiAsc(PortfolioItem.TYPE_HOLDING)).thenReturn(List.of(existing));
        when(quoteRepo.findLatestQuotesUpTo(anyCollection(), any())).thenReturn(List.of());
        PortfolioExposureSnapshot snap = service.loadExposureSnapshot(nov18, now);
        assertThat(snap.unverifiedHoldingFigis()).contains("F_NVDA");
        assertThat(snap.holdingMarketValueUsdByFigi()).doesNotContainKey("F_NVDA");
    }

    /** Scenario：PortfolioRiskConfigService 預設讀取、合法更新與非法邊界阻擋（含取整後為 0 之極小值 0.00001）。 */
    @Test
    @SuppressWarnings("unchecked")
    void riskConfigServiceReadsDefaultsAndValidatesUpdates() {
        PortfolioSysConfigRepository repo = mock(PortfolioSysConfigRepository.class);
        DateTimeService clock = mock(DateTimeService.class);
        ObjectProvider<CacheManager> cacheProvider = mock(ObjectProvider.class);
        ObjectProvider<EntityManager> emProvider = mock(ObjectProvider.class);
        when(clock.nowLocalDateTime()).thenReturn(LocalDateTime.of(2026, 11, 18, 12, 0));

        List<SysConfig> stored = new ArrayList<>();
        when(repo.findByConfigKeyInOrderByIdAsc(anyCollection())).thenAnswer(inv -> List.copyOf(stored));
        when(repo.save(any(SysConfig.class))).thenAnswer(inv -> {
            SysConfig c = inv.getArgument(0);
            if (!stored.contains(c)) {
                stored.add(c);
            }
            return c;
        });

        PortfolioRiskConfigService service = new PortfolioRiskConfigService(repo, clock, cacheProvider, emProvider);
        PortfolioRiskConfigDto defaults = service.getConfig();
        assertThat(defaults.totalCapital()).isEqualByComparingTo("100000.00");
        assertThat(defaults.riskPerTradePct()).isEqualByComparingTo("0.0100");
        assertThat(defaults.maxSinglePositionPct()).isEqualByComparingTo("0.1500");
        assertThat(defaults.maxSectorExposurePct()).isEqualByComparingTo("0.3000");

        PortfolioRiskConfigDto updated = service.updateConfig(new PortfolioRiskConfigUpdateRequest(
                new BigDecimal("250000"), new BigDecimal("0.015"), new BigDecimal("0.12"), new BigDecimal("0.25")));
        assertThat(updated.totalCapital()).isEqualByComparingTo("250000.00");
        assertThat(service.getConfig().maxSectorExposurePct()).isEqualByComparingTo("0.2500");

        assertThatThrownBy(() -> service.updateConfig(new PortfolioRiskConfigUpdateRequest(
                BigDecimal.ZERO, new BigDecimal("0.01"), new BigDecimal("0.15"), new BigDecimal("0.30"))))
                .isInstanceOf(ResponseStatusException.class);
        // 0.00001 經 4 位小數取整後為 0.0000，必須回 400 而不可靜默寫入 0.0000 後被還原預設值
        assertThatThrownBy(() -> service.updateConfig(new PortfolioRiskConfigUpdateRequest(
                new BigDecimal("100000"), new BigDecimal("0.00001"), new BigDecimal("0.15"), new BigDecimal("0.30"))))
                .isInstanceOf(ResponseStatusException.class);
    }
}
