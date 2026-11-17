package com.ykm.stock.analytics.model.dto.classification;

import java.util.List;

public record StockClassificationSnapshot(String symbol, String name, List<ClassificationPlate> industries, List<ClassificationPlate> concepts) {
    public StockClassificationSnapshot {
        industries = List.copyOf(industries);
        concepts = List.copyOf(concepts);
    }
}
