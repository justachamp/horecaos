package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * {@code recordCallProvenance} (ADR 0064) against a real order row: {@code
 * POST .../{orderId}/call-provenance} had zero callers anywhere before wave
 * W01, so nothing had ever exercised the write-once behaviour {@code
 * JdbcOrderStore.recordCallProvenance}'s own doc promises. This is the
 * service the operations app's order-taking path now calls with the claimed
 * screen-pop card's {@code callEventId}.
 *
 * <p>Hand-wired against a real PostgreSQL, the same shape {@code
 * DineInTests} uses, rather than a full {@code @SpringBootTest}: the
 * property under test — one {@code UPDATE ... WHERE source_call_id IS NULL}
 * — is a property of the database, and the HTTP/capability wiring is already
 * covered by {@code OperationsOrderControllerActionCapabilitiesTests}.
 */
class OrderCallProvenanceServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcOrderStore orders;
    private RecordingAuditRecorder audit;
    private OrderCallProvenanceService provenance;
    private UUID channelId;
    private UUID publicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the call-provenance test");
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
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        orders = new JdbcOrderStore(jdbc);
        audit = new RecordingAuditRecorder();
        provenance = new OrderCallProvenanceService(
                orders, audit, Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"), ZoneOffset.UTC));

        seedTenancy();
    }

    @Test
    @DisplayName("the first call recorded against an order links it and writes one audit fact")
    void firstCallLinksTheOrder() {
        UUID orderId = seedOrder("3101");
        UUID callId = UUID.randomUUID();

        provenance.record(TENANT, orderId, callId, ActorRef.user("operator-1", null), "order.provenance.record");

        assertThat(orders.hasCallProvenance(TENANT, orderId, callId)).isTrue();
        assertThat(audit.facts).hasSize(1);
        AuditFact fact = audit.facts.get(0);
        assertThat(fact.actionCode()).isEqualTo("ordering.order.call_provenance_recorded");
        assertThat(fact.changeDocument()).containsEntry("callId", callId.toString());
    }

    @Test
    @DisplayName("the same call id recorded twice is a harmless retry — no second audit fact")
    void sameCallTwiceIsIdempotent() {
        UUID orderId = seedOrder("3102");
        UUID callId = UUID.randomUUID();

        provenance.record(TENANT, orderId, callId, ActorRef.user("operator-1", null), "order.provenance.record");
        provenance.record(TENANT, orderId, callId, ActorRef.user("operator-1", null), "order.provenance.record");

        assertThat(orders.hasCallProvenance(TENANT, orderId, callId)).isTrue();
        // Write-once: the second call touched zero rows, so it must not have
        // produced a second fact — an operator's poll-driven retry must not
        // read as two separate links in the audit trail.
        assertThat(audit.facts).hasSize(1);
    }

    @Test
    @DisplayName("a second, different call id on an already-linked order is refused, and the "
            + "original link is undisturbed")
    void aDifferentCallIdIsRefused() {
        UUID orderId = seedOrder("3103");
        UUID firstCall = UUID.randomUUID();
        UUID secondCall = UUID.randomUUID();

        provenance.record(TENANT, orderId, firstCall, ActorRef.user("operator-1", null), "order.provenance.record");

        Throwable failure = catchThrowable(() -> provenance.record(
                TENANT, orderId, secondCall, ActorRef.user("operator-2", null), "order.provenance.record"));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(orders.hasCallProvenance(TENANT, orderId, firstCall)).isTrue();
        assertThat(orders.hasCallProvenance(TENANT, orderId, secondCall)).isFalse();
        // The refusal itself is not an audit-worthy fact; only the link is.
        assertThat(audit.facts).hasSize(1);
    }

    // ------------------------------------------------------------------ fixtures

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'call-provenance', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :t, 'CALL_CENTRE', 'CALL_CENTRE', 'Call centre', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'CALL_CENTRE', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
    }

    private UUID seedOrder(String number) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, 'PICKUP', 'UZS', 'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, :hash, 20000, 0, 20000,
                    now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("pub", publicationId)
                .param("hash", "hash-" + orderId)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, confirmed_at, version, created_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'CALL_CENTRE', :guest, 'PICKUP',
                    'AUTO_CONFIRM', 'HORECAOS_OPERATIONS', :status,
                    'UZS', 20000, 0, 0, 20000, :quote, :hash, :pub, :cart, :key, :at, 1, :at)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("guest", "guest-" + orderId)
                .param("status", OrderStatus.RECEIVED.name())
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("at", now.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
