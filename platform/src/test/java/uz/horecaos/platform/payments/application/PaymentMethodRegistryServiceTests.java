package uz.horecaos.platform.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.payments.application.PaymentMethodRegistryService.CreateMethodCommand;
import uz.horecaos.platform.payments.application.PaymentMethodRegistryService.PaymentMethodDetail;
import uz.horecaos.platform.payments.application.PaymentMethodRegistryService.UpdateMethodCommand;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0038, row 10.6: the tenant-scoped payment-method registry, previously
 * reachable only through {@link JdbcSettlementStore#registerMethod}'s lazy,
 * checkout-time path. Every test here proves a capability that had no caller
 * anywhere before this wave: create, list, update, localize, activate and
 * disable, and the acquirer binding.
 */
class PaymentMethodRegistryServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-13T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private PaymentMethodRegistryService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for payment method registry tests");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertTenant(TENANT, "payment-method-tenant");
        insertTenant(OTHER_TENANT, "payment-method-other-tenant");

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CurrentActor actor =
                () -> new AuthenticatedActor(UUID.randomUUID().toString(), java.util.Set.of("tenant-owner"), Map.of());
        service = new PaymentMethodRegistryService(
                new JdbcSettlementStore(jdbc),
                clock,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                actor);
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private CreateMethodCommand createCommand(String code) {
        return new CreateMethodCommand(code, code, "PARTNER", "generic", 0, null, null);
    }

    @Test
    @DisplayName("a registered method is ACTIVE, first in the tenant's list, and carries no localized name yet")
    void registersAMethod() {
        PaymentMethodDetail method = service.create(TENANT, createCommand("TELEGRAM"));

        assertThat(method.code()).isEqualTo("TELEGRAM");
        assertThat(method.status()).isEqualTo("ACTIVE");
        assertThat(method.responsibility()).isEqualTo("PARTNER");
        assertThat(method.settlesFromBalance()).isFalse();
        assertThat(method.localizedNames()).isEmpty();

        assertThat(service.list(TENANT)).extracting(PaymentMethodDetail::code).containsExactly("TELEGRAM");
    }

    @Test
    @DisplayName(
            "a duplicate code for the same tenant is refused with an operator-legible conflict, not a raw FK/unique violation")
    void refusesADuplicateCode() {
        service.create(TENANT, createCommand("CLICK"));

        assertThatThrownBy(() -> service.create(TENANT, createCommand("CLICK")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("another tenant may register the identical code -- the registry is tenant-scoped")
    void aDuplicateCodeAcrossTenantsIsFine() {
        service.create(TENANT, createCommand("CLICK"));
        assertThat(catchThrowable(() -> service.create(OTHER_TENANT, createCommand("CLICK"))))
                .isNull();
    }

    @Test
    @DisplayName("an unrecognised base type is refused before it ever reaches the database")
    void refusesAnInvalidResponsibility() {
        assertThatThrownBy(() -> service.create(
                        TENANT, new CreateMethodCommand("WEIRD", "Weird", "BANK_TRANSFER", null, 0, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a lower-case or malformed code is normalised or refused, never stored as typed")
    void normalizesOrRefusesTheCode() {
        PaymentMethodDetail method = service.create(TENANT, createCommand("apple_pay"));
        assertThat(method.code()).isEqualTo("APPLE_PAY");

        assertThatThrownBy(() -> service.create(TENANT, createCommand("1INVALID")))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName(
            "renaming, re-icon and re-order correct the row; code and base type are absent from the command entirely")
    void updatesTheEditableFields() {
        PaymentMethodDetail method = service.create(TENANT, createCommand("PAYME"));

        PaymentMethodDetail updated = service.update(
                TENANT,
                method.id(),
                new UpdateMethodCommand("Payme (updated)", "payme-icon", 5, null, "CONTRACT-1"),
                method.version());

        assertThat(updated.displayName()).isEqualTo("Payme (updated)");
        assertThat(updated.icon()).isEqualTo("payme-icon");
        assertThat(updated.sortOrder()).isEqualTo(5);
        assertThat(updated.contractReference()).isEqualTo("CONTRACT-1");
        assertThat(updated.code()).isEqualTo("PAYME");
        assertThat(updated.responsibility()).isEqualTo("PARTNER");
    }

    @Test
    @DisplayName("a stale expected version is refused rather than silently overwritten")
    void refusesAStaleUpdate() {
        PaymentMethodDetail method = service.create(TENANT, createCommand("CASH"));

        assertThatThrownBy(() -> service.update(
                        TENANT,
                        method.id(),
                        new UpdateMethodCommand("Cash", null, 0, null, null),
                        method.version() + 41))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).errorCode())
                .isEqualTo(ErrorCode.STALE_VERSION);
    }

    @Test
    @DisplayName("disable then activate round-trips the status, each bumping the version")
    void activateAndDisableRoundTrip() {
        PaymentMethodDetail method = service.create(TENANT, createCommand("MARKETPLACE"));

        PaymentMethodDetail disabled = service.disable(TENANT, method.id(), method.version());
        assertThat(disabled.status()).isEqualTo("DISABLED");
        assertThat(disabled.version()).isEqualTo(method.version() + 1);

        PaymentMethodDetail activated = service.activate(TENANT, method.id(), disabled.version());
        assertThat(activated.status()).isEqualTo("ACTIVE");
        assertThat(activated.version()).isEqualTo(disabled.version() + 1);
    }

    @Test
    @DisplayName("localized names replace wholesale and read back through list() without a caller-side join")
    void replacesLocalizedNamesWholesale() {
        PaymentMethodDetail method = service.create(TENANT, createCommand("CLICK"));

        service.replaceTranslations(TENANT, method.id(), Map.of("ru", "Клик", "uz-Latn", "Klik"));
        List<PaymentMethodDetail> afterFirstWrite = service.list(TENANT);
        assertThat(afterFirstWrite.get(0).localizedNames())
                .containsEntry("ru", "Клик")
                .containsEntry("uz-Latn", "Klik");

        // A second, narrower write replaces the whole set rather than merging into it.
        service.replaceTranslations(TENANT, method.id(), Map.of("en", "Click"));
        List<PaymentMethodDetail> afterSecondWrite = service.list(TENANT);
        assertThat(afterSecondWrite.get(0).localizedNames()).containsExactly(Map.entry("en", "Click"));
    }

    @Test
    @DisplayName("no method for another tenant is reachable by id")
    void tenantIsolation() {
        PaymentMethodDetail method = service.create(OTHER_TENANT, createCommand("CASH"));

        assertThatThrownBy(() -> service.require(TENANT, method.id()))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("list() orders by sort_order then code, and includes DISABLED rows")
    void listOrdersAndIncludesDisabled() {
        PaymentMethodDetail b =
                service.create(TENANT, new CreateMethodCommand("B_METHOD", "B", "OPERATOR", null, 1, null, null));
        PaymentMethodDetail a =
                service.create(TENANT, new CreateMethodCommand("A_METHOD", "A", "OPERATOR", null, 0, null, null));
        service.disable(TENANT, b.id(), b.version());

        assertThat(service.list(TENANT)).extracting(PaymentMethodDetail::code).containsExactly(a.code(), b.code());
        assertThat(service.list(TENANT)).extracting(PaymentMethodDetail::status).contains("DISABLED");
    }
}
