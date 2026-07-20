package io.modernia.pixerion.server.catalog.download;

import io.modernia.pixerion.domain.BookRef;
import io.modernia.pixerion.domain.CatalogException;
import io.modernia.pixerion.download.Downloader;
import io.modernia.pixerion.interop.BlockingCatalog;
import io.modernia.pixerion.interop.Refs;
import io.modernia.pixerion.server.catalog.dto.DownloadResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Registry and lifecycle for background download jobs.
 * <p>
 * A {@code POST /downloads} {@link #start}s a job on the {@code downloadExecutor}; the
 * job is the {@code DownloadProgress} sink core drives, so progress accrues in memory as
 * pages are written. A scheduled {@link #flushActive() flush} pushes each running job's
 * snapshot to its SSE subscribers, and finished jobs are swept after a retention window.
 */
@Service
public class DownloadJobService {

    private static final Duration RETENTION = Duration.ofMinutes(10);
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long FLUSH_MILLIS = 200;
    private static final long SWEEP_MILLIS = 60_000;

    private final ConcurrentMap<String, DownloadJob> jobs = new ConcurrentHashMap<>();
    private final TaskExecutor executor;
    private final TaskExecutor sseExecutor;

    public DownloadJobService(
            @Qualifier("downloadExecutor") TaskExecutor executor,
            @Qualifier("sseExecutor") TaskExecutor sseExecutor) {
        this.executor = executor;
        this.sseExecutor = sseExecutor;
    }

    /**
     * Starts a background download and returns its freshly created (RUNNING) job.
     * If the executor rejects the task (pool and queue full), the returned job is
     * already FAILED — a registered job must always reach a terminal state, or the
     * sweeper (which only evicts finished jobs) would retain it forever.
     */
    public DownloadJob start(String source, BlockingCatalog catalog, BookRef ref) {
        DownloadJob job = new DownloadJob(UUID.randomUUID().toString(), source, sseExecutor);
        jobs.put(job.id(), job);
        try {
            executor.execute(() -> run(job, catalog, ref));
        } catch (RejectedExecutionException e) {
            job.fail(DownloadStatus.FAILED, "Too many downloads in progress; retry later");
        }
        return job;
    }

    /**
     * @return the job with this id, or {@code null} if unknown (or already swept).
     */
    public DownloadJob find(String id) {
        return jobs.get(id);
    }

    /**
     * @return an SSE stream subscribed to the job, or {@code null} if the id is unknown.
     */
    public SseEmitter subscribe(String id) {
        DownloadJob job = jobs.get(id);
        if (job == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        job.attach(emitter);
        return emitter;
    }

    private void run(DownloadJob job, BlockingCatalog catalog, BookRef ref) {
        try {
            Downloader.Summary summary = catalog.download(ref, null, job);
            if (summary == null) {
                job.fail(DownloadStatus.NOT_FOUND, "No book found for " + Refs.render(ref));
            } else {
                job.complete(new DownloadResponse(
                        summary.getBook().getTitle(),
                        summary.getDirectory().toString(),
                        summary.getStats().getPages(),
                        summary.getStats().getChapters()));
            }
        } catch (CatalogException e) {
            job.fail(DownloadStatus.FAILED, e.getMessage());
        } catch (Exception e) {
            // Exception, not RuntimeException: core is Kotlin, so checked exceptions (an
            // IOException from a page write, say) arrive here undeclared. Letting one escape
            // would strand the job as RUNNING forever — unsweepable, subscribers hanging.
            job.fail(DownloadStatus.FAILED, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Pushes each running job's latest snapshot to its subscribers (no-op when nothing
     * changed). Each job's flush is dispatched onto the SSE send executor — this runs on
     * Boot's single scheduler thread, which must stay free for {@link #evictFinished()}
     * and the other jobs even when one subscriber's connection has stalled.
     */
    @Scheduled(fixedRate = FLUSH_MILLIS)
    void flushActive() {
        for (DownloadJob job : jobs.values()) {
            job.flushAsync();
        }
    }

    /**
     * Drops finished jobs once they've aged past the retention window, bounding memory.
     */
    @Scheduled(fixedRate = SWEEP_MILLIS)
    void evictFinished() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobs.values().removeIf(job -> job.finishedAt() != null && job.finishedAt().isBefore(cutoff));
    }
}
