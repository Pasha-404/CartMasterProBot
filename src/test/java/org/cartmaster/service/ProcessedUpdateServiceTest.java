package org.cartmaster.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedUpdateServiceTest {

    @Test
    void ignoresTheSameUpdateIdUntilItExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T12:00:00Z"));
        ProcessedUpdateService service = new ProcessedUpdateService(clock, Duration.ofMinutes(10), 10);

        assertThat(service.markIfNew(1)).isTrue();
        assertThat(service.markIfNew(1)).isFalse();

        clock.advance(Duration.ofMinutes(10));

        assertThat(service.markIfNew(1)).isTrue();
    }

    @Test
    void removesTheOldestEntryWhenTheCacheIsFull() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T12:00:00Z"));
        ProcessedUpdateService service = new ProcessedUpdateService(clock, Duration.ofHours(1), 2);

        assertThat(service.markIfNew(1)).isTrue();
        assertThat(service.markIfNew(2)).isTrue();
        assertThat(service.markIfNew(3)).isTrue();

        assertThat(service.markIfNew(1)).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
