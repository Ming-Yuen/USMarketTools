package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.enums.ProcessStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.UploadJobResponse;
import com.ykm.stock.analytics.service.upload.UploadJobService;
import com.ykm.stock.analytics.service.upload.UploadHandler;
import com.ykm.stock.analytics.repository.jpa.UploadJobRepository;
import com.ykm.stock.analytics.converter.jpa.UploadJobConverter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import java.util.List;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DataUploadControllerTests {
    @Test
    void listsRegisteredHandlerTypesWithoutReadingUploadJobs() throws Exception {
        UploadHandler stockIndustry = mock(UploadHandler.class);
        UploadHandler other = mock(UploadHandler.class);
        when(stockIndustry.type()).thenReturn("STOCK_INDUSTRY");
        when(other.type()).thenReturn("OTHER_UPLOAD");
        UploadJobRepository jobs = mock(UploadJobRepository.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        UploadJobService service = new UploadJobService(jobs, List.of(stockIndustry, other),
                mock(UploadJobConverter.class), mock(TransactionTemplate.class), executor);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DataUploadController(service)).build();

        mvc.perform(get("/upload/types"))
                .andExpect(status().isOk()).andExpect(content().json("[\"OTHER_UPLOAD\",\"STOCK_INDUSTRY\"]", true));

        verifyNoInteractions(jobs, executor);
        verify(stockIndustry, never()).handle(any());
        verify(other, never()).handle(any());
    }

    @Test
    void noRegisteredHandlersReturnsAnEmptyTypeList() throws Exception {
        UploadJobService service = new UploadJobService(mock(UploadJobRepository.class), List.of(),
                mock(UploadJobConverter.class), mock(TransactionTemplate.class), mock(TaskExecutor.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DataUploadController(service)).build();

        mvc.perform(get("/upload/types")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void acceptsBackgroundJobAndExposesStatusWithoutKeepingRequestOpen() throws Exception {
        UploadJobService service = mock(UploadJobService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DataUploadController(service)).build();
        UUID id = UUID.randomUUID();
        UploadJobResponse queued = new UploadJobResponse(id, "stocks.csv", ProcessStatus.PENDING, null, null, null, null);
        when(service.submit(eq(id), eq("STOCK_INDUSTRY"), any())).thenReturn(queued);
        when(service.get(id)).thenReturn(queued);
        mvc.perform(multipart("/upload").file(new MockMultipartFile("file", "csv".getBytes())).param("type", "STOCK_INDUSTRY").param("id", id.toString()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("status").value("PENDING")).andExpect(jsonPath("id").value(id.toString()));
        mvc.perform(get("/upload/" + id)).andExpect(status().isOk()).andExpect(jsonPath("status").value("PENDING"));
    }
}
