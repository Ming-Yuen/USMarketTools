package com.ykm.stock.analytics.model.entity.key;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockShareObservationId implements Serializable {
    private String figi;
    private Instant observedAt;
}
