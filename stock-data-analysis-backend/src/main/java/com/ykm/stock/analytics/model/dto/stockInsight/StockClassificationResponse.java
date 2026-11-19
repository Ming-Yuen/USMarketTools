package com.ykm.stock.analytics.model.dto.stockInsight;

import lombok.Data;

@Data
public class StockClassificationResponse {
    private Classification[] classifications = new Classification[0];
    private StockConceptDto[] concepts = new StockConceptDto[0];
    private String[] marketCapTiers = new String[0];
    private String[] verdicts = new String[0];
    private String[] sortFields = new String[0];
    private String[] assetTypes = new String[0];

    public record Classification(String code, String industry, String sourcePlateCode) {}
}
