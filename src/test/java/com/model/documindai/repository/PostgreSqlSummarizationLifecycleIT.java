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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlSummarizationLifecycleIT {

    @MockitoBean
    private com.model.documindai.service.GeminiModelService
            geminiModelService;

    @MockitoBean
    private com.model.documindai.service.GeminiVisionOcrService
            geminiVisionOcrService;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("documind_lifecycle_test")
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
    void deadlineSurvivesWorkerRecovery() {
        Document document = document();
        SummarizationJob job = queuedJob(document);
        job.setDeadlineAt(LocalDateTime.now().plusMinutes(10));
        jobRepository.saveAndFlush(job);
        LocalDateTime deadline = job.getDeadlineAt();

        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.LOADING_CHUNKS);
        job.setWorkerId("worker-a");
        job.setLeaseGeneration(1);
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        claimService.recoverExpired(LocalDateTime.now());

        LocalDateTime recoveredDeadline = jobRepository.findById(job.getId())
                .orElseThrow().getDeadlineAt();
        assertTrue(Math.abs(java.time.Duration.between(
                deadline, recoveredDeadline).toNanos()) <= 1_000);
    }

    @Test
    void expiredQueuedJobIsRejectedBeforeClaim() {
        SummarizationJob job = queuedJob(document());
        job.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        assertEquals(Optional.empty(), claimService.claimNextWithGeneration(
                "worker-a", LocalDateTime.now().plusMinutes(5)));
        assertEquals(SummarizationJobStatus.FAILED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(0, jobRepository.findById(job.getId()).orElseThrow()
                .getGeminiCallCount());
    }

    @Test
    void cancellationWinningBatchCompletionRaceCannotCompleteBatch()
            throws Exception {
        SummarizationJob job = processingJob("worker-a", 1);
        SummarizationBatch batch = processingBatch(job, "worker-a", 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> cancel = pool.submit(() -> {
                start.await();
                return jobRepository.requestCancellation(job.getId());
            });
            start.countDown();
            int cancellationResult = cancel.get(10, TimeUnit.SECONDS);
            Future<Integer> complete = pool.submit(() ->
                    batchRepository.completeBatch(
                            batch.getId(), "worker-a", 1,
                            SummarizationBatchStatus.COMPLETED.name(),
                            "summary", "[]", 10, 5, 1, null,
                            LocalDateTime.now()));
            int completionResult = complete.get(10, TimeUnit.SECONDS);
            assertEquals(1, cancellationResult);
            assertEquals(0, completionResult);
        } finally {
            pool.shutdownNow();
        }

        SummarizationJob persisted = jobRepository.findById(job.getId())
                .orElseThrow();
        SummarizationBatch persistedBatch = batchRepository
                .findById(batch.getId()).orElseThrow();
        if (persisted.isCancellationRequested()) {
            assertNotEquals(SummarizationBatchStatus.COMPLETED,
                    persistedBatch.getStatus());
        } else {
            assertEquals(SummarizationBatchStatus.COMPLETED,
                    persistedBatch.getStatus());
        }
    }

    @Test
    void cancellationAndTerminalCompletionHaveOneValidWinner()
            throws Exception {
        SummarizationJob job = processingJob("worker-a", 2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> cancel = pool.submit(() -> {
                start.await();
                return jobRepository.requestCancellation(job.getId());
            });
            start.countDown();
            assertEquals(1, cancel.get(10, TimeUnit.SECONDS));
            Future<Integer> finish = pool.submit(() ->
                    jobRepository.finishOwnedJob(
                            job.getId(), "worker-a", 2,
                            SummarizationJobStatus.COMPLETED, null, null,
                            LocalDateTime.now()));
            assertEquals(0, finish.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        SummarizationJob persisted = jobRepository.findById(job.getId())
                .orElseThrow();
        assertTrue(persisted.getStatus() == SummarizationJobStatus.CANCELLED
                || persisted.getStatus() == SummarizationJobStatus.COMPLETED);
        assertFalse(persisted.getStatus() == SummarizationJobStatus.COMPLETED
                && persisted.isCancellationRequested());
    }

    @Test
    void expiredCancellationIsRecoveredAsCancelledNotQueued() {
        SummarizationJob job = processingJob("worker-a", 4);
        job.setCancellationRequested(true);
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        claimService.recoverExpired(LocalDateTime.now());

        assertEquals(SummarizationJobStatus.CANCELLED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
    }

    @Test
    void failedLeasePreventsReservationsAndResultPersistence() {
        SummarizationJob job = processingJob("worker-a", 5);
        SummarizationBatch batch = processingBatch(job, "worker-a", 5);
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        assertEquals(0, jobRepository.renewLease(
                job.getId(), "worker-a", 5, LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(5)));
        assertEquals(0, jobRepository.reserveGeminiCall(
                job.getId(), "worker-a", 5, LocalDateTime.now()));
        assertEquals(0, batchRepository.completeBatch(
                batch.getId(), "worker-a", 5,
                SummarizationBatchStatus.COMPLETED.name(), "summary", "[]",
                1, 1, 1, null, LocalDateTime.now()));
    }

    @Test
    void recoveredWorkerFencesStaleWorkerState() {
        SummarizationJob job = processingJob("worker-a", 7);
        SummarizationBatch batch = processingBatch(job, "worker-a", 7);
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        claimService.recoverExpired(LocalDateTime.now());
        SummarizationJobClaimService.ClaimedJob claim =
                claimService.claimNextWithGeneration(
                        "worker-b", LocalDateTime.now().plusMinutes(5)
                ).orElseThrow();

        assertEquals(9, claim.leaseGeneration());
        assertEquals(0, jobRepository.updateOwnedProgress(
                job.getId(), "worker-a", 7, LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(5), 1, 50,
                SummarizationJobStage.SUMMARIZING_BATCHES));
        assertEquals(0, batchRepository.completeBatch(
                batch.getId(), "worker-a", 7,
                SummarizationBatchStatus.COMPLETED.name(), "old", "[]",
                1, 1, 1, null, LocalDateTime.now()));
    }

    private Document document() {
        Document document = new Document();
        document.setTitle("lifecycle");
        document.setFileName(UUID.randomUUID() + ".pdf");
        return documentRepository.saveAndFlush(document);
    }

    private SummarizationJob queuedJob(Document document) {
        SummarizationJob job = new SummarizationJob();
        job.setDocument(document);
        job.setStatus(SummarizationJobStatus.QUEUED);
        job.setProcessingStage(SummarizationJobStage.QUEUED);
        job.setMaximumGeminiCalls(10);
        job.setMaximumReductionDepth(2);
        job.setCancellationRequested(false);
        job.setDeadlineAt(LocalDateTime.now().plusMinutes(5));
        return job;
    }

    private SummarizationJob processingJob(String worker, long generation) {
        SummarizationJob job = queuedJob(document());
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.SUMMARIZING_BATCHES);
        job.setWorkerId(worker);
        job.setLeaseGeneration(generation);
        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        return jobRepository.saveAndFlush(job);
    }

    private SummarizationBatch processingBatch(
            SummarizationJob job,
            String worker,
            long generation) {
        SummarizationBatch batch = new SummarizationBatch();
        batch.setJob(job);
        batch.setBatchNumber(0);
        batch.setStatus(SummarizationBatchStatus.PROCESSING);
        batch.setWorkerId(worker);
        batch.setLeaseGeneration(generation);
        batch.setFirstChunkNumber(0);
        batch.setLastChunkNumber(1);
        return batchRepository.saveAndFlush(batch);
    }
}
