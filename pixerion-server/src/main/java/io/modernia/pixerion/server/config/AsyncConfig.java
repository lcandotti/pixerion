package io.modernia.pixerion.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async plumbing for streaming downloads. Spring MVC (servlet) has no background
 * infrastructure of its own, so this supplies both halves the download job service
 * needs: a bounded executor to run downloads off the request thread, and scheduling
 * (via {@link EnableScheduling}) for the periodic SSE snapshot flush.
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    /**
     * Runs server-side downloads off the request thread — one thread per in-flight
     * download, bounded so a burst can't exhaust the server.
     */
    @Bean
    public TaskExecutor downloadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("download-");
        executor.initialize();
        return executor;
    }

    /**
     * Delivers SSE events, one virtual thread per dispatched send. Sends must run off
     * both the shared scheduler thread (which flushes every running job) and core's
     * download threads (whose progress callbacks must never block): a subscriber with
     * a stalled TCP window then wedges only its own job's sends, never the flusher,
     * the sweeper, or another job's stream.
     */
    @Bean
    public TaskExecutor sseExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("sse-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
