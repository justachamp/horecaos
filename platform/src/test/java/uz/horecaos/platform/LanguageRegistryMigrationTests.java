package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * V0499 (ADR 0149): a language is a registry entry, not a constraint.
 *
 * <p>Three things the migration promises, each proved against a schema that holds what production
 * holds, because a fixture migrated from an empty schema has no row for a rewrite to get wrong:
 *
 * <ul>
 *   <li>the six columns that held a bare {@code uz} hold {@code uz-Latn} afterwards, and the rows
 *       that already said {@code ru} or {@code en} are untouched;
 *   <li>every one of the fourteen closed {@code CHECK}s is, by name, a shape check: it accepts
 *       {@code kk} and {@code kaa-Latn-KZ} and refuses {@code Russian};
 *   <li>no other locale column anywhere holds a bare {@code uz}, found by name and by the
 *       translation tables so a store added after this record is caught too, with the three named
 *       exemptions asserted (not skipped): the catalog's own code, the published snapshots and the
 *       ISO 639 spoken languages.
 * </ul>
 */
class LanguageRegistryMigrationTests {

    /**
     * The last migration that existed before V0499 was written. V0490 to V0498 are reserved by sibling
     * waves and merge between this and V0499; the rows below are valid under every one of them.
     */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("0489");

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-000000000001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-3000-7000-8000-000000000002");

    /** The fourteen, by name: ADR 0149's date-stamped count, as table, constraint and column. */
    private static final List<String[]> CLOSED_CHECKS = List.of(
            new String[] {"notifications.template_versions", "ck_template_version_locale", "locale"},
            new String[] {"notifications.notifications", "ck_notification_locale", "locale"},
            new String[] {"ordering.order_outcome_reason_texts", "ck_outcome_reason_text_locale", "locale"},
            new String[] {"marketing.customer_metrics", "ck_customer_metrics_locale", "preferred_locale"},
            new String[] {"ordering.order_reject_reason_texts", "ck_order_reject_reason_text_locale", "locale"},
            new String[] {"legal.terms_version_contents", "ck_terms_content_locale", "locale"},
            new String[] {"tenant.owner_invitations", "ck_owner_invitation_locale", "locale"},
            new String[] {"iam.password_resets", "ck_password_reset_locale", "locale"},
            new String[] {"tenant.owner_invitation_events", "ck_owner_invitation_event_locale", "locale"},
            new String[] {"payments.payment_method_translations", "ck_payment_method_translation_locale", "locale"},
            new String[] {"tenant.staff_invitations", "ck_staff_invitation_locale", "locale"},
            new String[] {"tenant.channel_page_contents", "ck_channel_page_content_locale", "locale"},
            new String[] {"ordering.branch_override_reason_texts", "ck_branch_override_reason_text_locale", "locale"},
            new String[] {"iam.staff_members", "ck_staff_member_locale", "ui_locale"});

    /** Where a bare {@code uz} is still correct, each with the reason, asserted below rather than skipped. */
    private static final Set<String> EXEMPT = Set.of("catalog.translations.locale");

    @Test
    @DisplayName("the six columns that held a bare uz hold uz-Latn after it, and nothing else about a row changes")
    void theSixColumnsAreRewritten() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();
            seedUnderTheOldSchema(dataSource);

            Flyway.configure().dataSource(dataSource).load().migrate();

            JdbcClient jdbc = JdbcClient.create(dataSource);
            assertThat(one(jdbc, "SELECT locale FROM tenant.owner_invitations WHERE subject_id = 'owner-uz'"))
                    .as("the owner invitation written in Uzbek")
                    .isEqualTo("uz-Latn");
            assertThat(one(jdbc, "SELECT locale FROM tenant.owner_invitations WHERE subject_id = 'owner-en'"))
                    .as("an invitation that already said en is not touched")
                    .isEqualTo("en");
            assertThat(one(jdbc, "SELECT locale FROM tenant.owner_invitation_events WHERE attempt = 1"))
                    .as("its history, which recorded the language the email went out in")
                    .isEqualTo("uz-Latn");
            assertThat(jdbc.sql("SELECT locale FROM tenant.owner_invitation_events WHERE attempt = 2")
                            .query(String.class)
                            .optional())
                    .as("an event with no language stays without one")
                    .isEmpty();
            assertThat(one(jdbc, "SELECT locale FROM iam.password_resets WHERE subject_id = 'reset-uz'"))
                    .isEqualTo("uz-Latn");
            assertThat(one(jdbc, "SELECT locale FROM tenant.staff_invitations WHERE subject_id = 'staff-uz'"))
                    .isEqualTo("uz-Latn");
            assertThat(one(jdbc, "SELECT ui_locale FROM iam.staff_members WHERE principal_subject = 'member-uz'"))
                    .as("the staff member's interface language")
                    .isEqualTo("uz-Latn");
            assertThat(one(jdbc, "SELECT ui_locale FROM iam.staff_members WHERE principal_subject = 'member-ru'"))
                    .isEqualTo("ru");
            assertThat(jdbc.sql("SELECT ui_locale FROM iam.staff_members WHERE principal_subject = 'member-none'")
                            .query(String.class)
                            .optional())
                    .as("a member with no preference still has none")
                    .isEmpty();
            assertThat(
                            one(
                                    jdbc,
                                    "SELECT preferred_locale FROM customer.customer_accounts WHERE display_name = 'uz customer'"))
                    .as("the customer who picked Uzbek on the storefront's language screen")
                    .isEqualTo("uz-Latn");
            assertThat(
                            one(
                                    jdbc,
                                    "SELECT preferred_locale FROM customer.customer_accounts WHERE display_name = 'ru customer'"))
                    .isEqualTo("ru");
        }
    }

    @Test
    @DisplayName(
            "the catalog's own uz, the published snapshots and the ISO 639 spoken languages are left exactly as they were")
    void theThreeNamedExemptionsAreUntouched() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();
            seedUnderTheOldSchema(dataSource);
            JdbcClient before = JdbcClient.create(dataSource);
            String snapshotBefore = one(before, "SELECT immutable_content_json::text FROM catalog.publication_items");

            Flyway.configure().dataSource(dataSource).load().migrate();

            JdbcClient jdbc = JdbcClient.create(dataSource);
            assertThat(jdbc.sql("SELECT locale FROM catalog.translations ORDER BY locale")
                            .query(String.class)
                            .list())
                    .as(
                            "catalog.translations keeps its established Uzbek code, uz-Latn's catalogCode (ADR 0149, Decision 3)")
                    .containsExactly("en", "ru", "uz");
            assertThat(one(jdbc, "SELECT immutable_content_json::text FROM catalog.publication_items"))
                    .as("a published snapshot is hashed over its keys and V0016 forbids editing it: not one byte moves")
                    .isEqualTo(snapshotBefore)
                    .contains("\"uz\"");
            assertThat(one(
                            jdbc,
                            "SELECT array_to_string(spoken_languages, ',') FROM iam.staff_members"
                                    + " WHERE principal_subject = 'member-uz'"))
                    .as(
                            "spoken_languages holds ISO 639 codes, where a person who speaks Uzbek speaks it in either script")
                    .isEqualTo("uz,ru");
        }
    }

    @Test
    @DisplayName(
            "every one of the fourteen closed locale CHECKs is, by name, a shape check, and none is left on a closed list")
    void everyClosedCheckIsNowAShapeCheck() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated()) {
            JdbcClient jdbc = JdbcClient.create(db.dataSource());

            for (String[] check : CLOSED_CHECKS) {
                String definition = jdbc.sql("""
                                SELECT pg_get_constraintdef(c.oid)
                                FROM pg_constraint c
                                JOIN pg_class t ON t.oid = c.conrelid
                                JOIN pg_namespace n ON n.oid = t.relnamespace
                                WHERE n.nspname || '.' || t.relname = :table AND c.conname = :name
                                """)
                        .param("table", check[0])
                        .param("name", check[1])
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> new AssertionError(check[1] + " no longer exists on " + check[0]));
                assertThat(definition)
                        .as("%s on %s is a BCP 47 shape check", check[1], check[0])
                        .contains("~")
                        .contains("[a-z]{2,3}")
                        .doesNotContain("'ru'")
                        .doesNotContain("'en'");
            }

            // The count above is a date-stamped fact, and the first draft of ADR 0149 was already one
            // constraint short when it was written: so look for a fifteenth, anywhere, by what it
            // says rather than by a name this test was told.
            List<String> stillClosed = jdbc.sql("""
                            SELECT n.nspname || '.' || t.relname || '.' || c.conname
                            FROM pg_constraint c
                            JOIN pg_class t ON t.oid = c.conrelid
                            JOIN pg_namespace n ON n.oid = t.relnamespace
                            WHERE c.contype = 'c'
                              AND pg_get_constraintdef(c.oid) ~* 'locale'
                              AND pg_get_constraintdef(c.oid) ~ '''(ru|en|uz|uz-Latn)'''
                            ORDER BY 1
                            """).query(String.class).list();
            assertThat(stillClosed)
                    .as("a CHECK on a locale column that still names a language: the registry decides which"
                            + " tags are valid, not the schema")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName(
            "a closed-list constraint now accepts kk and a script-and-region tag, and still refuses what is not a tag")
    void theShapeCheckAcceptsANewLanguageAndRefusesNonsense() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated();
                Connection connection = db.dataSource().getConnection()) {
            JdbcClient jdbc = withoutForeignKeys(connection);
            UUID member = UUID.randomUUID();
            insertStaffMember(jdbc, member, "probe-" + member, "S-0001", "kk");
            insertStaffMember(jdbc, UUID.randomUUID(), "probe-b", "S-0002", "kaa-Latn-KZ");
            assertThat(one(jdbc, "SELECT ui_locale FROM iam.staff_members WHERE principal_subject = 'probe-b'"))
                    .as("the column is wide enough for the tag its own shape check admits")
                    .isEqualTo("kaa-Latn-KZ");

            assertThatThrownBy(() -> insertStaffMember(jdbc, UUID.randomUUID(), "probe-c", "S-0003", "Russian"))
                    .as("a word is not a language tag")
                    .hasMessageContaining("ck_staff_member_locale");
            assertThatThrownBy(() -> insertStaffMember(jdbc, UUID.randomUUID(), "probe-d", "S-0004", "uz_UZ"))
                    .as("an underscore is not a BCP 47 separator")
                    .hasMessageContaining("ck_staff_member_locale");

            // The IS NULL OR form is kept where the original had one.
            insertStaffMember(jdbc, UUID.randomUUID(), "probe-e", "S-0005", null);

            // And one of the translation tables that never had a 'uz' in it: same shape, same promise.
            jdbc.sql("""
                            INSERT INTO payments.payment_method_translations
                                (tenant_id, payment_method_id, locale, display_name, created_at, updated_at)
                            VALUES (:tenant, :method, 'ka', 'ნაღდი ფული', now(), now())
                            """)
                    .param("tenant", TENANT)
                    .param("method", UUID.randomUUID())
                    .update();
            assertThatThrownBy(() -> jdbc.sql("""
                            INSERT INTO payments.payment_method_translations
                                (tenant_id, payment_method_id, locale, display_name, created_at, updated_at)
                            VALUES (:tenant, :method, 'ru_RU', 'Наличные', now(), now())
                            """)
                            .param("tenant", TENANT)
                            .param("method", UUID.randomUUID())
                            .update())
                    .hasMessageContaining("ck_payment_method_translation_locale");
        }
    }

    @Test
    @DisplayName(
            "no locale column in the schema holds a bare uz after the migration but the catalog's own, found by name")
    void noOtherLocaleColumnHoldsABareUz() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();
            seedUnderTheOldSchema(dataSource);
            Flyway.configure().dataSource(dataSource).load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);

            // Found by name (%locale%) and by the translation tables, so a store added after the
            // record is caught the day it exists, not the day somebody remembers it.
            List<String> candidates = jdbc.sql("""
                            SELECT c.table_schema || '.' || c.table_name || '.' || c.column_name
                            FROM information_schema.columns c
                            JOIN information_schema.tables t
                              ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                            WHERE t.table_type = 'BASE TABLE'
                              AND c.table_schema NOT IN ('pg_catalog', 'information_schema')
                              AND c.data_type IN ('character varying', 'text', 'character')
                              AND (c.column_name ILIKE '%locale%'
                                   OR (c.table_name ILIKE '%translation%' AND c.column_name = 'locale'))
                            ORDER BY 1
                            """).query(String.class).list();
            assertThat(candidates)
                    .as("the scan finds the columns this record is about (it is not vacuous)")
                    .contains(
                            "tenant.owner_invitations.locale",
                            "iam.staff_members.ui_locale",
                            "customer.customer_accounts.preferred_locale",
                            "catalog.translations.locale")
                    .hasSizeGreaterThan(15);

            List<String> offenders = candidates.stream()
                    .filter(column -> !EXEMPT.contains(column))
                    .filter(column -> holdsBareUz(jdbc, column))
                    .collect(Collectors.toList());
            assertThat(offenders)
                    .as("a row holding the bare 'uz' in a locale column the registry spells uz-Latn")
                    .isEmpty();

            // The exemption is asserted, not skipped: if it stopped holding 'uz' this test would be
            // exempting nothing and the next reader would not know why the line is there.
            assertThat(holdsBareUz(jdbc, "catalog.translations.locale"))
                    .as("catalog.translations is the registry's one named place for the catalog's own uz")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean holdsBareUz(JdbcClient jdbc, String qualifiedColumn) {
        int lastDot = qualifiedColumn.lastIndexOf('.');
        String table = qualifiedColumn.substring(0, lastDot);
        String column = qualifiedColumn.substring(lastDot + 1);
        // Both names come from information_schema in this very database, never from input.
        Integer count = jdbc.sql("SELECT count(*) FROM " + table + " WHERE \"" + column + "\" = 'uz'")
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private static String one(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(String.class).single();
    }

    private static JdbcClient withoutForeignKeys(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
        }
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    /** Rows as the schema before V0499 admits them: a bare uz where the five constrained columns held one. */
    private static void seedUnderTheOldSchema(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            JdbcClient jdbc = withoutForeignKeys(connection);

            for (String[] invitation : new String[][] {{"owner-uz", "uz"}, {"owner-en", "en"}}) {
                UUID id = UUID.randomUUID();
                jdbc.sql("""
                                INSERT INTO tenant.owner_invitations
                                    (id, tenant_id, subject_id, locale, status, queued_at, queued_by, next_attempt_at)
                                VALUES (:id, :tenant, :subject, :locale, 'QUEUED', now(), 'test', now())
                                """)
                        .param("id", id)
                        .param("tenant", TENANT)
                        .param("subject", invitation[0])
                        .param("locale", invitation[1])
                        .update();
                if ("owner-uz".equals(invitation[0])) {
                    jdbc.sql("""
                                    INSERT INTO tenant.owner_invitation_events
                                        (id, tenant_id, invitation_id, event_type, attempt, locale, actor_type, occurred_at)
                                    VALUES (:id, :tenant, :invitation, 'QUEUED', 1, 'uz', 'SYSTEM_JOB', now())
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("tenant", TENANT)
                            .param("invitation", id)
                            .update();
                    jdbc.sql("""
                                    INSERT INTO tenant.owner_invitation_events
                                        (id, tenant_id, invitation_id, event_type, attempt, locale, actor_type, occurred_at)
                                    VALUES (:id, :tenant, :invitation, 'SENT', 2, NULL, 'SYSTEM_JOB', now())
                                    """)
                            .param("id", UUID.randomUUID())
                            .param("tenant", TENANT)
                            .param("invitation", id)
                            .update();
                }
            }

            jdbc.sql("""
                            INSERT INTO iam.password_resets
                                (id, subject_id, console, locale, status, requested_at, next_attempt_at)
                            VALUES (:id, 'reset-uz', 'OPERATIONS', 'uz', 'QUEUED', now(), now())
                            """).param("id", UUID.randomUUID()).update();

            jdbc.sql("""
                            INSERT INTO tenant.staff_invitations
                                (id, tenant_id, subject_id, grant_id, locale, status, token_hash, expires_at,
                                 email_given, invited_by, invited_at)
                            VALUES (:id, :tenant, 'staff-uz', :grant, 'uz', 'SENT', :hash,
                                    now() + interval '1 day', false, 'test', now())
                            """)
                    .param("id", UUID.randomUUID())
                    .param("tenant", TENANT)
                    .param("grant", UUID.randomUUID())
                    .param("hash", "a".repeat(64))
                    .update();

            insertOldStaffMember(jdbc, "member-uz", "S-0001", "uz", "{uz,ru}");
            insertOldStaffMember(jdbc, "member-ru", "S-0002", "ru", "{ru}");
            insertOldStaffMember(jdbc, "member-none", "S-0003", null, "{}");

            for (String[] customer : new String[][] {{"uz customer", "uz"}, {"ru customer", "ru"}}) {
                jdbc.sql("""
                                INSERT INTO customer.customer_accounts
                                    (id, tenant_id, status, display_name, preferred_locale)
                                VALUES (:id, :tenant, 'ACTIVE', :name, :locale)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("tenant", TENANT)
                        .param("name", customer[0])
                        .param("locale", customer[1])
                        .update();
            }

            // The catalog's own vocabulary, which V0499 must leave alone.
            UUID product = UUID.randomUUID();
            for (String locale : new String[] {"uz", "ru", "en"}) {
                jdbc.sql("""
                                INSERT INTO catalog.translations
                                    (tenant_id, brand_id, entity_type, entity_id, locale, name)
                                VALUES (:tenant, :brand, 'PRODUCT', :entity, :locale, 'Lagman')
                                """)
                        .param("tenant", TENANT)
                        .param("brand", BRAND)
                        .param("entity", product)
                        .param("locale", locale)
                        .update();
            }
            jdbc.sql("""
                            INSERT INTO catalog.publication_items
                                (publication_id, tenant_id, brand_id, entity_type, entity_id, entity_version,
                                 immutable_content_json)
                            VALUES (:publication, :tenant, :brand, 'PRODUCT', :entity, 1,
                                    '{"names": {"uz": "Lagman", "ru": "Лагман", "en": "Lagman"}}'::jsonb)
                            """)
                    .param("publication", UUID.randomUUID())
                    .param("tenant", TENANT)
                    .param("brand", BRAND)
                    .param("entity", product)
                    .update();
        }
    }

    private static void insertOldStaffMember(
            JdbcClient jdbc, String subject, String reference, @Nullable String uiLocale, String spoken) {
        jdbc.sql("""
                        INSERT INTO iam.staff_members
                            (id, tenant_id, principal_subject, display_reference, ui_locale, spoken_languages,
                             employment_status)
                        VALUES (:id, :tenant, :subject, :reference, :uiLocale, CAST(:spoken AS varchar(8)[]), 'ACTIVE')
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", TENANT)
                .param("subject", subject)
                .param("reference", reference)
                .param("uiLocale", uiLocale, java.sql.Types.VARCHAR)
                .param("spoken", spoken)
                .update();
    }

    private static void insertStaffMember(
            JdbcClient jdbc, UUID id, String subject, String reference, @Nullable String uiLocale) {
        jdbc.sql("""
                        INSERT INTO iam.staff_members
                            (id, tenant_id, principal_subject, display_reference, ui_locale, employment_status)
                        VALUES (:id, :tenant, :subject, :reference, :uiLocale, 'ACTIVE')
                        """)
                .param("id", id)
                .param("tenant", TENANT)
                .param("subject", subject)
                .param("reference", reference)
                .param("uiLocale", uiLocale, java.sql.Types.VARCHAR)
                .update();
    }
}
