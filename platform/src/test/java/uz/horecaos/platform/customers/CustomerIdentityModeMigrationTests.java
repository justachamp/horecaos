package uz.horecaos.platform.customers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;

import uz.horecaos.platform.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V0060: making the tenant identity mode have one answer (ADR 0015).
 *
 * <p>These run the schema the way a deployment does — up to the migration before
 * V0060, then over it — because what is under test is the migration itself:
 * whether it corrects the denormalised column, and whether it refuses to
 * re-partition customers who already exist rather than splitting them quietly.
 * A migration cannot be re-run against a schema that has already had it, so each
 * test takes an <em>empty</em> database of its own — {@link TestDatabase#empty()}
 * rather than {@link TestDatabase#migrated()}, because the migrated template is
 * by definition past the point these tests start from. It used to take a whole
 * container of its own, five of them for five tests.
 */
class CustomerIdentityModeMigrationTests {

    /**
     * A version that is safely before V0060 and that exists — Flyway rejects a
     * target it cannot find, so this deliberately names a long-committed
     * migration rather than "the one immediately before", which changes under
     * anyone adding a migration in between. Whatever lands between it and V0060
     * is applied by the second migrate, exactly as a deployment would.
     */
    private static final MigrationVersion BEFORE_THE_FIX = MigrationVersion.fromVersion("0058");

    /**
     * The last migration before V0063, for the same reason and with the same
     * caveat: whatever lands between it and V0063 is applied by the second
     * migrate, exactly as a deployment would.
     */
    private static final MigrationVersion BEFORE_THE_VERSION_FIX =
            MigrationVersion.fromVersion("0061");

    /**
     * V0060 itself, so the backfill it performs can be asserted before V0072
     * removes the column that holds it.
     */
    private static final MigrationVersion THE_MIRROR_FIX = MigrationVersion.fromVersion("0060");

    /**
     * The last migration before V0072, where the mirror column and its trigger
     * still exist and can be caught disagreeing with the function that V0063
     * made the single definition of "current".
     */
    private static final MigrationVersion BEFORE_THE_MIRROR_DROP =
            MigrationVersion.fromVersion("0069");

    private static final OffsetDateTime EFFECTIVE_FROM =
            OffsetDateTime.of(2026, 8, 20, 0, 0, 0, 0, ZoneOffset.UTC);

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for migration tests");
    }

    @Test
    @DisplayName("the backfill gives the denormalised column the mode the operator configured")
    void theBackfillCorrectsTheDenormalisedColumn() {
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            JdbcClient jdbc = JdbcClient.create(dataSource);

            migrateTo(dataSource, BEFORE_THE_FIX);
            UUID tenantId = UUID.randomUUID();
            insertTenant(jdbc, tenantId, "tenant-isolated");
            configureIdentityMode(jdbc, tenantId, "BRAND_ISOLATED");

            // The bug, at rest: the operator's choice is recorded, and the column
            // customer identity resolution used to read still says the opposite
            // because no code ever wrote it.
            assertThat(identityColumn(jdbc, tenantId)).isEqualTo("TENANT_SHARED");

            // Asserted at V0060 rather than at LATEST because V0072 removes the
            // column. A deployment that stops mid-chain still gets the backfill,
            // and this is the only place that fact can still be checked.
            migrateTo(dataSource, THE_MIRROR_FIX);

            assertThat(identityColumn(jdbc, tenantId)).isEqualTo("BRAND_ISOLATED");

            migrateTo(dataSource, MigrationVersion.LATEST);

            assertThat(tenantColumns(jdbc)).doesNotContain("customer_identity_policy");
        }
    }

    /**
     * V0072: the mirror is dropped rather than repaired, because it cannot be
     * repaired.
     *
     * <p>This is the divergence at rest. V0060's trigger gates on {@code
     * superseded_at IS NULL} with no test of {@code effective_from}, so it mirrors
     * the newest policy row rather than the governing one — the third copy of the
     * predicate V0063 said it was reducing to one, hidden inside the trigger V0063
     * cited as its precedent. With a cutover scheduled for the first of September,
     * the column is wrong for the eleven days before it.
     *
     * <p>Teaching the trigger to consult {@code
     * tenant.current_customer_identity_policy} would only move the window in which
     * it is wrong, because the governing row changes at an instant when nothing
     * writes to the table and so nothing fires a trigger. There is no predicate
     * that fixes it, which is why the column goes.
     */
    @Test
    @DisplayName("the mirror that could not be kept is dropped, not repaired")
    void theMirrorIsDroppedBecauseNoTriggerCanKeepIt() {
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            JdbcClient jdbc = JdbcClient.create(dataSource);

            migrateTo(dataSource, BEFORE_THE_MIRROR_DROP);

            UUID tenantId = UUID.randomUUID();
            insertTenant(jdbc, tenantId, "tenant-scheduled-cutover");
            OffsetDateTime cutover = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            insertPolicy(jdbc, tenantId, 1, "BRAND_ISOLATED", EFFECTIVE_FROM, cutover);
            insertPolicy(jdbc, tenantId, 2, "TENANT_SHARED", cutover, null);

            OffsetDateTime duringTheWindow =
                    OffsetDateTime.of(2026, 8, 21, 12, 0, 0, 0, ZoneOffset.UTC);

            // The one definition of "current" is right.
            assertThat(currentPolicy(jdbc, tenantId, duringTheWindow))
                    .isEqualTo("1 BRAND_ISOLATED");
            // The trigger that was supposed to mirror it is not.
            assertThat(identityColumn(jdbc, tenantId)).isEqualTo("TENANT_SHARED");

            migrateTo(dataSource, MigrationVersion.LATEST);

            assertThat(tenantColumns(jdbc)).doesNotContain("customer_identity_policy");
            assertThat(triggerNames(jdbc)).doesNotContain("trg_customer_identity_policy_mirror");
            // The answer that survives is the one that takes the instant it is
            // asked about, and it still moves with the clock and no write.
            assertThat(currentPolicy(jdbc, tenantId, duringTheWindow))
                    .isEqualTo("1 BRAND_ISOLATED");
            assertThat(currentPolicy(jdbc, tenantId, cutover)).isEqualTo("2 TENANT_SHARED");
        }
    }

    @Test
    @DisplayName("the deployment stops rather than re-partition customers who already exist")
    void theMigrationRefusesToSplitExistingAccounts() {
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            JdbcClient jdbc = JdbcClient.create(dataSource);

            migrateTo(dataSource, BEFORE_THE_FIX);
            UUID tenantId = UUID.randomUUID();
            insertTenant(jdbc, tenantId, "tenant-with-customers");
            configureIdentityMode(jdbc, tenantId, "BRAND_ISOLATED");
            // An account created while the tenant was, in practice, TENANT_SHARED.
            // Honouring BRAND_ISOLATED from now on would stop finding it and give
            // that person a second account at their next sign-in.
            insertUnpartitionedAccount(jdbc, tenantId);

            assertThatThrownBy(() -> migrateTo(dataSource, MigrationVersion.LATEST))
                    .hasStackTraceContaining("ADR 0015")
                    .hasStackTraceContaining("split or merge");
        }
    }

    /**
     * V0063: the stamped policy version becomes the policy that was in effect
     * when the account was created.
     *
     * <p>The column's whole purpose is telling an account created before a
     * governed policy change from one created after. The ordinal expression made
     * both accounts below say 2 — the BRAND_ISOLATED enum ordinal plus one —
     * which is the right answer for one of them by coincidence and names a
     * version the tenant was not on for the other. The backfill has to
     * distinguish them from the policy history alone, because that is all a later
     * migration will have.
     */
    @Test
    @DisplayName("the backfill stamps each account with the policy that was in effect when it was created")
    void theBackfillReplacesTheEnumOrdinalWithTheGoverningVersion() {
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            JdbcClient jdbc = JdbcClient.create(dataSource);

            migrateTo(dataSource, BEFORE_THE_VERSION_FIX);

            UUID tenantId = UUID.randomUUID();
            insertTenant(jdbc, tenantId, "tenant-two-versions");
            // A tenant that has been through one governed change: version 1 ran
            // until the tenth, version 2 since.
            OffsetDateTime firstPolicyFrom = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime cutover = OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC);
            insertPolicy(jdbc, tenantId, 1, "BRAND_ISOLATED", firstPolicyFrom, cutover);
            insertPolicy(jdbc, tenantId, 2, "BRAND_ISOLATED", cutover, null);

            // Both were written by the ordinal expression, so both say 2. One of
            // them is right by accident.
            UUID before = insertAccount(jdbc, tenantId, 2,
                    OffsetDateTime.of(2026, 8, 5, 0, 0, 0, 0, ZoneOffset.UTC));
            UUID after = insertAccount(jdbc, tenantId, 2,
                    OffsetDateTime.of(2026, 8, 15, 0, 0, 0, 0, ZoneOffset.UTC));

            // A tenant that configured nothing at all. Its account claims version
            // 1, a decision nobody made.
            UUID unconfiguredTenant = UUID.randomUUID();
            insertTenant(jdbc, unconfiguredTenant, "tenant-never-configured");
            UUID unconfigured = insertAccount(jdbc, unconfiguredTenant, 1,
                    OffsetDateTime.of(2026, 8, 5, 0, 0, 0, 0, ZoneOffset.UTC));

            migrateTo(dataSource, MigrationVersion.LATEST);

            assertThat(policyVersion(jdbc, before)).isEqualTo("1");
            assertThat(policyVersion(jdbc, after)).isEqualTo("2");
            assertThat(policyVersion(jdbc, unconfigured)).isEqualTo("UNCONFIGURED");
        }
    }

    /**
     * An account created before any policy its tenant records cannot be mapped
     * without guessing, and guessing a starting point is what this column exists
     * to prevent. Same stance V0060 took on the same table.
     */
    @Test
    @DisplayName("the deployment stops rather than invent a starting point it cannot derive")
    void theMigrationRefusesToGuessAnUnmappableVersion() {
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            JdbcClient jdbc = JdbcClient.create(dataSource);

            migrateTo(dataSource, BEFORE_THE_VERSION_FIX);

            UUID tenantId = UUID.randomUUID();
            insertTenant(jdbc, tenantId, "tenant-retroactive-policy");
            insertPolicy(jdbc, tenantId, 1, "TENANT_SHARED", EFFECTIVE_FROM, null);
            // The customer predates the policy history that claims to govern it.
            insertAccount(jdbc, tenantId, 1,
                    OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC));

            assertThatThrownBy(() -> migrateTo(dataSource, MigrationVersion.LATEST))
                    .hasStackTraceContaining("ADR 0015")
                    .hasStackTraceContaining("policy version cannot be");
        }
    }

    private static void migrateTo(DataSource dataSource, MigrationVersion version) {
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    private static void insertTenant(JdbcClient jdbc, UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId).param("slug", slug)
                .update();
    }

    private static void configureIdentityMode(JdbcClient jdbc, UUID tenantId, String mode) {
        jdbc.sql("""
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from)
                VALUES (:id, :tenantId, 1, :mode, :effectiveFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("mode", mode)
                .param("effectiveFrom", EFFECTIVE_FROM)
                .update();
    }

    private static void insertUnpartitionedAccount(JdbcClient jdbc, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status)
                VALUES (:id, :tenantId, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .update();
    }

    private static void insertPolicy(JdbcClient jdbc, UUID tenantId, int version, String mode,
            OffsetDateTime effectiveFrom, OffsetDateTime supersededAt) {
        jdbc.sql("""
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from, superseded_at)
                VALUES (:id, :tenantId, :version, :mode, :effectiveFrom, :supersededAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("version", version)
                .param("mode", mode)
                .param("effectiveFrom", effectiveFrom)
                .param("supersededAt", supersededAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /** An account as the ordinal expression wrote it: a version, right or not. */
    private static UUID insertAccount(JdbcClient jdbc, UUID tenantId, int policyVersion,
            OffsetDateTime createdAt) {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (
                    id, tenant_id, status, identity_policy_version, created_at, updated_at)
                VALUES (:id, :tenantId, 'ACTIVE', :policyVersion, :createdAt, :createdAt)
                """)
                .param("id", accountId)
                .param("tenantId", tenantId)
                .param("policyVersion", policyVersion)
                .param("createdAt", createdAt)
                .update();
        return accountId;
    }

    /** As text, so that a null version cannot read the same as a real one. */
    private static String policyVersion(JdbcClient jdbc, UUID accountId) {
        return jdbc.sql("""
                SELECT coalesce(identity_policy_version::text, 'UNCONFIGURED')
                FROM customer.customer_accounts WHERE id = :id
                """)
                .param("id", accountId)
                .query(String.class)
                .single();
    }

    private static String currentPolicy(JdbcClient jdbc, UUID tenantId, OffsetDateTime at) {
        return jdbc.sql("""
                SELECT policy_version || ' ' || identity_mode
                FROM tenant.current_customer_identity_policy(:tenantId, :at)
                """)
                .param("tenantId", tenantId)
                .param("at", at)
                .query(String.class)
                .single();
    }

    private static java.util.List<String> tenantColumns(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'tenant' AND table_name = 'tenants'
                """)
                .query(String.class)
                .list();
    }

    private static java.util.List<String> triggerNames(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT t.tgname
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'tenant'
                  AND c.relname = 'customer_identity_policies'
                  AND NOT t.tgisinternal
                """)
                .query(String.class)
                .list();
    }

    private static String identityColumn(JdbcClient jdbc, UUID tenantId) {
        return jdbc.sql("SELECT customer_identity_policy FROM tenant.tenants WHERE id = :id")
                .param("id", tenantId)
                .query(String.class)
                .single();
    }
}
