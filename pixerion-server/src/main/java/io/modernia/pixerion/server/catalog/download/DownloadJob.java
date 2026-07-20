package io.modernia.pixerion.server.catalog.download;

import io.modernia.pixerion.download.DownloadProgress;
import io.modernia.pixerion.server.catalog.dto.ChapterProgress;
import io.modernia.pixerion.server.catalog.dto.DownloadResponse;
import io.modernia.pixerion.server.catalog.dto.ErrorEvent;
import io.modernia.pixerion.server.catalog.dto.JobStatusResponse;
import io.modernia.pixerion.server.catalog.dto.StateEvent;
import org.jspecify.annotations.NonNull;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One in-flight (or finished) server-side download and its live progress.
 *
 * It is the {@link DownloadProgress} sink core's downloader drives: each callback only
 * mutates an in-memory snapshot (cheap and non-blocking, as the contract requires), and
 * a periodic {@link #flushAsync()} pushes that snapshot to subscribed {@link SseEmitter}s —
 * the server analog of the CLI's repaint loop, which coalesces per-page churn for free.
 *
 * <p>Two locks keep the download's callbacks off the network path: {@code snapshotLock}
 * guards the (fast) progress fields, while {@code sendLock} serializes emitter sends. A
 * send is never issued while holding {@code snapshotLock}. On top of that, every send —
 * periodic flush and terminal event alike — runs on the {@code sendExecutor}, never on
 * the shared scheduler thread or a core download thread, so a slow SSE client can delay
 * this job's sends but can never stall other jobs' streams or the threads driving the
 * download.
 */
public final class DownloadJob implements DownloadProgress {

    private final String id;
    private final String source;
    private final Executor sendExecutor;

    private final Object snapshotLock = new Object();
    private int totalChapters;
    private int chaptersDone;
    private final Map<String, ChapterState> chapters = new LinkedHashMap<>();
    private long version;         // bumped on every change; the flusher sends only when it moves
    private long flushedVersion = -1;

    private final Object sendLock = new Object();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    // At most one flush task in flight per job: a stalled send must not pile up work.
    private final AtomicBoolean flushInFlight = new AtomicBoolean();

    private volatile DownloadStatus status = DownloadStatus.RUNNING;
    private volatile DownloadResponse summary; // set when COMPLETED
    private volatile String error;             // set when FAILED / NOT_FOUND
    private volatile Instant finishedAt;

    DownloadJob(String id, String source, Executor sendExecutor) {
        this.id = id;
        this.source = source;
        this.sendExecutor = sendExecutor;
    }

    public String id() {
        return id;
    }

    public DownloadStatus status() {
        return status;
    }

    public JobStatusResponse toStatusResponse() {
        return new JobStatusResponse(id, status.name(), source, summary, error);
    }

    Instant finishedAt() {
        return finishedAt;
    }

    // ---- DownloadProgress: called serially on core's download threads; snapshot only. ----

    @Override
    public void onStart(int totalChapters) {
        synchronized (snapshotLock) {
            this.totalChapters = totalChapters;
            version++;
        }
    }

    @Override
    public void onChapterStart(@NonNull String chapterId, @NonNull String label, int totalPages) {
        synchronized (snapshotLock) {
            chapters.put(chapterId, new ChapterState(label, totalPages));
            version++;
        }
    }

    @Override
    public void onPageWritten(@NonNull String chapterId, int written, int totalPages) {
        synchronized (snapshotLock) {
            ChapterState chapter = chapters.get(chapterId);
            if (chapter != null) {
                chapter.written = written;
            }
            version++;
        }
    }

    @Override
    public void onChapterComplete(@NonNull String chapterId) {
        synchronized (snapshotLock) {
            chaptersDone++;
            version++;
        }
    }

    // ---- Subscription + fan-out ----

    /** Attaches an SSE stream: replays the current snapshot at once, then follows live updates. */
    void attach(SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(t -> emitters.remove(emitter));

        // Build the snapshot before taking sendLock — attach()/flush() must never nest the two locks.
        StateEvent snapshot = snapshot();
        synchronized (sendLock) {
            if (status != DownloadStatus.RUNNING) {
                sendTerminal(emitter); // late subscriber to an already-finished job
                safeComplete(emitter);
                return;
            }
            emitters.add(emitter);
            trySend(emitter, "state", snapshot);
        }
    }

    /**
     * Dispatches {@link #flush()} onto the send executor, at most one in flight per job.
     * The compare-and-set guard means a subscriber with a stalled TCP window (its send
     * parked under {@code sendLock}) can't pile a task per scheduler tick behind itself
     * — the tick that finds a flush still running simply skips, and a later tick
     * retries once the stalled send finishes. The caller never blocks either way.
     */
    void flushAsync() {
        if (!flushInFlight.compareAndSet(false, true)) {
            return; // a previous flush is still sending; the next tick will catch up
        }
        try {
            sendExecutor.execute(() -> {
                try {
                    flush();
                } finally {
                    flushInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            // The executor refused the task (e.g. shutdown): release the guard so a
            // later tick can retry instead of wedging this job's flushes forever.
            flushInFlight.set(false);
        }
    }

    /** Pushes the latest snapshot to subscribers, but only when it changed since the last flush. */
    void flush() {
        StateEvent snapshot;
        synchronized (snapshotLock) {
            if (status != DownloadStatus.RUNNING || version == flushedVersion || emitters.isEmpty()) {
                return;
            }
            flushedVersion = version;
            snapshot = snapshotUnlocked();
        }
        synchronized (sendLock) {
            if (status != DownloadStatus.RUNNING) {
                return;
            }
            for (SseEmitter emitter : emitters) {
                trySend(emitter, "state", snapshot);
            }
        }
    }

    /** Marks the job complete, then emits the terminal {@code completed} event off-thread. */
    void complete(DownloadResponse result) {
        // Volatile writes, summary strictly before status: any thread that observes a
        // terminal status is guaranteed to also read the matching summary/error.
        this.summary = result;
        this.finishedAt = Instant.now();
        this.status = DownloadStatus.COMPLETED;
        dispatchTerminal(emitter -> trySend(emitter, "completed", result));
    }

    /** Marks the job failed/absent, then emits the terminal {@code error} event off-thread. */
    void fail(DownloadStatus terminal, String message) {
        ErrorEvent event = new ErrorEvent(terminal.name(), message);
        this.error = message;
        this.finishedAt = Instant.now();
        this.status = terminal;
        dispatchTerminal(emitter -> trySend(emitter, "error", event));
    }

    /**
     * Sends the terminal event to every subscriber and closes their streams, on the send
     * executor — the caller is a core download thread, which must never block on a slow
     * SSE client. Exactly-once delivery still holds for emitters attached around the
     * terminal transition, because attach() reads {@code status} under {@code sendLock}
     * and the terminal status was published (volatile) before this task was submitted:
     * <ul>
     * <li>an attach() that observes the terminal status replays the event itself and
     *     never joins {@code emitters}, so this task cannot send to it a second time;
     * <li>an attach() that observed RUNNING did so before the status write — hence before
     *     this task existed, let alone acquired {@code sendLock} — so its emitter is in
     *     {@code emitters} by the time this task iterates, and gets the event here;
     * <li>{@code emitters} is only cleared under the same lock, after the sends, so no
     *     subscriber can slip between the two and be dropped.
     * </ul>
     */
    private void dispatchTerminal(Consumer<SseEmitter> send) {
        sendExecutor.execute(() -> {
            synchronized (sendLock) {
                for (SseEmitter emitter : emitters) {
                    send.accept(emitter);
                    safeComplete(emitter);
                }
                emitters.clear();
            }
        });
    }

    private void sendTerminal(SseEmitter emitter) {
        if (status == DownloadStatus.COMPLETED) {
            trySend(emitter, "completed", summary);
        } else {
            trySend(emitter, "error", new ErrorEvent(status.name(), error));
        }
    }

    private StateEvent snapshot() {
        synchronized (snapshotLock) {
            return snapshotUnlocked();
        }
    }

    private StateEvent snapshotUnlocked() {
        List<ChapterProgress> progress = new ArrayList<>(chapters.size());
        for (Map.Entry<String, ChapterState> entry : chapters.entrySet()) {
            ChapterState chapter = entry.getValue();
            progress.add(new ChapterProgress(entry.getKey(), chapter.label, chapter.written, chapter.total));
        }
        return new StateEvent(status.name(), totalChapters, chaptersDone, progress);
    }

    private void trySend(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException ex) {
            // Client went away or the emitter is already closed — drop it.
            emitters.remove(emitter);
        }
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // Already completed.
        }
    }

    private static final class ChapterState {
        private final String label;
        private final int total;
        private int written;

        ChapterState(String label, int total) {
            this.label = label;
            this.total = total;
        }
    }
}
