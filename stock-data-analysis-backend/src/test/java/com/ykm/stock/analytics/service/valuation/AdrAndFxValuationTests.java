package com.ykm.stock.analytics.service.valuation;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.FxRateDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.FxRateDailyRepository;
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
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.FxRateService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.StockValuationService.ReportValuationBundle;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdrAndFxValuationTests {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 13);
    private static final Instant CLOSE = Instant.parse("2026-11-13T21:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final StockShareObservationRepository observations = mock(StockShareObservationRepository.class);
    private final StockIndustryHistoryRepository industryHistory = mock(StockIndustryHistoryRepository.class);
    private final IndustryValuationConfigRepository industryConfigs = mock(IndustryValuationConfigRepository.class);
    private final StockFinancialPeriodRepository periods = mock(StockFinancialPeriodRepository.class);
    private final StockValuationLatestRepository latestValuations = mock(StockValuationLatestRepository.class);
    private final StockCustomPeerRepository customPeers = mock(StockCustomPeerRepository.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final DateTimeService dateTime = mock(DateTimeService.class);
    private final SecIssuerHistory issuerHistory = mock(SecIssuerHistory.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final FxRateDailyRepository fxRepo = mock(FxRateDailyRepository.class);
    private final AdrShareRatioRepository adrRepo = mock(AdrShareRatioRepository.class);

    private StockValuationService service;

    @BeforeEach
    void setup() {
        when(dateTime.now()).thenReturn(CLOSE);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(DAY);
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getSessionCloseAt(any())).thenReturn(CLOSE);
        when(stocks.findMultiSecurityIssuers(anyCollection(), any())).thenReturn(java.util.Set.of());
        when(splits.findByFigiOrderByExecutionDateAsc(any())).thenReturn(List.of());
        when(latestValuations.findById(any())).thenReturn(Optional.empty());
        when(customPeers.findByFigiOrderBySortOrderAsc(any())).thenReturn(List.of());

        FxRateService fxRateService = new FxRateService(fxRepo, null, dateTime, 14L);
        service = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, observations,
                industryHistory, industryConfigs, periods, latestValuations, customPeers,
                calendar, dateTime, issuerHistory, transactions, entityManager,
                fxRateService, adrRepo);
    }

    @Test
    void verifiedAdrConvertsTwdToUsdAndUsesCompanyLevelPeConsistently() {
        // TSM: 5 ordinary shares per ADR, TWD reporting, fx = 0.03125 USD/TWD (32 TWD/USD)
        // Ordinary shares = 25,000,000,000 -> ADR count = 5,000,000,000
        // ADR close = $200 -> Market Cap = $1,000,000,000,000 ($1T)
        // TTM Net Income = 1,600,000,000,000 TWD = $50,000,000,000 USD -> Company-level P/E = 1T / 50B = 20.00x
        Stock tsm = adrStock("FIGI_TSM", "TSM");
        StockStatus st = syncedStatus("FIGI_TSM");
        StockQuote quote = quote("FIGI_TSM", DAY, "200.00");

        StockFinancialPeriod period = new StockFinancialPeriod();
        period.setFigi("FIGI_TSM");
        period.setPeriodStart(LocalDate.of(2026, 7, 1));
        period.setPeriodEnd(LocalDate.of(2026, 9, 30));
        period.setAvailableAt(CLOSE.minusSeconds(86400 * 10L));
        period.setReportingCurrency("TWD");
        period.setAccountingStandards("IFRS");
        period.setRevenueTtm(new BigDecimal("3200000000000"));
        period.setRevenueTtmStatus(MetricStatus.AVAILABLE.name());
        period.setNetIncomeCommonTtm(new BigDecimal("1600000000000"));
        period.setNetIncomeCommonTtmStatus(MetricStatus.AVAILABLE.name());
        period.setEpsTtm(new BigDecimal("320.00"));
        period.setEpsTtmStatus(MetricStatus.AVAILABLE.name());
        period.setEpsShareBasisDate(LocalDate.of(2026, 9, 30));
        period.setFreeCashFlowTtm(new BigDecimal("960000000000"));
        period.setFreeCashFlowTtmStatus(MetricStatus.AVAILABLE.name());
        period.setCommonEquity(new BigDecimal("4000000000000"));
        period.setCommonEquityStatus(MetricStatus.AVAILABLE.name());
        // StockFinancialPeriod.sharesOutstanding stores ADR-equivalent shares (25B ordinary / 5 = 5B ADR)
        period.setSharesOutstanding(new BigDecimal("5000000000"));
        period.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
        period.setSharesOutstandingSource("SEC_FILING_SHARES");
        period.setSharesBasisDate(LocalDate.of(2026, 9, 30));

        AdrShareRatio ratio = new AdrShareRatio();
        ratio.setSymbol("TSM");
        ratio.setAdrRatio(new BigDecimal("5"));
        ratio.setVerified(true);
        ratio.setNote("SEC_20F");

        FxRateDaily twd = new FxRateDaily();
        twd.setCurrency("TWD");
        twd.setRateDate(DAY);
        twd.setUsdPerUnit(new BigDecimal("0.03125000"));
        twd.setSource("FRED_H10:DEXTAUS");

        when(statuses.findById("FIGI_TSM")).thenReturn(Optional.of(st));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(any(), any(), any())).thenReturn(List.of(quote));
        when(periods.findByFigiOrderByPeriodEndAsc("FIGI_TSM")).thenReturn(List.of(period));
        when(adrRepo.findById("TSM")).thenReturn(Optional.of(ratio));
        when(fxRepo.findByCurrenciesBetween(anyCollection(), any(), any())).thenReturn(List.of(twd));

        ReportValuationBundle bundle = service.evaluateForReport(tsm, DAY, CLOSE, CLOSE, null);
        ValuationResultDto val = bundle.valuation();

        assertThat(val.status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(val.metrics().pe().value()).isEqualByComparingTo("20.0000");
        assertThat(val.metrics().ps().value()).isEqualByComparingTo("10.0000");
        assertThat(val.fxConversion()).isNotNull();
        assertThat(val.fxConversion().reportingCurrency()).isEqualTo("TWD");
        assertThat(val.fxConversion().targetCurrency()).isEqualTo("USD");
        assertThat(val.fxConversion().adrRatio()).isEqualByComparingTo("5");
        assertThat(val.fxConversion().adrRatioVerified()).isTrue();
        assertThat(bundle.fundamentals().earningsYield().value()).isEqualByComparingTo("0.050000");
    }

    @Test
    void unverifiedAdrIsBlockedWithAdrRatioUnverified() {
        Stock obscureAdr = adrStock("FIGI_OBSC", "OBSC");
        StockStatus st = syncedStatus("FIGI_OBSC");
        StockQuote quote = quote("FIGI_OBSC", DAY, "25.00");

        StockFinancialPeriod period = new StockFinancialPeriod();
        period.setFigi("FIGI_OBSC");
        period.setPeriodStart(LocalDate.of(2026, 7, 1));
        period.setPeriodEnd(LocalDate.of(2026, 9, 30));
        period.setAvailableAt(CLOSE.minusSeconds(86400 * 10L));
        period.setReportingCurrency("USD");
        period.setRevenueTtm(new BigDecimal("1000000000"));
        period.setRevenueTtmStatus(MetricStatus.AVAILABLE.name());
        period.setGrossProfitTtm(new BigDecimal("600000000"));
        period.setNetIncomeCommonTtm(new BigDecimal("100000000"));
        period.setNetIncomeCommonTtmStatus(MetricStatus.AVAILABLE.name());
        period.setFreeCashFlowTtm(new BigDecimal("200000000"));
        period.setFreeCashFlowTtmStatus(MetricStatus.AVAILABLE.name());
        period.setSbcTtm(new BigDecimal("20000000"));
        period.setCommonEquity(new BigDecimal("500000000"));
        period.setCommonEquityStatus(MetricStatus.AVAILABLE.name());
        period.setGoodwillAndIntangibles(new BigDecimal("50000000"));
        period.setSharesOutstanding(new BigDecimal("100000000"));
        period.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
        period.setSharesBasisDate(LocalDate.of(2026, 9, 30));

        when(statuses.findById("FIGI_OBSC")).thenReturn(Optional.of(st));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(any(), any(), any())).thenReturn(List.of(quote));
        when(periods.findByFigiOrderByPeriodEndAsc("FIGI_OBSC")).thenReturn(List.of(period));
        when(adrRepo.findById("OBSC")).thenReturn(Optional.empty());

        ReportValuationBundle bundle = service.evaluateForReport(obscureAdr, DAY, CLOSE, CLOSE, null);

        assertThat(bundle.valuation().status()).isEqualTo(MetricStatus.UNVERIFIED.name());
        assertThat(bundle.valuation().reasonCode()).isEqualTo("ADR_RATIO_UNVERIFIED");
        assertThat(bundle.valuation().metrics().pe().value()).isNull();
        assertThat(bundle.valuation().metrics().pe().reasonCode()).isEqualTo("ADR_RATIO_UNVERIFIED");
        assertThat(bundle.valuation().fxConversion()).isNotNull();
        assertThat(bundle.valuation().fxConversion().adrRatioVerified()).isFalse();
        // USD 報表之未核實 ADR 也必須阻斷所有依賴市值／每股的衍生指標與財務明細股數，但可保留純原幣毛利率
        assertThat(bundle.marketCapAtDay()).isNull();
        assertThat(bundle.sharesAtDay()).isNull();
        assertThat(bundle.financials()).isNotNull();
        assertThat(bundle.financials().sharesOutstanding().value()).isNull();
        assertThat(bundle.financials().bookValuePerShare().value()).isNull();
        assertThat(bundle.fundamentals().fcfYield().status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertThat(bundle.fundamentals().fcfYield().reasons().getFirst().code()).isEqualTo("ADR_RATIO_UNVERIFIED");
        assertThat(bundle.fundamentals().pToAdjFcf().status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertThat(bundle.fundamentals().pToTbv().status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertThat(bundle.fundamentals().grossMargin().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(bundle.fundamentals().grossMargin().value()).isEqualByComparingTo("0.600000");
    }

    @Test
    void verifiedAdrHistoricalSeriesDoesNotBackfillFutureFutuShareObservation() {
        Stock nvo = adrStock("FIGI_NVO", "NVO");
        StockStatus st = syncedStatus("FIGI_NVO");
        LocalDate pastDay = LocalDate.of(2025, 10, 31);
        StockQuote pastQuote = quote("FIGI_NVO", pastDay, "100.00");
        StockQuote latestQuote = quote("FIGI_NVO", DAY, "120.00");

        StockFinancialPeriod period = new StockFinancialPeriod();
        period.setFigi("FIGI_NVO");
        period.setPeriodStart(LocalDate.of(2025, 7, 1));
        period.setPeriodEnd(LocalDate.of(2025, 9, 30));
        period.setAvailableAt(Instant.parse("2025-10-15T21:00:00Z"));
        period.setReportingCurrency("USD");
        period.setRevenueTtm(new BigDecimal("40000000000"));
        period.setRevenueTtmStatus(MetricStatus.AVAILABLE.name());
        period.setNetIncomeCommonTtm(new BigDecimal("10000000000"));
        period.setNetIncomeCommonTtmStatus(MetricStatus.AVAILABLE.name());
        // 缺少該期 SEC 申報股數
        period.setSharesOutstanding(null);
        period.setSharesOutstandingStatus(MetricStatus.INSUFFICIENT_DATA.name());

        AdrShareRatio ratio = new AdrShareRatio();
        ratio.setSymbol("NVO");
        ratio.setAdrRatio(BigDecimal.ONE);
        ratio.setVerified(true);

        com.ykm.stock.analytics.model.entity.StockShareObservation futureFutuObs =
                new com.ykm.stock.analytics.model.entity.StockShareObservation();
        futureFutuObs.setFigi("FIGI_NVO");
        futureFutuObs.setSource("FUTU");
        futureFutuObs.setObservedAt(CLOSE);
        futureFutuObs.setOutstandingShares(4_400_000_000L);

        when(statuses.findById("FIGI_NVO")).thenReturn(Optional.of(st));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(any(), any(), any()))
                .thenReturn(List.of(pastQuote, latestQuote));
        when(periods.findByFigiOrderByPeriodEndAsc("FIGI_NVO")).thenReturn(List.of(period));
        when(adrRepo.findById("NVO")).thenReturn(Optional.of(ratio));
        when(observations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(any(), any(), any()))
                .thenReturn(Optional.of(futureFutuObs));

        ReportValuationBundle bundle = service.evaluateForReport(nvo, DAY, CLOSE, CLOSE, null);
        // 歷史月點（2025-10-31）絕不可倒填 2026-11-13 的 Futu 最新股數
        ValuationResultDto pastVal = bundle.history().stream()
                .filter(v -> pastDay.equals(v.valuationDate())).findFirst().orElseThrow();
        assertThat(pastVal.metrics().ps().value()).isNull();
        assertThat(pastVal.metrics().ps().reasonCode()).isEqualTo("VALUATION_DATE_SHARES_MISSING");
        // 最新交易日（2026-11-13）同日 Futu 股數仍可正常計算
        assertThat(bundle.valuation().metrics().ps().value()).isNotNull();
    }

    @Test
    void securityTypePolicyKeepsOperatingTypesFreeOfAdrWhileValuationAndEtfRoutesAreSeparated() {
        assertThat(SecurityTypePolicy.OPERATING_STOCK_TYPES).doesNotContain("ADR", "ETF", "ETP");
        assertThat(SecurityTypePolicy.VALUATION_STOCK_TYPES).contains("Common Stock", "ADR").doesNotContain("ETF", "ETP");
        assertThat(SecurityTypePolicy.ETF_STOCK_TYPES).containsExactlyInAnyOrder("ETF", "ETP");
        assertThat(SecurityTypePolicy.isEtfType("ETF")).isTrue();
        assertThat(SecurityTypePolicy.isEtfType("ETP")).isTrue();
        assertThat(SecurityTypePolicy.isAdrType("ADR")).isTrue();
        assertThat(SecurityTypePolicy.isAnalyzableType("ETF")).isTrue();
        assertThat(SecurityTypePolicy.isAnalyzableType("ADR")).isTrue();
    }

    private Stock adrStock(String figi, String symbol) {
        Stock s = new Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setLongName(symbol + " ADR");
        s.setCurrency("USD");
        s.setStockType("ADR");
        s.setTracked(true);
        s.setActiveStatus(ActiveStatus.ACTIVE);
        return s;
    }

    private StockStatus syncedStatus(String figi) {
        StockStatus st = new StockStatus();
        st.setFigi(figi);
        st.setQuoteDate(DAY);
        return st;
    }

    private StockQuote quote(String figi, LocalDate day, String close) {
        StockQuote q = new StockQuote();
        q.setFigi(figi);
        q.setQuoteDate(day);
        q.setClosePrice(new BigDecimal(close));
        q.setOpenPrice(new BigDecimal(close));
        q.setHighPrice(new BigDecimal(close));
        q.setLowPrice(new BigDecimal(close));
        q.setVolume(new BigDecimal("1000000"));
        return q;
    }
}
