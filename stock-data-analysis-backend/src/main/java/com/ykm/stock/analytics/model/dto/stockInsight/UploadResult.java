package com.ykm.stock.analytics.model.dto.stockInsight;

import java.util.List;

public record UploadResult(int totalRows, int updated, int unchanged, int createdIndustries,
                           int missingIndustry, int unmatched, int ambiguous, List<Issue> issues) {
    public record Issue(int row, String symbol, String reason) {}
}
