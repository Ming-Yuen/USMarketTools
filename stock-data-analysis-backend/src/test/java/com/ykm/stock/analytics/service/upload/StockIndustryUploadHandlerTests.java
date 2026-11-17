package com.ykm.stock.analytics.service.upload;

import com.ykm.orm.service.BusinessCodeGenerator;
import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.model.dto.stockInsight.UploadResult;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockConcept;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.stock.StockIndustryHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockIndustryUploadHandlerTests {
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockIndustryRepository classifications = mock(StockIndustryRepository.class);
    private final StockIndustryHistoryService industryHistory = mock(StockIndustryHistoryService.class);
    private final Map<String, String> industryHistoryCodes = new HashMap<>();
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final BusinessCodeGenerator businessCodeGenerator = mock(BusinessCodeGenerator.class);
    private final StockIndustryUploadHandler handler = new StockIndustryUploadHandler(stocks, classifications, industryHistory,
            Mappers.getMapper(StockClassificationConverter.class), new TransactionTemplate(manager), businessCodeGenerator);

    @BeforeEach
    void savedDictionaryReturnsPersistentInstances() {
        AtomicInteger nextCode = new AtomicInteger();
        when(industryHistory.assignIndustries(anyMap())).thenAnswer(invocation -> {
            Map<String, StockIndustry> updates = invocation.getArgument(0);
            int changed = 0;
            for (Map.Entry<String, StockIndustry> entry : updates.entrySet()) {
                if (!Objects.equals(industryHistoryCodes.put(entry.getKey(), entry.getValue().getCode()), entry.getValue().getCode())) changed++;
            }
            return changed;
        });
        when(businessCodeGenerator.nextCode("I")).thenAnswer(invocation -> "I" + nextCode.incrementAndGet());
        when(classifications.saveAllAndFlush(any())).thenAnswer(invocation -> {
            java.util.ArrayList<StockIndustry> saved = new java.util.ArrayList<>();
            Iterable<StockIndustry> inputs = invocation.getArgument(0);
            inputs.forEach(saved::add);
            return saved;
        });
    }

    private Stock stock(String figi, String symbol) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        return stock;
    }
    private UploadResult upload(String csv) throws Exception {
        return handler.handle(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void dictionaryIsManagedBeforeStocksReferenceIt() throws Exception {
        Stock stock = stock("F1", "AAA");
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(stock));
        doAnswer(invocation -> {
            // Hibernate flush 會檢查所有 managed Stock，不能先指向未保存的新字典。
            assertThat(stock.getClassification()).isNull();
            Iterable<StockIndustry> inputs = invocation.getArgument(0);
            StockIndustry original = inputs.iterator().next();
            StockIndustry managed = new StockIndustry();
            managed.setCode(original.getCode());
            managed.setIndustry(original.getIndustry());
            managed.setSourcePlateCode(original.getSourcePlateCode());
            return List.of(managed);
        }).when(classifications).saveAllAndFlush(any());
        assertThat(upload("Symbol,Name,Industry\nAAA,Alpha,Software").updated()).isEqualTo(1);
        assertThat(stock.getClassification().getIndustry()).isEqualTo("Software");
    }

    @Test
    void createsOneSharedIndustryOverwritesStocksAndRerunDoesNotWriteStocks() throws Exception {
        Stock first = stock("F1", "AAA"), second = stock("F2", "BBB");
        StockIndustry old = new StockIndustry();
        old.setCode("OLD");
        first.setClassification(old);
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(first, second));
        String csv = "Symbol,Name,Price,Industry\nAAA,Alpha,999,Biotechnology\nBBB,Beta,123,Biotechnology\n";
        UploadResult result = upload(csv);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.createdIndustries()).isEqualTo(1);
        assertThat(first.getClassification()).isSameAs(second.getClassification());
        assertThat(first.getClassification().getIndustry()).isEqualTo("Biotechnology");
        assertThat(first.getClassification().getSourcePlateCode()).isNull();
        assertThat(first.getClassification().getCode()).isEqualTo("I1");
        assertThat(first.getSymbol()).isEqualTo("AAA");
        assertThat(first.getLongName()).isNull();
        when(classifications.findAll()).thenReturn(List.of(first.getClassification()));
        UploadResult rerun = upload(csv);
        assertThat(rerun.updated()).isZero(); assertThat(rerun.unchanged()).isEqualTo(2);
        assertThat(rerun.createdIndustries()).isZero();
        verify(businessCodeGenerator, times(1)).nextCode("I");
    }

    @Test
    void reusesExistingDictionaryWithoutChangingClassificationPlateOrConcepts() throws Exception {
        Stock stock = stock("F1", "AAA");
        StockIndustry dictionary = new StockIndustry();
        dictionary.setCode("BANK");
        dictionary.setIndustry("Banks - Regional");
        dictionary.setSourcePlateCode("US.LIST_TEST_BANK");
        StockConcept concept = new StockConcept();
        concept.setCode("C1");
        concept.setName("Existing concept");
        stock.getConcepts().add(concept);
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(stock));
        when(classifications.findAll()).thenReturn(List.of(dictionary));
        UploadResult result = upload("Symbol,Name,Industry\nAAA,Alpha,banks - regional");
        assertThat(result.createdIndustries()).isZero();
        assertThat(stock.getClassification()).isSameAs(dictionary);
        assertThat(dictionary.getCode()).isEqualTo("BANK");
        assertThat(dictionary.getIndustry()).isEqualTo("Banks - Regional");
        verify(classifications, never()).saveAllAndFlush(any());
        verify(industryHistory).assignIndustries(Map.of("F1", dictionary));
        assertThat(dictionary.getSourcePlateCode()).isEqualTo("US.LIST_TEST_BANK");
        assertThat(stock.getConcepts()).containsExactly(concept);
        verifyNoInteractions(businessCodeGenerator);
    }

    @Test
    void missingUnmatchedAndAmbiguousRowsAreCountedAndPreserved() throws Exception {
        Stock one = stock("F1", "DUP"), two = stock("F2", "DUP");
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(one, two));
        UploadResult result = upload("Symbol,Name,Industry\nNONE,Alpha,-\nNEW,Beta,Software\nDUP,Duplicate,Software\n");
        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.missingIndustry()).isEqualTo(1); assertThat(result.unmatched()).isEqualTo(1);
        assertThat(result.ambiguous()).isEqualTo(1); assertThat(result.updated()).isZero();
        assertThat(result.createdIndustries()).isZero(); assertThat(result.issues()).hasSize(3);
        verifyNoInteractions(businessCodeGenerator);
    }

    @Test
    void malformedFilesFailBeforeStartingTransaction() {
        for (String csv : List.of("Symbol,Name\nAAA,A", "Symbol,Name,Industry\nAAA,A,X\nAAA,A,Y",
                "Symbol,Name,Industry\nAAA,A,X,extra", "Symbol,Name,Industry\nAAA,\"unterminated,X",
                "Symbol,Name,Industry\nAAA,\"closed\"junk,X", "Symbol,Name,Industry\n,A,X")) {
            assertThatThrownBy(() -> upload(csv)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(manager, stocks, businessCodeGenerator);
        verify(classifications, never()).lockForIndustryImport();
        verify(classifications, never()).saveAllAndFlush(any());
    }

    @Test
    void csvSupportsBomCrLfQuotedCommaEscapedQuoteAndMultiline() throws Exception {
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(stock("F1", "AAA")));
        UploadResult result = upload("\uFEFF\"Industry\",\"Name\",\"Symbol\"\r\n\"Software, Services\",\"Alpha \"\"A\"\"\r\nInc\",\"AAA\"\r\n");
        assertThat(result.updated()).isEqualTo(1); assertThat(result.createdIndustries()).isEqualTo(1);
    }

    @Test
    void persistenceFailureRollsBackWholeImport() {
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(stock("F1", "AAA")));
        doThrow(new IllegalStateException("save failed")).when(industryHistory).assignIndustries(anyMap());
        assertThatThrownBy(() -> upload("Symbol,Name,Industry\nAAA,Alpha,Software"))
                .isInstanceOf(IllegalStateException.class).hasMessage("save failed");
        verify(manager).rollback(any()); verify(manager, never()).commit(any());
    }

    @Test
    void invalidUtf8IsRejected() {
        assertThatThrownBy(() -> handler.handle(new byte[]{(byte) 0xff})).isInstanceOf(IllegalArgumentException.class).hasMessage("CSV_INVALID_UTF8");
        verifyNoInteractions(manager);
    }

    @Test
    void takesImportLockBeforeReadingStocksAndDictionary() throws Exception {
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(stock("F1", "AAA")));

        upload("Symbol,Name,Industry\nAAA,Alpha,Software");

        InOrder order = inOrder(classifications, stocks, businessCodeGenerator);
        order.verify(classifications).lockForIndustryImport();
        order.verify(stocks).findBySymbolIn(any());
        order.verify(classifications).findAll();
        order.verify(businessCodeGenerator).nextCode("I");
    }

    @Test
    void eachDistinctNewIndustryGetsOneSequentialCode() throws Exception {
        Stock first = stock("F1", "AAA");
        Stock second = stock("F2", "BBB");
        Stock third = stock("F3", "CCC");
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(first, second, third));

        UploadResult result = upload("Symbol,Name,Industry\nAAA,Alpha,Software\nBBB,Beta,Biotechnology\nCCC,Gamma,software");

        assertThat(result.createdIndustries()).isEqualTo(2);
        assertThat(first.getClassification().getCode()).isEqualTo("I1");
        assertThat(second.getClassification().getCode()).isEqualTo("I2");
        assertThat(third.getClassification()).isSameAs(first.getClassification());
        verify(businessCodeGenerator, times(2)).nextCode("I");
    }

    @Test
    void failedCounterDoesNotWriteDictionaryOrChangeStocks() {
        Stock stock = stock("F1", "AAA");
        StockIndustry previous = new StockIndustry();
        previous.setCode("EXISTING");
        stock.setClassification(previous);
        when(stocks.findBySymbolIn(any())).thenReturn(List.of(stock));
        doThrow(new IllegalStateException("counter unavailable")).when(businessCodeGenerator).nextCode("I");

        assertThatThrownBy(() -> upload("Symbol,Name,Industry\nAAA,Alpha,Software"))
                .isInstanceOf(IllegalStateException.class).hasMessage("counter unavailable");

        assertThat(stock.getClassification()).isSameAs(previous);
        verify(classifications, never()).saveAllAndFlush(any());
        verify(stocks, never()).saveAllAndFlush(any());
        verify(manager).rollback(any());
    }
}
