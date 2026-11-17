package com.ykm.stock.analytics.model.dto.finnhub.response;

import lombok.Data;

@Data
public class Symbol {
    private String symbol;

    private String mic;

    private String figi;

    private String figiComposite;

    private String type;

    private String shareClassFIGI;
}
