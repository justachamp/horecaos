package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.api.TillRevisionNoticeOpened;
import uz.horecaos.platform.ordering.api.TillRevisionNotices.Notice;
import uz.horecaos.platform.ordering.application.TillRevisionNoticeService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcTillRevisionNoticeStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * An order the till holds whose total moved (ADR 0137, row 4.2c), on the migrated schema: one open notice per
 * order, the till's total kept across a second weighing, a notice that closes when the totals agree again, and
 * what the database refuses so a service bug cannot write it.
 *
 * <p>The foreign key to {@code ordering.orders} is dropped in this class's setup: building a real order needs a
 * tenant, a brand, a channel, a publication, a quote and a cart, none of which this class is about, and the
 * key is the same composite shape {@code V0557} proved. Everything else is the production schema.
 */
class TillRevisionNoticeServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TillRevisionNoticeService service;
    private final List<Object> published = new ArrayList<>();
    private UUID order;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql(
                        "ALTER TABLE ordering.order_till_revision_notices DROP CONSTRAINT IF EXISTS fk_till_revision_notice_order")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_till_revision_notices").update();
        published.clear();
        service = new TillRevisionNoticeService(new JdbcTillRevisionNoticeStore(jdbc), published::add);
        order = UUID.randomUUID();
    }

    @Test
    @DisplayName("a weight that moves the total of an order the till holds opens one notice with what the till holds, "
            + "and signals the alert once")
    void opensAOneNoticeAndSignalsOnce() {
        moved(2, 215_000, 208_000, T0);

        Notice notice = service.openFor(TENANT, order).orElseThrow();
        assertThat(notice.status()).isEqualTo("OPEN");
        assertThat(notice.tillTotalMinor())
                .as("what the till holds is the total before the weighing")
                .isEqualTo(215_000);
        assertThat(notice.revisedTotalMinor()).isEqualTo(208_000);
        assertThat(notice.deltaMinor()).isEqualTo(-7_000);
        assertThat(notice.revision()).isEqualTo(2);
        assertThat(notice.source()).isEqualTo("CATCHWEIGHT");
        assertThat(published).singleElement().isInstanceOfSatisfying(TillRevisionNoticeOpened.class, signal -> {
            assertThat(signal.noticeId()).isEqualTo(notice.id());
            assertThat(signal.orderId()).isEqualTo(order);
            assertThat(signal.tillTotalMinor()).isEqualTo(215_000);
            assertThat(signal.revisedTotalMinor()).isEqualTo(208_000);
            assertThat(signal.brandId()).isEqualTo(BRAND);
            assertThat(signal.locationId()).isEqualTo(LOCATION);
        });
    }

    @Test
    @DisplayName("a total that did not move opens nothing")
    void aTotalThatDidNotMoveOpensNothing() {
        moved(2, 215_000, 215_000, T0);

        assertThat(service.openFor(TENANT, order)).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a second weighing before anyone acted keeps what the till holds, takes the new figure and "
            + "raises no second alert")
    void aSecondWeighingUpdatesTheOpenNotice() {
        moved(2, 215_000, 208_000, T0);
        moved(3, 208_000, 231_000, T0.plusSeconds(60));

        Notice notice = service.openFor(TENANT, order).orElseThrow();
        assertThat(notice.tillTotalMinor())
                .as("the till still holds the total it held when the notice opened")
                .isEqualTo(215_000);
        assertThat(notice.revisedTotalMinor()).isEqualTo(231_000);
        assertThat(notice.revision()).isEqualTo(3);
        assertThat(published).as("one alert per notice").hasSize(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a weight corrected back to what the till holds closes the notice, by the platform")
    void aWeightCorrectedBackClosesTheNotice() {
        moved(2, 215_000, 208_000, T0);
        moved(3, 208_000, 215_000, T0.plusSeconds(60));

        assertThat(service.openFor(TENANT, order)).isEmpty();
        Notice latest = service.latestFor(TENANT, order).orElseThrow();
        assertThat(latest.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(latest.resolvedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName("a notice is closed once, as told or as acknowledged, and a later weighing opens a new one "
            + "against what the till was told")
    void aClosedNoticeStaysClosedAndALaterWeighingOpensANewOne() {
        moved(2, 215_000, 208_000, T0);
        Notice first = service.openFor(TENANT, order).orElseThrow();

        assertThat(service.markTold(TENANT, first.id(), first.revision(), "operator-1", T0.plusSeconds(30)))
                .isTrue();
        assertThat(service.markAcknowledged(TENANT, first.id(), first.revision(), "operator-2", T0.plusSeconds(31)))
                .as("a notice already closed is not closed again")
                .isFalse();
        Notice told = service.latestFor(TENANT, order).orElseThrow();
        assertThat(told.status()).isEqualTo("TOLD");
        assertThat(told.resolvedAt()).isEqualTo(T0.plusSeconds(30));

        moved(3, 208_000, 220_000, T0.plusSeconds(120));

        Notice second = service.openFor(TENANT, order).orElseThrow();
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.tillTotalMinor())
                .as("the till was told 208 000, so that is what it holds")
                .isEqualTo(208_000);
        assertThat(published).as("a new notice is a new alert").hasSize(2);
    }

    @Test
    @DisplayName("a notice is closed only as the revision the caller acted on: one weighed again since stays open, "
            + "and the same click on the newer revision closes it")
    void aNoticeThatMovedIsNotClosedAsTheOldRevision() {
        moved(2, 215_000, 208_000, T0);
        Notice seen = service.openFor(TENANT, order).orElseThrow();
        moved(3, 208_000, 203_000, T0.plusSeconds(10));

        assertThat(service.markTold(TENANT, seen.id(), seen.revision(), "operator-1", T0.plusSeconds(20)))
                .as("told the 208 000 that revision 2 said")
                .isFalse();
        assertThat(service.markAcknowledged(TENANT, seen.id(), seen.revision(), "operator-1", T0.plusSeconds(21)))
                .as("confirmed the till against the 208 000 that revision 2 said")
                .isFalse();
        Notice still = service.openFor(TENANT, order).orElseThrow();
        assertThat(still.status()).isEqualTo("OPEN");
        assertThat(still.revision()).isEqualTo(3);
        assertThat(still.revisedTotalMinor()).isEqualTo(203_000);
        assertThat(still.resolvedAt()).isNull();

        assertThat(service.markAcknowledged(TENANT, still.id(), still.revision(), "operator-1", T0.plusSeconds(30)))
                .isTrue();
        assertThat(service.latestFor(TENANT, order).orElseThrow().status()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    @DisplayName("an amount the till took for an older revision becomes what the till holds, and the notice stays "
            + "open for the newer total")
    void aTellingOfAnOlderRevisionUpdatesWhatTheTillHolds() {
        moved(2, 215_000, 208_000, T0);
        Notice seen = service.openFor(TENANT, order).orElseThrow();
        moved(3, 208_000, 203_000, T0.plusSeconds(10));

        assertThat(service.recordTold(
                        TENANT, seen.id(), seen.revision(), seen.revisedTotalMinor(), "operator-1", T0.plusSeconds(20)))
                .isTrue();

        Notice open = service.openFor(TENANT, order).orElseThrow();
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.tillTotalMinor()).as("the till took 208 000").isEqualTo(208_000);
        assertThat(open.revisedTotalMinor()).isEqualTo(203_000);
        assertThat(open.deltaMinor()).isEqualTo(-5_000);
        assertThat(open.revision()).isEqualTo(3);
    }

    @Test
    @DisplayName("if the newer total is the very amount the till took there is nothing left to correct: told")
    void aTellingThatLandsOnTheNewerTotalClosesTheNotice() {
        moved(2, 215_000, 208_000, T0);
        Notice seen = service.openFor(TENANT, order).orElseThrow();
        moved(3, 208_000, 209_000, T0.plusSeconds(10));
        moved(4, 209_000, 208_000, T0.plusSeconds(20));
        Notice newer = service.latestFor(TENANT, order).orElseThrow();
        assertThat(newer.status())
                .as("revision 4 is back at what revision 2 said, not what the till holds")
                .isEqualTo("OPEN");

        assertThat(service.recordTold(TENANT, seen.id(), seen.revision(), 208_000, "operator-1", T0.plusSeconds(30)))
                .isTrue();

        Notice told = service.latestFor(TENANT, order).orElseThrow();
        assertThat(told.status()).isEqualTo("TOLD");
        assertThat(told.resolvedAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(service.openFor(TENANT, order)).isEmpty();
    }

    @Test
    @DisplayName("recording an older telling touches nothing that is closed, or that is not newer than what was told")
    void anOlderTellingOnlyTouchesANewerOpenNotice() {
        moved(2, 215_000, 208_000, T0);
        Notice only = service.openFor(TENANT, order).orElseThrow();

        assertThat(service.recordTold(TENANT, only.id(), only.revision(), 208_000, "operator-1", T0.plusSeconds(5)))
                .as("the same revision is closed by markTold, not by this")
                .isFalse();
        assertThat(service.openFor(TENANT, order).orElseThrow().tillTotalMinor())
                .isEqualTo(215_000);

        service.markAcknowledged(TENANT, only.id(), only.revision(), "operator-1", T0.plusSeconds(10));
        assertThat(service.recordTold(TENANT, only.id(), 1, 100_000, "operator-1", T0.plusSeconds(20)))
                .as("a closed notice is not touched")
                .isFalse();
        assertThat(service.latestFor(TENANT, order).orElseThrow().tillTotalMinor())
                .isEqualTo(215_000);
    }

    @Test
    @DisplayName("a failed try is counted with its code, and only while the notice is open")
    void failedAttemptsAreCountedWhileOpen() {
        moved(2, 215_000, 208_000, T0);
        Notice open = service.openFor(TENANT, order).orElseThrow();

        service.recordFailedAttempt(TENANT, open.id(), "PROVIDER_REJECTED", T0.plusSeconds(5));
        service.recordFailedAttempt(TENANT, open.id(), "UNCERTAIN", T0.plusSeconds(10));

        Notice after = service.openFor(TENANT, order).orElseThrow();
        assertThat(after.attemptCount()).isEqualTo(2);
        assertThat(after.lastFailureCode()).isEqualTo("UNCERTAIN");

        service.markAcknowledged(TENANT, open.id(), open.revision(), "operator-1", T0.plusSeconds(20));
        service.recordFailedAttempt(TENANT, open.id(), "PROVIDER_REJECTED", T0.plusSeconds(30));
        assertThat(service.latestFor(TENANT, order).orElseThrow().attemptCount())
                .as("a closed notice is not touched by a late failure")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the database allows one open notice per order, a differing total, and a resolver on every "
            + "closed notice")
    void theDatabaseRefusesWhatTheServiceCouldGetWrong() {
        moved(2, 215_000, 208_000, T0);

        assertThatThrownBy(() -> insertRaw(order, "OPEN", 215_000, 200_000, false))
                .as("a second open notice for the order")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uq_till_revision_notice_open");
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "OPEN", 100_000, 100_000, false))
                .as("a notice about two totals that agree")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_till_revision_notice_differs");
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "TOLD", 100_000, 90_000, false))
                .as("a closed notice with nobody who closed it")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_till_revision_notice_resolution");
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "SHRUG", 100_000, 90_000, true))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_till_revision_notice_status");

        // The negative control: the same statement, well formed.
        insertRaw(UUID.randomUUID(), "TOLD", 100_000, 90_000, true);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    @DisplayName("an amendment cannot be the source: only a captured weight moves a till-held total")
    void onlyACapturedWeightIsASource() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ordering.order_till_revision_notices (
                            id, tenant_id, brand_id, location_id, order_id, source, revision, currency,
                            till_total_minor, revised_total_minor, status, opened_at, updated_at)
                        VALUES (:id, :t, :b, :l, :o, 'AMENDMENT', 2, 'UZS', 1, 2, 'OPEN', now(), now())
                        """)
                        .param("id", UUID.randomUUID())
                        .param("t", TENANT)
                        .param("b", BRAND)
                        .param("l", LOCATION)
                        .param("o", UUID.randomUUID())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_till_revision_notice_source");
    }

    // ------------------------------------------------------------------ helpers

    private void moved(int revision, long before, long after, Instant at) {
        service.orderTotalMoved(TENANT, BRAND, LOCATION, order, revision, "UZS", before, after, at);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM ordering.order_till_revision_notices")
                .query(Long.class)
                .single();
    }

    private void insertRaw(UUID orderId, String status, long till, long revised, boolean resolved) {
        jdbc.sql("""
                        INSERT INTO ordering.order_till_revision_notices (
                            id, tenant_id, brand_id, location_id, order_id, source, revision, currency,
                            till_total_minor, revised_total_minor, status, opened_at, updated_at,
                            resolved_at, resolved_by)
                        VALUES (:id, :t, :b, :l, :o, 'CATCHWEIGHT', 2, 'UZS', :till, :revised, :status, now(), now(),
                                CASE WHEN :resolved THEN now() END, CASE WHEN :resolved THEN 'operator' END)
                        """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("o", orderId)
                .param("till", till)
                .param("revised", revised)
                .param("status", status)
                .param("resolved", resolved)
                .update();
    }
}
