package com.ykm.stock.analytics.service.stock;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockClassificationSyncService {
    private final StockRepository stocks;
    private final StockStatusRepository statuses;
    private final StockIndustryRepository industries;
    private final StockConceptRepository concepts;
    private final StockIndustryHistoryService historyService;
    private final BusinessCodeGenerator codeGenerator;
    private final StockClassificationSyncConverter converter;

    /** 在呼叫端的交易內原子保存分類字典、FIGI 產業歷史及股票概念關聯。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int updateClassifications(List<Stock> prepared, Map<String, StockClassificationSnapshot> downloaded) {
        industries.lockForIndustryImport();
        List<StockIndustry> dictionary = industries.findAll();
        Map<String, StockIndustry> bySource = dictionary.stream().filter(item -> item.getSourcePlateCode() != null)
                .collect(Collectors.toMap(StockIndustry::getSourcePlateCode, Function.identity()));
        Map<String, StockIndustry> byName = dictionary.stream()
                .collect(Collectors.toMap(item -> nameKey(item.getIndustry()), Function.identity()));
        Map<String, StockConcept> conceptBySource = concepts.findAll().stream()
                .collect(Collectors.toMap(StockConcept::getSourcePlateCode, Function.identity()));

        Map<String, StockClassificationSnapshot> eligible = new LinkedHashMap<>();
        int missing = 0;
        int ambiguous = 0;
        for (Stock stock : prepared) {
            StockClassificationSnapshot source = downloaded.get(stock.getSymbol());
            if (source == null || source.industries().isEmpty()) {
                missing++;
            } else if (source.industries().size() != 1) {
                ambiguous++;
            } else {
                eligible.put(stock.getFigi(), source);
            }
        }
        Map<String, Stock> managedStocks = eligible.isEmpty() ? Map.of() : stocks.findAllById(eligible.keySet()).stream()
                .collect(Collectors.toMap(Stock::getFigi, Function.identity()));
        Map<String, StockStatus> managedStatuses = eligible.isEmpty() ? Map.of() : statuses.findAllById(eligible.keySet()).stream()
                .collect(Collectors.toMap(StockStatus::getFigi, Function.identity()));
        Map<String, StockIndustry> industryWrites = new LinkedHashMap<>();
        Map<String, StockConcept> conceptWrites = new LinkedHashMap<>();
        List<Assignment> assignments = new ArrayList<>();

        for (Map.Entry<String, StockClassificationSnapshot> entry : eligible.entrySet()) {
            Stock stock = managedStocks.get(entry.getKey());
            if (stock == null) throw new IllegalStateException("CLASSIFICATION_STOCK_NOT_PERSISTED:" + entry.getKey());
            StockClassificationSnapshot source = entry.getValue();
            if (!Objects.equals(stock.getSymbol(), source.symbol()))
                throw new IllegalStateException("CLASSIFICATION_SYMBOL_CHANGED:" + stock.getFigi());
            StockIndustry industry = resolveIndustry(source.industries().get(0), bySource, byName, industryWrites);
            List<StockConcept> assignedConcepts = new ArrayList<>();
            for (ClassificationPlate plate : source.concepts()) {
                String sourceCode = plate.code().trim();
                StockConcept concept = conceptBySource.get(sourceCode);
                if (concept == null) {
                    concept = converter.newConcept(codeGenerator.nextCode("C"), plate);
                    conceptBySource.put(sourceCode, concept);
                    conceptWrites.put(concept.getCode(), concept);
                } else if (!Objects.equals(concept.getName(), plate.name().trim())) {
                    converter.updateConcept(plate, concept);
                    conceptWrites.put(concept.getCode(), concept);
                }
                assignedConcepts.add(concept);
            }
            assignments.add(new Assignment(stock, industry, assignedConcepts));
        }

        // assigned-id 字典可能經 merge 回傳不同實例；完成字典 flush 後才連到 managed Stock。
        Map<String, StockIndustry> savedIndustries = industryWrites.isEmpty() ? Map.of() : industries
                .saveAllAndFlush(industryWrites.values()).stream().collect(Collectors.toMap(StockIndustry::getCode, Function.identity()));
        Map<String, StockConcept> savedConcepts = conceptWrites.isEmpty() ? Map.of() : concepts
                .saveAllAndFlush(conceptWrites.values()).stream().collect(Collectors.toMap(StockConcept::getCode, Function.identity()));
        List<Stock> changed = new ArrayList<>();
        Map<String, StockIndustry> industryAssignments = new LinkedHashMap<>();
        for (Assignment assignment : assignments) {
            Stock stock = assignment.stock();
            StockIndustry industry = savedIndustries.getOrDefault(assignment.industry().getCode(), assignment.industry());
            Set<StockConcept> nextConcepts = assignment.concepts().stream()
                    .map(item -> savedConcepts.getOrDefault(item.getCode(), item)).collect(Collectors.toCollection(LinkedHashSet::new));
            boolean industryChanged = stock.getClassification() == null || !Objects.equals(stock.getClassification().getCode(), industry.getCode());
            boolean conceptsChanged = !stock.getConcepts().equals(nextConcepts);
            // 產業是否需要建立新歷史由資料庫的當前 FIGI 記錄決定，避免依賴 Stock 投影快取。
            industryAssignments.put(stock.getFigi(), industry);
            if (industryChanged) stock.setClassification(industry);
            if (conceptsChanged) {
                stock.setConcepts(nextConcepts);
                changed.add(stock);
            }
            // StockStatus 由交易內載入並由 JPA 一起提交；同代碼已標記時不會更新。
            StockStatus status = managedStatuses.get(stock.getFigi());
            if (status == null) throw new IllegalStateException("MISSING_STOCK_STATUS:" + stock.getFigi());
            status.setClassificationSyncedSymbol(stock.getSymbol());
        }
        int historyChanges = historyService.assignIndustries(industryAssignments);
        if (!changed.isEmpty()) stocks.saveAllAndFlush(changed);
        log.info("Stock classification batch completed | prepared={} eligible={} missingIndustry={} ambiguousIndustry={} industryWrites={} conceptWrites={} historyChanges={} changedConceptStocks={}",
                prepared.size(), eligible.size(), missing, ambiguous, industryWrites.size(), conceptWrites.size(), historyChanges, changed.size());
        return eligible.size();
    }

    private StockIndustry resolveIndustry(ClassificationPlate plate, Map<String, StockIndustry> bySource,
            Map<String, StockIndustry> byName, Map<String, StockIndustry> writes) {
        String sourceCode = plate.code().trim();
        String key = nameKey(plate.name());
        StockIndustry industry = bySource.get(sourceCode);
        StockIndustry nameOwner = byName.get(key);
        if (industry != null && nameOwner != null && !Objects.equals(industry.getCode(), nameOwner.getCode()))
            throw new IllegalStateException("INDUSTRY_NAME_CONFLICT:" + plate.name());
        if (industry == null) {
            industry = nameOwner;
            if (industry != null && industry.getSourcePlateCode() != null && !sourceCode.equals(industry.getSourcePlateCode()))
                throw new IllegalStateException("INDUSTRY_NAME_CONFLICT:" + plate.name());
        }
        if (industry == null) {
            industry = converter.newIndustry(codeGenerator.nextCode("I"), plate);
            writes.put(industry.getCode(), industry);
        } else if (!sourceCode.equals(industry.getSourcePlateCode()) || !plate.name().trim().equals(industry.getIndustry())) {
            byName.remove(nameKey(industry.getIndustry()));
            converter.updateIndustry(plate, industry);
            writes.put(industry.getCode(), industry);
        }
        bySource.put(sourceCode, industry);
        byName.put(key, industry);
        return industry;
    }

    private static String nameKey(String name) {
        return name.trim().toUpperCase(Locale.ROOT);
    }

    private record Assignment(Stock stock, StockIndustry industry, List<StockConcept> concepts) {}
}
