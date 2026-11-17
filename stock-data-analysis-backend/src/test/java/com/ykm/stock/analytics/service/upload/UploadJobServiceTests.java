package com.ykm.stock.analytics.service.upload;

import com.ykm.stock.analytics.converter.jpa.UploadJobConverter;
import com.ykm.stock.analytics.enums.ProcessStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.UploadResult;
import com.ykm.stock.analytics.model.entity.UploadJob;
import com.ykm.stock.analytics.repository.jpa.UploadJobRepository;
import com.ykm.stock.analytics.service.upload.UploadHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UploadJobServiceTests {
    private final UploadJobRepository jobs = mock(UploadJobRepository.class);
    private final UploadHandler handler = mock(UploadHandler.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Map<UUID, UploadJob> records = new HashMap<>();
    private final AtomicReference<Runnable> work = new AtomicReference<>();
    private UploadJobService service;
    private final MockMultipartFile file = new MockMultipartFile("file", "stocks.csv", "text/csv", "Symbol,Name,Industry\nAAA,Alpha,Software".getBytes());

    @BeforeEach
    void setup() {
        when(handler.type()).thenReturn("STOCK_INDUSTRY");
        service = new UploadJobService(jobs, List.of(handler), Mappers.getMapper(UploadJobConverter.class),
                new TransactionTemplate(manager), executor);
        when(jobs.findById(any())).thenAnswer(i -> Optional.ofNullable(records.get(i.getArgument(0))));
        when(jobs.saveAndFlush(any())).thenAnswer(i -> { UploadJob job = i.getArgument(0); records.put(job.getId(), job); return job; });
        when(jobs.findSummaryById(any())).thenAnswer(i -> Optional.ofNullable(records.get(i.getArgument(0)))
                .map(job -> new SpelAwareProxyProjectionFactory().createProjection(UploadJobRepository.Summary.class, job)));
        doAnswer(i -> { work.set(i.getArgument(0)); return null; }).when(executor).execute(any());
    }

    @Test
    void returnsPendingBeforeWorkThenPersistsCompletedResultAndClearsInput() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(service.submit(id, "STOCK_INDUSTRY", file).status()).isEqualTo(ProcessStatus.PENDING);
        verify(handler, never()).handle(any());
        assertThat(service.get(id).result()).isNull();
        when(handler.handle(any())).thenReturn(new UploadResult(1, 1, 0, 1, 0, 0, 0, List.of()));
        work.get().run();
        assertThat(service.get(id).status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(service.get(id).result().updated()).isEqualTo(1);
        assertThat(records.get(id).getInputData()).isNull();
    }

    @Test
    void lostAcknowledgementCanReuseIdWithoutSchedulingAnotherImport() throws Exception {
        UUID id = UUID.randomUUID();
        service.submit(id, "STOCK_INDUSTRY", file);
        service.submit(id, "STOCK_INDUSTRY", file);
        verify(executor, times(1)).execute(any());
        assertThatThrownBy(() -> service.submit(id, "STOCK_INDUSTRY", new MockMultipartFile("file", "other".getBytes())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("UPLOAD_ID_CONTENT_MISMATCH");
    }

    @Test
    void failedImportRollsBackAndSavesFailureWithoutInput() throws Exception {
        UUID id = UUID.randomUUID(); service.submit(id, "STOCK_INDUSTRY", file);
        when(handler.handle(any())).thenThrow(new IllegalArgumentException("CSV_MISSING_HEADER:Industry"));
        work.get().run();
        assertThat(service.get(id).status()).isEqualTo(ProcessStatus.FAILED);
        assertThat(service.get(id).error()).isEqualTo("CSV_MISSING_HEADER:Industry");
        assertThat(records.get(id).getInputData()).isNull();
        verify(manager).rollback(any());
    }

    @Test
    void startupMarksOldPendingAndProcessingAsFailedWithoutRetrying() {
        service.interrupted();
        verify(jobs).failInterrupted(any(), any(), eq(ProcessStatus.FAILED), eq(List.of(ProcessStatus.PENDING, ProcessStatus.PROCESSING)));
        verifyNoInteractions(executor);
        verify(handler, never()).handle(any());
    }

    @Test
    void registeredTypeDispatchesToItsOwnHandlerWithoutChangingTheService() throws Exception {
        UploadHandler otherHandler = mock(UploadHandler.class);
        when(otherHandler.type()).thenReturn("OTHER_UPLOAD");
        when(otherHandler.handle(any())).thenReturn(new UploadResult(1, 0, 1, 0, 0, 0, 0, List.of()));
        UploadJobService multiTypeService = new UploadJobService(jobs, List.of(handler, otherHandler),
                Mappers.getMapper(UploadJobConverter.class), new TransactionTemplate(manager), executor);
        UUID id = UUID.randomUUID();

        multiTypeService.submit(id, "OTHER_UPLOAD", file);
        work.get().run();

        assertThat(multiTypeService.get(id).status()).isEqualTo(ProcessStatus.COMPLETED);
        verify(otherHandler).handle(file.getBytes());
        verify(handler, never()).handle(any());
    }

    @Test
    void unknownOrMissingTypeIsRejectedBeforeSavingOrScheduling() {
        for (String type : new String[]{null, "OTHER"}) {
            assertThatThrownBy(() -> service.submit(UUID.randomUUID(), type, file))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("UNSUPPORTED_UPLOAD_TYPE");
        }
        verifyNoInteractions(jobs, executor);
        verify(handler, never()).handle(any());
    }

    @Test
    void duplicateHandlerTypesFailRegistration() {
        UploadHandler duplicate = mock(UploadHandler.class);
        when(duplicate.type()).thenReturn("STOCK_INDUSTRY");

        assertThatThrownBy(() -> new UploadJobService(jobs, List.of(handler, duplicate),
                Mappers.getMapper(UploadJobConverter.class), new TransactionTemplate(manager), executor))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate key STOCK_INDUSTRY");
    }
}
