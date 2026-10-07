package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0111 §8: a call outcome "cannot be quietly rewritten after the fact" because the application
 * role holds {@code INSERT} and {@code SELECT} on {@code customer.contact_attempts} and nothing
 * else (V0507) -- asserted under that role, not under the owner that every other test connects as.
 *
 * <p>The shape is {@code DatabasePrivilegeTests}': a LOGIN role whose only privilege is {@code
 * horecaos_application}, because a REVOKE asserted from the owner's connection asserts nothing. The
 * first test says the probe is a real one; without it every refusal below could pass vacuously.
 */
class ContactAttemptJournalPrivilegeTests {

    private static final String PROBE = "journal_probe_app";
    private static final String PASSWORD = "journal-probe-app";

    private static TestDatabase.Handle db;
    private static JdbcClient owner;
    private static JdbcClient application;

    private static final UUID TENANT = UUID.fromString("018f9f10-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-6000-7000-8000-0000000000b1");
    private static final UUID LEAD = UUID.fromString("018f9f10-6000-7000-8000-0000000000d1");

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the privilege probes");
        db = TestDatabase.migrated();
        owner = JdbcClient.create(db.dataSource());
        owner.sql("DROP ROLE IF EXISTS " + PROBE).update();
        owner.sql("CREATE ROLE " + PROBE + " LOGIN PASSWORD '" + PASSWORD + "'").update();
        owner.sql("ALTER ROLE " + PROBE + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION INHERIT")
                .update();
        owner.sql("GRANT " + TestDatabase.APPLICATION_ROLE + " TO " + PROBE).update();
        DataSource asApplication = db.dataSourceAs(PROBE, PASSWORD);
        application = JdbcClient.create(asApplication);

        owner.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'journal-probe', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        owner.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
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
            System.err.println("ContactAttemptJournalPrivilegeTests: " + PROBE + " outlived the suite");
        }
    }

    @Test
    @DisplayName("the probe is neither a superuser nor the owner, so the refusals below mean something")
    void theProbeHoldsOnlyWhatWasGrantedToIt() {
        assertThat(application.sql("""
                        SELECT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS is_superuser,
                               pg_get_userbyid(d.datdba) = current_user AS owns_database
                          FROM pg_database d WHERE d.datname = current_database()
                        """).query().singleRow())
                .containsEntry("is_superuser", false)
                .containsEntry("owns_database", false);
    }

    @Test
    @DisplayName(
            "the application inserts and reads a contact attempt, and can neither rewrite, delete nor truncate one")
    void theJournalIsAppendOnlyForTheApplication() {
        insertLead();
        UUID id = UUID.randomUUID();
        assertThat(application
                        .sql("""
                        INSERT INTO customer.contact_attempts (id, tenant_id, brand_id, lead_id, direction,
                            attempt_id, outcome, operator_actor_id, occurred_at)
                        VALUES (:id, :t, :b, :lead, 'OUTBOUND', :attempt, 'NO_ANSWER', 'operator-1', :at)
                        """)
                        .param("id", id)
                        .param("t", TENANT)
                        .param("b", BRAND)
                        .param("lead", LEAD)
                        .param("attempt", UUID.randomUUID())
                        .param("at", Instant.now().atOffset(java.time.ZoneOffset.UTC))
                        .update())
                .isEqualTo(1);
        assertThat(application
                        .sql("SELECT count(*) FROM customer.contact_attempts WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);

        assertThatThrownBy(() -> application
                        .sql("UPDATE customer.contact_attempts SET outcome = 'CONNECTED' WHERE id = :id")
                        .param("id", id)
                        .update())
                .as("a call outcome cannot be rewritten")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM customer.contact_attempts WHERE id = :id")
                        .param("id", id)
                        .update())
                .as("nor removed")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() ->
                        application.sql("TRUNCATE customer.contact_attempts").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");

        assertThat(owner.sql("SELECT outcome FROM customer.contact_attempts WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .as("the row is exactly as it was written")
                .isEqualTo("NO_ANSWER");
    }

    @Test
    @DisplayName("leads, unlike the journal, are updated in place by the application")
    void aLeadIsUpdatedInPlaceButNeverDeleted() {
        insertLead();

        assertThat(application
                        .sql("UPDATE customer.leads SET status = 'CONTACTED', version = version + 1 WHERE id = :id")
                        .param("id", LEAD)
                        .update())
                .isEqualTo(1);
        assertThatThrownBy(() -> application
                        .sql("DELETE FROM customer.leads WHERE id = :id")
                        .param("id", LEAD)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("the database holds the status machine's own invariants")
    void theDatabaseHoldsTheInvariants() {
        insertLead();

        assertThatThrownBy(() -> owner.sql("UPDATE customer.leads SET status = 'CONVERTED' WHERE id = :id")
                        .param("id", LEAD)
                        .update())
                .as("CONVERTED without an order or a reservation")
                .hasMessageContaining("ck_lead_converted");
        assertThatThrownBy(() -> owner.sql("UPDATE customer.leads SET status = 'DECLINED' WHERE id = :id")
                        .param("id", LEAD)
                        .update())
                .as("DECLINED without a reason")
                .hasMessageContaining("ck_lead_closed");
        assertThatThrownBy(() -> owner.sql("""
                                UPDATE customer.leads SET status = 'CONVERTED', converted_order_id = :o,
                                       converted_reservation_id = :r WHERE id = :id
                                """)
                        .param("o", UUID.randomUUID())
                        .param("r", UUID.randomUUID())
                        .param("id", LEAD)
                        .update())
                .as("converted into two things at once")
                .hasMessageContaining("ck_lead_converted_one");
        assertThatThrownBy(() -> owner.sql("UPDATE customer.leads SET status = 'CALLBACK_SCHEDULED' WHERE id = :id")
                        .param("id", LEAD)
                        .update())
                .as("a scheduled callback with no time")
                .hasMessageContaining("ck_lead_callback");
        assertThatThrownBy(() -> owner.sql("""
                                INSERT INTO customer.contact_attempts (id, tenant_id, brand_id, lead_id, direction,
                                    attempt_id, outcome, operator_actor_id, occurred_at)
                                VALUES (gen_random_uuid(), :t, :b, :lead, 'OUTBOUND', gen_random_uuid(), 'BLOCKED',
                                        'operator-1', now())
                                """)
                        .param("t", TENANT)
                        .param("b", BRAND)
                        .param("lead", LEAD)
                        .update())
                .as("a refusal that names no reason")
                .hasMessageContaining("ck_attempt_blocked_pair");
        assertThatThrownBy(() ->
                        owner.sql("""
                                INSERT INTO customer.contact_attempts (id, tenant_id, brand_id, direction,
                                    attempt_id, outcome, operator_actor_id, occurred_at)
                                VALUES (gen_random_uuid(), :t, :b, 'OUTBOUND', gen_random_uuid(), 'CONNECTED',
                                        'operator-1', now())
                                """).param("t", TENANT).param("b", BRAND).update())
                .as("a call about nobody")
                .hasMessageContaining("ck_attempt_subject");
    }

    private static void insertLead() {
        owner.sql("""
                INSERT INTO customer.leads (id, tenant_id, brand_id, status, source, phone_lookup_hash,
                    phone_encrypted, phone_masked, created_by)
                VALUES (:id, :t, :b, 'NEW', 'CALLBACK_REQUEST', 'hash', 'ciphertext', '*** 0000', 'fixture')
                ON CONFLICT (id) DO UPDATE SET status = 'NEW', version = 1,
                    closed_reason = NULL, callback_due_at = NULL,
                    converted_order_id = NULL, converted_reservation_id = NULL
                """).param("id", LEAD).param("t", TENANT).param("b", BRAND).update();
    }
}
