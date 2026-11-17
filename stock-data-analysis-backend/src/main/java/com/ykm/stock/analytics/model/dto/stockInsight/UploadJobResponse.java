package com.ykm.stock.analytics.model.dto.stockInsight;

import com.ykm.stock.analytics.enums.ProcessStatus;
import java.time.Instant;
import java.util.UUID;

public record UploadJobResponse(UUID id, String fileName, ProcessStatus status, Instant startedAt,
                                Instant finishedAt, String error, UploadResult result) {}
