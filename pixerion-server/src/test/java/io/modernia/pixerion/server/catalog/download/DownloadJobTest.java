package io.modernia.pixerion.server.catalog.download;

import io.modernia.pixerion.server.catalog.dto.ChapterProgress;
import io.modernia.pixerion.server.catalog.dto.DownloadResponse;
import io.modernia.pixerion.server.catalog.dto.ErrorEvent;
import io.modernia.pixerion.server.catalog.dto.StateEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of {@link DownloadJob}'s snapshot + SSE fan-out logic — the parts the
 * HTTP tests can't reach without writing pages to disk. It drives the {@link
 * io.modernia.pixerion.download.DownloadProgress} callbacks directly and captures what
 * would be streamed via a recording {@link SseEmitter} subclass (no servlet, no network).
 * The send executor is the direct {@code Runnable::run}, so the (normally async) flush
 * and terminal sends happen inline and the assertions stay synchronous.
 */
class DownloadJobTest {

    @Test
    void flushPushesTheCurrentSnapshotAsAStateEvent() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        job.onStart(2);
        job.onChapterStart("c1", "1", 3);
        job.onPageWritten("c1", 2, 3);

        RecordingSseEmitter emitter = attach(job); // replays the current snapshot on subscribe

        StateEvent state = first(emitter, StateEvent.class);
        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("RUNNING");
        assertThat(state.totalChapters()).isEqualTo(2);
        assertThat(state.chaptersDone()).isZero();
        assertThat(state.chapters()).hasSize(1);
        ChapterProgress chapter = state.chapters().get(0);
        assertThat(chapter.chapterId()).isEqualTo("c1");
        assertThat(chapter.label()).isEqualTo("1");
        assertThat(chapter.written()).isEqualTo(2);
        assertThat(chapter.total()).isEqualTo(3);
    }

    @Test
    void flushSendsOnlyWhenTheSnapshotChanged() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        job.onStart(1);
        RecordingSseEmitter emitter = attach(job);

        job.flush();
        int afterFirstFlush = count(emitter, StateEvent.class);
        job.flush(); // nothing changed
        job.flush(); // nothing changed
        assertThat(count(emitter, StateEvent.class)).isEqualTo(afterFirstFlush);

        job.onPageWritten("c1", 1, 5); // now something moved (even for an as-yet-unseen chapter)
        job.onChapterStart("c1", "1", 5);
        job.flush();
        assertThat(count(emitter, StateEvent.class)).isEqualTo(afterFirstFlush + 1);
    }

    @Test
    void completeEmitsACompletedEventAndClosesTheStream() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        RecordingSseEmitter emitter = attach(job);

        DownloadResponse summary = new DownloadResponse("Berserk", "/tmp/x", 10, 2);
        job.complete(summary);

        assertThat(first(emitter, DownloadResponse.class)).isEqualTo(summary);
        assertThat(emitter.completed).isTrue();
        assertThat(job.status()).isEqualTo(DownloadStatus.COMPLETED);
    }

    @Test
    void failEmitsAnErrorEventCarryingTheStatusAndMessage() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        RecordingSseEmitter emitter = attach(job);

        job.fail(DownloadStatus.FAILED, "boom");

        ErrorEvent error = first(emitter, ErrorEvent.class);
        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo("FAILED");
        assertThat(error.message()).isEqualTo("boom");
        assertThat(emitter.completed).isTrue();
        assertThat(job.status()).isEqualTo(DownloadStatus.FAILED);
    }

    @Test
    void aLateSubscriberToAFinishedJobGetsTheTerminalEventImmediately() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        DownloadResponse summary = new DownloadResponse("Berserk", "/tmp/x", 10, 2);
        job.complete(summary); // finishes before anyone subscribes

        RecordingSseEmitter emitter = attach(job);

        assertThat(first(emitter, DownloadResponse.class)).isEqualTo(summary);
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void terminalEventsFanOutToEverySubscriber() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        RecordingSseEmitter a = attach(job);
        RecordingSseEmitter b = attach(job);

        job.complete(new DownloadResponse("Berserk", "/tmp/x", 0, 0));

        assertThat(first(a, DownloadResponse.class)).isNotNull();
        assertThat(first(b, DownloadResponse.class)).isNotNull();
        assertThat(a.completed).isTrue();
        assertThat(b.completed).isTrue();
    }

    @Test
    void onChapterCompleteAdvancesTheDoneCount() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        job.onStart(2);
        job.onChapterStart("c1", "1", 3);
        RecordingSseEmitter emitter = attach(job);

        job.onChapterComplete("c1");
        job.flush();

        StateEvent state = last(emitter, StateEvent.class);
        assertThat(state).isNotNull();
        assertThat(state.chaptersDone()).isEqualTo(1);
    }

    @Test
    void flushAsyncDispatchesAtMostOneFlushAtATime() {
        List<Runnable> dispatched = new ArrayList<>();
        DownloadJob job = new DownloadJob("job-1", "mangadex", dispatched::add); // queue, don't run
        attach(job);
        job.onStart(1);

        job.flushAsync();
        job.flushAsync(); // in-flight guard: the first task hasn't run yet
        assertThat(dispatched).hasSize(1);

        dispatched.remove(0).run(); // completing the flush releases the guard
        job.onPageWritten("c1", 1, 3);
        job.flushAsync();
        assertThat(dispatched).hasSize(1);
    }

    @Test
    void finishedAtIsNullUntilTheJobTerminates() {
        DownloadJob job = new DownloadJob("job-1", "mangadex", Runnable::run);
        assertThat(job.finishedAt()).isNull();

        job.complete(new DownloadResponse("Berserk", "/tmp/x", 1, 1));
        assertThat(job.finishedAt()).isNotNull();
    }

    @Test
    void toStatusResponseReflectsTheOutcome() {
        DownloadJob running = new DownloadJob("j1", "mangadex", Runnable::run);
        assertThat(running.toStatusResponse().status()).isEqualTo("RUNNING");
        assertThat(running.toStatusResponse().summary()).isNull();
        assertThat(running.toStatusResponse().error()).isNull();

        DownloadResponse summary = new DownloadResponse("Berserk", "/tmp/x", 5, 1);
        running.complete(summary);
        assertThat(running.toStatusResponse().status()).isEqualTo("COMPLETED");
        assertThat(running.toStatusResponse().summary()).isEqualTo(summary);

        DownloadJob absent = new DownloadJob("j2", "mangadex", Runnable::run);
        absent.fail(DownloadStatus.NOT_FOUND, "No book found");
        assertThat(absent.toStatusResponse().status()).isEqualTo("NOT_FOUND");
        assertThat(absent.toStatusResponse().error()).isEqualTo("No book found");
    }

    private static RecordingSseEmitter attach(DownloadJob job) {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        job.attach(emitter);
        return emitter;
    }

    private static <T> T first(RecordingSseEmitter emitter, Class<T> type) {
        return emitter.sent.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static int count(RecordingSseEmitter emitter, Class<?> type) {
        return (int) emitter.sent.stream().filter(type::isInstance).count();
    }

    private static <T> T last(RecordingSseEmitter emitter, Class<T> type) {
        return emitter.sent.stream().filter(type::isInstance).map(type::cast).reduce((a, b) -> b).orElse(null);
    }

    /**
     * An {@link SseEmitter} that records the payloads it would stream instead of writing to
     * an HTTP response, so a test can assert on them. Overriding {@code send}/{@code complete}
     * bypasses the servlet machinery entirely.
     */
    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<Object> sent = new ArrayList<>();
        private boolean completed;

        @Override
        public void send(SseEmitter.SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                sent.add(part.getData());
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
