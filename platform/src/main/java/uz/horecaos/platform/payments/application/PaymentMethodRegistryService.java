package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.MethodRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The tenant-scoped payment-method registry (ADR 0038, row 10.6).
 *
 * <p>{@code payments.payment_methods} (V0042) has never had a controller on any
 * surface. Every row that exists today was created lazily, mid-checkout, by
 * {@link JdbcSettlementStore#registerMethod} the first time a tender named a
 * code nobody had registered — "the registry only ever grows by accident,
 * from whatever a checkout happened to tender", the operations gap map's own
 * words for row 10.6. This service is the explicit door: an operator
 * registers a method before any customer can pay through it, chooses its
 * base type (fiscal {@code responsibility}), names it in every platform
 * locale, gives it an icon and a position, and binds it to the ADR 0026
 * installation that settles it — all before {@code tenant.sales_channels}'s
 * own matrix (10.4b, {@code SalesChannelController}) can offer it on a
 * channel, since that matrix's foreign key (V0175) refuses a code this
 * registry has not registered.
 *
 * <p>{@code code} and {@code responsibility} (the "base type") are fixed at
 * creation, the same discipline {@code SalesChannelService} applies to a
 * channel's own code and system type: a tender already snapshots the
 * responsibility at settlement time ({@code CheckoutSettlementPlanner}), and
 * changing which party discharges a method's fiscal obligation after orders
 * have settled against it would rewrite history rather than correct it.
 */
@Service
public class PaymentMethodRegistryService {

    /** {@code ck_payment_method_responsibility}'s own closed set. */
    private static final Set<String> RESPONSIBILITIES = Set.of("PARTNER", "TERMINAL", "MARKETPLACE", "OPERATOR");

    /** {@code ck_payment_method_code}'s own pattern. */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

    private final JdbcSettlementStore store;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;

    public PaymentMethodRegistryService(
            JdbcSettlementStore store, Clock clock, AuditRecorder audit, CurrentActor currentActor) {
        this.store = store;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
    }

    @Transactional
    public PaymentMethodDetail create(UUID tenantId, CreateMethodCommand command) {
        String code = requireValidCode(command.code());
        String responsibility = requireValidResponsibility(command.responsibility());

        Instant now = clock.instant();
        MethodRow row;
        try {
            row = store.insertMethod(
                    tenantId,
                    code,
                    command.displayName(),
                    responsibility,
                    command.icon(),
                    command.sortOrder(),
                    command.providerInstallationId(),
                    command.contractReference(),
                    now);
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSettlementStore.explain(violation);
        }

        audit.record(AuditFact.of("payment-method.registered", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.tenant(tenantId))
                .target("PaymentMethod", row.id())
                .because("Registered payment method '%s' (%s)".formatted(code, responsibility))
                .changed(Map.of("code", code, "responsibility", responsibility))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return PaymentMethodDetail.of(row, Map.of());
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodDetail> list(UUID tenantId) {
        List<MethodRow> rows = store.listMethodsForTenant(tenantId);
        Map<UUID, Map<String, String>> translations = store.methodTranslationsForTenant(tenantId);
        return rows.stream()
                .map(row -> PaymentMethodDetail.of(row, translations.getOrDefault(row.id(), Map.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public MethodRow require(UUID tenantId, UUID methodId) {
        return store.findMethod(tenantId, methodId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No payment method %s for this tenant".formatted(methodId)));
    }

    /** Renames, re-icons, re-orders or re-binds a method. Neither {@code code} nor {@code responsibility} is here. */
    @Transactional
    public PaymentMethodDetail update(UUID tenantId, UUID methodId, UpdateMethodCommand command, int expectedVersion) {
        MethodRow existing = require(tenantId, methodId);
        Instant now = clock.instant();
        boolean updated;
        try {
            updated = store.updateMethod(
                    tenantId,
                    methodId,
                    command.displayName(),
                    command.icon(),
                    command.sortOrder(),
                    command.providerInstallationId(),
                    command.contractReference(),
                    expectedVersion,
                    now);
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSettlementStore.explain(violation);
        }
        if (!updated) {
            throw ApiException.staleVersion(expectedVersion, existing.version());
        }
        audit.record(AuditFact.of("payment-method.updated", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.tenant(tenantId))
                .target("PaymentMethod", methodId)
                .because("Corrected payment method '%s'".formatted(existing.code()))
                .changed(Map.of("displayName", command.displayName()))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return PaymentMethodDetail.of(
                new MethodRow(
                        methodId,
                        existing.code(),
                        command.displayName(),
                        existing.responsibility(),
                        existing.settlesFromBalance(),
                        existing.status(),
                        command.icon(),
                        command.sortOrder(),
                        command.providerInstallationId(),
                        command.contractReference(),
                        expectedVersion + 1),
                store.methodTranslations(tenantId, methodId));
    }

    @Transactional
    public PaymentMethodDetail activate(UUID tenantId, UUID methodId, int expectedVersion) {
        return transitionStatus(tenantId, methodId, "ACTIVE", expectedVersion, "payment-method.activated");
    }

    @Transactional
    public PaymentMethodDetail disable(UUID tenantId, UUID methodId, int expectedVersion) {
        return transitionStatus(tenantId, methodId, "DISABLED", expectedVersion, "payment-method.disabled");
    }

    private PaymentMethodDetail transitionStatus(
            UUID tenantId, UUID methodId, String status, int expectedVersion, String actionCode) {
        MethodRow existing = require(tenantId, methodId);
        Instant now = clock.instant();
        if (!store.updateMethodStatus(tenantId, methodId, status, expectedVersion, now)) {
            throw ApiException.staleVersion(expectedVersion, existing.version());
        }
        audit.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.tenant(tenantId))
                .target("PaymentMethod", methodId)
                .because("%s payment method '%s'".formatted(status, existing.code()))
                .changed(Map.of("status", status))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return PaymentMethodDetail.of(
                new MethodRow(
                        methodId,
                        existing.code(),
                        existing.displayName(),
                        existing.responsibility(),
                        existing.settlesFromBalance(),
                        status,
                        existing.icon(),
                        existing.sortOrder(),
                        existing.providerInstallationId(),
                        existing.contractReference(),
                        expectedVersion + 1),
                store.methodTranslations(tenantId, methodId));
    }

    /** Replaces the method's whole set of localized names -- {@code q-localized-field-group}'s save action. */
    @Transactional
    public PaymentMethodDetail replaceTranslations(UUID tenantId, UUID methodId, Map<String, String> byLocale) {
        MethodRow existing = require(tenantId, methodId);
        Instant now = clock.instant();
        try {
            store.replaceMethodTranslations(tenantId, methodId, byLocale, now);
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSettlementStore.explain(violation);
        }
        audit.record(AuditFact.of("payment-method.localized", AuditClass.BUSINESS)
                .by(actor())
                .at(ResourceScope.tenant(tenantId))
                .target("PaymentMethod", methodId)
                .because("Set localized names for payment method '%s'".formatted(existing.code()))
                .changed(Map.of("locales", String.join(",", byLocale.keySet())))
                .correlatedBy(correlationId())
                .occurredAt(now)
                .build());
        return PaymentMethodDetail.of(existing, byLocale);
    }

    private static String requireValidCode(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A payment method code is upper-case letters, digits and underscores, starting with a letter");
        }
        return normalized;
    }

    private static String requireValidResponsibility(String responsibility) {
        String normalized = responsibility == null ? "" : responsibility.strip().toUpperCase(java.util.Locale.ROOT);
        if (!RESPONSIBILITIES.contains(normalized)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Base type must be one of " + String.join(", ", RESPONSIBILITIES));
        }
        return normalized;
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    public record CreateMethodCommand(
            String code,
            String displayName,
            String responsibility,
            @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Nullable String contractReference) {}

    public record UpdateMethodCommand(
            String displayName,
            @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Nullable String contractReference) {}

    /** What a settings screen shows: the registry row plus its localized names. */
    public record PaymentMethodDetail(
            UUID id,
            String code,
            String displayName,
            Map<String, String> localizedNames,
            String responsibility,
            boolean settlesFromBalance,
            String status,
            @Nullable String icon,
            int sortOrder,
            @Nullable UUID providerInstallationId,
            @Nullable String contractReference,
            int version) {

        static PaymentMethodDetail of(MethodRow row, Map<String, String> localizedNames) {
            return new PaymentMethodDetail(
                    row.id(),
                    row.code(),
                    row.displayName(),
                    Map.copyOf(localizedNames),
                    row.responsibility(),
                    row.settlesFromBalance(),
                    row.status(),
                    row.icon(),
                    row.sortOrder(),
                    row.providerInstallationId(),
                    row.contractReference(),
                    row.version());
        }
    }
}
