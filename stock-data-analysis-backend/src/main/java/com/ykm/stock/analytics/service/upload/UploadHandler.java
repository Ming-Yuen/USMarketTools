package com.ykm.stock.analytics.service.upload;

import com.ykm.stock.analytics.model.dto.stockInsight.UploadResult;

public interface UploadHandler {
    String type();

    UploadResult handle(byte[] content);
}
