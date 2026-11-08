package com.model.documindai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration
public class SummarizationWorkerConfig {

    @Bean(name = "summarizationTaskExecutor")
    public ThreadPoolTaskExecutor summarizationTaskExecutor(
            @Value("${documind.summarization.worker.core-pool-size:1}")
            int corePoolSize,
            @Value("${documind.summarization.worker.maximum-pool-size:2}")
            int maximumPoolSize,
            @Value("${documind.summarization.worker.queue-capacity:4}")
            int queueCapacity) {
        if (corePoolSize < 1
                || maximumPoolSize < corePoolSize
                || queueCapacity < 1) {
            throw new IllegalArgumentException(
                    "Invalid summarization worker executor settings."
            );
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maximumPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("summary-worker-");
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "summarizationHeartbeatExecutor",
            destroyMethod = "shutdown")
    public ScheduledThreadPoolExecutor summarizationHeartbeatExecutor() {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "summary-heartbeat-"
                    );
                    thread.setDaemon(false);
                    return thread;
                });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }
}
