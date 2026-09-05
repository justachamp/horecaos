package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderEntityMappingLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosOrderSource;
import uz.horecaos.platform.pos.domain.ExportState;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The guarantee ADR 0023 named as missing until this wave: a confirmed order is
 * dispatched to the till regardless of which replica confirmed it.
 *
 * <p><strong>Why this is two object graphs and not one.</strong> {@link
 * PosOrderExportTrigger}'s in-process queue is exactly the process-affinity bug
 * (wave 61's brief, ADR 0023's Runtime shape): a hint enqueued by the process
 * that served the confirming HTTP request is invisible to any other process. A
 * test that confirms and dispatches through the same {@code PosOrderExportTrigger}
 * instance would prove nothing about that — its in-memory queue would simply
 * still hold the hint. So this test builds two complete, independent {@code
 * PosOrderExportTrigger}/{@code PosOrderExportService} object graphs — two
 * {@link FakePosAdapter}s, two hint queues, the lot — wired to the one thing two
 * real replicas of this platform actually would share: the database. "Instance
 * A" confirms and is never asked to drain its own queue; "instance B" never
 * receives a hint for A's order at all, and has to find it the only way a second
 * replica could — {@link PosOrderExportTrigger#sweepStale}'s durable read of
 * {@code integration.pos_order_exports}.
 *
 * <p>What would still be true if the sweep did not exist, or did not work: this
 * test would fail with the export stuck {@code PENDING} and instance B's adapter
 * never called, because nothing but the sweep ever gives instance B a reason to
 * look at an order it was never told about. Wave 61 confirmed exactly that by
 * hand: with {@code sweepStale}'s {@code stale.forEach(...)} dispatch line
 * commented out, {@link #aConfirmedOrderIsDispatchedByADifferentInstanceOnceStale()}
 * failed on {@code adapterOnB.sideEffectCount()} — expected {@code 1}, got
 * {@code 0} — with the export left {@code PENDING}, before the line was
 * restored.
 */
class PosOrderExportCrossInstanceDispatchTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0003");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0004");
    private static final UUID BINDING = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0005");
    private static final UUID CHANNEL = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0006");
    private static final UUID CATALOG = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0007");
    private static final UUID PUBLICATION = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0008");
    private static final UUID VARIANT = UUID.fromString("018f6f4e-6100-7000-8000-0000000a0009");

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofSeconds(15);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        // catalog.catalogs and catalog.publications carry a tenant_id/brand_id
        // by convention rather than a foreign key — catalog does not reference
        // tenancy's tables directly — so a tenant.tenants CASCADE never reaches
        // them and they need their own truncate.
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        // Everything else here chains back to tenant.tenants through a real
        // foreign key, directly or through another table that does.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        insertFixture();
    }

    @Test
    @DisplayName("an order confirmed on one instance is dispatched by a different instance's durable sweep")
    void aConfirmedOrderIsDispatchedByADifferentInstanceOnceStale() {
        UUID orderId = insertConfirmedOrder("A-1001");

        FakePosAdapter adapterOnA = new FakePosAdapter();
        PosOrderExportTrigger instanceA = newTrigger(adapterOnA);

        // Instance A confirms the order: open() commits the export row and, on
        // commit, queues a hint in A's own memory.
        TransactionSynchronizationManager.initSynchronization();
        instanceA.onOrderConfirmed(confirmedEvent(orderId));
        commit();
        assertThat(instanceA.queueDepth())
                .as("A's own hint queue holds the confirmation it just processed")
                .isEqualTo(1);

        // Instance A is deliberately never asked to drain its queue. On a real
        // second replica there would be no "instance A" object left to ask —
        // the request that confirmed this order was handled by a container that
        // may already have moved on to the next request, or does not run this
        // scheduler's worker role at all.

        FakePosAdapter adapterOnB = new FakePosAdapter();
        PosOrderExportTrigger instanceB = newTrigger(adapterOnB);
        assertThat(instanceB.queueDepth())
                .as("B never received A's hint; its queue was never told this order exists")
                .isZero();

        // Immediately after confirmation, B's sweep must not dispatch: the
        // sweep is a backstop for a hint that did not arrive, not a second,
        // faster dispatch path racing the first.
        instanceB.sweepStale();
        assertThat(adapterOnB.sideEffectCount())
                .as("the export has not gone stale yet; B's sweep has nothing to claim")
                .isZero();
        assertThat(exportState(orderId)).isEqualTo(ExportState.PENDING);

        // Advance the shared clock past the staleness threshold. Both trigger
        // instances share this clock the way two real replicas share NTP time —
        // it is not the process-local state under test.
        clock.advanceBy(STALE_AFTER.plusSeconds(1));

        instanceB.sweepStale();

        assertThat(adapterOnB.sideEffectCount())
                .as("instance B — which never held the hint — is what actually called the till")
                .isEqualTo(1);
        assertThat(adapterOnA.sideEffectCount())
                .as("instance A's adapter was never called; A never drained its queue")
                .isZero();
        assertThat(exportState(orderId))
                .as("the export reached the till through B's durable sweep alone")
                .isEqualTo(ExportState.ACCEPTED);

        // A's stale hint is now harmless rather than a double ticket: draining
        // it finds the export already settled and calls send() one more time,
        // which the state machine refuses without touching the provider again.
        instanceA.dispatchPending();
        assertThat(adapterOnA.sideEffectCount())
                .as("A's late hint cannot print a second ticket: send() refuses a non-PENDING export")
                .isZero();
    }

    @Test
    @DisplayName("a second replica's sweep claims exactly once even if it ticks twice before the first replica notices")
    void aRepeatedSweepOnTheSameReplicaSendsExactlyOnce() {
        UUID orderId = insertConfirmedOrder("A-1002");

        PosOrderExportTrigger instanceA = newTrigger(new FakePosAdapter());
        TransactionSynchronizationManager.initSynchronization();
        instanceA.onOrderConfirmed(confirmedEvent(orderId));
        commit();

        clock.advanceBy(STALE_AFTER.plusSeconds(1));

        FakePosAdapter adapterOnB = new FakePosAdapter();
        PosOrderExportTrigger instanceB = newTrigger(adapterOnB);
        instanceB.sweepStale();
        // A second tick before the next confirmation must not find the same
        // row again: it settled out of PENDING on the first tick, so the
        // sweep's own WHERE state = 'PENDING' excludes it now.
        instanceB.sweepStale();

        assertThat(adapterOnB.sideEffectCount())
                .as("one claim, one send, however many times the sweep re-checks an already-settled export")
                .isEqualTo(1);
        assertThat(exportState(orderId)).isEqualTo(ExportState.ACCEPTED);
    }

    // ------------------------------------------------------------------ wiring

    private PosOrderExportTrigger newTrigger(FakePosAdapter adapter) {
        PosOrderExportService service = newService(adapter);
        return new PosOrderExportTrigger(service, clock, 10_000, 50, STALE_AFTER, 50);
    }

    private PosOrderExportService newService(FakePosAdapter adapter) {
        JdbcClient serviceJdbc = JdbcClient.create(db.dataSource());
        var json = JsonMapper.builder().build();
        return new PosOrderExportService(
                new PosAdapterRegistry(List.of(adapter)),
                new StubProviderInstallationLookup(),
                new StubProviderEntityMappingLookup(),
                new JdbcPosBindingConfiguration(serviceJdbc, json),
                new JdbcPosExportStore(serviceJdbc),
                new JdbcPosCapabilityStore(serviceJdbc, json),
                new StubPosOrderSource(),
                event -> {},
                clock);
    }

    private ExportState exportState(UUID orderId) {
        return jdbc.sql("""
                SELECT state FROM integration.pos_order_exports WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .query((row, number) -> ExportState.valueOf(row.getString("state")))
                .single();
    }

    private static OrderConfirmed confirmedEvent(UUID orderId) {
        return new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(TENANT),
                orderId,
                NOW,
                BRAND,
                LOCATION,
                "AUTO_CONFIRM",
                null,
                NOW,
                "UZS",
                50_000L,
                "CONFIRMED",
                1);
    }

    /** Fires exactly what a committing transaction manager fires, and no more. */
    private static void commit() {
        List<TransactionSynchronization> registered =
                List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(TransactionSynchronization::afterCommit);
    }

    // ------------------------------------------------------------------ fixture

    private void insertFixture() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'cross-instance-pos', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", CHANNEL).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", CATALOG)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications
                    (id, tenant_id, brand_id, catalog_id, channel, status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", PUBLICATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", CATALOG)
                .update();

        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('fake-pos-env', 'POS', :type, 'https://provider.example', false, 'provider.example')
                ON CONFLICT DO NOTHING
                """).param("type", FakePosAdapter.PROVIDER_TYPE).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status,
                     secret_reference, non_sensitive_config)
                VALUES (:id, :tenantId, 'POS', :type, 'fake-pos-env', 'Fake till', 'ACTIVE',
                        'horecaos:local:pos:fake:key', '{"venueId": "3"}'::jsonb)
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("type", FakePosAdapter.PROVIDER_TYPE)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, location_id, status, priority, effective_from)
                VALUES (:id, :tenantId, :installationId, :brandId, :locationId, 'ACTIVE', 100, :from)
                """)
                .param("id", BINDING)
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("from", NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC))
                .update();
    }

    private UUID insertConfirmedOrder(String number) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        50000, 0, 50000, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("publicationId", PUBLICATION)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'PICKUP', 'UZS',
                        'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", CHANNEL)
                .param("guest", "guest-" + number)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", number);
        order.put("tenantId", TENANT);
        order.put("brandId", BRAND);
        order.put("locationId", LOCATION);
        order.put("channelId", CHANNEL);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", PUBLICATION);
        order.put("guest", "guest-" + number);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'STOREFRONT',
                    :guest, 'PICKUP', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'CONFIRMED', 'UZS',
                    50000, 0, 50000, :quoteId, 'hash', :publicationId, :cartId, :guest,
                    1, now())
                """).params(order).update();

        return orderId;
    }

    // ------------------------------------------------------------------ doubles

    /**
     * Always answers the one binding this fixture created, regardless of scope.
     * Standing in for {@code JdbcProviderInstallationLookup}, which both a real
     * app and a real worker replica would resolve identically from the same
     * shared installation/binding rows — the resolution itself is not the
     * process-affine part of this story, the in-process queue is.
     */
    private static final class StubProviderInstallationLookup implements ProviderInstallationLookup {

        private static final BindingRef BINDING_REF = new BindingRef(
                BINDING, INSTALLATION, TENANT, ProviderCategory.POS, FakePosAdapter.PROVIDER_TYPE, BRAND, LOCATION);

        @Override
        public Optional<BindingRef> primaryBinding(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            if (!TENANT.equals(tenantId) || !PosCapability.ORDER_EXPORT.code().equals(capabilityCode)) {
                return Optional.empty();
            }
            return Optional.of(BINDING_REF);
        }

        @Override
        public List<BindingRef> candidateBindings(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            return primaryBinding(tenantId, brandId, locationId, capabilityCode)
                    .map(List::of)
                    .orElse(List.of());
        }

        @Override
        public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
            return Optional.empty();
        }
    }

    /** Answers a stable external id for any variant; nothing here exercises modifiers or a mapped customer. */
    private static final class StubProviderEntityMappingLookup implements ProviderEntityMappingLookup {

        @Override
        public Optional<String> externalIdFor(UUID bindingId, String entityType, UUID horecaosEntityId) {
            return Optional.of("ext-" + horecaosEntityId);
        }

        @Override
        public Optional<UUID> horecaosIdFor(UUID bindingId, String entityType, String externalId) {
            return Optional.empty();
        }
    }

    /** One canned order, keyed by the id the real {@code ordering.orders} fixture row was given. */
    private final class StubPosOrderSource implements PosOrderSource {

        @Override
        public Optional<ExportableOrder> find(UUID tenantId, UUID orderId, String revealPurpose) {
            if (!TENANT.equals(tenantId)) {
                return Optional.empty();
            }
            return Optional.of(new ExportableOrder(
                    orderId,
                    TENANT,
                    BRAND,
                    LOCATION,
                    "A-" + orderId.toString().substring(0, 4),
                    "CONFIRMED",
                    "AUTO_CONFIRM",
                    "PICKUP",
                    "UZS",
                    50_000L,
                    NOW,
                    null,
                    "Test customer",
                    "+998901234567",
                    null,
                    List.of(new ExportableOrder.Line(
                            UUID.randomUUID(), VARIANT, "Fake dish", null, 1, 50_000L, List.of()))));
        }
    }

    /** A clock a test moves, so a staleness threshold is crossed by decree rather than by sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceBy(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
