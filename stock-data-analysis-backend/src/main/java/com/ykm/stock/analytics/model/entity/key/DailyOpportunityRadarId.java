package com.ykm.stock.analytics.model.entity.key;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyOpportunityRadarId implements Serializable {
    private LocalDate radarDate;
    private String trackType;
    private String figi;
}
