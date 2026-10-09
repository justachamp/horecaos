package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * How an operator starts the station-load backfill (ADR 0163): by setting a property for one start-up, and
 * never by a restart that forgot to remove it. The backfill's own rules (56 dates, oldest first, nothing on or
 * after the cutover) are {@code KitchenExecutionTests}'; this is the switch in front of it.
 */
class StationLoadBackfillRunnerTests {

    private static final LocalDate CUTOVER = LocalDate.parse("2026-10-08");
    private static final String CUTOVER_PROPERTY = "horecaos.reporting.station-load-backfill.cutover";

    private final Recording backfill = new Recording();

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(StationLoadBackfill.class, () -> backfill)
            .withUserConfiguration(StationLoadBackfillRunner.class);

    @Test
    @DisplayName("without the property there is no runner at all, so nothing runs on a restart")
    void absentThePropertyNothingExists() {
        contexts.run(context -> assertThat(context).doesNotHaveBean(StationLoadBackfillRunner.class));
        assertThat(backfill.calls).isEmpty();
    }

    @Test
    @DisplayName("with the cutover set the runner exists, and the backfill it starts covers every tenant")
    void theCutoverStartsAnAllTenantBackfill() throws Exception {
        contexts.withPropertyValues(CUTOVER_PROPERTY + "=2026-10-08").run(context -> {
            assertThat(context).hasSingleBean(StationLoadBackfillRunner.class);
            context.getBean(StationLoadBackfillRunner.class).run();
        });

        assertThat(backfill.calls).containsExactly("all before " + CUTOVER);
    }

    @Test
    @DisplayName("a tenant id restricts the run to that one tenant")
    void aTenantIdRestrictsTheRun() throws Exception {
        UUID tenant = UUID.fromString("018fe300-4000-7000-8000-0000000000a1");

        contexts.withPropertyValues(
                        CUTOVER_PROPERTY + "=2026-10-08",
                        "horecaos.reporting.station-load-backfill.tenant-id=" + tenant)
                .run(context -> context.getBean(StationLoadBackfillRunner.class).run());

        assertThat(backfill.calls).containsExactly(tenant + " before " + CUTOVER);
    }

    @Test
    @DisplayName("a backfill that fails is logged and never propagates, so it cannot stop the application")
    void aFailureDoesNotPropagate() throws Exception {
        backfill.failWith = new IllegalStateException("the close is unreachable");

        contexts.withPropertyValues(CUTOVER_PROPERTY + "=2026-10-08").run(context -> {
            context.getBean(StationLoadBackfillRunner.class).run();
            assertThat(context).hasNotFailed();
        });

        assertThat(backfill.calls).containsExactly("all before " + CUTOVER);
    }

    @Test
    @DisplayName("the application-ready hook starts the work on its own thread, so the health check is not held up")
    void readyStartsTheWorkOffTheCallingThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        backfill.started = started;
        Thread caller = Thread.currentThread();

        contexts.withPropertyValues(CUTOVER_PROPERTY + "=2026-10-08").run(context -> {
            context.getBean(StationLoadBackfillRunner.class).onReady();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        });

        Thread ranOn = Objects.requireNonNull(backfill.ranOn);
        assertThat(ranOn).isNotSameAs(caller);
        assertThat(ranOn.getName()).isEqualTo("station-load-backfill");
        assertThat(ranOn.isDaemon()).isTrue();
    }

    /** A backfill that records what it was asked to do and reads and writes nothing. */
    private static final class Recording extends StationLoadBackfill {

        private final List<String> calls = new ArrayList<>();
        private @Nullable RuntimeException failWith;
        private @Nullable CountDownLatch started;
        private volatile @Nullable Thread ranOn;

        @SuppressWarnings("NullAway")
        Recording() {
            super(null, null);
        }

        @Override
        public Map<UUID, Report> runAll(LocalDate cutover) {
            return record("all before " + cutover);
        }

        @Override
        public Report run(UUID tenantId, LocalDate cutover) {
            record(tenantId + " before " + cutover);
            return new Report(tenantId, cutover, List.of());
        }

        private Map<UUID, Report> record(String call) {
            synchronized (calls) {
                calls.add(call);
            }
            ranOn = Thread.currentThread();
            CountDownLatch latch = started;
            if (latch != null) {
                latch.countDown();
            }
            RuntimeException failure = failWith;
            if (failure != null) {
                throw failure;
            }
            return Map.of();
        }
    }
}
