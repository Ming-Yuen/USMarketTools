package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.service.analysis.StockAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** API 查詢保留唯讀交易；實際無寫入/無採集行為由 screen 與 analysis 的 mock 業務案例驗證。 */
class ReadModelIsolationBoundaryTests {
    @Test
    void stockScreenAndAnalysisEntrypointsAreReadOnly() throws Exception {
        Transactional screen = StockService.class.getMethod("screen", String.class, String.class, String.class,
                int.class, int.class).getAnnotation(Transactional.class);
        Transactional analysis = StockAnalysisService.class.getMethod("analyze", AnalysisReportRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(screen).isNotNull();
        assertThat(screen.readOnly()).isTrue();
        assertThat(analysis).isNotNull();
        assertThat(analysis.readOnly()).isTrue();
    }
}
