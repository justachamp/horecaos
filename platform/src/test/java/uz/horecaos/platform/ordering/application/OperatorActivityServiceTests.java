package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0156's «Работает без смены» reads who took orders through {@code OperatorActivity}; the service
 * and the SQL under it are proven here against the real {@code ordering.orders} columns, since the
 * workforce tests stand in for this port.
 *
 * <p>Only the attribution columns matter, so the rows are inserted with foreign keys switched off
 * ({@code session_replication_role = replica}, as the migration tests do): building a cart, a quote
 * and a publication for every order would test those tables and hide what this one asserts.
 */
class OperatorActivityServiceTests {

    private static final UUID TENANT = UUID.fromString("018fb300-6000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb300-6000-7000-8000-0000000000a2");
    private static final UUID LOCATION = UUID.fromString("018fb300-6000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fb300-6000-7000-8000-0000000000c2");

    private static final Instant FROM = Instant.parse("2026-10-08T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-09T00:00:00Z");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OperatorActivityService activity;
    private int sequence;

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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        activity = new OperatorActivityService(new JdbcOrderStore(jdbc));
        sequence = 0;
    }

    @Test
    @DisplayName("the person who created an order and the person who accepted it are both operators of the window")
    void creatorsAndAcceptersAreCounted() throws SQLException {
        order(TENANT, LOCATION, "USER", "subject-creator", at("2026-10-08T06:00:00Z"), null, null, null);
        order(
                TENANT,
                LOCATION,
                "CUSTOMER",
                "a-customer-account",
                at("2026-10-08T07:00:00Z"),
                "USER",
                "subject-accepter",
                at("2026-10-08T07:05:00Z"));

        assertThat(activity.operatorsAt(TENANT, LOCATION, FROM, TO))
                .containsExactlyInAnyOrder("subject-creator", "subject-accepter");
    }

    @Test
    @DisplayName("a customer, a service or a system job is not an operator, and neither is an order nobody took")
    void onlyUsersAreOperators() throws SQLException {
        order(TENANT, LOCATION, "CUSTOMER", "a-customer", at("2026-10-08T06:00:00Z"), null, null, null);
        order(TENANT, LOCATION, "SERVICE", "storefront", at("2026-10-08T06:10:00Z"), null, null, null);
        order(
                TENANT,
                LOCATION,
                "SYSTEM_JOB",
                "auto-accept",
                at("2026-10-08T06:20:00Z"),
                "SYSTEM_JOB",
                "auto-accept",
                at("2026-10-08T06:21:00Z"));
        order(TENANT, LOCATION, null, null, at("2026-10-08T06:30:00Z"), null, null, null);

        assertThat(activity.operatorsAt(TENANT, LOCATION, FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("the window is closed at its start and open at its end")
    void theWindowIsHalfOpen() throws SQLException {
        order(TENANT, LOCATION, "USER", "at-the-start", FROM, null, null, null);
        order(TENANT, LOCATION, "USER", "at-the-end", TO, null, null, null);
        order(TENANT, LOCATION, "USER", "before", FROM.minusSeconds(1), null, null, null);

        assertThat(activity.operatorsAt(TENANT, LOCATION, FROM, TO)).containsExactly("at-the-start");
    }

    @Test
    @DisplayName(
            "an acceptance outside the window does not make an operator of the window, even if the order was created inside it by someone else")
    void acceptanceIsJudgedByItsOwnInstant() throws SQLException {
        order(
                TENANT,
                LOCATION,
                "USER",
                "creator",
                at("2026-10-08T23:50:00Z"),
                "USER",
                "late-accepter",
                at("2026-10-09T00:10:00Z"));

        assertThat(activity.operatorsAt(TENANT, LOCATION, FROM, TO)).containsExactly("creator");
    }

    @Test
    @DisplayName("another branch's or another company's orders are never counted")
    void tenantAndBranchScoped() throws SQLException {
        order(TENANT, OTHER_LOCATION, "USER", "other-branch", at("2026-10-08T06:00:00Z"), null, null, null);
        order(OTHER_TENANT, LOCATION, "USER", "other-tenant", at("2026-10-08T06:00:00Z"), null, null, null);
        order(TENANT, LOCATION, "USER", "mine", at("2026-10-08T06:00:00Z"), null, null, null);

        assertThat(activity.operatorsAt(TENANT, LOCATION, FROM, TO)).containsExactly("mine");
    }

    @Test
    @DisplayName("a person who took many orders is one subject")
    void subjectsAreDistinct() throws SQLException {
        for (int i = 0; i < 5; i++) {
            order(TENANT, LOCATION, "USER", "busy", at("2026-10-08T06:00:00Z").plusSeconds(i), null, null, null);
        }

        assertThat(activity.operatorsAt(TENANT, LOCATION, FROM, TO)).containsExactly("busy");
    }

    // ------------------------------------------------------------------------------ fixtures

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    private void order(
            UUID tenant,
            UUID location,
            @Nullable String creatorType,
            @Nullable String creator,
            Instant createdAt,
            @Nullable String accepterType,
            @Nullable String accepter,
            @Nullable Instant acceptedAt)
            throws SQLException {
        int n = ++sequence;
        try (Connection connection = db.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                        channel_id, channel_code_snapshot, fulfillment_mode, acceptance_mode_snapshot,
                        approval_channel_snapshot, status, currency, subtotal_minor, tax_minor, discount_minor,
                        fee_minor, total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                        cart_id, idempotency_key, promise_basis, version, created_at, confirmed_at, closed_at,
                        created_by_actor_type, created_by_actor_id, accepted_by_actor_type, accepted_by_actor_id,
                        accepted_at, customer_account_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'TELEGRAM', 'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'COMPLETED', 'UZS',
                        1000, 0, 0, 0, 1000, ?, ?, ?, ?, ?, 'NOT_PROMISED', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                Timestamp created = Timestamp.from(createdAt);
                int i = 1;
                insert.setObject(i++, UUID.randomUUID());
                insert.setString(i++, "OA-" + n);
                insert.setObject(i++, tenant);
                insert.setObject(i++, UUID.randomUUID());
                insert.setObject(i++, location);
                insert.setObject(i++, UUID.randomUUID());
                insert.setObject(i++, UUID.randomUUID());
                insert.setString(i++, "hash-" + n);
                insert.setObject(i++, UUID.randomUUID());
                insert.setObject(i++, UUID.randomUUID());
                insert.setString(i++, "idem-" + n);
                insert.setTimestamp(i++, created);
                insert.setTimestamp(i++, created);
                insert.setTimestamp(i++, created);
                insert.setString(i++, creatorType);
                insert.setString(i++, creator);
                insert.setString(i++, accepterType);
                insert.setString(i++, accepter);
                insert.setTimestamp(i++, acceptedAt == null ? null : Timestamp.from(acceptedAt));
                insert.setObject(i, UUID.randomUUID());
                insert.executeUpdate();
            }
            connection.commit();
        }
    }
}
