package com.ykm.stock.analytics.service.valuation;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockCustomPeerRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Non-PIT 拆股 EPS 股份基準驗證回歸測試：
 * 涵蓋空真 periodEnd 漏洞、混口徑 Futu、真實未復權 Futu、非重述分拆（DELL）、反向拆股進位（GE）、
 * 拆股早於首報（APG）、ADR 隔離、SEC 跨拆股混算（QUIT）、Q4 年報歧義及完整 SEC 失敗保留已驗 FUTU_AND_SEC。
 */
class EpsSplitBasisVerificationTests {
    private static final LocalDate LATEST_DAY = LocalDate.of(2026, 11, 20);
    private static final Instant LATEST_CLOSE = Instant.parse("2026-11-20T21:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-21T02:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final StockShareObservationRepository shareObservations = mock(StockShareObservationRepository.class);
    private final StockIndustryHistoryRepository industries = mock(StockIndustryHistoryRepository.class);
    private final IndustryValuationConfigRepository industryConfigs = mock(IndustryValuationConfigRepository.class);
    private final StockFinancialPeriodRepository periodRepo = mock(StockFinancialPeriodRepository.class);
    private final StockValuationLatestRepository latestRepo = mock(StockValuationLatestRepository.class);
    private final StockCustomPeerRepository customPeerRepo = mock(StockCustomPeerRepository.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private final Map<String, List<StockFinancialPeriod>> storedPeriods = new LinkedHashMap<>();
    private StockValuationService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        service = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, periodRepo, latestRepo, customPeerRepo, calendar, clock,
                new SecIssuerHistory(""), tx, entityManager);
        when(clock.now()).thenReturn(NOW);
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(LATEST_DAY);
        when(calendar.getSessionCloseAt(any())).thenReturn(LATEST_CLOSE);
        when(calendar.getSessionOpenAt(any())).thenReturn(LATEST_CLOSE.minusSeconds(6 * 3600));
        when(industries.findByFigisAt(anyCollection(), any())).thenReturn(List.of());
        when(periodRepo.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenAnswer(inv -> {
            List<StockFinancialPeriod> out = new ArrayList<>();
            for (Object figi : (Iterable<?>) inv.getArgument(0)) {
                out.addAll(storedPeriods.getOrDefault(String.valueOf(figi), List.of()));
            }
            return out;
        });
        doAnswer(inv -> {
            for (Object figi : (Iterable<?>) inv.getArgument(0)) {
                storedPeriods.remove(String.valueOf(figi));
            }
            return null;
        }).when(periodRepo).deleteByFigiIn(anyCollection());
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof StockFinancialPeriod p) {
                storedPeriods.computeIfAbsent(p.getFigi(), k -> new ArrayList<>()).add(p);
            }
            return null;
        }).when(entityManager).persist(any());
        when(latestRepo.saveAll(anyCollection())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void shopLikeSparseForeignQuartersBeforeSplitMustNotVacuouslyStampPeriodEnd() {
        Stock shop = stock("SHOP_FIGI", "SHOP", "0001594805", "Common Stock");
        StockStatus st = status("SHOP_FIGI");
        stubMaster(shop, st, List.of(split("SHOP_FIGI", "2022-06-29", "1", "10")), 1_205_676_577L);

        List<FinancialReport> frs = List.of(
                quarterReport("SHOP_FIGI", "2020-12-31", 4, 2020, "0.099", "123000000"),
                quarterReport("SHOP_FIGI", "2020-12-31", 7, 2020, "0.259", "319509000"),
                quarterReport("SHOP_FIGI", "2021-03-31", 1, 2021, "0.994", "1258000000"),
                quarterReport("SHOP_FIGI", "2021-06-30", 2, 2021, "0.690", "879000000"),
                quarterReport("SHOP_FIGI", "2021-09-30", 3, 2021, "0.900", "1148000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(frs);
        // 40-F 僅有年度 SEC 標籤，無 Q1..Q3 單季標籤
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001594805", "EarningsPerShareDiluted", "USD/shares", "2020-01-01", "2020-12-31", "2021-02-17", "40-F", "2.59")));

        service.rebuildFinancialPeriods(List.of("SHOP_FIGI"));

        StockFinancialPeriod annual2020 = periodAt("SHOP_FIGI", "2020-12-31");
        assertThat(annual2020.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(annual2020.getEpsShareBasisDate()).isEqualTo(LATEST_DAY);

        StockFinancialPeriod q3_2021 = periodAt("SHOP_FIGI", "2021-09-30");
        assertThat(q3_2021.getEpsTtm()).isNull();
        assertThat(q3_2021.getEpsShareBasisDate()).isNull();
        assertThat(q3_2021.getEpsTtmStatus()).isEqualTo(MetricStatus.UNVERIFIED.name());
        assertThat(q3_2021.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void vabkLikeMixedFutuQuartersAcrossStockDividendMustBeBlocked() {
        Stock vabk = stock("VABK_FIGI", "VABK", "0001572334", "Common Stock");
        StockStatus st = status("VABK_FIGI");
        stubMaster(vabk, st, List.of(split("VABK_FIGI", "2026-11-20", "1", "1.08")), 6_000_000L);

        // Q4 2025..Q2 2026 已除 1.08，但 Q3 2026 仍保留拆前 1.20
        List<FinancialReport> frs = List.of(
                quarterReport("VABK_FIGI", "2025-03-31", 1, 2025, "0.70", "4000000"),
                quarterReport("VABK_FIGI", "2025-06-30", 2, 2025, "0.722222", "4200000"),
                quarterReport("VABK_FIGI", "2025-09-30", 3, 2025, "0.777778", "4600000"),
                quarterReport("VABK_FIGI", "2025-12-31", 4, 2025, "1.018519", "6000000"),
                quarterReport("VABK_FIGI", "2025-12-31", 7, 2025, "3.287037", "19000000"),
                quarterReport("VABK_FIGI", "2026-03-31", 1, 2026, "0.898148", "5300000"),
                quarterReport("VABK_FIGI", "2026-06-30", 2, 2026, "1.527778", "9000000"),
                quarterReport("VABK_FIGI", "2026-09-30", 3, 2026, "1.20", "7000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(frs);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001572334", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2025-05-12", "10-Q", "0.76"),
                secFact("0001572334", "EarningsPerShareDiluted", "USD/shares", "2025-04-01", "2025-06-30", "2025-08-12", "10-Q", "0.78"),
                secFact("0001572334", "EarningsPerShareDiluted", "USD/shares", "2025-07-01", "2025-09-30", "2025-11-12", "10-Q", "0.84"),
                secFact("0001572334", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-12-31", "2026-03-27", "10-K", "3.55"),
                secFact("0001572334", "EarningsPerShareDiluted", "USD/shares", "2026-01-01", "2026-03-31", "2026-05-12", "10-Q", "0.97"),
                secFact("0001572334", "EarningsPerShareDiluted", "USD/shares", "2026-04-01", "2026-06-30", "2026-08-13", "10-Q", "1.65"),
                secFact("0001572334", "EarningsPerShareDiluted", "USD/shares", "2026-07-01", "2026-09-30", "2026-11-16", "10-Q", "1.20")));

        service.rebuildFinancialPeriods(List.of("VABK_FIGI"));

        StockFinancialPeriod q3_2026 = periodAt("VABK_FIGI", "2026-09-30");
        assertThat(q3_2026.getEpsTtm()).isNull();
        assertThat(q3_2026.getEpsShareBasisDate()).isNull();
        assertThat(q3_2026.getEpsTtmStatus()).isEqualTo(MetricStatus.UNVERIFIED.name());
        assertThat(q3_2026.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void adrCrossingSplitMustNotBeVerifiedByOrdinaryShareSecEpsAndMustKeepSplitReason() {
        Stock edu = stock("EDU_FIGI", "EDU", "0001372920", "ADR");
        StockStatus st = status("EDU_FIGI");
        stubMaster(edu, st, List.of(split("EDU_FIGI", "2022-04-08", "10", "1")), 160_000_000L);

        List<FinancialReport> frs = List.of(
                quarterReport("EDU_FIGI", "2021-05-31", 7, 2021, "2.00", "320000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(frs);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001372920", "EarningsPerShareDiluted", "USD/shares", "2020-06-01", "2021-05-31", "2021-09-20", "20-F", "0.20")));

        service.rebuildFinancialPeriods(List.of("EDU_FIGI"));

        StockFinancialPeriod fy21 = periodAt("EDU_FIGI", "2021-05-31");
        assertThat(fy21.getEpsTtm()).isNull();
        assertThat(fy21.getEpsShareBasisDate()).isNull();
        assertThat(fy21.getEpsTtmStatus()).isEqualTo(MetricStatus.UNVERIFIED.name());
        assertThat(fy21.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void quitLikeSecMixedPreAndPostSplitFilingsMustNotBeStampedAsPeriodEnd() {
        Stock quit = stock("QUIT_FIGI", "QUIT", "0001500000", "Common Stock");
        StockStatus st = status("QUIT_FIGI");
        stubMaster(quit, st, List.of(split("QUIT_FIGI", "2020-07-31", "20", "1")), 1_000_000L);

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of());
        // Q4 2018 僅在拆前申報 (-0.55)，Q1..Q3 2019 另有拆後比較期 (-17.57/-10.08/-9.07)
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001500000", "EarningsPerShareBasicAndDiluted", "USD/shares", "2018-10-01", "2018-12-31", "2020-03-13", "10-K", "-0.55"),
                secFact("0001500000", "EarningsPerShareBasicAndDiluted", "USD/shares", "2019-01-01", "2019-03-31", "2019-05-15", "10-Q", "-0.88"),
                secFact("0001500000", "EarningsPerShareBasicAndDiluted", "USD/shares", "2019-01-01", "2019-03-31", "2021-03-11", "10-K", "-17.57"),
                secFact("0001500000", "EarningsPerShareBasicAndDiluted", "USD/shares", "2019-04-01", "2019-06-30", "2019-08-08", "10-Q", "-0.50"),
                secFact("0001500000", "EarningsPerShareBasicAndDiluted", "USD/shares", "2019-04-01", "2019-06-30", "2020-08-06", "10-Q", "-10.08"),
                secFact("0001500000", "EarningsPerShareBasicAndDiluted", "USD/shares", "2019-07-01", "2019-09-30", "2019-11-06", "10-Q", "-0.45"),
                secFact("0001500000", "EarningsPerShareBasicAndDiluted", "USD/shares", "2019-07-01", "2019-09-30", "2020-11-12", "10-Q", "-9.07")));

        service.rebuildFinancialPeriods(List.of("QUIT_FIGI"));

        StockFinancialPeriod q3_2019 = periodAt("QUIT_FIGI", "2019-09-30");
        assertThat(q3_2019.getEpsTtm()).isNull();
        assertThat(q3_2019.getEpsShareBasisDate()).isNull();
        assertThat(q3_2019.getEpsTtmStatus()).isEqualTo(MetricStatus.UNVERIFIED.name());
        assertThat(q3_2019.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void dellLikeUnrestatedPostSpinOffComparativeMustNotVerifyLatestAndMustResolveToPeriodEnd() {
        Stock dell = stock("DELL_FIGI", "DELL", "0001571996", "Common Stock");
        StockStatus st = status("DELL_FIGI");
        stubMaster(dell, st, List.of(split("DELL_FIGI", "2021-11-02", "10000", "19768")), 760_000_000L);

        List<FinancialReport> frs = List.of(
                quarterReport("DELL_FIGI", "2021-01-29", 7, 2021, "4.22", "3250000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(frs);
        // 拆前原報與分拆後比較期同樣為 4.22（未按 1.9768 重述）
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001571996", "EarningsPerShareDiluted", "USD/shares", "2020-02-01", "2021-01-29", "2021-03-26", "10-K", "4.22"),
                secFact("0001571996", "EarningsPerShareDiluted", "USD/shares", "2020-02-01", "2021-01-29", "2022-03-24", "10-K", "4.22")));

        service.rebuildFinancialPeriods(List.of("DELL_FIGI"));

        StockFinancialPeriod fy21 = periodAt("DELL_FIGI", "2021-01-29");
        assertThat(fy21.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(fy21.getEpsTtm()).isEqualByComparingTo("4.22");
        assertThat(fy21.getEpsShareBasisDate()).isEqualTo(LocalDate.of(2021, 1, 29));
    }

    @Test
    void apgLikeSplitBeforeFirstFilingStillVerifiesAtLatestAndGeReverseSplitRoundingPasses() {
        Stock apg = stock("APG_FIGI", "APG", "0001796209", "Common Stock");
        StockStatus apgStatus = status("APG_FIGI");
        stubMaster(apg, apgStatus, List.of(split("APG_FIGI", "2025-07-01", "2", "3")), 420_000_000L);

        List<FinancialReport> apgReports = List.of(
                quarterReport("APG_FIGI", "2024-09-30", 3, 2024, "0.15", "62000000"),
                quarterReport("APG_FIGI", "2024-12-31", 4, 2024, "0.20", "82000000"),
                quarterReport("APG_FIGI", "2025-03-31", 1, 2025, "0.07", "31000000"),
                quarterReport("APG_FIGI", "2025-06-30", 2, 2025, "0.16", "69000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(apgReports);
        // Q2 2025 首次申報日 2025-07-31 已晚於 2025-07-01 3拆2，僅有拆後直接申報 0.16
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001796209", "EarningsPerShareDiluted", "USD/shares", "2024-07-01", "2024-09-30", "2024-10-31", "10-Q", "0.23"),
                secFact("0001796209", "EarningsPerShareDiluted", "USD/shares", "2024-07-01", "2024-09-30", "2025-10-30", "10-Q", "0.15"),
                secFact("0001796209", "EarningsPerShareDiluted", "USD/shares", "2024-10-01", "2024-12-31", "2025-02-26", "10-K", "0.30"),
                secFact("0001796209", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "0.11"),
                secFact("0001796209", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2026-04-30", "10-Q", "0.07"),
                secFact("0001796209", "EarningsPerShareDiluted", "USD/shares", "2025-04-01", "2025-06-30", "2025-07-31", "10-Q", "0.16")));

        service.rebuildFinancialPeriods(List.of("APG_FIGI"));

        StockFinancialPeriod q2_2025 = periodAt("APG_FIGI", "2025-06-30");
        assertThat(q2_2025.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(q2_2025.getEpsTtm()).isEqualByComparingTo("0.58");
        assertThat(q2_2025.getEpsShareBasisDate()).isEqualTo(LATEST_DAY);

        // GE 1合8 反向拆股：原申報 -0.14 乘 8 為 -1.12，Futu / 拆後實值 -1.08 落在分位四捨五入容差內應通過
        SecFinancialFactsCalculator calc = new SecFinancialFactsCalculator();
        StockSplit geReverse = split("GE_FIGI", "2021-08-02", "8", "1");
        SecFinancialFact geOrig = secFact("0000040545", "EarningsPerShareDiluted", "USD/shares",
                "2018-01-01", "2018-03-31", "2018-05-01", "10-Q", "-0.14");
        assertThat(calc.isEpsSplitBasisVerified(new BigDecimal("-1.08"), null, LocalDate.of(2018, 3, 31), false,
                List.of(geOrig), "USD", List.of(geReverse), LATEST_DAY, NOW)).isTrue();
        assertThat(calc.isEpsSplitBasisVerified(new BigDecimal("-0.14"), null, LocalDate.of(2018, 3, 31), false,
                List.of(geOrig), "USD", List.of(geReverse), LATEST_DAY, NOW)).isFalse();
    }

    @Test
    void tinyAmbiguousEpsAndOppositeSignAndAmbiguousQ4AreRejected() {
        SecFinancialFactsCalculator calc = new SecFinancialFactsCalculator();
        StockSplit tenForOne = split("TINY_FIGI", "2024-06-10", "1", "10");
        SecFinancialFact tinyOrig = secFact("0001999999", "EarningsPerShareDiluted", "USD/shares",
                "2024-01-01", "2024-03-31", "2024-05-01", "10-Q", "0.02");
        // 0.01 在 0.02 容差下同時貼近 0.02（拆前）與 0.002（拆後），無法區分基準，必須攔截
        assertThat(calc.isEpsSplitBasisVerified(new BigDecimal("0.01"), null, LocalDate.of(2024, 3, 31), false,
                List.of(tinyOrig), "USD", List.of(tenForOne), LATEST_DAY, NOW)).isFalse();
        // 異號 -0.01 不得通過 +0.01 基準驗證
        SecFinancialFact posOrig = secFact("0001999999", "EarningsPerShareDiluted", "USD/shares",
                "2024-01-01", "2024-03-31", "2024-05-01", "10-Q", "0.10");
        assertThat(calc.isEpsSplitBasisVerified(new BigDecimal("-0.01"), null, LocalDate.of(2024, 3, 31), false,
                List.of(posOrig), "USD", List.of(tenForOne), LATEST_DAY, NOW)).isFalse();

        // Q4 數額過小（0.10 vs 全年 10.00，2拆1）：拆前 0.20 與拆後 0.10 皆落於 4% 容差內，必須攔截
        Stock ambQ4 = stock("AMBQ4_FIGI", "AMBQ4", "0001888888", "Common Stock");
        StockStatus ambSt = status("AMBQ4_FIGI");
        stubMaster(ambQ4, ambSt, List.of(split("AMBQ4_FIGI", "2026-06-10", "1", "2")), 50_000_000L);
        List<FinancialReport> ambReports = List.of(
                quarterReport("AMBQ4_FIGI", "2025-03-31", 1, 2025, "3.30", "165000000"),
                quarterReport("AMBQ4_FIGI", "2025-06-30", 2, 2025, "3.30", "165000000"),
                quarterReport("AMBQ4_FIGI", "2025-09-30", 3, 2025, "3.30", "165000000"),
                quarterReport("AMBQ4_FIGI", "2025-12-31", 4, 2025, "0.10", "5000000"),
                quarterReport("AMBQ4_FIGI", "2025-12-31", 7, 2025, "10.00", "500000000"),
                quarterReport("AMBQ4_FIGI", "2026-03-31", 1, 2026, "3.40", "170000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(ambReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001888888", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "6.60"),
                secFact("0001888888", "EarningsPerShareDiluted", "USD/shares", "2025-04-01", "2025-06-30", "2025-08-01", "10-Q", "6.60"),
                secFact("0001888888", "EarningsPerShareDiluted", "USD/shares", "2025-07-01", "2025-09-30", "2025-11-01", "10-Q", "6.60"),
                secFact("0001888888", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-12-31", "2026-02-20", "10-K", "20.00"),
                secFact("0001888888", "EarningsPerShareDiluted", "USD/shares", "2026-01-01", "2026-03-31", "2026-05-01", "10-Q", "6.80")));

        service.rebuildFinancialPeriods(List.of("AMBQ4_FIGI"));

        StockFinancialPeriod q1_2026 = periodAt("AMBQ4_FIGI", "2026-03-31");
        assertThat(q1_2026.getEpsTtm()).isNull();
        assertThat(q1_2026.getEpsTtmStatus()).isEqualTo(MetricStatus.UNVERIFIED.name());
        assertThat(q1_2026.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void verifiedMixedFutuAndSecCandidateSurvivesWhenCompleteSecAlternativeFailsSplitCheck() {
        Stock mix = stock("MIX_FIGI", "MIX", "0001777777", "Common Stock");
        StockStatus st = status("MIX_FIGI");
        stubMaster(mix, st, List.of(split("MIX_FIGI", "2025-06-10", "1", "2")), 100_000_000L);

        // Futu 缺 Q1 2025，但 Q2..Q4 2024 為拆前原值；SEC 四季中 Q2 2024 僅有拆前原報，Q3..Q1 有拆後比較期，導致完整 SEC 混口徑失敗，
        // 但若 Futu 缺季補入且同口徑驗證通過，不得因完整 SEC 無法驗證而被丟棄
        List<FinancialReport> frs = List.of(
                quarterReport("MIX_FIGI", "2024-06-30", 2, 2024, "0.50", "50000000"),
                quarterReport("MIX_FIGI", "2024-09-30", 3, 2024, "0.60", "60000000"),
                quarterReport("MIX_FIGI", "2024-12-31", 4, 2024, "0.70", "70000000"),
                quarterReportWithoutEps("MIX_FIGI", "2025-03-31", 1, 2025, "80000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(frs);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001777777", "EarningsPerShareDiluted", "USD/shares", "2024-04-01", "2024-06-30", "2024-08-01", "10-Q", "1.00"),
                secFact("0001777777", "EarningsPerShareDiluted", "USD/shares", "2024-07-01", "2024-09-30", "2024-11-01", "10-Q", "1.20"),
                secFact("0001777777", "EarningsPerShareDiluted", "USD/shares", "2024-07-01", "2024-09-30", "2025-11-01", "10-Q", "0.60"),
                secFact("0001777777", "EarningsPerShareDiluted", "USD/shares", "2024-10-01", "2024-12-31", "2025-02-15", "10-K", "1.40"),
                secFact("0001777777", "EarningsPerShareDiluted", "USD/shares", "2024-10-01", "2024-12-31", "2026-02-15", "10-K", "0.70"),
                secFact("0001777777", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "1.60"),
                secFact("0001777777", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2026-05-01", "10-Q", "0.80")));

        service.rebuildFinancialPeriods(List.of("MIX_FIGI"));

        StockFinancialPeriod q1_2025 = periodAt("MIX_FIGI", "2025-03-31");
        assertThat(q1_2025.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(q1_2025.getEpsTtmSource()).isEqualTo("FUTU_AND_SEC");
        assertThat(q1_2025.getEpsTtm()).isEqualByComparingTo("2.60");
        assertThat(q1_2025.getEpsShareBasisDate()).isEqualTo(LATEST_DAY);
    }

    @Test
    void multiSplitGenuinePreSplitPassesAtPeriodEndWhileIntermediateOnlyValueIsRejected() {
        Stock airi = stock("AIRI_FIGI", "AIRI", "0001009891", "Common Stock");
        StockStatus st = status("AIRI_FIGI");
        List<StockSplit> twoReverseSplits = List.of(
                split("AIRI_FIGI", "2026-11-10", "5", "1"),
                split("AIRI_FIGI", "2026-11-20", "5", "1"));
        stubMaster(airi, st, twoReverseSplits, 200_000L);

        // Futu 仍保留拆前原值 -0.60（SEC 拆前原報 -0.60）-> 應精準驗證為 periodEnd
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("AIRI_FIGI", "2025-12-31", 7, 2025, "-0.60", "-3000000")));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001009891", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-12-31", "2026-03-20", "10-K", "-0.60")));

        service.rebuildFinancialPeriods(List.of("AIRI_FIGI"));
        StockFinancialPeriod preSplitRow = periodAt("AIRI_FIGI", "2025-12-31");
        assertThat(preSplitRow.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(preSplitRow.getEpsShareBasisDate()).isEqualTo(LocalDate.of(2025, 12, 31));

        // 中間態（僅套用第一次 5合1 = -3.00，未套用第二次 = -15.00）：Futu 對 latest 與 periodEnd 均失敗，由已核實的拆前 SEC 10-K 接手補救
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("AIRI_FIGI", "2025-12-31", 7, 2025, "-3.00", "-3000000")));
        service.rebuildFinancialPeriods(List.of("AIRI_FIGI"));
        StockFinancialPeriod rescuedBySec = periodAt("AIRI_FIGI", "2025-12-31");
        assertThat(rescuedBySec.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(rescuedBySec.getEpsTtmSource()).isEqualTo("SEC");
        assertThat(rescuedBySec.getEpsTtm()).isEqualByComparingTo("-0.60");
        assertThat(rescuedBySec.getEpsShareBasisDate()).isEqualTo(LocalDate.of(2025, 12, 31));

        // 若無 SEC EPS 備援（僅有加權稀釋股數 5,000,000 供交叉驗證），中間態 -3.00 必須明確攔截
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001009891", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares",
                        "2025-01-01", "2025-12-31", "2026-03-20", "10-K", "5000000")));
        service.rebuildFinancialPeriods(List.of("AIRI_FIGI"));
        StockFinancialPeriod midSplitRow = periodAt("AIRI_FIGI", "2025-12-31");
        assertThat(midSplitRow.getEpsTtm()).isNull();
        assertThat(midSplitRow.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void secAllPostFourQuarterUnrestatedComparativeIsBlocked() {
        Stock sp = stock("SPIN4_FIGI", "SPIN4", "0001666666", "Common Stock");
        StockStatus st = status("SPIN4_FIGI");
        stubMaster(sp, st, List.of(split("SPIN4_FIGI", "2021-11-02", "10000", "19768")), 500_000_000L);

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of());
        // SEC 四季皆有拆後比較期，但原值皆重複 1.00 未按 1.9768 重述，不可誤當 latest 放行
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2020-10-01", "2020-12-31", "2021-02-20", "10-K", "1.00"),
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2020-10-01", "2020-12-31", "2022-02-20", "10-K", "1.00"),
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2021-01-01", "2021-03-31", "2021-05-01", "10-Q", "1.00"),
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2021-01-01", "2021-03-31", "2022-05-01", "10-Q", "1.00"),
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2021-04-01", "2021-06-30", "2021-08-01", "10-Q", "1.00"),
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2021-04-01", "2021-06-30", "2022-08-01", "10-Q", "1.00"),
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2021-07-01", "2021-09-30", "2021-10-25", "10-Q", "1.00"),
                secFact("0001666666", "EarningsPerShareDiluted", "USD/shares", "2021-07-01", "2021-09-30", "2022-10-25", "10-Q", "1.00")));

        service.rebuildFinancialPeriods(List.of("SPIN4_FIGI"));
        StockFinancialPeriod q3_2021 = periodAt("SPIN4_FIGI", "2021-09-30");
        assertThat(q3_2021.getEpsTtm()).isNull();
        assertThat(q3_2021.getEpsTtmStatus()).isEqualTo(MetricStatus.UNVERIFIED.name());
        assertThat(q3_2021.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void directSecRivalMatchVetoesWeightedSharesAndQ4FallbackAndMissingQ1BlocksQ4() {
        // 1. 直接 SEC EPS 命中對照拆股口徑時，禁止被加權股數公式覆蓋（1.08 股利反例）
        SecFinancialFactsCalculator calc = new SecFinancialFactsCalculator();
        StockSplit divSplit = split("VETO_FIGI", "2026-11-01", "1", "1.08");
        SecFinancialFact directPre = secFact("0001555555", "EarningsPerShareDiluted", "USD/shares",
                "2026-04-01", "2026-06-30", "2026-08-01", "10-Q", "1.00");
        SecFinancialFact wShares = secFact("0001555555", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares",
                "2026-04-01", "2026-06-30", "2026-08-01", "10-Q", "100000000");
        // Futu 留著拆前 1.00，但分母分子偏差剛好讓 108M / 108M = 1.00；因直接 SEC EPS 已反證命中拆前口徑，必須回傳 REJECTED
        assertThat(calc.verifyEpsSplitBasis(new BigDecimal("1.00"), new BigDecimal("108000000"),
                LocalDate.of(2026, 6, 30), false, List.of(directPre, wShares), "USD",
                List.of(divSplit), LATEST_DAY, LATEST_DAY, NOW))
                .isEqualTo(SecFinancialFactsCalculator.EpsSplitVerification.REJECTED);

        // 2. Q4 年報驗證缺少同財年 Q1 拆股證據時須攔截
        Stock missingQ1 = stock("NOQ1_FIGI", "NOQ1", "0001444444", "Common Stock");
        StockStatus st = status("NOQ1_FIGI");
        stubMaster(missingQ1, st, List.of(split("NOQ1_FIGI", "2026-06-10", "1", "10")), 100_000_000L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("NOQ1_FIGI", "2025-03-31", 1, 2025, "0.20", "20000000"),
                quarterReport("NOQ1_FIGI", "2025-06-30", 2, 2025, "0.25", "25000000"),
                quarterReport("NOQ1_FIGI", "2025-09-30", 3, 2025, "0.35", "35000000"),
                quarterReport("NOQ1_FIGI", "2025-12-31", 4, 2025, "0.40", "40000000"),
                quarterReport("NOQ1_FIGI", "2025-12-31", 7, 2025, "1.20", "120000000"),
                quarterReport("NOQ1_FIGI", "2026-03-31", 1, 2026, "0.50", "50000000")));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001444444", "EarningsPerShareDiluted", "USD/shares", "2025-04-01", "2025-06-30", "2026-08-01", "10-Q", "0.25"),
                secFact("0001444444", "EarningsPerShareDiluted", "USD/shares", "2025-07-01", "2025-09-30", "2026-11-01", "10-Q", "0.35"),
                secFact("0001444444", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-12-31", "2026-02-20", "10-K", "12.00"),
                secFact("0001444444", "EarningsPerShareDiluted", "USD/shares", "2026-01-01", "2026-03-31", "2026-05-01", "10-Q", "5.00")));
        service.rebuildFinancialPeriods(List.of("NOQ1_FIGI"));
        StockFinancialPeriod q1_2026 = periodAt("NOQ1_FIGI", "2026-03-31");
        assertThat(q1_2026.getEpsTtm()).isNull();
        assertThat(q1_2026.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void exactZeroQuarterWithZeroSecEvidenceIsNeutralInVerifiedTtm() {
        Stock zeroQ = stock("ZEROQ_FIGI", "ZEROQ", "0001333333", "Common Stock");
        StockStatus st = status("ZEROQ_FIGI");
        stubMaster(zeroQ, st, List.of(split("ZEROQ_FIGI", "2026-06-10", "1", "10")), 100_000_000L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("ZEROQ_FIGI", "2025-06-30", 2, 2025, "0.00", "0"),
                quarterReport("ZEROQ_FIGI", "2025-09-30", 3, 2025, "0.30", "30000000"),
                quarterReport("ZEROQ_FIGI", "2025-12-31", 4, 2025, "0.40", "40000000"),
                quarterReport("ZEROQ_FIGI", "2026-03-31", 1, 2026, "0.50", "50000000")));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001333333", "EarningsPerShareDiluted", "USD/shares", "2025-04-01", "2025-06-30", "2025-08-01", "10-Q", "0.00"),
                secFact("0001333333", "EarningsPerShareDiluted", "USD/shares", "2025-07-01", "2025-09-30", "2025-11-01", "10-Q", "3.00"),
                secFact("0001333333", "EarningsPerShareDiluted", "USD/shares", "2025-10-01", "2025-12-31", "2026-02-20", "10-K", "4.00"),
                secFact("0001333333", "EarningsPerShareDiluted", "USD/shares", "2026-01-01", "2026-03-31", "2026-05-01", "10-Q", "5.00")));
        service.rebuildFinancialPeriods(List.of("ZEROQ_FIGI"));
        StockFinancialPeriod q1_2026 = periodAt("ZEROQ_FIGI", "2026-03-31");
        assertThat(q1_2026.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(q1_2026.getEpsTtm()).isEqualByComparingTo("1.20");
        assertThat(q1_2026.getEpsShareBasisDate()).isEqualTo(LATEST_DAY);
    }

    @Test
    void partiallyAdjustedMultiSplitSecComparativeAndUnchangedSmallEpsAreBlocked() {
        // 1. 雙拆股（2:1 後 1.08:1）：SEC 年報 4.00 -> 2.00 -> 2.00，最後一版未再隨 1.08 調整（應為 ~1.8519），不可放行 latest
        Stock multi = stock("PART_FIGI", "PART", "0001222222", "Common Stock");
        StockStatus st = status("PART_FIGI");
        stubMaster(multi, st, List.of(
                split("PART_FIGI", "2023-06-01", "1", "2"),
                split("PART_FIGI", "2025-06-01", "1", "1.08")), 100_000_000L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of());
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001222222", "EarningsPerShareDiluted", "USD/shares", "2022-01-01", "2022-12-31", "2023-02-20", "10-K", "4.00"),
                secFact("0001222222", "EarningsPerShareDiluted", "USD/shares", "2022-01-01", "2022-12-31", "2024-02-20", "10-K", "2.00"),
                secFact("0001222222", "EarningsPerShareDiluted", "USD/shares", "2022-01-01", "2022-12-31", "2026-02-20", "10-K", "2.00")));
        service.rebuildFinancialPeriods(List.of("PART_FIGI"));
        StockFinancialPeriod fy22 = periodAt("PART_FIGI", "2022-12-31");
        assertThat(fy22.getEpsTtm()).isNull();
        assertThat(fy22.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);

        // 2. 2:1 拆股前後皆為 0.04（未重述）：不可因 0.02 絕對容差誤當成拆後 latest 放行
        Stock smallUnchanged = stock("SM04_FIGI", "SM04", "0001222223", "Common Stock");
        StockStatus smSt = status("SM04_FIGI");
        stubMaster(smallUnchanged, smSt, List.of(split("SM04_FIGI", "2024-06-01", "1", "2")), 100_000_000L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of());
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001222223", "EarningsPerShareDiluted", "USD/shares", "2023-01-01", "2023-12-31", "2024-02-20", "10-K", "0.04"),
                secFact("0001222223", "EarningsPerShareDiluted", "USD/shares", "2023-01-01", "2023-12-31", "2025-02-20", "10-K", "0.04")));
        service.rebuildFinancialPeriods(List.of("SM04_FIGI"));
        StockFinancialPeriod fy23 = periodAt("SM04_FIGI", "2023-12-31");
        assertThat(fy23.getEpsShareBasisDate()).isNotEqualTo(LATEST_DAY);
    }

    @Test
    void crossCikSecUnrestatedPredecessorRepeatIsBlocked() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        StockValuationService lineageService = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, periodRepo, latestRepo, customPeerRepo, calendar, clock,
                new SecIssuerHistory("0002000002:0001000001:2025-02-01:0001193125-25-000001"), tx, entityManager);

        Stock s = stock("LIN_FIGI", "LIN", "0002000002", "Common Stock");
        StockStatus st = status("LIN_FIGI");
        stubMaster(s, st, List.of(split("LIN_FIGI", "2025-06-01", "1", "2")), 100_000_000L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of());
        // 前任 CIK 在拆前報 8.00；後任 CIK 在拆後比較期照錄 8.00（未除 2），不可誤當 latest 放行 25.00
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001000001", "EarningsPerShareDiluted", "USD/shares", "2024-04-01", "2024-06-30", "2024-08-01", "10-Q", "8.00"),
                secFact("0001000001", "EarningsPerShareDiluted", "USD/shares", "2024-07-01", "2024-09-30", "2024-11-01", "10-Q", "8.00"),
                secFact("0001000001", "EarningsPerShareDiluted", "USD/shares", "2024-10-01", "2024-12-31", "2025-02-15", "10-K", "8.00"),
                secFact("0002000002", "EarningsPerShareDiluted", "USD/shares", "2024-04-01", "2024-06-30", "2025-08-01", "10-Q", "8.00"),
                secFact("0002000002", "EarningsPerShareDiluted", "USD/shares", "2024-07-01", "2024-09-30", "2025-11-01", "10-Q", "8.00"),
                secFact("0002000002", "EarningsPerShareDiluted", "USD/shares", "2024-10-01", "2024-12-31", "2026-02-15", "10-K", "8.00"),
                secFact("0002000002", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2026-05-01", "10-Q", "1.00")));

        lineageService.rebuildFinancialPeriods(List.of("LIN_FIGI"));
        StockFinancialPeriod q1_2025 = periodAt("LIN_FIGI", "2025-03-31");
        assertThat(q1_2025.getEpsTtm()).isNull();
        assertThat(q1_2025.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
    }

    @Test
    void preSplitZeroCannotOverridePostSplitMaterialRestatementAndSolePostSplitFilingChecksRival() {
        // 1. 1合100 反向拆股：拆前原報 0.00、拆後重述 0.30；Futu 仍留 0.00 不得於 periodEnd 放行蓋掉有效 SEC 0.30
        Stock revZero = stock("REV0_FIGI", "REV0", "0001222224", "Common Stock");
        StockStatus st = status("REV0_FIGI");
        stubMaster(revZero, st, List.of(split("REV0_FIGI", "2025-06-01", "100", "1")), 1_000_000L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("REV0_FIGI", "2024-12-31", 7, 2024, "0.00", "300000")));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001222224", "EarningsPerShareDiluted", "USD/shares", "2024-01-01", "2024-12-31", "2025-03-01", "10-K", "0.00"),
                secFact("0001222224", "EarningsPerShareDiluted", "USD/shares", "2024-01-01", "2024-12-31", "2026-03-01", "10-K", "0.30")));
        service.rebuildFinancialPeriods(List.of("REV0_FIGI"));
        StockFinancialPeriod fy24 = periodAt("REV0_FIGI", "2024-12-31");
        assertThat(fy24.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(fy24.getEpsTtmSource()).isEqualTo("SEC");
        assertThat(fy24.getEpsTtm()).isEqualByComparingTo("0.30");
        assertThat(fy24.getEpsShareBasisDate()).isEqualTo(LATEST_DAY);

        // 2. 僅有拆後單一 SEC 申報 0.01（2:1 拆股）：Futu 0.02 同時吻合拆前等值 0.02 與 0.02 容差，必須攔截
        SecFinancialFactsCalculator calc = new SecFinancialFactsCalculator();
        StockSplit twoForOne = split("SOLE_FIGI", "2025-04-05", "1", "2");
        SecFinancialFact solePost = secFact("0001222225", "EarningsPerShareDiluted", "USD/shares",
                "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "0.01");
        assertThat(calc.verifyEpsSplitBasis(new BigDecimal("0.02"), null, LocalDate.of(2025, 3, 31), false,
                List.of(solePost), "USD", List.of(twoForOne), LATEST_DAY, LATEST_DAY, NOW))
                .isEqualTo(SecFinancialFactsCalculator.EpsSplitVerification.REJECTED);
    }

    @Test
    void weightedSharesRejectsMismatchedPeriodDatesAndGenericNetIncomeWithPreferredStock() {
        SecFinancialFactsCalculator calc = new SecFinancialFactsCalculator();
        StockSplit twoForOne = split("WS_FIGI", "2025-06-01", "1", "2");

        // 1. 股數區間 01-01..03-31 與淨利區間 01-08..04-07 不一致，不得跨期拼湊
        SecFinancialFact sharesP1 = secFact("0001222226", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares",
                "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "100000000");
        SecFinancialFact niP2 = secFact("0001222226", "NetIncomeLossAvailableToCommonStockholdersBasic", "USD",
                "2025-01-08", "2025-04-07", "2025-05-01", "10-Q", "100000000");
        assertThat(calc.verifyEpsSplitBasis(new BigDecimal("0.50"), null, LocalDate.of(2025, 4, 2), false,
                List.of(sharesP1, niP2), "USD", List.of(twoForOne), LATEST_DAY, LATEST_DAY, NOW))
                .isNotEqualTo(SecFinancialFactsCalculator.EpsSplitVerification.VERIFIED);

        // 2. 存在優先股時不得用未扣優先股股利的通用 NetIncomeLoss 認證拆股基準
        SecFinancialFact genericNi = secFact("0001222226", "NetIncomeLoss", "USD",
                "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "100000000");
        SecFinancialFact prefStock = new SecFinancialFact();
        prefStock.setCik("0001222226");
        prefStock.setTaxonomy("us-gaap");
        prefStock.setTag("PreferredStockValue");
        prefStock.setUnit("USD");
        prefStock.setPeriodStart(null);
        prefStock.setPeriodEnd(LocalDate.of(2025, 3, 31));
        prefStock.setFiled(LocalDate.of(2025, 5, 1));
        prefStock.setAvailableFromDate(LocalDate.of(2025, 5, 2));
        prefStock.setObservedAt(LATEST_CLOSE.minusSeconds(300));
        prefStock.setAccession("0001222226-2025-03-31-pref");
        prefStock.setForm("10-Q");
        prefStock.setValue(new BigDecimal("500000000"));
        assertThat(calc.verifyEpsSplitBasis(new BigDecimal("0.50"), null, LocalDate.of(2025, 3, 31), false,
                List.of(sharesP1, genericNi, prefStock), "USD", List.of(twoForOne), LATEST_DAY, LATEST_DAY, NOW))
                .isNotEqualTo(SecFinancialFactsCalculator.EpsSplitVerification.VERIFIED);
    }

    @Test
    void directSecQ4RivalMatchVetoesAnnual10KReconciliationInService() {
        // 同財年 Q1..Q3 與年報皆可完美配平，但 SEC 另有單季 Q4 事實顯示 Futu Q4 命中對照基準 -> service 必須直接攔截，不得進入 Q4 年報補救
        Stock vetoQ4 = stock("VETOQ4_FIGI", "VETOQ4", "0001222227", "Common Stock");
        StockStatus st = status("VETOQ4_FIGI");
        stubMaster(vetoQ4, st, List.of(split("VETOQ4_FIGI", "2026-06-10", "1", "2")), 100_000_000L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("VETOQ4_FIGI", "2025-03-31", 1, 2025, "4.00", "400000000"),
                quarterReport("VETOQ4_FIGI", "2025-06-30", 2, 2025, "0.50", "50000000"),
                quarterReport("VETOQ4_FIGI", "2025-09-30", 3, 2025, "0.50", "50000000"),
                quarterReport("VETOQ4_FIGI", "2025-12-31", 4, 2025, "1.00", "50000000"),
                quarterReport("VETOQ4_FIGI", "2025-12-31", 7, 2025, "6.00", "600000000"),
                quarterReport("VETOQ4_FIGI", "2026-03-31", 1, 2026, "1.00", "100000000")));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001222227", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "8.00"),
                secFact("0001222227", "EarningsPerShareDiluted", "USD/shares", "2025-04-01", "2025-06-30", "2025-08-01", "10-Q", "1.00"),
                secFact("0001222227", "EarningsPerShareDiluted", "USD/shares", "2025-07-01", "2025-09-30", "2025-11-01", "10-Q", "1.00"),
                // 單季 Q4 拆前原報 1.00（拆後應為 0.50），故 Futu 1.00 明確命中拆前對照口徑！
                secFact("0001222227", "EarningsPerShareDiluted", "USD/shares", "2025-10-01", "2025-12-31", "2026-02-20", "10-K", "1.00"),
                secFact("0001222227", "EarningsPerShareDiluted", "USD/shares", "2025-01-01", "2025-12-31", "2026-02-20", "10-K", "12.00"),
                secFact("0001222227", "EarningsPerShareDiluted", "USD/shares", "2026-01-01", "2026-03-31", "2026-05-01", "10-Q", "2.00")));
        service.rebuildFinancialPeriods(List.of("VETOQ4_FIGI"));
        StockFinancialPeriod q1_2026 = periodAt("VETOQ4_FIGI", "2026-03-31");
        // Futu 混入拆前 Q4 1.00 須被攔截；SEC 四季全為拆前原報（8+1+1+1，其中 2025-06..2026-03 = 5.00）或不可回傳錯誤的 Futu 3.00
        assertThat(q1_2026.getEpsTtmSource()).isNotEqualTo("FUTU");
        assertThat(q1_2026.getEpsTtm()).isNotEqualByComparingTo("3.00");
    }

    @Test
    void multiSplitRepeatedIntermediateWeightedSharesAndFutuParentIncomeOverrideAreRejected() {
        // 1. 兩次 2:1 拆股：SEC 加權股數 100 -> 200 -> 200（末期重複中間態），普通股淨利 1,000，不得認證 Futu 5.00（完全拆後應為 2.50）
        Stock midSh = stock("MIDSH_FIGI", "MIDSH", "0001222228", "Common Stock");
        StockStatus midSt = status("MIDSH_FIGI");
        stubMaster(midSh, midSt, List.of(
                split("MIDSH_FIGI", "2023-06-01", "1", "2"),
                split("MIDSH_FIGI", "2025-06-01", "1", "2")), 400L);
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("MIDSH_FIGI", "2022-12-31", 7, 2022, "5.00", "1000")));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001222228", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", "2022-01-01", "2022-12-31", "2023-02-20", "10-K", "100"),
                secFact("0001222228", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", "2022-01-01", "2022-12-31", "2024-02-20", "10-K", "200"),
                secFact("0001222228", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", "2022-01-01", "2022-12-31", "2026-02-20", "10-K", "200"),
                secFact("0001222228", "NetIncomeLossAvailableToCommonStockholdersBasic", "USD", "2022-01-01", "2022-12-31", "2023-02-20", "10-K", "1000")));
        service.rebuildFinancialPeriods(List.of("MIDSH_FIGI"));
        StockFinancialPeriod fy22 = periodAt("MIDSH_FIGI", "2022-12-31");
        assertThat(fy22.getEpsTtm()).isNull();
        assertThat(fy22.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);

        // 1b. 移除首版 100 股原報，僅剩兩次拆股間重複的 200 -> 200：回退路徑亦不得重選未重述之 200 並認證 5.00，而須依首份 200 支援完全拆後 2.50
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001222228", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", "2022-01-01", "2022-12-31", "2024-02-20", "10-K", "200"),
                secFact("0001222228", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", "2022-01-01", "2022-12-31", "2026-02-20", "10-K", "200"),
                secFact("0001222228", "NetIncomeLossAvailableToCommonStockholdersBasic", "USD", "2022-01-01", "2022-12-31", "2024-02-20", "10-K", "1000")));
        service.rebuildFinancialPeriods(List.of("MIDSH_FIGI"));
        StockFinancialPeriod fy22NoOrig = periodAt("MIDSH_FIGI", "2022-12-31");
        assertThat(fy22NoOrig.getEpsTtm()).isNull();
        assertThat(fy22NoOrig.getEpsTtmReason()).isEqualTo(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(
                quarterReport("MIDSH_FIGI", "2022-12-31", 7, 2022, "2.50", "1000")));
        service.rebuildFinancialPeriods(List.of("MIDSH_FIGI"));
        StockFinancialPeriod fy22Adjusted = periodAt("MIDSH_FIGI", "2022-12-31");
        assertThat(fy22Adjusted.getEpsTtmStatus()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(fy22Adjusted.getEpsTtm()).isEqualByComparingTo("2.50");
        assertThat(fy22Adjusted.getEpsShareBasisDate()).isEqualTo(LATEST_DAY);

        // 2. Futu 僅有母公司淨利 100M（缺普通股淨利與優先股股利），SEC 明示普通股淨利 50M 且有優先股：不得以母公司淨利 100M / 200M = 0.50 誤認證拆前 EPS 0.50
        Stock prefFutu = stock("PREFF_FIGI", "PREFF", "0001222229", "Common Stock");
        StockStatus prefSt = status("PREFF_FIGI");
        stubMaster(prefFutu, prefSt, List.of(split("PREFF_FIGI", "2025-06-01", "1", "2")), 200_000_000L);
        FinancialReport parentOnly = new FinancialReport();
        parentOnly.setFigi("PREFF_FIGI");
        parentOnly.setStatementType(1);
        parentOnly.setPeriodType(7);
        parentOnly.setFiscalYear(2024);
        parentOnly.setPeriodEnd(LocalDate.of(2024, 12, 31));
        parentOnly.setCurrency("USD");
        parentOnly.setAccountingStandards("US_GAAP");
        parentOnly.setObservedAt(LATEST_CLOSE.minusSeconds(300));
        parentOnly.setItems(List.of(
                new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal("0.50"), null, null),
                new FinancialReport.Item(8040L, "Net Income to Parent Company", new BigDecimal("100000000"), null, null)));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(parentOnly));
        SecFinancialFact prefVal = new SecFinancialFact();
        prefVal.setCik("0001222229");
        prefVal.setTaxonomy("us-gaap");
        prefVal.setTag("PreferredStockValue");
        prefVal.setUnit("USD");
        prefVal.setPeriodStart(null);
        prefVal.setPeriodEnd(LocalDate.of(2024, 12, 31));
        prefVal.setFiled(LocalDate.of(2025, 2, 20));
        prefVal.setAvailableFromDate(LocalDate.of(2025, 2, 21));
        prefVal.setObservedAt(LATEST_CLOSE.minusSeconds(300));
        prefVal.setAccession("0001222229-2024-12-31-pref");
        prefVal.setForm("10-K");
        prefVal.setValue(new BigDecimal("500000000"));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(
                secFact("0001222229", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", "2024-01-01", "2024-12-31", "2025-02-20", "10-K", "100000000"),
                secFact("0001222229", "NetIncomeLossAvailableToCommonStockholdersBasic", "USD", "2024-01-01", "2024-12-31", "2025-02-20", "10-K", "50000000"),
                prefVal));
        service.rebuildFinancialPeriods(List.of("PREFF_FIGI"));
        StockFinancialPeriod fy24 = periodAt("PREFF_FIGI", "2024-12-31");
        assertThat(fy24.getEpsShareBasisDate()).isNotEqualTo(LATEST_DAY);
        // 拆前應以 50M / 100M = 0.50 精準對齊至 periodEnd（而非誤標 latest）
        assertThat(fy24.getEpsShareBasisDate()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    private StockFinancialPeriod periodAt(String figi, String endDate) {
        LocalDate end = LocalDate.parse(endDate);
        return storedPeriods.getOrDefault(figi, List.of()).stream()
                .filter(p -> end.equals(p.getPeriodEnd()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing period " + endDate + " for " + figi));
    }

    private void stubMaster(Stock stock, StockStatus status, List<StockSplit> stockSplits, long shares) {
        String figi = stock.getFigi();
        when(stocks.findById(figi)).thenReturn(Optional.of(stock));
        when(stocks.findAllById(anyCollection())).thenReturn(List.of(stock));
        when(statuses.findById(figi)).thenReturn(Optional.of(status));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(status));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(stockSplits);
        StockShareObservation obs = new StockShareObservation();
        obs.setFigi(figi);
        obs.setObservedAt(LATEST_CLOSE.minusSeconds(600));
        obs.setIssuedShares(shares);
        obs.setOutstandingShares(shares);
        obs.setSource("FUTU");
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(obs));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq(figi), eq("FUTU"), any()))
                .thenReturn(Optional.of(obs));
        StockQuote q = new StockQuote();
        q.setFigi(figi);
        q.setQuoteDate(LATEST_DAY);
        q.setClosePrice(new BigDecimal("100"));
        when(quotes.findStoredQuotes(anyCollection(), eq(LATEST_DAY))).thenReturn(List.of(q));
    }

    private Stock stock(String figi, String symbol, String cik, String stockType) {
        Stock s = new Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setLongName(symbol + " Corp");
        s.setCik(cik);
        s.setCurrency("USD");
        s.setStockType(stockType);
        s.setActiveStatus(ActiveStatus.ACTIVE);
        s.setTracked(true);
        return s;
    }

    private StockStatus status(String figi) {
        StockStatus st = new StockStatus();
        st.setFigi(figi);
        st.setQuoteDate(LATEST_DAY);
        st.setFinancialReportDate(LATEST_DAY);
        st.setSecFinancialFactsDate(LATEST_DAY);
        st.setSplitFromDate(LocalDate.of(2014, 1, 1));
        st.setSplitDate(LATEST_DAY);
        return st;
    }

    private StockSplit split(String figi, String execDate, String from, String to) {
        StockSplit sp = new StockSplit();
        sp.setSourceEventId(figi + ":" + execDate);
        sp.setFigi(figi);
        sp.setExecutionDate(LocalDate.parse(execDate));
        sp.setSplitFrom(new BigDecimal(from));
        sp.setSplitTo(new BigDecimal(to));
        return sp;
    }

    private FinancialReport quarterReport(String figi, String end, int periodType, int fy, String eps, String netIncome) {
        FinancialReport fr = new FinancialReport();
        fr.setFigi(figi);
        fr.setStatementType(1);
        fr.setPeriodType(periodType);
        fr.setFiscalYear(fy);
        fr.setPeriodEnd(LocalDate.parse(end));
        fr.setCurrency("USD");
        fr.setAccountingStandards("US_GAAP");
        fr.setObservedAt(LATEST_CLOSE.minusSeconds(300));
        fr.setItems(List.of(
                new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal(eps), null, null),
                new FinancialReport.Item(8046L, "Net Income to Common Stockholders", new BigDecimal(netIncome), null, null),
                new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal("100000000"), null, null)));
        return fr;
    }

    private FinancialReport quarterReportWithoutEps(String figi, String end, int periodType, int fy, String netIncome) {
        FinancialReport fr = new FinancialReport();
        fr.setFigi(figi);
        fr.setStatementType(1);
        fr.setPeriodType(periodType);
        fr.setFiscalYear(fy);
        fr.setPeriodEnd(LocalDate.parse(end));
        fr.setCurrency("USD");
        fr.setAccountingStandards("US_GAAP");
        fr.setObservedAt(LATEST_CLOSE.minusSeconds(300));
        fr.setItems(List.of(
                new FinancialReport.Item(8046L, "Net Income to Common Stockholders", new BigDecimal(netIncome), null, null),
                new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal("100000000"), null, null)));
        return fr;
    }

    private SecFinancialFact secFact(String cik, String tag, String unit, String start, String end, String filed, String form, String val) {
        SecFinancialFact f = new SecFinancialFact();
        f.setCik(cik);
        f.setTaxonomy("us-gaap");
        f.setTag(tag);
        f.setUnit(unit);
        f.setPeriodStart(LocalDate.parse(start));
        f.setPeriodEnd(LocalDate.parse(end));
        f.setFiled(LocalDate.parse(filed));
        f.setAvailableFromDate(LocalDate.parse(filed).plusDays(1));
        f.setObservedAt(LATEST_CLOSE.minusSeconds(300));
        f.setAccession(cik + "-" + end + "-" + filed);
        f.setForm(form);
        f.setValue(new BigDecimal(val));
        return f;
    }
}
