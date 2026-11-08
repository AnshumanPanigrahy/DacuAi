package com.model.documindai.service;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.SummarizationJob;
import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;
import com.model.documindai.model.SummaryJobRequest;
import com.model.documindai.model.SummaryJobStatusResponse;
import com.model.documindai.repository.DocumentRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryJobServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private SummarizationJobRepository jobRepository;

    private SummaryJobService service;

    @BeforeEach
    void setUp() {
        service = new SummaryJobService(
                documentRepository,
                jobRepository,
                100,
                8
        );
    }

    @Test
    void createsQueuedJobForExistingDocument() {
        Document document = document(12L);
        when(documentRepository.findById(12L))
                .thenReturn(Optional.of(document));
        when(jobRepository.save(any(SummarizationJob.class)))
                .thenAnswer(invocation -> {
                    SummarizationJob job = invocation.getArgument(0);
                    job.setId(UUID.randomUUID());
                    return job;
                });

        var response = service.createJob(new SummaryJobRequest(12L));

        assertEquals(12L, response.documentId());
        assertEquals(SummarizationJobStatus.QUEUED, response.status());
        assertEquals(0, response.progress());
        assertEquals(SummarizationJobStage.QUEUED,
                response.processingStage());

        ArgumentCaptor<SummarizationJob> captor =
                ArgumentCaptor.forClass(SummarizationJob.class);
        verify(jobRepository).save(captor.capture());
        assertFalse(captor.getValue().isCancellationRequested());
        assertEquals(100, captor.getValue().getMaximumGeminiCalls());
        assertEquals(8, captor.getValue().getMaximumReductionDepth());
    }

    @Test
    void rejectsInvalidDocumentId() {
        assertThrows(
                ResponseStatusException.class,
                () -> service.createJob(new SummaryJobRequest(0L))
        );
    }

    @Test
    void rejectsMissingDocument() {
        when(documentRepository.findById(99L))
                .thenReturn(Optional.empty());

        assertThrows(
                ResponseStatusException.class,
                () -> service.createJob(new SummaryJobRequest(99L))
        );
    }

    @Test
    void enforcesValidStateTransitions() {
        SummarizationJob job = job(SummarizationJobStatus.QUEUED);
        job.transitionTo(SummarizationJobStatus.PROCESSING);
        job.transitionTo(SummarizationJobStatus.COMPLETED);

        assertEquals(
                SummarizationJobStatus.COMPLETED,
                job.getStatus()
        );
        assertTrue(job.getCompletedAt() != null);
    }

    @Test
    void rejectsInvalidAndTerminalStateTransitions() {
        SummarizationJob job = job(SummarizationJobStatus.QUEUED);

        assertThrows(
                IllegalStateException.class,
                () -> job.transitionTo(SummarizationJobStatus.COMPLETED)
        );

        job.transitionTo(SummarizationJobStatus.CANCELLED);
        assertThrows(
                IllegalStateException.class,
                () -> job.transitionTo(SummarizationJobStatus.PROCESSING)
        );
    }

    @Test
    void enforcesProgressBoundaries() {
        SummarizationJob job = job(SummarizationJobStatus.QUEUED);
        job.setProgress(0);
        job.setProgress(100);

        assertEquals(100, job.getProgress());
        assertThrows(
                IllegalArgumentException.class,
                () -> job.setProgress(-1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> job.setProgress(101)
        );
    }

    @Test
    void doesNotReturnResultBeforeCompletion() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = job(SummarizationJobStatus.PROCESSING);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThrows(
                ResponseStatusException.class,
                () -> service.getCompletedResult(jobId)
        );
    }

    @Test
    void returnsCompletedResult() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = job(SummarizationJobStatus.COMPLETED);
        job.setId(jobId);
        job.setFinalSummary("completed summary");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        var result = service.getCompletedResult(jobId);

        assertEquals("completed summary", result.summary());
        assertEquals(SummarizationJobStatus.COMPLETED, result.status());
    }

    @Test
    void retrievesStatus() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = job(SummarizationJobStatus.QUEUED);
        job.setId(jobId);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        SummaryJobStatusResponse result = service.getStatus(jobId);

        assertEquals(jobId, result.jobId());
        assertEquals(SummarizationJobStatus.QUEUED, result.status());
    }

    private Document document(Long id) {
        Document document = new Document();
        document.setId(id);
        return document;
    }

    private SummarizationJob job(SummarizationJobStatus status) {
        SummarizationJob job = new SummarizationJob();
        job.setId(UUID.randomUUID());
        job.setDocument(document(1L));
        job.setStatus(status);
        job.setProcessingStage(SummarizationJobStage.QUEUED);
        job.setProgress(0);
        return job;
    }
}
