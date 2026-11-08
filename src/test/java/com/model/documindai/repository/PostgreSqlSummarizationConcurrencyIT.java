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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgreSqlSummarizationConcurrencyIT {

    @MockitoBean
    private com.model.documindai.service.GeminiModelService
            geminiModelService;

    @MockitoBean
    private com.model.documindai.service.GeminiVisionOcrService
            geminiVisionOcrService;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("documind_test")
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
    void concurrentClaimsUseSkipLockedAndClaimEachJobOnce()
            throws Exception {
        SummarizationJob first = queuedJob();
        SummarizationJob second = queuedJob();
        jobRepository.saveAll(List.of(first, second));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<SummarizationJobClaimService.ClaimedJob>>>
                    futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return claimService.claimNextWithGeneration(
                                "worker-a", LocalDateTime.now().plusMinutes(5));
                    }),
                    pool.submit(() -> {
                        start.await();
                        return claimService.claimNextWithGeneration(
                                "worker-b", LocalDateTime.now().plusMinutes(5));
                    })
            );
            start.countDown();
            List<Optional<SummarizationJobClaimService.ClaimedJob>> claims =
                    List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                            futures.get(1).get(10, TimeUnit.SECONDS));

            assertTrue(claims.stream().allMatch(Optional::isPresent));
            assertEquals(2, claims.stream().map(Optional::get)
                    .map(SummarizationJobClaimService.ClaimedJob::jobId)
                    .distinct().count());
            assertEquals(2, jobRepository.findByStatus(
                    SummarizationJobStatus.PROCESSING).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reservationsAreAtomicAndNeverExceedMaximum() throws Exception {
        SummarizationJob job = queuedJob();
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.SUMMARIZING_BATCHES);
        job.setWorkerId("worker-a");
        job.setLeaseGeneration(4);
        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        job.setMaximumGeminiCalls(10);
        jobRepository.saveAndFlush(job);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(pool.submit(() -> jobRepository.reserveGeminiCall(
                        job.getId(), "worker-a", 4,
                        LocalDateTime.now())));
            }
            long successes = 0;
            for (Future<Integer> future : futures) {
                successes += future.get(10, TimeUnit.SECONDS);
            }

            assertEquals(10, successes);
            assertEquals(10, jobRepository.findById(job.getId()).orElseThrow()
                    .getGeminiCallCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidOwnershipExpiredAndCancelledJobsCannotReserve() {
        SummarizationJob job = queuedJob();
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.SUMMARIZING_BATCHES);
        job.setWorkerId("worker-a");
        job.setLeaseGeneration(4);
        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        job.setMaximumGeminiCalls(10);
        jobRepository.saveAndFlush(job);

        assertEquals(0, jobRepository.reserveGeminiCall(
                job.getId(), "worker-b", 4, LocalDateTime.now()));
        assertEquals(0, jobRepository.reserveGeminiCall(
                job.getId(), "worker-a", 3, LocalDateTime.now()));

        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);
        assertEquals(0, jobRepository.reserveGeminiCall(
                job.getId(), "worker-a", 4, LocalDateTime.now()));

        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        job.setCancellationRequested(true);
        jobRepository.saveAndFlush(job);
        assertEquals(0, jobRepository.reserveGeminiCall(
                job.getId(), "worker-a", 4, LocalDateTime.now()));
    }

    @Test
    void terminalJobsAreNotRecovered() {
        SummarizationJob job = queuedJob();
        job.setStatus(SummarizationJobStatus.COMPLETED);
        job.setProcessingStage(SummarizationJobStage.FINALIZING);
        job.setLeaseGeneration(9);
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        assertEquals(0, jobRepository.recoverExpiredJob(
                job.getId(), SummarizationJobStatus.QUEUED, LocalDateTime.now()));
        assertEquals(SummarizationJobStatus.COMPLETED,
                jobRepository.findById(job.getId()).orElseThrow().getStatus());
    }

    @Test
    void staleGenerationCannotUpdateProgressOrCompleteBatch() {
        SummarizationJob job = queuedJob();
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.SUMMARIZING_BATCHES);
        job.setWorkerId("worker-new");
        job.setLeaseGeneration(2);
        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        jobRepository.saveAndFlush(job);

        SummarizationBatch batch = new SummarizationBatch();
        batch.setJob(job);
        batch.setBatchNumber(0);
        batch.setStatus(SummarizationBatchStatus.PROCESSING);
        batch.setFirstChunkNumber(0);
        batch.setLastChunkNumber(1);
        batch.setLeaseGeneration(2);
        batchRepository.saveAndFlush(batch);

        assertEquals(0, jobRepository.updateOwnedProgress(
                job.getId(), "worker-old", 1, LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(5), 1, 50,
                SummarizationJobStage.SUMMARIZING_BATCHES));
        assertEquals(0, batchRepository.completeBatch(
                batch.getId(), "worker-old", 1,
                SummarizationBatchStatus.COMPLETED.name(), "summary", "[]",
                10, 5, 1, null, LocalDateTime.now()));
    }

    @Test
    void expiredLeaseRecoveryIncrementsGenerationAndPreservesCompletedBatch() {
        SummarizationJob job = queuedJob();
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.SUMMARIZING_BATCHES);
        job.setWorkerId("worker-old");
        job.setLeaseGeneration(7);
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        SummarizationBatch completed = new SummarizationBatch();
        completed.setJob(job);
        completed.setBatchNumber(0);
        completed.setStatus(SummarizationBatchStatus.COMPLETED);
        completed.setFirstChunkNumber(0);
        completed.setLastChunkNumber(0);
        completed.setSummaryText("kept");
        completed.setLeaseGeneration(7);
        batchRepository.saveAndFlush(completed);

        int recovered = jobRepository.recoverExpiredJob(
                job.getId(), SummarizationJobStatus.QUEUED, LocalDateTime.now());

        assertEquals(1, recovered);
        SummarizationJob reloaded = jobRepository.findById(job.getId())
                .orElseThrow();
        assertEquals(8, reloaded.getLeaseGeneration());
        assertEquals(SummarizationJobStatus.QUEUED, reloaded.getStatus());
        assertEquals("kept", batchRepository.findById(completed.getId())
                .orElseThrow().getSummaryText());
    }

    private SummarizationJob queuedJob() {
        Document document = new Document();
        document.setTitle("test");
        document.setFileName(UUID.randomUUID() + ".pdf");
        documentRepository.saveAndFlush(document);

        SummarizationJob job = new SummarizationJob();
        job.setDocument(document);
        job.setStatus(SummarizationJobStatus.QUEUED);
        job.setProcessingStage(SummarizationJobStage.QUEUED);
        job.setMaximumGeminiCalls(10);
        job.setMaximumReductionDepth(2);
        job.setCancellationRequested(false);
        return job;
    }
}
