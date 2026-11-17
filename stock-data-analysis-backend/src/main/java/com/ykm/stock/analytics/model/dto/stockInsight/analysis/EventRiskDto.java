package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.Instant;
import java.time.LocalDate;

public record EventRiskDto(String eventId, String type, String subtype, String title, LocalDate eventDate,
                           Instant occurrenceTime, String timePrecision, Instant observedAt, String source,
                           boolean blocksNewEntry) {}
