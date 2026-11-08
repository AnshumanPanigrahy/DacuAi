package com.model.documindai.repository;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.SummarizationBatch;
import com.model.documindai.entity.SummarizationBatchStatus;
import com.model.documindai.entity.SummarizationJob;
import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;
import com.model.documindai.service.SummarizationJobClaimService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlSummarizationRetryIT {

    @MockitoBean
    private com.model.documindai.service.GeminiModelService geminiModelService;

    @MockitoBean
    private com.model.documindai.service.GeminiVisionOcrService geminiVisionOcrService;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("documind_retry_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private DocumentRepository documentRepository;
    @Autowired
    private SummarizationJobRepository jobRepository;
    @Autowired
    private SummarizationBatchRepository batchRepository;
    @Autowired
    private SummarizationJobClaimService claimService;

    @AfterEach
    void cleanDatabase() {
        batchRepository.deleteAll();
        jobRepository.deleteAll();
        documentRepository.deleteAll();
    }

    @Test
    void processingBatchBelowMaximumIsRecoverable() {
        SummarizationJob job = expiredProcessingJob(1);
        SummarizationBatch batch = batch(
                job, SummarizationBatchStatus.PROCESSING, 1, 1
        );

        claimService.recoverExpired(LocalDateTime.now());

        assertEquals(SummarizationJobStatus.QUEUED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(SummarizationBatchStatus.PENDING,
                batchRepository.findById(batch.getId()).orElseThrow()
                        .getStatus());
        assertEquals(1, batchRepository.findById(batch.getId()).orElseThrow()
                .getAttemptCount());
    }

    @Test
    void exhaustedBatchCannotBeRecoveredOrClaimed() {
        SummarizationJob job = expiredProcessingJob(3);
        SummarizationBatch batch = batch(
                job, SummarizationBatchStatus.PROCESSING, 3, 3
        );

        claimService.recoverExpired(LocalDateTime.now());

        assertEquals(SummarizationJobStatus.FAILED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(SummarizationBatchStatus.FAILED,
                batchRepository.findById(batch.getId()).orElseThrow()
                        .getStatus());
        assertEquals("SUMMARIZATION_RETRY_EXHAUSTED",
                batchRepository.findById(batch.getId()).orElseThrow()
                        .getErrorCode());
        assertEquals(0, batchRepository.claimPendingBatch(
                batch.getId(), "worker-b", 4, 3));
    }

    @Test
    void permanentlyFailedBatchRemainsTerminalAfterRecovery() {
        SummarizationJob job = expiredProcessingJob(1);
        SummarizationBatch batch = batch(
                job, SummarizationBatchStatus.FAILED, 1, 1
        );
        batch.setErrorCode("GEMINI_AUTHENTICATION_FAILED");
        batchRepository.saveAndFlush(batch);

        claimService.recoverExpired(LocalDateTime.now());

        assertEquals(SummarizationJobStatus.FAILED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(SummarizationBatchStatus.FAILED,
                batchRepository.findById(batch.getId()).orElseThrow()
                        .getStatus());
        assertEquals("GEMINI_AUTHENTICATION_FAILED",
                batchRepository.findById(batch.getId()).orElseThrow()
                        .getErrorCode());
    }

    @Test
    void completedBatchIsNeverReset() {
        SummarizationJob job = expiredProcessingJob(3);
        SummarizationBatch batch = batch(
                job, SummarizationBatchStatus.COMPLETED, 3, 1
        );
        batch.setSummaryText("preserved");
        batchRepository.saveAndFlush(batch);

        claimService.recoverExpired(LocalDateTime.now());

        SummarizationBatch persisted = batchRepository.findById(batch.getId())
                .orElseThrow();
        assertEquals(SummarizationBatchStatus.COMPLETED,
                persisted.getStatus());
        assertEquals("preserved", persisted.getSummaryText());
    }

    @Test
    void cancelledJobBatchesCannotBeReclaimed() {
        SummarizationJob job = expiredProcessingJob(1);
        job.setCancellationRequested(true);
        jobRepository.saveAndFlush(job);
        SummarizationBatch batch = batch(
                job, SummarizationBatchStatus.PROCESSING, 1, 1
        );

        claimService.recoverExpired(LocalDateTime.now());

        assertEquals(SummarizationJobStatus.CANCELLED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(SummarizationBatchStatus.PROCESSING,
                batchRepository.findById(batch.getId()).orElseThrow()
                        .getStatus());
        assertEquals(0, batchRepository.claimPendingBatch(
                batch.getId(), "worker-b", 2, 3));
    }

    @Test
    void concurrentWorkersCannotClaimExhaustedPendingBatch()
            throws Exception {
        SummarizationJob job = activeJob();
        SummarizationBatch batch = batch(
                job, SummarizationBatchStatus.PENDING, 3, 3
        );
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return batchRepository.claimPendingBatch(
                        batch.getId(), "worker-a", 1, 3);
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return batchRepository.claimPendingBatch(
                        batch.getId(), "worker-b", 1, 3);
            });
            start.countDown();
            assertEquals(0, first.get(10, TimeUnit.SECONDS));
            assertEquals(0, second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void belowLimitClaimIncrementsAttemptAtomically() {
        SummarizationJob job = activeJob();
        SummarizationBatch batch = batch(
                job, SummarizationBatchStatus.PENDING, 1, 3
        );

        assertEquals(1, batchRepository.claimPendingBatch(
                batch.getId(), "worker-a", 1, 3));
        SummarizationBatch persisted = batchRepository.findById(batch.getId())
                .orElseThrow();
        assertEquals(SummarizationBatchStatus.PROCESSING,
                persisted.getStatus());
        assertEquals(2, persisted.getAttemptCount());
    }

    @Test
    void exhaustedRecoveryFailsJobWithoutDiscardingCompletedBatch() {
        SummarizationJob job = expiredProcessingJob(3);
        SummarizationBatch completed = batch(
                job, SummarizationBatchStatus.COMPLETED, 3, 1
        );
        completed.setSummaryText("kept");
        batchRepository.saveAndFlush(completed);
        SummarizationBatch exhausted = batch(
                job, SummarizationBatchStatus.PROCESSING, 3, 2
        );

        claimService.recoverExpired(LocalDateTime.now());

        assertEquals(SummarizationJobStatus.FAILED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
        assertEquals("kept", batchRepository.findById(completed.getId())
                .orElseThrow().getSummaryText());
        assertEquals(SummarizationBatchStatus.FAILED,
                batchRepository.findById(exhausted.getId()).orElseThrow()
                        .getStatus());
    }

    private Document document() {
        Document document = new Document();
        document.setTitle("retry");
        document.setFileName(UUID.randomUUID() + ".pdf");
        return documentRepository.saveAndFlush(document);
    }

    private SummarizationJob activeJob() {
        SummarizationJob job = new SummarizationJob();
        job.setDocument(document());
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.SUMMARIZING_BATCHES);
        job.setWorkerId("worker-a");
        job.setLeaseGeneration(1);
        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        job.setDeadlineAt(LocalDateTime.now().plusMinutes(5));
        job.setMaximumGeminiCalls(10);
        job.setMaximumReductionDepth(2);
        job.setCancellationRequested(false);
        return jobRepository.saveAndFlush(job);
    }

    private SummarizationJob expiredProcessingJob(int generation) {
        SummarizationJob job = activeJob();
        job.setLeaseGeneration(generation);
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        return jobRepository.saveAndFlush(job);
    }

    private SummarizationBatch batch(
            SummarizationJob job,
            SummarizationBatchStatus status,
            int attemptCount,
            int batchNumber) {
        SummarizationBatch batch = new SummarizationBatch();
        batch.setJob(job);
        batch.setBatchNumber(batchNumber);
        batch.setStatus(status);
        batch.setAttemptCount(attemptCount);
        batch.setFirstChunkNumber(batchNumber);
        batch.setLastChunkNumber(batchNumber);
        batch.setLeaseGeneration(job.getLeaseGeneration());
        batch.setWorkerId(job.getWorkerId());
        return batchRepository.saveAndFlush(batch);
    }
}
