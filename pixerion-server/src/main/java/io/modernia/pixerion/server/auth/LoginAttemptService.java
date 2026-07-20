package io.modernia.pixerion.server.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory brute-force throttle for {@code POST /auth/login}: after
 * {@link #MAX_FAILURES} failed attempts for the same client IP + username within a
 * sliding {@link #WINDOW}, further attempts are rejected (429) until the window
 * relaxes. A successful login clears the key. Deliberately simple — per-instance
 * state, like the download-job registry — with a periodic sweep bounding the map.
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final long SWEEP_MILLIS = 5 * 60_000;

    // Key -> timestamps of failures still inside the window. Entries are pruned on
    // access (compute* keeps that atomic per key) and swept periodically.
    private final ConcurrentMap<String, List<Instant>> failures = new ConcurrentHashMap<>();

    /** @return whether this client/username pair has exhausted its failure budget. */
    public boolean isBlocked(String clientIp, String username) {
        List<Instant> recent = failures.computeIfPresent(key(clientIp, username), (key, attempts) -> pruneOrNull(attempts));
        return recent != null && recent.size() >= MAX_FAILURES;
    }

    /** Records one failed login for this client/username pair. */
    public void recordFailure(String clientIp, String username) {
        failures.compute(key(clientIp, username), (key, attempts) -> {
            List<Instant> recent = attempts == null ? new ArrayList<>() : prune(attempts);
            recent.add(Instant.now());
            return recent;
        });
    }

    /** Clears the pair's failures — a successful login proves the caller owns the account. */
    public void recordSuccess(String clientIp, String username) {
        failures.remove(key(clientIp, username));
    }

    /** Drops keys whose failures have all aged out of the window, bounding the map. */
    @Scheduled(fixedRate = SWEEP_MILLIS)
    void sweep() {
        for (String key : failures.keySet()) {
            failures.computeIfPresent(key, (k, attempts) -> pruneOrNull(attempts));
        }
    }

    private static String key(String clientIp, String username) {
        return clientIp + "|" + username.toLowerCase(Locale.ROOT);
    }

    private static List<Instant> prune(List<Instant> attempts) {
        Instant cutoff = Instant.now().minus(WINDOW);
        List<Instant> recent = new ArrayList<>(attempts.size());
        for (Instant attempt : attempts) {
            if (attempt.isAfter(cutoff)) {
                recent.add(attempt);
            }
        }
        return recent;
    }

    /** As {@link #prune}, but {@code null} when empty so computeIfPresent drops the entry. */
    private static List<Instant> pruneOrNull(List<Instant> attempts) {
        List<Instant> recent = prune(attempts);
        return recent.isEmpty() ? null : recent;
    }
}
