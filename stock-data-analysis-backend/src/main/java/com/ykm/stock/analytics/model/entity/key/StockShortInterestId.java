package com.ykm.stock.analytics.model.entity.key;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockShortInterestId implements Serializable {
    private String figi;
    private LocalDate settlementDate;
}
