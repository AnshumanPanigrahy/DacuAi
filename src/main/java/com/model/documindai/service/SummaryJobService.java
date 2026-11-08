package com.model.documindai.service;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.SummarizationJob;
import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;
import com.model.documindai.model.SummaryJobRequest;
import com.model.documindai.model.SummaryJobResponse;
import com.model.documindai.model.SummaryJobResultResponse;
import com.model.documindai.model.SummaryJobStatusResponse;
import com.model.documindai.repository.DocumentRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class SummaryJobService {

    private final DocumentRepository documentRepository;
    private final SummarizationJobRepository jobRepository;
    private final int maximumGeminiCalls;
    private final int maximumReductionDepth;
    private final long maximumDurationMillis;

    public SummaryJobService(
            DocumentRepository documentRepository,
            SummarizationJobRepository jobRepository) {
        this(
                documentRepository,
                jobRepository,
                100,
                8,
                120000
        );
    }

    public SummaryJobService(
            DocumentRepository documentRepository,
            SummarizationJobRepository jobRepository,
            int maximumGeminiCalls,
            int maximumReductionDepth) {
        this(
                documentRepository,
                jobRepository,
                maximumGeminiCalls,
                maximumReductionDepth,
                120000
        );
    }

    @Autowired
    public SummaryJobService(
            DocumentRepository documentRepository,
            SummarizationJobRepository jobRepository,
            @Value("${documind.summarization.maximum-gemini-calls:100}")
            int maximumGeminiCalls,
            @Value("${documind.summarization.maximum-reduction-depth:8}")
            int maximumReductionDepth,
            @Value("${documind.summarization.maximum-duration-millis:120000}")
            long maximumDurationMillis) {
        if (maximumGeminiCalls < 1 || maximumReductionDepth < 0) {
            throw new IllegalArgumentException(
                    "Invalid summarization job limits."
            );
        }
        if (maximumDurationMillis < 1) {
            throw new IllegalArgumentException(
                    "Maximum summarization duration must be positive."
            );
        }
        this.documentRepository = documentRepository;
        this.jobRepository = jobRepository;
        this.maximumGeminiCalls = maximumGeminiCalls;
        this.maximumReductionDepth = maximumReductionDepth;
        this.maximumDurationMillis = maximumDurationMillis;
    }

    @Transactional
    public SummaryJobResponse createJob(SummaryJobRequest request) {
        Long documentId = request == null ? null : request.documentId();
        if (documentId == null || documentId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A positive document ID is required."
            );
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Document not found."
                ));

        SummarizationJob job = new SummarizationJob();
        job.setDocument(document);
        job.setStatus(SummarizationJobStatus.QUEUED);
        job.setProgress(0);
        job.setProcessingStage(SummarizationJobStage.QUEUED);
        job.setCompletedBatchCount(0);
        job.setTotalBatchCount(0);
        job.setGeminiCallCount(0);
        job.setMaximumGeminiCalls(maximumGeminiCalls);
        job.setMaximumReductionDepth(maximumReductionDepth);
        job.setCancellationRequested(false);
        job.setLeaseGeneration(0);
        job.setDeadlineAt(LocalDateTime.now().plusNanos(
                TimeUnit.MILLISECONDS.toNanos(maximumDurationMillis)
        ));

        return toResponse(jobRepository.save(job));
    }

    @Transactional(readOnly = true)
    public SummaryJobStatusResponse getStatus(UUID jobId) {
        return toStatusResponse(findJob(jobId));
    }

    @Transactional(readOnly = true)
    public SummaryJobResultResponse getCompletedResult(UUID jobId) {
        SummarizationJob job = findJob(jobId);
        if (job.getStatus() != SummarizationJobStatus.COMPLETED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The summarization result is not available."
            );
        }
        return new SummaryJobResultResponse(
                job.getId(),
                job.getDocument().getId(),
                job.getStatus(),
                job.getFinalSummary(),
                job.getCompletedAt()
        );
    }

    @Transactional
    public SummaryJobStatusResponse transition(
            UUID jobId,
            SummarizationJobStatus nextStatus) {
        if (nextStatus == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A target job status is required."
            );
        }
        SummarizationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Summarization job not found."
                ));
        if (nextStatus == SummarizationJobStatus.CANCELLED) {
            job.setCancellationRequested(true);
        }
        job.transitionTo(nextStatus);
        return toStatusResponse(jobRepository.save(job));
    }

    @Transactional
    public SummaryJobStatusResponse updateProgress(
            UUID jobId,
            int progress) {
        SummarizationJob job = findJob(jobId);
        job.setProgress(progress);
        return toStatusResponse(jobRepository.save(job));
    }

    private SummarizationJob findJob(UUID jobId) {
        if (jobId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A job ID is required."
            );
        }
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Summarization job not found."
                ));
    }

    private SummaryJobResponse toResponse(SummarizationJob job) {
        return new SummaryJobResponse(
                job.getId(),
                job.getDocument().getId(),
                job.getStatus(),
                job.getProgress(),
                job.getProcessingStage(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }

    private SummaryJobStatusResponse toStatusResponse(
            SummarizationJob job) {
        return new SummaryJobStatusResponse(
                job.getId(),
                job.getDocument().getId(),
                job.getStatus(),
                job.getProgress(),
                job.getProcessingStage(),
                job.getCompletedBatchCount(),
                job.getTotalBatchCount(),
                job.getErrorCode(),
                job.getSafeErrorMessage(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getCompletedAt()
        );
    }
}
