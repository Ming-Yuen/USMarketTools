package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.converter.jpa.StockConverterImpl;
import com.ykm.stock.analytics.client.SecTickerClient;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.dto.finnhub.response.CompanyProfile2;
import com.ykm.stock.analytics.model.dto.finnhub.response.Symbol;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.FinnhubService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockDailyTaskTests {
    private final SecTickerClient sec = mock(SecTickerClient.class);
    private final MassiveService massive = mock(MassiveService.class);
    private final FinnhubService finnhub = mock(FinnhubService.class);
    private final StockService stocks = mock(StockService.class);
    private final StockDailyTask task = new StockDailyTask(massive, finnhub, sec, new StockConverterImpl(), stocks,
            new SecurityTypePolicy(), new TransactionTemplate(new NoOpTransactionManager()));

    @BeforeEach
    void defaults() {
        when(stocks.findAllStocks()).thenReturn(List.of());
        when(sec.getTickerCiks()).thenReturn(Map.of("ABC", "0000123456"));
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(finnhubSymbol("F1", "ABC", "Common Stock")));
    }

    /** Scenario：FIGI為主鍵，主檔由bulk來源映射，新股票同時建立status。 */
    @Test
    void createsMasterAndStatusWithoutPerTickerDetails() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        task.execute(null);
        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1 && "F1".equals(rows.getFirst().getFigi())
                && "ABC".equals(rows.getFirst().getSymbol()) && "USD".equals(rows.getFirst().getCurrency())
                && "0000123456".equals(rows.getFirst().getCik())));
        verify(stocks).updateStockStatuses(Set.of("F1"));
        verify(massive).getAllStockTickers();
        verifyNoMoreInteractions(massive);
    }

    /** Scenario：同FIGI改名只更新既有主檔，不建立另一份股票或新status。 */
    @Test
    void renameKeepsFigiAndStatus() {
        Stock existing = stock("F1", "OLD");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "NEW")));
        task.execute(null);
        assertThat(existing.getSymbol()).isEqualTo("NEW");
        verify(stocks).updateStocks(List.of(existing));
        verify(stocks, never()).updateStockStatuses(anySet());
    }

    /** Scenario：完整active快照成功後，消失的舊股票不再被日行情誤當目前ticker。 */
    @Test
    void completeMasterDeactivatesAbsentStock() {
        Stock present = stock("F1", "ABC");
        Stock absent = stock("F2", "OLD");
        when(stocks.findAllStocks()).thenReturn(List.of(present, absent));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        task.execute(null);
        assertThat(absent.getActiveStatus()).isEqualTo(ActiveStatus.INACTIVE);
        verify(stocks).updateStocks(List.of(absent));
    }

    /** Scenario：來源失敗、全空或無效主檔，不進行任何停用或其他DB寫入。 */
    @Test
    void missingMasterNeverMutatesDatabase() {
        when(massive.getAllStockTickers()).thenReturn(List.of());
        assertThatThrownBy(() -> task.execute(null)).hasMessage("EMPTY_STOCK_MASTER");
        verifyNoInteractions(stocks);
        when(massive.getAllStockTickers()).thenThrow(new IllegalStateException("SOURCE_FAILED"));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("SOURCE_FAILED");
        verifyNoInteractions(stocks);
    }

    /** Scenario：同FIGI不同ticker的來源衝突不能默默取最後一筆。 */
    @Test
    void conflictingFigiDoesNotReachPersistence() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "A"), ticker("F1", "B")));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("AMBIGUOUS_MASTER_FIGI:F1");
        verifyNoInteractions(stocks);
    }

    /** Scenario：401隻股票仍以200/200/1批量保存，不逐股寫入或呼叫Details。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void batchesMasterAndNewStatuses() {
        when(massive.getAllStockTickers()).thenReturn(IntStream.range(0, 401).mapToObj(i -> ticker("F" + i, "S" + i)).toList());
        task.execute(null);
        ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
        verify(stocks, times(3)).updateStocks(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(200, 200, 1);
        verify(stocks, times(3)).updateStockStatuses(anySet());
        verify(massive).getAllStockTickers();
        verifyNoMoreInteractions(massive);
    }


    /** Scenario：普通股、普通 ADR、ETF 入庫；優先股、債券、非普通 ADR、未知類型不入庫。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retainsCommonSharesCommonAdrAndEtfOnly() {
        MassiveTickerDetails ordinary = ticker("F2", "ORD");
        ordinary.setType("OS");
        MassiveTickerDetails adr = ticker("F3", "TSM");
        adr.setType("ADRC");
        MassiveTickerDetails etf = ticker("F4", "SPY");
        etf.setType("ETF");
        MassiveTickerDetails preferred = ticker("F5", "PREF");
        preferred.setType("PFD");
        MassiveTickerDetails preferredAdr = ticker("F6", "ADRP");
        preferredAdr.setType("ADRP");
        MassiveTickerDetails warrant = ticker("F7", "WAR");
        warrant.setType("WARRANT");
        MassiveTickerDetails adrWarrant = ticker("F8", "ADRW");
        adrWarrant.setType("ADRW");
        MassiveTickerDetails bond = ticker("F9", "BOND");
        bond.setType("BOND");
        MassiveTickerDetails unknown = ticker("F10", "UNKNOWN");
        unknown.setType(null);
        when(massive.getAllStockTickers()).thenReturn(List.of(
                ticker("F1", "COMMON"), ordinary, adr, etf, preferred, preferredAdr, warrant, adrWarrant, bond, unknown));
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(
                finnhubSymbol("F1", "COMMON", "Common Stock"), finnhubSymbol("F2", "ORD", "Common Stock"),
                finnhubSymbol("F3", "TSM", "ADR"), finnhubSymbol("F4", "SPY", "ETP")));

        task.execute(null);

        ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
        verify(stocks).updateStocks(batches.capture());
        List<Stock> saved = (List<Stock>) batches.getValue();
        assertThat(saved).extracting(Stock::getFigi).containsExactly("F1", "F2", "F3", "F4");
        assertThat(saved).extracting(Stock::getStockType).containsExactly("Common Stock", "Common Stock", "ADR", "ETP");
        verify(stocks).updateStockStatuses(Set.of("F1", "F2", "F3", "F4"));
        verify(finnhub, never()).getCompanyProfile2("TSM");
        verify(finnhub, never()).getCompanyProfile2("SPY");
    }

    /** Scenario：以前已入庫的優先股在完成一次成功主檔同步後停用，不直接刪除。 */
    @Test
    void deactivatesExistingExcludedSecurity() {
        Stock existingPreferred = stock("F2", "PREF");
        when(stocks.findAllStocks()).thenReturn(List.of(existingPreferred));
        MassiveTickerDetails preferred = ticker("F2", "PREF");
        preferred.setType("PFD");
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "COMMON"), preferred));

        task.execute(null);

        assertThat(existingPreferred.getActiveStatus()).isEqualTo(ActiveStatus.INACTIVE);
        verify(stocks).updateStocks(List.of(existingPreferred));
        verify(stocks).updateStockStatuses(Set.of("F1"));
    }

    @Test
    void massiveConverterNeverOverwritesSecCik() {
        Stock existing = stock("F1", "ABC");
        existing.setCik("0000000001");
        new StockConverterImpl().updateFromMassive(ticker("F1", "ABC"), existing);
        assertThat(existing.getCik()).isEqualTo("0000000001");
    }
    @Test
    void secResolvedCikReplacesProviderValue() {
        Stock moved = stock("F1", "NBN");
        moved.setCik("0000060519");
        Stock oldPeer = stock("F2", "LPX");
        oldPeer.setCik("0000060519");
        Stock newPeer = stock("F3", "CLASS-B");
        newPeer.setCik("0000000007");
        when(stocks.findAllStocks()).thenReturn(List.of(moved, oldPeer, newPeer));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "NBN"), ticker("F2", "LPX"), ticker("F3", "CLASS-B")));
        when(sec.getTickerCiks()).thenReturn(Map.of("NBN", "0000000007", "LPX", "0000060519", "CLASS-B", "0000000007"));
        task.execute(null);
        assertThat(moved.getCik()).isEqualTo("0000000007");
        verify(sec).getTickerCiks();
    }

    @Test
    void unknownNbnClearsOldMassiveCikButRetainsActiveStock() {
        Stock existing = stock("F1", "NBN");
        existing.setCik("0000060519");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "NBN")));
        when(sec.getTickerCiks()).thenReturn(Map.of("LPX", "0000060519"));
        task.execute(null);
        assertThat(existing.getCik()).isNull();
        assertThat(existing.getActiveStatus()).isEqualTo(ActiveStatus.ACTIVE);
    }

    @Test
    void secFailureNeverStartsTransactionOrMutatesMaster() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        StockDailyTask guarded = new StockDailyTask(massive, finnhub, sec, new StockConverterImpl(), stocks,
                new SecurityTypePolicy(), transaction);
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        when(sec.getTickerCiks()).thenThrow(new IllegalStateException("SEC_TICKERS_INVALID"));
        assertThatThrownBy(() -> guarded.execute(null)).hasMessage("SEC_TICKERS_INVALID");
        verifyNoInteractions(stocks, transaction);
    }

    @Test
    void repeatedMasterKeepsTheSameCik() {
        Stock existing = stock("F1", "ABC");
        existing.setCik("0000123456");
        existing.setStockType("CS");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        task.execute(null);
        task.execute(null);
        verify(sec, times(2)).getTickerCiks();
    }

    @Test
    void newShareClassGetsItsOwnStatus() {
        Stock existing = stock("F1", "ABC");
        existing.setCik("0000123456");
        existing.setStockType("CS");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(sec.getTickerCiks()).thenReturn(Map.of("ABC", "0000123456", "ABC-B", "0000123456"));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC"), ticker("F2", "ABC.B")));
        task.execute(null);
        verify(stocks).updateStockStatuses(Set.of("F2"));
    }

    @Test
    void delistedShareClassCanBeReactivated() {
        Stock existing = stock("F1", "ABC");
        existing.setCik("0000123456");
        existing.setStockType("CS");
        Stock sibling = stock("F2", "ABC-B");
        sibling.setCik("0000123456");
        sibling.setStockType("CS");
        when(stocks.findAllStocks()).thenReturn(List.of(existing, sibling));
        when(sec.getTickerCiks()).thenReturn(Map.of("ABC", "0000123456", "ABC-B", "0000123456"));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        task.execute(null);
        assertThat(sibling.getActiveStatus()).isEqualTo(ActiveStatus.INACTIVE);
        clearInvocations(stocks);
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC"), ticker("F2", "ABC-B")));
        task.execute(null);
        assertThat(sibling.getActiveStatus()).isEqualTo(ActiveStatus.ACTIVE);
    }
    /** Scenario：基金及信託仍留在主檔，但原始 Finnhub 分類不能被 Massive CS 覆蓋。 */
    @Test
    void writesDetailedFinnhubTypesForFundsAndTrusts() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "BANX"), ticker("F2", "CRT")));
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(
                finnhubSymbol("F1", "BANX", "Closed-End Fund"), finnhubSymbol("F2", "CRT", "Royalty Trst")));

        task.execute(null);

        verify(stocks).updateStocks(argThat(rows -> rows.size() == 2
                && "Closed-End Fund".equals(rows.get(0).getStockType())
                && "Royalty Trst".equals(rows.get(1).getStockType())));
    }

    /** Scenario：ticker/MIC 相同但 FIGI、Share Class FIGI 不同，不誤用另一家公司分類。 */
    @Test
    void tickerReuseWithoutMatchingFigiRemainsUnclassified() {
        MassiveTickerDetails target = ticker("F1", "FTW");
        target.setPrimaryExchange("XNYS");
        target.setShareClassFigi("MASSIVE-SHARE");
        Symbol differentIssuer = finnhubSymbol("OTHER", "FTW", "Common Stock");
        differentIssuer.setMic("XNYS");
        differentIssuer.setShareClassFIGI("FINNHUB-SHARE");
        when(massive.getAllStockTickers()).thenReturn(List.of(target));
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(differentIssuer));

        task.execute(null);

        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1 && rows.getFirst().getStockType() == null));
    }

    /** Scenario：Composite FIGI 不同但股份類別、Symbol、MIC 相同，仍可確認基金分類。 */
    @Test
    void matchesSameShareClassAndVenueWhenCompositeFigiDiffers() {
        MassiveTickerDetails target = ticker("F1", "CHY");
        target.setPrimaryExchange("XNAS");
        target.setShareClassFigi("SHARE-1");
        Symbol sameShareClass = finnhubSymbol("OTHER", "CHY", "Closed-End Fund");
        sameShareClass.setShareClassFIGI("SHARE-1");
        when(massive.getAllStockTickers()).thenReturn(List.of(target));
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(sameShareClass));

        task.execute(null);

        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1
                && "Closed-End Fund".equals(rows.getFirst().getStockType())));
    }

    /** Scenario：Finnhub 清單下載失敗時不修改主檔或停用股票。 */
    @Test
    void finnhubFailureNeverMutatesMaster() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        when(finnhub.getStockSymbols("US")).thenThrow(new IllegalStateException("FINNHUB_FAILED"));

        assertThatThrownBy(() -> task.execute(null)).hasMessage("FINNHUB_FAILED");
        verifyNoInteractions(stocks);
    }

    /** Scenario：富途尚無產業的銀行，以 Finnhub 當前公司產業補入主檔。 */
    @Test
    void missingFutuIndustryCanUseFinnhubBankingProfile() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ASBH")));
        CompanyProfile2 profile = new CompanyProfile2();
        profile.setTicker("ASBH");
        profile.setFinnhubIndustry("Banking");
        when(finnhub.getCompanyProfile2("ASBH")).thenReturn(profile);

        task.execute(null);

        verify(finnhub).getCompanyProfile2("ASBH");
        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1
                && "Banking".equals(rows.getFirst().getFinnhubIndustry())));
    }

    /** Scenario：免費 API 的實際多欄位 JSON 只提取產業，不誤用缺失的股份資料。 */
    @Test
    void companyProfile2ReadsBankingFromRealisticApiPayload() throws Exception {
        String json = """
                {"ticker":"ASBH","name":"American Savings Bank NA","shareOutstanding":0,
                 "marketCapitalization":1373.407386,"finnhubIndustry":"Banking","currency":""}
                """;

        CompanyProfile2 profile = new ObjectMapper().readValue(json, CompanyProfile2.class);

        assertThat(profile.getTicker()).isEqualTo("ASBH");
        assertThat(profile.getFinnhubIndustry()).isEqualTo("Banking");
    }

    /** Scenario：已有 Finnhub 產業，不會在下次主檔同步重抓。 */
    @Test
    void existingFinnhubIndustrySkipsProfileRequest() {
        Stock existing = stock("F1", "ASBH");
        existing.setFinnhubIndustry("Banking");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ASBH")));

        task.execute(null);

        verify(finnhub, never()).getCompanyProfile2(anyString());
        assertThat(existing.getFinnhubIndustry()).isEqualTo("Banking");
    }

    /** Scenario：已有富途產業的普通股，仍要填寫獨立的 Finnhub 產業。 */
    @Test
    void existingFutuIndustryStillFetchesFinnhubIndustry() {
        Stock existing = stock("F1", "BANC");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "BANC")));
        CompanyProfile2 profile = new CompanyProfile2();
        profile.setTicker("BANC");
        profile.setFinnhubIndustry("Banking");
        when(finnhub.getCompanyProfile2("BANC")).thenReturn(profile);

        task.execute(null);

        verify(finnhub).getCompanyProfile2("BANC");
        assertThat(existing.getFinnhubIndustry()).isEqualTo("Banking");
        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1
                && "Banking".equals(rows.getFirst().getFinnhubIndustry())));
    }

    /** Scenario：公司簡介返回不同 ticker，不能將其他股票的產業套在目前 FIGI。 */
    @Test
    void differentProfileTickerDoesNotPopulateIndustry() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ASBH")));
        CompanyProfile2 profile = new CompanyProfile2();
        profile.setTicker("OTHER");
        profile.setFinnhubIndustry("Banking");
        when(finnhub.getCompanyProfile2("ASBH")).thenReturn(profile);

        task.execute(null);

        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1 && rows.getFirst().getFinnhubIndustry() == null));
    }

    /** Scenario：單一公司簡介網路失敗，不阻止主檔、CIK 及 stock_status 的正式同步。 */
    @Test
    void failedProfileDoesNotBlockStockMaster() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ASBH")));
        when(finnhub.getCompanyProfile2("ASBH")).thenThrow(new ResourceAccessException("API_DOWN"));

        task.execute(null);

        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1 && rows.getFirst().getFinnhubIndustry() == null));
        verify(stocks).updateStockStatuses(Set.of("F1"));
    }

    private Symbol finnhubSymbol(String figi, String symbol, String type) {
        Symbol row = new Symbol();
        row.setFigiComposite(figi);
        row.setSymbol(symbol);
        row.setMic("XNAS");
        row.setType(type);
        return row;
    }

    private MassiveTickerDetails ticker(String figi, String symbol) {
        MassiveTickerDetails row = new MassiveTickerDetails();
        row.setCompositeFigi(figi);
        row.setTicker(symbol);
        row.setName(symbol);
        row.setType("CS");
        row.setCik("123456");
        row.setCurrencyName("usd");
        row.setActive(true);
        return row;
    }

    private Stock stock(String figi, String symbol) {
        Stock row = new Stock();
        row.setFigi(figi);
        row.setSymbol(symbol);
        row.setActiveStatus(ActiveStatus.ACTIVE);
        return row;
    }
}
