package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0076's own testing checklist for {@link Ids}, plus the two properties the
 * owner's 2026-09-08 answer on customer-account disclosure added.
 *
 * <p>Several tests build a private, freshly-constructed {@link TimeBasedEpochGenerator}
 * over a controllable clock instead of calling {@link Ids#newId()} directly.
 * That is deliberate: {@code Ids.newId()} shares one process-wide generator, so
 * driving it from a fake clock would mean mutating shared state the rest of
 * the suite also relies on. Constructing a second generator the same way
 * {@code Ids} does — same factory method, same {@link Ids.NeverGoesBackwardClock}
 * — exercises the identical code path deterministically instead of hoping a
 * tight loop happens to straddle a millisecond boundary.
 */
class IdsTests {

    @Test
    @DisplayName("newId emits version 7 and variant 0b10 for every id")
    void newIdHasVersion7AndVariant10() {
        for (int i = 0; i < 5_000; i++) {
            UUID id = Ids.newId();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a large batch of newId has no duplicates")
    void newIdBatchHasNoDuplicates() {
        int count = 50_000;
        Set<UUID> ids = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            ids.add(Ids.newId());
        }
        assertThat(ids).hasSize(count);
    }

    @Test
    @DisplayName("concurrent minting produces no duplicates and the exact count")
    void concurrentMintingIsExactAndDuplicateFree() throws Exception {
        int threads = 16;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<UUID>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    List<UUID> minted = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        minted.add(Ids.newId());
                    }
                    return minted;
                });
            }
            List<Future<List<UUID>>> results = pool.invokeAll(tasks);
            Set<UUID> all = new HashSet<>(threads * perThread * 2);
            int total = 0;
            for (Future<List<UUID>> result : results) {
                List<UUID> minted = result.get(30, TimeUnit.SECONDS);
                total += minted.size();
                all.addAll(minted);
            }
            assertThat(total).isEqualTo(threads * perThread);
            assertThat(all).hasSize(threads * perThread);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("two ids minted in the same millisecond compare in mint order")
    void sameMillisecondIdsCompareInMintOrder() {
        long fixedMillis = 1_800_000_000_000L; // a fixed, realistic instant
        TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator(
                new SecureRandom(), new Ids.NeverGoesBackwardClock(() -> fixedMillis));

        List<UUID> minted = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            minted.add(generator.generate());
        }

        assertThat(minted).isSortedAccordingTo(UUID::compareTo);
        assertThat(new HashSet<>(minted)).hasSize(minted.size());
    }

    @Test
    @DisplayName("ids minted across a millisecond boundary compare in mint order")
    void crossMillisecondBoundaryIdsCompareInMintOrder() {
        long base = 1_800_000_000_000L;
        // same, same, +1ms, +1ms, +2ms: exercises both the within-millisecond
        // counter and the roll into a fresh millisecond.
        long[] schedule = {base, base, base + 1, base + 1, base + 2};
        int[] index = {0};
        TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator(
                new SecureRandom(), new Ids.NeverGoesBackwardClock(() -> schedule[index[0]++]));

        List<UUID> minted = new ArrayList<>();
        for (int i = 0; i < schedule.length; i++) {
            minted.add(generator.generate());
        }

        assertThat(minted).isSortedAccordingTo(UUID::compareTo);
    }

    @Test
    @DisplayName("a backwards clock step does not produce an id that sorts before one already issued")
    void backwardsClockStepDoesNotBreakOrdering() {
        long base = 1_800_000_000_000L;
        // The clock walks forward, then steps backwards by five seconds (an NTP
        // correction is the realistic cause), then forward again past where it
        // was before the step.
        long[] schedule = {base, base + 5_000, base - 5_000, base - 5_000, base + 10_000};
        int[] index = {0};
        Ids.NeverGoesBackwardClock clock = new Ids.NeverGoesBackwardClock(() -> schedule[index[0]++]);
        TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator(new SecureRandom(), clock);

        List<UUID> minted = new ArrayList<>();
        for (int i = 0; i < schedule.length; i++) {
            minted.add(generator.generate());
        }

        assertThat(minted).isSortedAccordingTo(UUID::compareTo);
    }

    @Test
    @DisplayName("NeverGoesBackwardClock itself never reports a smaller millisecond than it already has")
    void clockNeverReportsATimeEarlierThanItsOwnHighWaterMark() {
        long[] schedule = {100, 200, 150, 50, 300, 10};
        int[] index = {0};
        Ids.NeverGoesBackwardClock clock = new Ids.NeverGoesBackwardClock(() -> schedule[index[0]++]);

        long previous = Long.MIN_VALUE;
        for (int i = 0; i < schedule.length; i++) {
            long observed = clock.currentTimeMillis();
            assertThat(observed).isGreaterThanOrEqualTo(previous);
            previous = observed;
        }
    }

    @Test
    @DisplayName("newUndisclosedTimestampId has the v7 shape")
    void newUndisclosedTimestampIdHasVersion7AndVariant10() {
        for (int i = 0; i < 5_000; i++) {
            UUID id = Ids.newUndisclosedTimestampId();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a large batch of newUndisclosedTimestampId has no duplicates")
    void newUndisclosedTimestampIdBatchHasNoDuplicates() {
        int count = 50_000;
        Set<UUID> ids = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            ids.add(Ids.newUndisclosedTimestampId());
        }
        assertThat(ids).hasSize(count);
    }

    @Test
    @DisplayName("two accounts created in the same millisecond do not sort adjacently")
    void undisclosedTimestampIdsMintedTogetherDoNotSortAdjacently() {
        // Ids.newId() mints two same-millisecond ids by incrementing a counter
        // by a handful of units (see sameMillisecondIdsCompareInMintOrder
        // above) — that is exactly the "adjacent in time" signal this
        // generator exists to not have. Minted back-to-back, these two must
        // not show that pattern: their would-be-timestamp fields should differ
        // by a wide margin, not a small one.
        UUID first = Ids.newUndisclosedTimestampId();
        UUID second = Ids.newUndisclosedTimestampId();

        long firstField = timestampField(first);
        long secondField = timestampField(second);

        // Chance of a true 48-bit uniform draw landing this close by accident
        // is on the order of 1 in 280 million; a generator that (wrongly) used
        // a real, shared clock plus a small counter would fail this every time.
        assertThat(Math.abs(firstField - secondField)).isGreaterThan(1_000_000L);
    }

    @Test
    @DisplayName("the timestamp component does not correlate with the creation instant")
    void undisclosedTimestampComponentDoesNotCorrelateWithCreationInstant() {
        long before = System.currentTimeMillis();
        List<UUID> minted = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            minted.add(Ids.newUndisclosedTimestampId());
        }
        long after = System.currentTimeMillis();

        // A generous +/- one day window around the real minting window: real
        // v7 ids from Ids.newId() would land inside it every time. A field
        // drawn uniformly across roughly 8.9 million years should land inside
        // a two-day window essentially never; asserting "none of 200 did" is
        // already astronomically unlikely to hold by chance if the field were
        // in fact derived from the real clock.
        long windowStart = before - 86_400_000L;
        long windowEnd = after + 86_400_000L;
        List<UUID> withinRealTimeWindow = minted.stream()
                .filter(id -> {
                    long field = timestampField(id);
                    return field >= windowStart && field <= windowEnd;
                })
                .toList();

        assertThat(withinRealTimeWindow).isEmpty();
    }

    /** The 48 bits that would be a millisecond timestamp on a real v7 id. */
    private static long timestampField(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }
}
