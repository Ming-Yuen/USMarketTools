package com.ykm.common.common_lib.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Generic API response object encapsulating status, message, and data payload.
 */
@Data
@AllArgsConstructor
public class ApiResponse{
    private boolean success = true;

    /**
     * Descriptive message providing more context about the response.
     */
    private String message;

    /**
     * Default no-argument constructor for ApiResponse.
     * Useful for serialization and framework instantiation.
     */
    public ApiResponse() {
        // No initialization required
    }
}
