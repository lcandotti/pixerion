package io.modernia.pixerion.server.catalog.download;

import io.modernia.pixerion.domain.Book;
import io.modernia.pixerion.domain.BookRef;
import io.modernia.pixerion.domain.Catalog;
import io.modernia.pixerion.domain.DownloadEvent;
import io.modernia.pixerion.domain.SourceRef;
import io.modernia.pixerion.interop.BlockingCatalog;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of {@link DownloadJobService}'s job lifecycle guarantees: a registered
 * job must always reach a terminal state, whatever the worker throws — the sweeper only
 * evicts finished jobs, so a stranded RUNNING job would be retained forever — and one
 * subscriber's stalled connection must never freeze another job's SSE stream.
 */
class DownloadJobServiceTest {

    private static final BookRef REF = new SourceRef("mangadex", "id-1");

    @Test
    void aSneakyCheckedExceptionFailsTheJobInsteadOfStrandingIt() {
        // core is Kotlin: a checked IOException propagates through runBlocking undeclared,
        // which a catch of RuntimeException alone would miss. Reproduce it the same way.
        DownloadJobService service = new DownloadJobService(Runnable::run, Runnable::run);
        BlockingCatalog catalog = new BlockingCatalog(new ThrowingCatalog(new IOException("disk full")));

        DownloadJob job = service.start("mangadex", catalog, REF);

        assertThat(job.status()).isEqualTo(DownloadStatus.FAILED);
        assertThat(job.toStatusResponse().error()).contains("disk full");
    }

    @Test
    void anExecutorRejectionFailsTheJobInsteadOfStrandingIt() {
        TaskExecutor saturated = task -> {
            throw new TaskRejectedException("queue full");
        };
        DownloadJobService service = new DownloadJobService(saturated, Runnable::run);

        DownloadJob job = service.start("mangadex", new BlockingCatalog(new ThrowingCatalog(new IOException())), REF);

        assertThat(job.status()).isEqualTo(DownloadStatus.FAILED);
        assertThat(job.toStatusResponse().error()).contains("retry later");
        // The failed job stays queryable until the sweeper's retention window passes.
        assertThat(service.find(job.id())).isSameAs(job);
    }

    @Test
    void aStalledSseClientDoesNotBlockAnotherJobsFlush() throws Exception {
        // The download executor parks every task, so both jobs stay RUNNING and flushes
        // actually send; the send executor is one virtual thread per task, like the real
        // sseExecutor bean.
        TaskExecutor parked = task -> {
        };
        ExecutorService sends = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch stallRelease = new CountDownLatch(1);
        try {
            DownloadJobService service = new DownloadJobService(parked, sends::execute);
            BlockingCatalog catalog = new BlockingCatalog(new ThrowingCatalog(new IOException()));
            DownloadJob stalledJob = service.start("mangadex", catalog, REF);
            DownloadJob healthyJob = service.start("mangadex", catalog, REF);

            StallingSseEmitter stalled = new StallingSseEmitter(stallRelease);
            stalledJob.attach(stalled);
            CountingSseEmitter healthy = new CountingSseEmitter();
            healthyJob.attach(healthy);

            stalledJob.onStart(1);
            healthyJob.onStart(1);
            // The scheduler-tick entry point: it must dispatch and return, never send inline.
            service.flushActive();

            assertThat(stalled.stallEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // The stalled client is now wedged inside its send (on its own virtual
            // thread), yet the other job's snapshot still reaches its subscriber.
            assertThat(healthy.received.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            stallRelease.countDown();
            sends.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * A {@link Catalog} whose {@code find} throws its failure <em>undeclared</em> — the
     * Java analog of Kotlin code throwing a checked exception. The downloader calls
     * {@code find} first, so the other operations are never reached.
     */
    private static final class ThrowingCatalog implements Catalog {
        private final Throwable failure;

        ThrowingCatalog(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public Object find(BookRef ref, Continuation<? super Book> completion) {
            throw sneakyThrow(failure);
        }

        @Override
        public Object search(Map<String, String> query, Continuation<? super List<Book>> completion) {
            throw sneakyThrow(failure);
        }

        @Override
        public Flow<DownloadEvent> download(BookRef ref) {
            throw sneakyThrow(failure);
        }
    }

    /**
     * An {@link SseEmitter} whose second send (the first is attach()'s inline snapshot
     * replay, which must not park the test thread) blocks until released — a client
     * whose TCP window has stalled mid-stream.
     */
    private static final class StallingSseEmitter extends SseEmitter {
        final CountDownLatch stallEntered = new CountDownLatch(1);
        private final AtomicInteger sendCount = new AtomicInteger();
        private final CountDownLatch release;

        StallingSseEmitter(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void send(SseEmitter.SseEventBuilder builder) {
            if (sendCount.incrementAndGet() == 1) {
                return;
            }
            stallEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void complete() {
        }
    }

    /** An {@link SseEmitter} that just counts down once per send. */
    private static final class CountingSseEmitter extends SseEmitter {
        // Two sends expected: attach()'s snapshot replay, then the flushed update.
        final CountDownLatch received = new CountDownLatch(2);

        @Override
        public void send(SseEmitter.SseEventBuilder builder) {
            received.countDown();
        }

        @Override
        public void complete() {
        }
    }
}
