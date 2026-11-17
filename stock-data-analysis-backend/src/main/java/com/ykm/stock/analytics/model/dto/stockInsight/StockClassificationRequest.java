package com.ykm.stock.analytics.model.dto.stockInsight;

import lombok.Data;

@Data
public class StockClassificationRequest {
    private String code;
    private String industry;
}
