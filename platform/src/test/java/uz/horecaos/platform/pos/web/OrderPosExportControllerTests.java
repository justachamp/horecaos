package uz.horecaos.platform.pos.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderEntityMappingLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.PosAdapterRegistry;
import uz.horecaos.platform.pos.application.PosOrderExportService;
import uz.horecaos.platform.pos.application.port.PosOrderSource;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;
import uz.horecaos.platform.support.RecordingProviderActivityRecorder;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The operations-plane sibling read and command (ADR 0011, gap map row
 * {@code 1.2i}, wave P42): a merchant's own view of an order's POS export,
 * and the push/retry command that was previously reachable only from the
 * control plane's {@code AWAITING_OPERATOR} queue.
 *
 * <p>Against the migrated schema, the same fixture shape {@code
 * PosOrderExportCrossInstanceDispatchTests} already established for this
 * module: a real {@link JdbcPosExportStore}, a real {@link
 * PosOrderExportService} wired to a {@link FakePosAdapter}, and a togglable
 * {@link ProviderInstallationLookup} stub so "the tenant's POS adapter does
 * not declare the capability" is a fixture flag rather than a second object
 * graph.
 */
class OrderPosExportControllerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0003");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0004");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0005");
    private static final UUID CHANNEL = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0006");
    private static final UUID CATALOG = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0007");
    private static final UUID PUBLICATION = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0008");
    private static final UUID VARIANT = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0010");

    private static final Instant NOW = Instant.parse("2026-09-15T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;
    private FakePosAdapter adapter;
    private StubProviderInstallationLookup installations;
    private OrderPosExportController controller;
    private PosOrderExportService exportService;
    private JdbcPosExportStore exportStore;

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
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        adapter = new FakePosAdapter();
        installations = new StubProviderInstallationLookup();
        insertFixture();

        exportStore = new JdbcPosExportStore(jdbc);
        var json = JsonMapper.builder().build();
        TransactionTemplate unitOfWork = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        exportService = new PosOrderExportService(
                new PosAdapterRegistry(List.of(adapter)),
                installations,
                new StubProviderEntityMappingLookup(),
                new JdbcPosBindingConfiguration(jdbc, json),
                exportStore,
                new JdbcPosCapabilityStore(jdbc, json),
                new StubPosOrderSource(),
                (tenantId, brandId, priceableIds) -> Map.of(),
                event -> {},
                new RecordingProviderActivityRecorder(),
                clock,
                unitOfWork);

        controller = new OrderPosExportController(
                new StubOrderDirectory(),
                installations,
                exportService,
                exportStore,
                new JdbcAuditRecorder(jdbc, json),
                () -> new AuthenticatedActor("operator-1", Set.of(), Map.of()),
                clock);
    }

    // ------------------------------------------------------------------ GET

    @Test
    @DisplayName("no such order is a 404, not a leaked cross-tenant row")
    void anUnknownOrderIsNotFound() {
        assertThatThrownBy(() -> controller.forOrder(TENANT, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(exception ->
                        assertThat(((ApiException) exception).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("the affordance is suppressed when the tenant's POS adapter does not declare ORDER_EXPORT")
    void suppressedWhenTheBindingDoesNotDeclareOrderExport() {
        installations.declaresOrderExport = false;
        UUID orderId = insertConfirmedOrder("A-3001");

        var response =
                Objects.requireNonNull(controller.forOrder(TENANT, orderId).getBody());

        assertThat(response.posCapable()).isFalse();
        assertThat(response.export())
                .as("nothing to show once the affordance itself is suppressed")
                .isNull();
    }

    @Test
    @DisplayName("posCapable but not yet exported is a real, non-error answer")
    void posCapableWithNoExportRowYet() {
        // A real order row, but nothing ever called push/open for it -- exactly
        // an order too recently confirmed for the trigger to have run yet.
        UUID orderId = insertConfirmedOrder("A-3999");
        var response =
                Objects.requireNonNull(controller.forOrder(TENANT, orderId).getBody());

        assertThat(response.posCapable()).isTrue();
        assertThat(response.export()).isNull();
    }

    @Test
    @DisplayName("a pushed export's read carries its state, its error and the amendment interlock signal")
    void readsAFailedExportsErrorHonestly() {
        UUID orderId = insertConfirmedOrder("A-3002");
        adapter.failNextExportWith(ProviderOutcome.rejected("LINE_UNMAPPED", "no provider mapping"));
        controller.push(TENANT, orderId, new OrderPosExportController.PushRequest("Operator retry"));

        var response =
                Objects.requireNonNull(controller.forOrder(TENANT, orderId).getBody());

        var export = Objects.requireNonNull(response.export());
        assertThat(export.state()).isEqualTo("REJECTED");
        assertThat(export.lastErrorCode()).isEqualTo("LINE_UNMAPPED");
        assertThat(export.permitsAmendment())
                .as("REJECTED is one of the states ExportState#permitsAmendment names as settled")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a real, unsettled export survives the binding losing ORDER_EXPORT -- the §3.11 AMEND interlock cannot silently disarm")
    void aRealExportSurvivesTheBindingLosingOrderExportCapability() {
        UUID orderId = insertConfirmedOrder("A-3010");
        // A real ticket reached the till while the binding still declared
        // ORDER_EXPORT.
        controller.push(TENANT, orderId, new OrderPosExportController.PushRequest("first push"));

        // A tenant admin reconfigures or removes the location's POS binding --
        // an ordinary, unrelated admin action that has nothing to do with
        // whether this specific order's own export is settled.
        installations.declaresOrderExport = false;

        var response =
                Objects.requireNonNull(controller.forOrder(TENANT, orderId).getBody());

        assertThat(response.posCapable())
                .as("the push/retry affordance is correctly suppressed")
                .isFalse();
        var export = Objects.requireNonNull(response.export());
        assertThat(export.state()).isEqualTo("ACCEPTED");
        assertThat(export.permitsAmendment())
                .as("ACCEPTED does not permit amendment -- the interlock must keep seeing this real "
                        + "export rather than silently disappearing because posCapable went false")
                .isFalse();
    }

    @Test
    @DisplayName("neither the read nor the push response ever carries the provider's raw error text (ADR 0029)")
    void neitherResponseCarriesTheProvidersRawText() {
        UUID orderId = insertConfirmedOrder("A-3011");
        // A Clopos error body has been observed to echo request content back,
        // including a customer's address (CloposEnvelope#trim's own comment).
        // "CLOPOS_REFUSED" is not one of the codes this controller maps to a
        // fixed sentence via a more specific case, so it also proves the
        // default branch never leaks the raw text.
        String piiShapedDetail = "customer 90 Chilonzor tumani, 12-uy, Tashkent refused delivery";
        adapter.failNextExportWith(ProviderOutcome.rejected("CLOPOS_REFUSED", piiShapedDetail));

        var pushResult = Objects.requireNonNull(controller
                .push(TENANT, orderId, new OrderPosExportController.PushRequest("Operator retry"))
                .getBody());
        assertThat(pushResult.detail())
                .as("the push response carries a platform-authored message, not the provider's text")
                .isNotNull()
                .doesNotContain("Chilonzor")
                .doesNotContain(piiShapedDetail);

        var readResult =
                Objects.requireNonNull(controller.forOrder(TENANT, orderId).getBody());
        var export = Objects.requireNonNull(readResult.export());
        assertThat(export.lastError())
                .as("the read response carries a platform-authored message, not the provider's text")
                .isNotNull()
                .doesNotContain("Chilonzor")
                .doesNotContain(piiShapedDetail);
        // The diagnostic code itself is not PII and is still exactly what the
        // provider (via CloposEnvelope) returned.
        assertThat(export.lastErrorCode()).isEqualTo("CLOPOS_REFUSED");
    }

    // ------------------------------------------------------------------ push

    @Test
    @DisplayName("a first push sends the order and the response carries the new state")
    void aFirstPushSendsThePendingExport() {
        UUID orderId = insertConfirmedOrder("A-3003");

        var result = Objects.requireNonNull(controller
                .push(TENANT, orderId, new OrderPosExportController.PushRequest("Kitchen never printed"))
                .getBody());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.state()).isEqualTo("ACCEPTED");
        assertThat(adapter.sideEffectCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a push is audited with the operator's reason, correlated to the export")
    void aPushIsAudited() {
        UUID orderId = insertConfirmedOrder("A-3004");

        controller.push(TENANT, orderId, new OrderPosExportController.PushRequest("Kitchen never printed"));
        UUID exportId = exportIdOf(orderId);

        // Scoped to this test's own export: audit.audit_events is append-only
        // and outlives the tenant.tenants TRUNCATE this class's @BeforeEach
        // runs, so earlier tests in this class leave their own
        // pos.export_push_requested rows standing under the same tenant id.
        Map<String, Object> row = jdbc.sql("""
                SELECT action_code, reason, actor_subject, capability_used
                  FROM audit.audit_events
                 WHERE tenant_id = :tenantId AND action_code = 'pos.export_push_requested'
                   AND target_id = :exportId
                """)
                .param("tenantId", TENANT)
                .param("exportId", exportId)
                .query()
                .singleRow();

        assertThat(row.get("reason")).isEqualTo("Kitchen never printed");
        assertThat(row.get("actor_subject")).isEqualTo("operator-1");
        assertThat(row.get("capability_used")).isEqualTo("pos.export.resolve");
    }

    @Test
    @DisplayName("a retry re-sends an export an operator has already confirmed absent")
    void aRetryResendsFromResolvedAbsent() {
        UUID orderId = insertConfirmedOrder("A-3005");
        adapter.failNextExportWith(ProviderOutcome.rejected("EXPORT_UNKNOWN", "simulated loss"));
        controller.push(TENANT, orderId, new OrderPosExportController.PushRequest("first attempt lost the ticket"));
        // A REJECTED first attempt is terminal (ExportStateMachine has no edge
        // out of it) -- force RESOLVED_ABSENT directly, the state an operator's
        // own settleByOperator(ABSENT) call leaves behind, so this test proves
        // the retry edge rather than re-proving REJECTED's own terminality.
        UUID exportId = exportIdOf(orderId);
        jdbc.sql("""
                UPDATE integration.pos_order_exports
                   SET state = 'RESOLVED_ABSENT', version = version + 1
                 WHERE id = :id
                """).param("id", exportId).update();

        var result = Objects.requireNonNull(controller
                .push(TENANT, orderId, new OrderPosExportController.PushRequest("Confirmed absent, retrying"))
                .getBody());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.state()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("pushing an export the state machine will not send again answers, rather than throws")
    void pushingAnAlreadyAcceptedExportRefusesWithoutTouchingTheProvider() {
        UUID orderId = insertConfirmedOrder("A-3006");
        controller.push(TENANT, orderId, new OrderPosExportController.PushRequest("first push"));
        assertThat(adapter.sideEffectCount()).isEqualTo(1);

        var result = Objects.requireNonNull(controller
                .push(TENANT, orderId, new OrderPosExportController.PushRequest("pressed twice"))
                .getBody());

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.errorCode()).isEqualTo("EXPORT_NOT_SENDABLE");
        assertThat(result.state()).isEqualTo("ACCEPTED");
        assertThat(adapter.sideEffectCount())
                .as("the till was not called a second time")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("push refuses when the tenant's POS adapter does not declare ORDER_EXPORT")
    void pushRefusesWithoutTheCapability() {
        installations.declaresOrderExport = false;
        UUID orderId = insertConfirmedOrder("A-3007");

        assertThatThrownBy(
                        () -> controller.push(TENANT, orderId, new OrderPosExportController.PushRequest("try anyway")))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException apiException = (ApiException) exception;
                    assertThat(apiException.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(apiException.properties()).containsEntry("reason", "POS_NOT_CAPABLE");
                });
        assertThat(adapter.sideEffectCount()).isZero();
    }

    @Test
    @DisplayName("the export's settled state and its pos.export_push_requested audit fact commit or roll back "
            + "together (ADR 0027)")
    void aFailedAuditWriteRollsBackTheSettledStateWithIt() {
        UUID orderId = insertConfirmedOrder("A-3012");

        AuditRecorder failingAudit = fact -> {
            throw new IllegalStateException("audit sink is unavailable");
        };
        OrderPosExportController withFailingAudit = new OrderPosExportController(
                new StubOrderDirectory(),
                installations,
                exportService,
                exportStore,
                failingAudit,
                () -> new AuthenticatedActor("operator-1", Set.of(), Map.of()),
                clock);

        assertThatThrownBy(() ->
                        withFailingAudit.push(TENANT, orderId, new OrderPosExportController.PushRequest("first push")))
                .isInstanceOf(IllegalStateException.class);
        UUID exportId = exportIdOf(orderId);

        // recordAttempt/settle ran inside the same PosOrderExportService
        // transaction as the audit write that then threw, so both rolled
        // back: the export must still show the claim (SENT, attempt 1) that
        // committed on its own before the provider call, but nothing the
        // post-call write would have added -- proving the state change and
        // its audit fact are not two independent writes.
        var row = jdbc.sql("""
                SELECT state, attempt_count, external_order_id
                  FROM integration.pos_order_exports
                 WHERE id = :id
                """).param("id", exportId).query().singleRow();
        assertThat(row.get("state"))
                .as("settle() rolled back with the audit write -- the export never reached ACCEPTED")
                .isEqualTo("SENT");
        assertThat(row.get("external_order_id")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM integration.pos_export_attempts WHERE export_id = :id")
                        .param("id", exportId)
                        .query(Integer.class)
                        .single())
                .as("recordAttempt() rolled back too")
                .isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_events
                         WHERE action_code = 'pos.export_push_requested' AND target_id = :id
                        """).param("id", exportId).query(Integer.class).single())
                .isZero();
    }

    // ------------------------------------------------------------------ fixture

    private UUID exportIdOf(UUID orderId) {
        return jdbc.sql("""
                SELECT id FROM integration.pos_order_exports
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .query(UUID.class)
                .single();
    }

    private void insertFixture() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'wave-p42-pos-export', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
                VALUES ('wave-p42-pos-env', 'POS', :type, 'https://provider.example', false, 'provider.example')
                ON CONFLICT DO NOTHING
                """).param("type", FakePosAdapter.PROVIDER_TYPE).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status,
                     secret_reference, non_sensitive_config)
                VALUES (:id, :tenantId, 'POS', :type, 'wave-p42-pos-env', 'Fake till', 'ACTIVE',
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

    /** Reads the same order row this test's own fixture wrote, through the port the controller actually depends on. */
    private final class StubOrderDirectory implements OrderDirectory {

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            return jdbc.sql("""
                    SELECT id, tenant_id, brand_id, location_id, public_order_number, status,
                           currency, total_minor, version
                      FROM ordering.orders
                     WHERE id = :orderId AND tenant_id = :tenantId
                    """)
                    .param("orderId", orderId)
                    .param("tenantId", tenantId)
                    .query((row, number) -> new OrderSummary(
                            row.getObject("id", UUID.class),
                            row.getObject("tenant_id", UUID.class),
                            row.getObject("brand_id", UUID.class),
                            row.getObject("location_id", UUID.class),
                            row.getString("public_order_number"),
                            null,
                            null,
                            row.getString("status"),
                            row.getString("currency"),
                            row.getLong("total_minor"),
                            row.getInt("version")))
                    .optional();
        }
    }

    /** Toggles the one fact the operations-plane read/push depend on: whether the binding declares ORDER_EXPORT. */
    private static final class StubProviderInstallationLookup implements ProviderInstallationLookup {

        private boolean declaresOrderExport = true;

        private static final BindingRef BINDING_REF = new BindingRef(
                BINDING, INSTALLATION, TENANT, ProviderCategory.POS, FakePosAdapter.PROVIDER_TYPE, BRAND, LOCATION);

        @Override
        public Optional<BindingRef> primaryBinding(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String capabilityCode) {
            if (!declaresOrderExport
                    || !TENANT.equals(tenantId)
                    || !PosCapability.ORDER_EXPORT.code().equals(capabilityCode)) {
                return Optional.empty();
            }
            return Optional.of(BINDING_REF);
        }

        @Override
        public Optional<BindingRef> binding(UUID tenantId, UUID bindingId) {
            return declaresOrderExport && TENANT.equals(tenantId) && BINDING.equals(bindingId)
                    ? Optional.of(BINDING_REF)
                    : Optional.empty();
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
                    null,
                    null,
                    List.of(new ExportableOrder.Line(
                            UUID.randomUUID(), VARIANT, "Fake dish", null, 1, 50_000L, List.of()))));
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
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
