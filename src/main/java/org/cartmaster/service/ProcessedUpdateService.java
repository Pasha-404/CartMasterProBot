package org.cartmaster.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProcessedUpdateService {

    static final int MAX_ENTRIES = 10_000;
    static final Duration TTL = Duration.ofMinutes(15);

    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final Map<Integer, Instant> processedUpdates = new LinkedHashMap<>();

    public ProcessedUpdateService() {
        this(Clock.systemUTC(), TTL, MAX_ENTRIES);
    }

    ProcessedUpdateService(Clock clock, Duration ttl, int maxEntries) {
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    public synchronized boolean markIfNew(Integer updateId) {
        if (updateId == null) {
            return true;
        }

        Instant now = clock.instant();
        removeExpiredEntries(now);
        if (processedUpdates.containsKey(updateId)) {
            return false;
        }

        processedUpdates.put(updateId, now);
        removeOverflowEntries();
        return true;
    }

    private void removeExpiredEntries(Instant now) {
        Instant expiresBefore = now.minus(ttl);
        Iterator<Map.Entry<Integer, Instant>> iterator = processedUpdates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Instant> entry = iterator.next();
            if (entry.getValue().isAfter(expiresBefore)) {
                return;
            }
            iterator.remove();
        }
    }

    private void removeOverflowEntries() {
        Iterator<Integer> iterator = processedUpdates.keySet().iterator();
        while (processedUpdates.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
