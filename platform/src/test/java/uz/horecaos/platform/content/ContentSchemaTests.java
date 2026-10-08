package uz.horecaos.platform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import uz.horecaos.platform.support.TestDatabase;

/**
 * What the application role can and cannot do to the content tables, asserted under that role (ADR
 * 0157, Physical model).
 *
 * <p>Immutability is a grant and not a convention: a revision is what a customer may have been
 * shown, evidence in a disputed promotion claim, and an {@code UPDATE} grant on it would make that a
 * sentence in a comment. The shape is {@code DatabasePrivilegeTests}': the container's login role
 * is the migrator, and every assertion below is made through a second connection belonging to a
 * login role whose only privilege is {@code horecaos_application} -- a test that asserts a REVOKE
 * from the owner's connection asserts nothing at all, so the first test proves this connection is
 * not the owner's.
 */
class ContentSchemaTests {

    private static final String PROBE = "content_privilege_probe";
    private static final String PASSWORD = "content-privilege-probe";

    private static TestDatabase.Handle db;
    private static JdbcClient owner;
    private static JdbcClient application;

    private final UUID tenant = UUID.randomUUID();
    private final UUID brand = UUID.randomUUID();
    private UUID item;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        owner = JdbcClient.create(db.dataSource());
        owner.sql("DROP ROLE IF EXISTS " + PROBE).update();
        owner.sql("CREATE ROLE " + PROBE + " LOGIN PASSWORD '" + PASSWORD + "'").update();
        owner.sql("ALTER ROLE " + PROBE + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + PROBE).update();
        DataSource asApplication = db.dataSourceAs(PROBE, PASSWORD);
        application = JdbcClient.create(asApplication);
    }

    @AfterAll
    static void stopDatabase() {
        if (db == null) {
            return;
        }
        db.close();
        try {
            TestDatabase.onCluster("DROP ROLE IF EXISTS " + PROBE);
        } catch (RuntimeException leftover) {
            System.err.println("ContentSchemaTests: " + PROBE + " outlived the suite (" + leftover.getMessage() + ")");
        }
    }

    @BeforeEach
    void seed() {
        ContentFixture fixture = new ContentFixture(owner);
        fixture.tenant(tenant, "schema-" + tenant.toString().substring(0, 8));
        fixture.brand(tenant, brand, "B" + tenant.toString().substring(0, 6).toUpperCase());
        item = UUID.randomUUID();
        application
                .sql("""
                INSERT INTO content.items (id, tenant_id, brand_id, kind, placement, internal_name, display_title,
                                           state, sort_key, version, created_by)
                VALUES (:id, :t, :b, 'BANNER', 'HOME_CAROUSEL', 'x', '', 'DRAFT', 10, 1, 'test')
                """)
                .param("id", item)
                .param("t", tenant)
                .param("b", brand)
                .update();
    }

    private void revision(int number) {
        application
                .sql("""
                INSERT INTO content.item_revisions (item_id, revision_no, tenant_id, brand_id, document, content_hash,
                                                    published_by, published_at)
                VALUES (:item, :n, :t, :b, '{}'::jsonb, :hash, 'test', now())
                """)
                .param("item", item)
                .param("n", number)
                .param("t", tenant)
                .param("b", brand)
                .param("hash", "a".repeat(64))
                .update();
    }

    @Test
    @DisplayName(
            "the probe is not the owner: it holds the application role and nothing more, so the refusals below mean something")
    void theProbeIsNotTheOwner() {
        assertThat(application.sql("SELECT current_user").query(String.class).single())
                .isEqualTo(PROBE);
        assertThat(application
                        .sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(application
                        .sql("SELECT pg_has_role(current_user, 'horecaos_application', 'member')")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    @DisplayName(
            "a revision is insert-only: the application role may write and read one and may not update or delete it")
    void revisionsAreImmutableByGrant() {
        revision(1);

        assertThat(application
                        .sql("SELECT count(*) FROM content.item_revisions WHERE item_id = :i")
                        .param("i", item)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThatThrownBy(() -> application
                        .sql("UPDATE content.item_revisions SET document = '{\"x\":1}'::jsonb WHERE item_id = :i")
                        .param("i", item)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("UPDATE content.item_revisions SET content_hash = :h WHERE item_id = :i")
                        .param("h", "b".repeat(64))
                        .param("i", item)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM content.item_revisions WHERE item_id = :i")
                        .param("i", item)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(
                        () -> application.sql("TRUNCATE content.item_revisions").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName(
            "an item is archived and never deleted; a draft may be discarded; revision media is insert-only; the allowlist may be added to and removed from but not rewritten; counters are never deleted")
    void everyTableHasExactlyTheGrantsItNeeds() {
        revision(1);

        assertThat(grants("content.items")).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
        assertThat(grants("content.item_drafts")).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE", "DELETE");
        assertThat(grants("content.item_revisions")).containsExactlyInAnyOrder("SELECT", "INSERT");
        assertThat(grants("content.revision_media")).containsExactlyInAnyOrder("SELECT", "INSERT");
        assertThat(grants("content.allowed_hosts")).containsExactlyInAnyOrder("SELECT", "INSERT", "DELETE");
        assertThat(grants("content.item_daily_stats")).containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");

        assertThatThrownBy(() -> application
                        .sql("DELETE FROM content.items WHERE id = :i")
                        .param("i", item)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        application.sql("""
                INSERT INTO content.allowed_hosts (tenant_id, brand_id, host, created_by)
                VALUES (:t, :b, 'shop.example.uz', 'test')
                """).param("t", tenant).param("b", brand).update();
        assertThatThrownBy(() -> application
                        .sql("UPDATE content.allowed_hosts SET host = 'other.example.uz'")
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThat(application
                        .sql("DELETE FROM content.allowed_hosts WHERE host = 'shop.example.uz'")
                        .update())
                .isEqualTo(1);
    }

    /** What the probe role holds on a table, read the way PostgreSQL itself answers. */
    private List<String> grants(String table) {
        return List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER").stream()
                .filter(privilege -> Boolean.TRUE.equals(application
                        .sql("SELECT has_table_privilege(current_user, :table, :privilege)")
                        .param("table", table)
                        .param("privilege", privilege)
                        .query(Boolean.class)
                        .single()))
                .toList();
    }

    @Test
    @DisplayName(
            "the database holds the invariants the service relies on: the kind and placement pairs, live exactly when published, a slug exactly for editorial kinds, and a window that ends after it starts")
    void constraintsHold() {
        assertThatThrownBy(() -> application
                        .sql("""
                        INSERT INTO content.items (id, tenant_id, brand_id, kind, placement, internal_name, display_title,
                                                   state, sort_key, version, created_by)
                        VALUES (gen_random_uuid(), :t, :b, 'BANNER', 'STORY_RAIL', 'x', '', 'DRAFT', 10, 1, 't')
                        """)
                        .param("t", tenant)
                        .param("b", brand)
                        .update())
                .as("a banner cannot sit in the story rail")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> application
                        .sql("UPDATE content.items SET state = 'PUBLISHED' WHERE id = :i")
                        .param("i", item)
                        .update())
                .as("published means a live revision")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> application
                        .sql("UPDATE content.items SET slug = 'banner-slug' WHERE id = :i")
                        .param("i", item)
                        .update())
                .as("a merchandising item has no slug")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> application
                        .sql("""
                        INSERT INTO content.item_drafts (item_id, tenant_id, brand_id, document, starts_at, ends_at, updated_by)
                        VALUES (:i, :t, :b, '{}'::jsonb, now(), now() - interval '1 day', 't')
                        """)
                        .param("i", item)
                        .param("t", tenant)
                        .param("b", brand)
                        .update())
                .as("a window must end after it starts")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> application
                        .sql("""
                        INSERT INTO content.allowed_hosts (tenant_id, brand_id, host, created_by)
                        VALUES (:t, :b, '*.example.uz', 't')
                        """)
                        .param("t", tenant)
                        .param("b", brand)
                        .update())
                .as("no wildcard in the allowlist")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> application
                        .sql("""
                        INSERT INTO content.allowed_hosts (tenant_id, brand_id, host, created_by)
                        VALUES (:t, :b, 'shop.example.uz:8443', 't')
                        """)
                        .param("t", tenant)
                        .param("b", brand)
                        .update())
                .as("no port in the allowlist")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a row cannot point at another tenant's brand: every foreign key names the tenant")
    void tenantScopeIsEnforcedByTheDatabase() {
        UUID otherTenant = UUID.randomUUID();
        UUID foreignBrand = UUID.randomUUID();
        ContentFixture fixture = new ContentFixture(owner);
        fixture.tenant(otherTenant, "other-" + otherTenant.toString().substring(0, 8));
        fixture.brand(
                otherTenant,
                foreignBrand,
                "F" + otherTenant.toString().substring(0, 6).toUpperCase());

        assertThatThrownBy(() -> application
                        .sql("""
                        INSERT INTO content.items (id, tenant_id, brand_id, kind, placement, internal_name, display_title,
                                                   state, sort_key, version, created_by)
                        VALUES (gen_random_uuid(), :t, :b, 'BANNER', 'HOME_CAROUSEL', 'x', '', 'DRAFT', 10, 1, 't')
                        """)
                        .param("t", tenant)
                        .param("b", foreignBrand)
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> application
                        .sql("""
                        INSERT INTO content.item_revisions (item_id, revision_no, tenant_id, brand_id, document, content_hash,
                                                            published_by, published_at)
                        VALUES (:i, 1, :t, :b, '{}'::jsonb, :h, 't', now())
                        """)
                        .param("i", item)
                        .param("t", otherTenant)
                        .param("b", foreignBrand)
                        .param("h", "a".repeat(64))
                        .update())
                .as("a revision cannot hang on another tenant's item")
                .isInstanceOf(DataAccessException.class);
    }
}
