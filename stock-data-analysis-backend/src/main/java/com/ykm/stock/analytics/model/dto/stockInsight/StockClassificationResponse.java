package com.ykm.stock.analytics.model.dto.stockInsight;

import lombok.Data;

@Data
public class StockClassificationResponse {
    private Classification[] classifications = new Classification[0];
    private StockConceptDto[] concepts = new StockConceptDto[0];

    public record Classification(String code, String industry, String sourcePlateCode) {}
}
