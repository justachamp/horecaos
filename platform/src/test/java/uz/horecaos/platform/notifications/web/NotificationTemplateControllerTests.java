package uz.horecaos.platform.notifications.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.TemplateTestSendService;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Gap map rows {@code 10.9a}/{@code 10.9c}/{@code X.27} (wave P36).
 *
 * <p>Against the migrated schema, the same direct-construction shape {@code
 * SmsWordingModerationTests} already uses for this controller's own service
 * layer — a real {@code JdbcTemplateStore} over a real PostgreSQL, the
 * controller instantiated directly rather than through a Spring context
 * (its {@code @RequiresCapability} annotations are enforced by an
 * interceptor this test does not need to prove).
 */
class NotificationTemplateControllerTests {

    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private NotificationTemplateController controller;
    private UUID tenantId;
    private UUID brandId;
    private UUID otherBrandId;
    private FakeTransport transport;

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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        // Cascades to installations/bindings/binding_capabilities (all FK back
        // to a code here) and clears the seeded smsgw_vas_production row along
        // with it. A single class-wide database (TestDatabase.migrated() is
        // called once in @BeforeAll) means every @Test method shares this
        // table otherwise: a second bindSmsTo("moderating-gateway", ...) call
        // would collide on its own previous insert, and
        // smsWordingAwaitsGateway's own tenant-less fallback branch would see
        // the seeded row's moderation state leak into a test that bound no
        // gateway of its own at all.
        jdbc.sql("TRUNCATE TABLE integration.provider_environments CASCADE").update();

        JdbcTemplateStore store = new JdbcTemplateStore(jdbc);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NotificationTemplateService service =
                new NotificationTemplateService(store, JsonMapper.builder().build(), clock);
        transport = new FakeTransport();
        TemplateTestSendService testSend = new TemplateTestSendService(store, transport);
        CurrentActor currentActor = () -> new AuthenticatedActor("tester", java.util.Set.of(), Map.of());
        controller = new NotificationTemplateController(service, testSend, currentActor);

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'p36', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'p36-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();

        otherBrandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'SIBLING', 'p36-sibling-brand', 'Sibling brand', 'ACTIVE')
                """).param("id", otherBrandId).param("tenantId", tenantId).update();
    }

    @Test
    @DisplayName(
            "provider_review, its reference/note/timestamp and the stored schema reach the tenant-facing WordingResponse")
    void providerReviewAndSchemaReachTheTenantResponse() {
        bindSmsTo("moderating-gateway", true);
        UUID templateId = createSmsTemplate("OTP_CODE");

        NotificationTemplateController.AddVersionRequest request = new NotificationTemplateController.AddVersionRequest(
                Map.of(
                        "ru", new NotificationTemplateController.WordingRequest(null, "Код {{code}}"),
                        "uz-Latn", new NotificationTemplateController.WordingRequest(null, "Kod {{code}}"),
                        "en", new NotificationTemplateController.WordingRequest(null, "Code {{code}}")),
                Map.of("code", "string"));

        NotificationTemplateController.VersionResponse saved = Objects.requireNonNull(
                controller.addVersion(tenantId, brandId, templateId, request).getBody());

        assertThat(saved.awaitsProviderReview())
                .as("ADR 0091: told at save time, not discovered after activating")
                .isTrue();

        List<NotificationTemplateController.WordingResponse> rows = Objects.requireNonNull(controller
                .version(tenantId, brandId, templateId, saved.versionNumber())
                .getBody());
        assertThat(rows).hasSize(3);
        NotificationTemplateController.WordingResponse ru = rows.stream()
                .filter(row -> "ru".equals(row.locale()))
                .findFirst()
                .orElseThrow();

        assertThat(ru.providerReview()).isEqualTo("PENDING");
        assertThat(ru.providerReviewNote()).isNotBlank();
        assertThat(ru.providerReviewUpdatedAt()).isNotNull();
        // The defect this wave fixes: the page used to save {} regardless of
        // what an author typed. The stored schema now round-trips on GET.
        assertThat(ru.variablesSchema()).containsExactly(Map.entry("code", "string"));
    }

    @Test
    @DisplayName("the versions list (X.27) reads back every version a create-only editor could never see again")
    void allVersionsAreReadable() {
        UUID templateId = createSmsTemplate("ORDER_CONFIRMED");
        controller
                .addVersion(tenantId, brandId, templateId, wordings("Заказ принят"))
                .getBody();
        controller
                .addVersion(tenantId, brandId, templateId, wordings("Заказ подтверждён"))
                .getBody();

        List<NotificationTemplateController.WordingResponse> all = Objects.requireNonNull(
                controller.versions(tenantId, brandId, templateId).getBody());

        assertThat(all.stream()
                        .map(NotificationTemplateController.WordingResponse::versionNumber)
                        .distinct())
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("the per-notification-class variable catalogue is real, fixing the empty-schema defect")
    void variableCatalogueIsPublished() {
        List<NotificationTemplateController.VariableCatalogueEntry> catalogue =
                controller.variableCatalogue().getBody();

        assertThat(catalogue).isNotNull().isNotEmpty();
        assertThat(catalogue).anySatisfy(entry -> {
            assertThat(entry.notificationClass()).isEqualTo("TRANSACTIONAL_REQUIRED");
            assertThat(entry.variables())
                    .extracting(NotificationTemplateController.VariableCatalogueVariable::name)
                    .contains("orderNumber", "amount", "currency");
        });
    }

    @Test
    @DisplayName("a test send is refused, with the reason named, for a version awaiting its SMS gateway (ADR 0091)")
    void testSendRefusesAWithheldVersion() {
        bindSmsTo("moderating-gateway", true);
        UUID templateId = createSmsTemplate("OTP_CODE");
        NotificationTemplateController.VersionResponse saved = Objects.requireNonNull(controller
                .addVersion(tenantId, brandId, templateId, wordingsWithSchema("Код {{code}}", "code"))
                .getBody());

        NotificationTemplateController.TestSendRequest testSendRequest =
                new NotificationTemplateController.TestSendRequest("ru", "+998901234567");

        assertThatThrownBy(() ->
                        controller.testSend(tenantId, brandId, templateId, saved.versionNumber(), testSendRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("awaiting");
        assertThat(transport.lastDispatch)
                .as("refused before ever reaching the transport")
                .isNull();
    }

    @Test
    @DisplayName("a test send renders sample values and reaches the transport for a wording that needs no review")
    void testSendReachesTheTransportWhenNotWithheld() {
        UUID templateId = createSmsTemplate("ORDER_CONFIRMED");
        NotificationTemplateController.VersionResponse saved = Objects.requireNonNull(controller
                .addVersion(
                        tenantId,
                        brandId,
                        templateId,
                        wordingsWithSchema("Order {{orderNumber}} confirmed", "orderNumber"))
                .getBody());
        assertThat(saved.awaitsProviderReview()).isFalse();

        NotificationTemplateController.TestSendResponse response = Objects.requireNonNull(controller
                .testSend(
                        tenantId,
                        brandId,
                        templateId,
                        saved.versionNumber(),
                        new NotificationTemplateController.TestSendRequest("en", "+998901234567"))
                .getBody());

        assertThat(response.status()).isEqualTo("ACCEPTED");
        NotificationDispatch dispatch = Objects.requireNonNull(transport.lastDispatch);
        assertThat(dispatch.body()).contains("A-1042");
        assertThat(dispatch.recipientValue()).isEqualTo("+998901234567");
    }

    // -------------------------------------------- P36 second-pass adversarial review

    /**
     * The core cross-brand isolation gap: every endpoint here declares a
     * BRAND-scoped capability, so {@code brandId} in the URL only proves the
     * caller was authorised for {@code brandId} -- never that {@code
     * templateId} actually belongs to it. A template created under {@code
     * brandId} must be unreachable through {@code otherBrandId}'s own path,
     * for every one of addVersion/activate/versions/version/testSend.
     */
    @Test
    @DisplayName("addVersion refuses a templateId that belongs to a sibling brand")
    void addVersionRefusesASiblingBrand() {
        UUID templateId = createSmsTemplate("ORDER_READY");

        assertThatThrownBy(() -> controller.addVersion(tenantId, otherBrandId, templateId, wordings("Готово")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("activate refuses a templateId that belongs to a sibling brand")
    void activateRefusesASiblingBrand() {
        UUID templateId = createSmsTemplate("ORDER_READY");
        NotificationTemplateController.VersionResponse saved =
                Objects.requireNonNull(controller.addVersion(tenantId, brandId, templateId, wordings("Готово"))
                        .getBody());

        assertThatThrownBy(() -> controller.activate(tenantId, otherBrandId, templateId, saved.versionNumber()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the versions list refuses a templateId that belongs to a sibling brand")
    void versionsListRefusesASiblingBrand() {
        UUID templateId = createSmsTemplate("ORDER_READY");
        controller.addVersion(tenantId, brandId, templateId, wordings("Готово"));

        assertThatThrownBy(() -> controller.versions(tenantId, otherBrandId, templateId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("one version refuses a templateId that belongs to a sibling brand")
    void oneVersionRefusesASiblingBrand() {
        UUID templateId = createSmsTemplate("ORDER_READY");
        NotificationTemplateController.VersionResponse saved =
                Objects.requireNonNull(controller.addVersion(tenantId, brandId, templateId, wordings("Готово"))
                        .getBody());

        assertThatThrownBy(() -> controller.version(tenantId, otherBrandId, templateId, saved.versionNumber()))
                .isInstanceOf(ApiException.class);
    }

    /**
     * The urgent half: a real outbound SMS. Without the fix, an actor
     * authorised only for {@code otherBrandId} could render {@code brandId}'s
     * private wording and reach the transport -- a live side effect and a
     * content leak, not merely a read.
     */
    @Test
    @DisplayName("testSend refuses a templateId that belongs to a sibling brand, and never reaches the transport")
    void testSendRefusesASiblingBrand() {
        UUID templateId = createSmsTemplate("OTP_CODE");
        NotificationTemplateController.VersionResponse saved = Objects.requireNonNull(controller
                .addVersion(tenantId, brandId, templateId, wordingsWithSchema("Код {{code}}", "code"))
                .getBody());

        assertThatThrownBy(() -> controller.testSend(
                        tenantId,
                        otherBrandId,
                        templateId,
                        saved.versionNumber(),
                        new NotificationTemplateController.TestSendRequest("ru", "+998901234567")))
                .isInstanceOf(ApiException.class);
        assertThat(transport.lastDispatch)
                .as("a refused cross-brand test send must never reach the transport")
                .isNull();
    }

    @Test
    @DisplayName("every endpoint still works normally through the template's own brand")
    void everyEndpointStillWorksThroughItsOwnBrand() {
        UUID templateId = createSmsTemplate("ORDER_READY");
        NotificationTemplateController.VersionResponse saved =
                Objects.requireNonNull(controller.addVersion(tenantId, brandId, templateId, wordings("Готово"))
                        .getBody());

        assertThat(controller.versions(tenantId, brandId, templateId).getBody()).isNotEmpty();
        assertThat(controller.version(tenantId, brandId, templateId, saved.versionNumber()).getBody())
                .isNotEmpty();
        assertThat(controller.activate(tenantId, brandId, templateId, saved.versionNumber()).getStatusCode().value())
                .isEqualTo(204);
    }

    // ------------------------------------------------------------- fixtures

    private UUID createSmsTemplate(String key) {
        return Objects.requireNonNull(controller
                        .create(
                                tenantId,
                                brandId,
                                new NotificationTemplateController.CreateTemplateRequest(
                                        key, NotificationClass.TRANSACTIONAL_REQUIRED, NotificationChannel.SMS, null))
                        .getBody())
                .id();
    }

    private NotificationTemplateController.AddVersionRequest wordings(String body) {
        return wordingsWithSchema(body, null);
    }

    private NotificationTemplateController.AddVersionRequest wordingsWithSchema(
            String body, @Nullable String declaredVariable) {
        Map<String, NotificationTemplateController.WordingRequest> byLocale = new LinkedHashMap<>();
        for (MessageLocale locale : MessageLocale.required()) {
            byLocale.put(locale.tag(), new NotificationTemplateController.WordingRequest(null, body));
        }
        Map<String, String> schema = declaredVariable == null ? Map.of() : Map.of(declaredVariable, "string");
        return new NotificationTemplateController.AddVersionRequest(byLocale, schema);
    }

    private void bindSmsTo(String environmentCode, boolean moderates) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist,
                    moderates_wordings)
                VALUES (:code, 'NOTIFICATION', 'SMSGW_QUIET', 'http://127.0.0.1:1', false, '127.0.0.1', :moderates)
                """)
                .param("code", environmentCode)
                .param("moderates", moderates)
                .update();
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code,
                    display_name, status, secret_reference, webhook_secret_reference)
                VALUES (:id, :tenantId, 'NOTIFICATION', 'SMSGW_QUIET', :code,
                        'Quiet gateway', 'ACTIVE', 'horecaos:local:provider_notification:platform:quiet',
                        'horecaos:local:provider_notification:platform:quiet')
                """)
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("code", environmentCode)
                .update();
        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", bindingId)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities (binding_id, tenant_id, capability_code, is_primary)
                VALUES (:bindingId, :tenantId, 'SEND_SMS', true)
                """).param("bindingId", bindingId).param("tenantId", tenantId).update();
    }

    /** Records the one dispatch a test send makes, never reaching a real provider. */
    private static final class FakeTransport implements NotificationTransport {
        private @Nullable NotificationDispatch lastDispatch;

        @Override
        public DispatchOutcome dispatch(NotificationDispatch dispatch) {
            this.lastDispatch = dispatch;
            return DispatchOutcome.accepted("ext-1", "queued");
        }

        @Override
        public DispatchOutcome reconcile(
                UUID tenantId, UUID brandId, @Nullable UUID locationId, String channel, String providerIdempotencyKey) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public boolean supports(String channel) {
            return true;
        }
    }
}
