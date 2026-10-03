package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffProfile;
import uz.horecaos.platform.iam.application.staff.StaffMemberRetentionSweeper.Mode;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0139's backfill, reconciliation and retention: the work that brings the
 * tenant's own record level with Keycloak's account list, says how far it has got,
 * and (only in report-only mode, until someone has seen a sample) overwrites an
 * ended employee's personal data.
 */
class StaffMemberReconciliationTests {

    private static TestDatabase.Handle db;
    private static StaffKit kit;

    private SimpleMeterRegistry meters;
    private StaffMemberReconciler reconciler;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        kit = new StaffKit(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        kit.reset();
        meters = new SimpleMeterRegistry();
        reconciler = new StaffMemberReconciler(kit.store, kit.accounts, kit.members, kit.clock, meters, 100);
    }

    private void account(
            String subject,
            @Nullable String first,
            @Nullable String last,
            @Nullable String phone,
            boolean hasPassword) {
        kit.accounts.profiles.put(subject, new StaffProfile(first, last, phone, hasPassword));
    }

    // -------------------------------------------------------------- backfill

    @Test
    @DisplayName(
            "an account that predates the record gets a row: ACTIVE with a password, PENDING without, names and phone from Keycloak")
    void thePredatingAccountsAreBackfilled() {
        kit.grant(StaffKit.TENANT_A, "old-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "old-2", PlatformRole.LOCATION_MANAGER, "LOCATION", StaffKit.LOCATION_1);
        account("old-1", "Olim", "Qosimov", "+998 90 321 00 11", true);
        account("old-2", "Pending", "Person", null, false);

        StaffMemberReconciler.Report report = reconciler.run();

        assertThat(report.created()).isEqualTo(2);
        assertThat(report.createdByTenant()).containsEntry(StaffKit.TENANT_A, 2);
        assertThat(report.unbackedAfter()).isZero();
        MemberRow active = kit.store.findBySubject(StaffKit.TENANT_A, "old-1").orElseThrow();
        assertThat(active.employmentStatus()).isEqualTo("ACTIVE");
        assertThat(kit.codec.displayName(active)).isEqualTo("Olim Qosimov");
        assertThat(kit.codec.openAll(active).phone()).isEqualTo("+998903210011");
        assertThat(kit.store
                        .findBySubject(StaffKit.TENANT_A, "old-2")
                        .orElseThrow()
                        .employmentStatus())
                .isEqualTo("PENDING");
        assertThat(kit.jdbc
                        .sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'staff.member.created' AND actor_type = 'SYSTEM_JOB'")
                        .query(Integer.class)
                        .single())
                .as("each created row says it was the backfill, not a person")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a second run is a no-op, and the completion gauges read zero")
    void aSecondRunIsANoOp() {
        kit.grant(StaffKit.TENANT_A, "old-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        account("old-1", "Olim", "Qosimov", null, true);

        assertThat(reconciler.run().created()).isEqualTo(1);
        StaffMemberReconciler.Report again = reconciler.run();

        assertThat(again.created()).isZero();
        assertThat(meters.get("horecaos.iam.staff.unbacked_active").gauge().value())
                .isZero();
        assertThat(meters.get("horecaos.iam.staff.members")
                        .tag("status", "ACTIVE")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meters.get("horecaos.iam.staff.ended_with_access").gauge().value())
                .isZero();
    }

    @Test
    @DisplayName(
            "a subject Keycloak cannot be asked about is left for retry and never guessed; a missing account is counted and left alone")
    void unansweredSubjectsAreLeftForRetry() {
        kit.grant(StaffKit.TENANT_A, "down-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "gone-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        account("down-1", "Down", "Time", null, true);
        kit.accounts.unreachable.add("down-1");
        // gone-1: no Keycloak account at all

        StaffMemberReconciler.Report first = reconciler.run();

        assertThat(first.created()).isZero();
        assertThat(first.unanswered()).isEqualTo(1);
        assertThat(first.noAccount()).isEqualTo(1);
        assertThat(first.unbackedAfter()).as("both are still unbacked").isEqualTo(2);
        assertThat(meters.get("horecaos.iam.staff.unbacked_active").gauge().value())
                .isEqualTo(2.0);

        kit.accounts.unreachable.clear();
        StaffMemberReconciler.Report retry = reconciler.run();
        assertThat(retry.created()).isEqualTo(1);
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "down-1")).isPresent();
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "gone-1"))
                .as("a person is never invented from a grant")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "subjects a pass cannot resolve do not hold the head of the work list: with a batch smaller than their number the healthy ones behind them still get a row")
    void unresolvableSubjectsDoNotStarveTheRest() {
        // Its own registry: a gauge name registered twice on one registry reads the first owner's value.
        SimpleMeterRegistry smallMeters = new SimpleMeterRegistry();
        StaffMemberReconciler small =
                new StaffMemberReconciler(kit.store, kit.accounts, kit.members, kit.clock, smallMeters, 2);
        // Three subjects Keycloak has no account for, sorting before the healthy two,
        // and a batch of two: read from the head every time, the first two fill every
        // pass and nobody behind them is ever reached.
        for (String gone : new String[] {"aaa-gone-1", "aaa-gone-2", "aaa-gone-3"}) {
            kit.grant(StaffKit.TENANT_A, gone, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        }
        for (String healthy : new String[] {"zzz-ok-1", "zzz-ok-2"}) {
            kit.grant(StaffKit.TENANT_A, healthy, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
            account(healthy, "Healthy", healthy.substring(healthy.length() - 1), null, true);
        }

        for (int pass = 0; pass < 4; pass++) {
            small.run();
        }

        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "zzz-ok-1")).isPresent();
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "zzz-ok-2")).isPresent();
        assertThat(smallMeters.get("horecaos.iam.staff.unbacked_active").gauge().value())
                .as("the three without an account are still counted: nobody is invented from a grant")
                .isEqualTo(3.0);

        // The ones left for retry do come round again once the end of the list is reached.
        account("aaa-gone-1", "Came", "Back", null, true);
        account("aaa-gone-3", "Also", "Back", null, true);
        for (int pass = 0; pass < 4; pass++) {
            small.run();
        }
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "aaa-gone-1")).isPresent();
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "aaa-gone-3")).isPresent();
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "aaa-gone-2")).isEmpty();
    }

    @Test
    @DisplayName(
            "a subject Keycloak could not be asked about is retried on a later pass even when a smaller batch moved on past it")
    void anUnansweredSubjectIsRetriedAfterTheCursorWraps() {
        StaffMemberReconciler small =
                new StaffMemberReconciler(kit.store, kit.accounts, kit.members, kit.clock, meters, 1);
        kit.grant(StaffKit.TENANT_A, "down-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "up-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        account("down-1", "Down", "Time", null, true);
        account("up-1", "Up", "Time", null, true);
        kit.accounts.unreachable.add("down-1");

        small.run();
        small.run();
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "up-1")).isPresent();
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "down-1")).isEmpty();

        kit.accounts.unreachable.clear();
        small.run();
        small.run();
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "down-1")).isPresent();
    }

    @Test
    @DisplayName("a device and a support session are skipped by class: they are grants without a colleague behind them")
    void machinePrincipalsGetNoRow() {
        kit.grant(StaffKit.TENANT_A, "kds-1", PlatformRole.KITCHEN_DEVICE, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "support-1", PlatformRole.SUPPORT_SESSION_VIEW, "TENANT", StaffKit.TENANT_A);
        account("kds-1", "Kitchen", "Display", null, true);
        account("support-1", "Support", "Person", null, true);

        StaffMemberReconciler.Report report = reconciler.run();

        assertThat(report.created()).isZero();
        assertThat(report.unbackedAfter()).isZero();
        assertThat(kit.store.listByTenant(StaffKit.TENANT_A, null)).isEmpty();
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "support-1"))
                .as("a HorecaOS support person is no tenant's staff, even though Keycloak knows their name")
                .isNull();
    }

    @Test
    @DisplayName(
            "an account with no name gets a row with null names, shown by its reference; Keycloak's 'Pending Profile' placeholder is no name")
    void aMissingNameIsAllowedAndNeverInvented() {
        kit.grant(StaffKit.TENANT_A, "nameless-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "placeholder-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        account("nameless-1", null, null, null, true);
        account("placeholder-1", "Pending", "Profile", null, false);

        reconciler.run();

        for (String subject : new String[] {"nameless-1", "placeholder-1"}) {
            MemberRow row = kit.store.findBySubject(StaffKit.TENANT_A, subject).orElseThrow();
            assertThat(row.protectedFirstName()).isNull();
            assertThat(row.protectedLastName()).isNull();
            assertThat(kit.directory.nameOf(StaffKit.TENANT_A, subject)).isEqualTo(row.displayReference());
        }
    }

    @Test
    @DisplayName("each tenant's subjects are backfilled into that tenant, and the report counts per tenant")
    void theBackfillIsPerTenant() {
        kit.grant(StaffKit.TENANT_A, "both-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_B, "both-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.B_LOCATION);
        account("both-1", "Both", "Tenants", null, true);

        StaffMemberReconciler.Report report = reconciler.run();

        assertThat(report.createdByTenant()).containsEntry(StaffKit.TENANT_A, 1).containsEntry(StaffKit.TENANT_B, 1);
        UUID a = kit.store
                .findBySubject(StaffKit.TENANT_A, "both-1")
                .orElseThrow()
                .id();
        UUID b = kit.store
                .findBySubject(StaffKit.TENANT_B, "both-1")
                .orElseThrow()
                .id();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName(
            "the rollout fallback names a staff subject with no row, and the row's own name wins from the moment it exists")
    void theFallbackIsRetiredByTheRow() {
        kit.grant(StaffKit.TENANT_A, "fallback-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        account("fallback-1", "Interim", "Keycloak", null, true);

        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "fallback-1")).isEqualTo("Interim Keycloak");
        assertThat(kit.cache.get(StaffKit.TENANT_A, "fallback-1"))
                .as("cached under the tenant")
                .isNotNull();

        kit.accounts.profiles.put("fallback-1", new StaffProfile("Real", "Name", null, true));
        kit.tx.executeWithoutResult(status -> kit.members.registerFromIdentityProvider(
                StaffKit.TENANT_A, "fallback-1", kit.accounts.profiles.get("fallback-1"), "test", "corr"));

        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "fallback-1"))
                .as("creating the row evicted the interim name")
                .isEqualTo("Real Name");
    }

    @Test
    @DisplayName(
            "a Keycloak outage does not fail the directory: a subject the fallback would have named simply shows by id for now")
    void aKeycloakOutageDegradesToNothing() {
        kit.grant(StaffKit.TENANT_A, "outage-1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.accounts.profiles.put("outage-1", new StaffProfile("A", "B", null, true));
        kit.accounts.unreachable.add("outage-1");

        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "outage-1")).isNull();

        kit.accounts.unreachable.clear();
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "outage-1"))
                .as("and nothing was cached for the failed attempt")
                .isEqualTo("A B");
    }

    // ------------------------------------------------- the second pass

    @Test
    @DisplayName("a PENDING member whose invitation was accepted is promoted from the invitation table")
    void anAcceptedInvitationPromotesAPendingMember() {
        UUID grantId = kit.grant(
                StaffKit.TENANT_A, "lost-accept", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        UUID id = kit.invite(StaffKit.TENANT_A, "lost-accept", "Lost", "Accept", null);
        insertInvitation("lost-accept", grantId, "ACCEPTED");

        StaffMemberReconciler.Report report = reconciler.run();

        assertThat(report.promoted()).isEqualTo(1);
        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().employmentStatus())
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a PENDING member whose invitation is still open stays PENDING")
    void anOpenInvitationLeavesThePendingMemberAlone() {
        UUID grantId = kit.grant(
                StaffKit.TENANT_A, "still-open", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        UUID id = kit.invite(StaffKit.TENANT_A, "still-open", "Still", "Open", null);
        insertInvitation("still-open", grantId, "SENT");

        assertThat(reconciler.run().promoted()).isZero();
        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().employmentStatus())
                .isEqualTo("PENDING");
    }

    private void insertInvitation(String subject, UUID grantId, String status) {
        boolean live = !status.equals("ACCEPTED");
        kit.jdbc
                .sql("""
                INSERT INTO tenant.staff_invitations
                    (id, tenant_id, subject_id, grant_id, locale, status, token_hash, expires_at, email_given,
                     invited_by, invited_at, accepted_at)
                VALUES (:id, :t, :subject, :grant, 'ru', :status, :hash, :expires, false, 'fixture', :invited, :accepted)
                """)
                .param("id", UUID.randomUUID())
                .param("t", StaffKit.TENANT_A)
                .param("subject", subject)
                .param("grant", grantId)
                .param("status", status)
                .param("hash", live ? "a".repeat(64) : null)
                .param("expires", StaffKit.NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("invited", StaffKit.NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .param("accepted", live ? null : StaffKit.NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    // -------------------------------------------------------------- retention

    private UUID endedLongAgo(String subject, LocalDate until) {
        UUID id = kit.activeMember(StaffKit.TENANT_A, subject, "Former", "Employee", "+998 90 100 20 30");
        kit.jdbc
                .sql("UPDATE iam.staff_members SET employment_status = 'ENDED', employed_until = :until WHERE id = :id")
                .param("until", until)
                .param("id", id)
                .update();
        return id;
    }

    @Test
    @DisplayName("report-only mode counts what it would anonymise and writes nothing")
    void reportOnlyWritesNothing() {
        UUID old = endedLongAgo("ended-old", LocalDate.parse("2024-06-01"));
        StaffMemberRetentionSweeper sweeper = sweeper(Mode.REPORT_ONLY);

        assertThat(sweeper.runOnce()).isEqualTo(1);

        MemberRow row = kit.store.find(StaffKit.TENANT_A, old).orElseThrow();
        assertThat(row.protectedFirstName()).as("nothing was overwritten").isNotNull();
        assertThat(meters.get("horecaos.iam.staff.retention_due").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("enforcing overwrites only an ended member past retention, once, and leaves everyone else alone")
    void enforcingAnonymisesOnlyThoseDue() {
        UUID old = endedLongAgo("ended-old", LocalDate.parse("2024-06-01"));
        UUID recent = endedLongAgo("ended-recent", LocalDate.parse("2026-08-01"));
        UUID active = kit.activeMember(StaffKit.TENANT_A, "still-here", "Still", "Here", null);
        StaffMemberRetentionSweeper sweeper = sweeper(Mode.ENFORCE);

        assertThat(sweeper.runOnce()).isEqualTo(1);

        assertThat(kit.store.find(StaffKit.TENANT_A, old).orElseThrow().protectedFirstName())
                .isNull();
        assertThat(kit.store.find(StaffKit.TENANT_A, recent).orElseThrow().protectedFirstName())
                .as("ended a few weeks ago: the 24-month provisional retention has not run")
                .isNotNull();
        assertThat(kit.store.find(StaffKit.TENANT_A, active).orElseThrow().protectedFirstName())
                .isNotNull();
        assertThat(sweeper.runOnce())
                .as("an anonymised member is not a candidate again")
                .isZero();
    }

    private StaffMemberRetentionSweeper sweeper(Mode mode) {
        return new StaffMemberRetentionSweeper(kit.store, kit.members, kit.clock, meters, mode, 24, 100);
    }
}
