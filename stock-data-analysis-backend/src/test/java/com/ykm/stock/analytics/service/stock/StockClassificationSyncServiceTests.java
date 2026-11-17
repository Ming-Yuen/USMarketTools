package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.service.stock.StockIndustryHistoryService;
import com.ykm.orm.service.BusinessCodeGenerator;
import com.ykm.stock.analytics.converter.jpa.StockClassificationSyncConverter;
import com.ykm.stock.analytics.model.dto.classification.ClassificationPlate;
import com.ykm.stock.analytics.model.dto.classification.StockClassificationSnapshot;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockConcept;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockConceptRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StockClassificationSyncServiceTests {
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockIndustryRepository industries = mock(StockIndustryRepository.class);
    private final StockConceptRepository concepts = mock(StockConceptRepository.class);
    private final StockIndustryHistoryService historyService = mock(StockIndustryHistoryService.class);
    private final BusinessCodeGenerator codes = mock(BusinessCodeGenerator.class);
    private final StockClassificationSyncConverter converter = Mappers.getMapper(StockClassificationSyncConverter.class);
    private final StockClassificationSyncService service = new StockClassificationSyncService(stocks, statuses, industries, concepts, historyService, codes, converter);
    private final List<Stock> stockRows = new ArrayList<>();
    private final Map<String, StockStatus> statusRows = new HashMap<>();
    private final List<StockIndustry> industryRows = new ArrayList<>();
    private final List<StockConcept> conceptRows = new ArrayList<>();

    @BeforeEach
    void repositoriesReturnManagedBatchAndPersistDictionaries() {
        when(industries.findAll()).thenReturn(industryRows);
        when(concepts.findAll()).thenReturn(conceptRows);
        when(stocks.findAllById(any())).thenAnswer(invocation -> {
            Iterable<String> figis = invocation.getArgument(0);
            Set<String> requested = new LinkedHashSet<>();
            figis.forEach(requested::add);
            return stockRows.stream().filter(stock -> requested.contains(stock.getFigi())).toList();
        });
        when(statuses.findAllById(any())).thenAnswer(invocation -> {
            Iterable<String> figis = invocation.getArgument(0);
            return StreamSupport.stream(figis.spliterator(), false).map(figi -> statusRows.computeIfAbsent(figi, key -> {
                StockStatus status = new StockStatus();
                status.setFigi(key);
                return status;
            })).toList();
        });
        when(industries.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<StockIndustry> values = invocation.getArgument(0);
            List<StockIndustry> saved = StreamSupport.stream(values.spliterator(), false).toList();
            for (StockIndustry value : saved)
                if (industryRows.stream().noneMatch(existing -> existing.getCode().equals(value.getCode()))) industryRows.add(value);
            return saved;
        });
        when(concepts.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<StockConcept> values = invocation.getArgument(0);
            List<StockConcept> saved = StreamSupport.stream(values.spliterator(), false).toList();
            for (StockConcept value : saved)
                if (conceptRows.stream().noneMatch(existing -> existing.getCode().equals(value.getCode()))) conceptRows.add(value);
            return saved;
        });
        Map<String, AtomicInteger> counters = new HashMap<>();
        when(codes.nextCode(any())).thenAnswer(invocation -> {
            String prefix = invocation.getArgument(0);
            return prefix + counters.computeIfAbsent(prefix, ignored -> new AtomicInteger()).incrementAndGet();
        });
    }

    // Scenario：分類字典只保存分類；估值設定已獨立，這裡驗證穩定代碼、名稱與產業/概念關聯。
    @Test
    void twoStocksShareIndustryButRetainDifferentCompleteConceptSets() {
        Stock first = stock("F1", "AAA");
        Stock second = stock("F2", "BBB");
        ClassificationPlate industry = plate("US.I", "Biotechnology");
        ClassificationPlate one = plate("US.C1", "Cancer research");
        ClassificationPlate two = plate("US.C2", "Innovation");

        service.updateClassifications(List.of(first, second), Map.of(
                "AAA", source("AAA", industry, List.of(one, two)), "BBB", source("BBB", industry, List.of(two))));

        assertThat(first.getClassification()).isSameAs(second.getClassification());
        assertThat(first.getClassification().getCode()).isEqualTo("I1");
        assertThat(first.getClassification().getIndustry()).isEqualTo("Biotechnology");
        assertThat(first.getClassification().getSourcePlateCode()).isEqualTo("US.I");
        assertThat(first.getConcepts()).extracting(StockConcept::getSourcePlateCode).containsExactly("US.C1", "US.C2");
        assertThat(second.getConcepts()).extracting(StockConcept::getSourcePlateCode).containsExactly("US.C2");
        assertThat(statusRows.get("F1").getClassificationSyncedSymbol()).isEqualTo("AAA");
        assertThat(statusRows.get("F2").getClassificationSyncedSymbol()).isEqualTo("BBB");
        assertThat(industryRows).hasSize(1);
        assertThat(conceptRows).hasSize(2);
        verify(codes).nextCode("I");
        verify(codes, times(2)).nextCode("C");
        verify(stocks).findAllById(Set.of("F1", "F2"));
        verify(historyService).assignIndustries(argThat(assignments -> assignments.size() == 2
                && "I1".equals(assignments.get("F1").getCode()) && "I1".equals(assignments.get("F2").getCode())));
    }

    @Test
    void bindsCsvIndustryByNormalizedNameWithoutChangingIndustryCode() {
        Stock stock = stock("F1", "AAA");
        StockIndustry existing = industry("I9", null, "Banks - Regional");

        service.updateClassifications(List.of(stock), Map.of("AAA", source("AAA", plate("US.BANK", " banks - regional "), List.of())));

        assertThat(stock.getClassification()).isSameAs(existing);
        assertThat(existing.getCode()).isEqualTo("I9");
        assertThat(existing.getSourcePlateCode()).isEqualTo("US.BANK");
        assertThat(existing.getIndustry()).isEqualTo("banks - regional");
        verify(historyService).assignIndustries(Map.of("F1", existing));
        verifyNoInteractions(codes);
    }

    @Test
    void sourceCodeRenamesIndustryAndConceptWithoutRewritingUnchangedStockRelations() {
        Stock stock = stock("F1", "AAA");
        StockIndustry existing = industry("I9", "US.I", "Old industry name");
        StockConcept concept = concept("C9", "US.C", "Old concept name");
        stock.setClassification(existing);
        stock.getConcepts().add(concept);

        service.updateClassifications(List.of(stock), Map.of("AAA", source("AAA", plate("US.I", "New industry name"), List.of(plate("US.C", "New concept name")))));

        assertThat(stock.getClassification()).isSameAs(existing);
        assertThat(existing.getIndustry()).isEqualTo("New industry name");
        assertThat(concept.getName()).isEqualTo("New concept name");
        assertThat(stock.getConcepts()).containsExactly(concept);
        assertThat(statusRows.get("F1").getClassificationSyncedSymbol()).isEqualTo("AAA");
        verifyNoInteractions(codes);
        verify(stocks, never()).saveAllAndFlush(any());
        verify(historyService).assignIndustries(Map.of("F1", existing));
    }

    @Test
    void missingAndMultipleIndustriesPreserveBothExistingRelations() {
        Stock absent = stock("F1", "AAA");
        Stock empty = stock("F2", "BBB");
        Stock multiple = stock("F3", "CCC");
        StockIndustry existing = industry("I9", "US.OLD", "Existing industry");
        StockConcept concept = concept("C9", "US.OLD_C", "Existing concept");
        for (Stock stock : stockRows) {
            stock.setClassification(existing);
            stock.getConcepts().add(concept);
        }
        Map<String, StockClassificationSnapshot> downloaded = Map.of(
                "BBB", new StockClassificationSnapshot("BBB", "Beta", List.of(), List.of()),
                "CCC", new StockClassificationSnapshot("CCC", "Gamma", List.of(plate("US.I1", "One"), plate("US.I2", "Two")), List.of()));

        service.updateClassifications(List.of(absent, empty, multiple), downloaded);

        for (Stock stock : stockRows) {
            assertThat(stock.getClassification()).isSameAs(existing);
            assertThat(stock.getConcepts()).containsExactly(concept);
        }
        verify(stocks, never()).findAllById(any());
        verifyNoInteractions(statuses);
        verify(stocks, never()).saveAllAndFlush(any());
        verifyNoInteractions(codes);
    }

    @Test
    void validIndustryWithNoConceptsClearsOldConceptMemberships() {
        Stock stock = stock("F1", "AAA");
        StockIndustry existing = industry("I9", "US.I", "Existing industry");
        stock.setClassification(existing);
        stock.getConcepts().add(concept("C9", "US.C", "Old concept"));

        service.updateClassifications(List.of(stock), Map.of("AAA", source("AAA", plate("US.I", "Existing industry"), List.of())));

        assertThat(stock.getClassification()).isSameAs(existing);
        assertThat(stock.getConcepts()).isEmpty();
        verify(stocks).saveAllAndFlush(List.of(stock));
        verifyNoInteractions(codes);
    }

    @Test
    void sourceRenameToAnotherIndustryNameFailsInsteadOfMergingDictionaryEntries() {
        Stock stock = stock("F1", "AAA");
        StockIndustry existing = industry("I9", "US.I", "Original");
        StockIndustry other = industry("I10", "US.OTHER", "Occupied name");
        stock.setClassification(existing);

        assertThatThrownBy(() -> service.updateClassifications(List.of(stock), Map.of("AAA", source("AAA", plate("US.I", "Occupied name"), List.of()))))
                .isInstanceOf(IllegalStateException.class).hasMessage("INDUSTRY_NAME_CONFLICT:Occupied name");

        assertThat(existing.getIndustry()).isEqualTo("Original");
        assertThat(existing.getCode()).isEqualTo("I9");
        assertThat(other.getCode()).isEqualTo("I10");
        assertThat(other.getIndustry()).isEqualTo("Occupied name");
        assertThat(stock.getClassification()).isSameAs(existing);
        verify(industries, never()).saveAllAndFlush(any());
        verify(stocks, never()).saveAllAndFlush(any());
        verifyNoInteractions(codes);
    }

    @Test
    void sameIndustryNameBoundToDifferentProviderCodeFails() {
        Stock stock = stock("F1", "AAA");
        industry("I9", "US.OLD", "Existing name");

        assertThatThrownBy(() -> service.updateClassifications(List.of(stock), Map.of("AAA", source("AAA", plate("US.NEW", "Existing name"), List.of()))))
                .isInstanceOf(IllegalStateException.class).hasMessage("INDUSTRY_NAME_CONFLICT:Existing name");
        verifyNoInteractions(codes);
        verify(stocks, never()).saveAllAndFlush(any());
    }

    @Test
    void rerunUsesSameNumbersAndDoesNotRewriteStockRelations() {
        Stock stock = stock("F1", "AAA");
        Map<String, StockClassificationSnapshot> downloaded = Map.of("AAA", source("AAA", plate("US.I", "Industry"), List.of(plate("US.C", "Concept"))));

        service.updateClassifications(List.of(stock), downloaded);
        service.updateClassifications(List.of(stock), downloaded);

        verify(codes).nextCode("I");
        verify(codes).nextCode("C");
        verify(industries, times(1)).saveAllAndFlush(any());
        verify(concepts, times(1)).saveAllAndFlush(any());
        verify(stocks, times(1)).saveAllAndFlush(any());
    }

    @Test
    void connectsOnlyDictionaryInstancesReturnedFromAssignedIdMerge() {
        Stock stock = stock("F1", "AAA");
        StockIndustry managedIndustry = converter.newIndustry("I1", plate("US.I", "Industry"));
        StockConcept managedConcept = converter.newConcept("C1", plate("US.C", "Concept"));
        doAnswer(invocation -> {
            assertThat(stock.getClassification()).isNull();
            assertThat(stock.getConcepts()).isEmpty();
            return List.of(managedIndustry);
        }).when(industries).saveAllAndFlush(any());
        doAnswer(invocation -> {
            assertThat(stock.getClassification()).isNull();
            assertThat(stock.getConcepts()).isEmpty();
            return List.of(managedConcept);
        }).when(concepts).saveAllAndFlush(any());

        service.updateClassifications(List.of(stock), Map.of("AAA", source("AAA", plate("US.I", "Industry"), List.of(plate("US.C", "Concept")))));

        assertThat(stock.getClassification()).isSameAs(managedIndustry);
        assertThat(stock.getConcepts().iterator().next()).isSameAs(managedConcept);
    }

    private Stock stock(String figi, String symbol) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stockRows.add(stock);
        return stock;
    }

    private StockIndustry industry(String code, String sourceCode, String name) {
        StockIndustry industry = new StockIndustry();
        industry.setCode(code);
        industry.setSourcePlateCode(sourceCode);
        industry.setIndustry(name);
        industryRows.add(industry);
        return industry;
    }

    private StockConcept concept(String code, String sourceCode, String name) {
        StockConcept concept = new StockConcept();
        concept.setCode(code);
        concept.setSourcePlateCode(sourceCode);
        concept.setName(name);
        conceptRows.add(concept);
        return concept;
    }

    private ClassificationPlate plate(String code, String name) {
        return new ClassificationPlate(code, name);
    }

    private StockClassificationSnapshot source(String symbol, ClassificationPlate industry, List<ClassificationPlate> concepts) {
        return new StockClassificationSnapshot(symbol, symbol, List.of(industry), concepts);
    }
}