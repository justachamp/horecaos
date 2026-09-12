package uz.horecaos.platform.fiscal.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A restaurant's own fiscal-capable equipment (ADR 0038 lines 503-513).
 *
 * <p>The row that lets {@code CheckoutSettlementPlanner.responsibilityOf}
 * declare a cash tender's fiscal responsibility as {@code TERMINAL} instead of
 * the wrong-direction {@code OPERATOR} fallback its own doc comment names, and
 * the row Settings 10.7 Tab 2 has had a schema to render and nothing to call.
 *
 * <p>{@link #capabilitySnapshot()} is keyed by capability name, the same
 * convention {@code integration.installations.capability_snapshot} uses.
 * {@link #ISSUE_FISCAL_RECEIPT} is the one capability code this build reads:
 * whether a fiscal document can be requested from this box right now. A
 * terminal cannot claim it without a provider binding — see
 * {@link #capable()} — because an unverified claim is an operator's typo away
 * from a receipt that was never actually requested.
 */
public final class FiscalTerminal {

    /** The one capability code {@code responsibilityOf} and the coverage view both read. */
    public static final String ISSUE_FISCAL_RECEIPT = "IssueFiscalReceipt";

    private final UUID id;
    private final UUID tenantId;
    private final UUID brandId;
    private final UUID locationId;
    private UUID legalEntityId;
    private final FiscalTerminalKind kind;
    private @Nullable UUID providerBindingId;
    private String terminalReference;
    private Map<String, Boolean> capabilitySnapshot;
    private FiscalTerminalStatus status;
    private @Nullable Instant lastHealthCheckAt;
    private @Nullable FiscalTerminalHealth lastHealthStatus;
    private final int version;

    private FiscalTerminal(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID legalEntityId,
            FiscalTerminalKind kind,
            @Nullable UUID providerBindingId,
            String terminalReference,
            Map<String, Boolean> capabilitySnapshot,
            FiscalTerminalStatus status,
            @Nullable Instant lastHealthCheckAt,
            @Nullable FiscalTerminalHealth lastHealthStatus,
            int version) {
        this.id = Objects.requireNonNull(id, "Fiscal terminal ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.brandId = Objects.requireNonNull(brandId, "Brand ID is required");
        this.locationId = Objects.requireNonNull(locationId, "Location ID is required");
        this.legalEntityId = Objects.requireNonNull(legalEntityId, "Legal entity ID is required");
        this.kind = Objects.requireNonNull(kind, "Terminal kind is required");
        this.providerBindingId = providerBindingId;
        this.terminalReference = requireReference(terminalReference);
        this.capabilitySnapshot = requireBindingForCapability(providerBindingId, Map.copyOf(capabilitySnapshot));
        this.status = Objects.requireNonNull(status, "Terminal status is required");
        this.lastHealthCheckAt = lastHealthCheckAt;
        this.lastHealthStatus = lastHealthStatus;
        this.version = version;
    }

    public static FiscalTerminal register(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID legalEntityId,
            FiscalTerminalKind kind,
            @Nullable UUID providerBindingId,
            String terminalReference,
            Map<String, Boolean> capabilitySnapshot) {
        return new FiscalTerminal(
                id,
                tenantId,
                brandId,
                locationId,
                legalEntityId,
                kind,
                providerBindingId,
                terminalReference,
                capabilitySnapshot,
                FiscalTerminalStatus.ACTIVE,
                null,
                null,
                1);
    }

    public static FiscalTerminal reconstitute(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID legalEntityId,
            FiscalTerminalKind kind,
            @Nullable UUID providerBindingId,
            String terminalReference,
            Map<String, Boolean> capabilitySnapshot,
            FiscalTerminalStatus status,
            @Nullable Instant lastHealthCheckAt,
            @Nullable FiscalTerminalHealth lastHealthStatus,
            int version) {
        return new FiscalTerminal(
                id,
                tenantId,
                brandId,
                locationId,
                legalEntityId,
                kind,
                providerBindingId,
                terminalReference,
                capabilitySnapshot,
                status,
                lastHealthCheckAt,
                lastHealthStatus,
                version);
    }

    /** Records the outcome of an operator-triggered "Проверить связь" check. */
    public void recordHealthCheck(FiscalTerminalHealth outcome, Instant checkedAt) {
        this.lastHealthStatus = Objects.requireNonNull(outcome, "A health check records an outcome");
        this.lastHealthCheckAt = Objects.requireNonNull(checkedAt, "A health check records when it ran");
    }

    /** Settings 10.7 Tab 2's "Отключить". Reversible: {@link #reactivate()} undoes it. */
    public void suspend() {
        requireStatus(FiscalTerminalStatus.ACTIVE);
        status = FiscalTerminalStatus.SUSPENDED;
    }

    public void reactivate() {
        requireStatus(FiscalTerminalStatus.SUSPENDED);
        status = FiscalTerminalStatus.ACTIVE;
    }

    /**
     * Permanently withdraws this terminal. Permitted from any status: unlike a
     * legal entity, a terminal can be scrapped without first being suspended —
     * an operator who hands back a rented POS box has no reason to click twice.
     */
    public void retire() {
        status = FiscalTerminalStatus.RETIRED;
    }

    /**
     * Whether this box can be asked for a fiscal receipt right now.
     *
     * <p>The exact predicate {@code CheckoutSettlementPlanner.responsibilityOf}
     * and the activation precondition both need: {@link FiscalTerminalStatus#ACTIVE}
     * and {@link #ISSUE_FISCAL_RECEIPT} present and true in the snapshot. The
     * database index and the {@code ck_fiscal_terminal_capable_needs_binding}
     * constraint mirror this so a query never has to trust an application-only
     * rule.
     */
    public boolean capable() {
        return status == FiscalTerminalStatus.ACTIVE
                && Boolean.TRUE.equals(capabilitySnapshot.get(ISSUE_FISCAL_RECEIPT));
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID brandId() {
        return brandId;
    }

    public UUID locationId() {
        return locationId;
    }

    public UUID legalEntityId() {
        return legalEntityId;
    }

    public FiscalTerminalKind kind() {
        return kind;
    }

    public @Nullable UUID providerBindingId() {
        return providerBindingId;
    }

    public String terminalReference() {
        return terminalReference;
    }

    public Map<String, Boolean> capabilitySnapshot() {
        return capabilitySnapshot;
    }

    public FiscalTerminalStatus status() {
        return status;
    }

    public @Nullable Instant lastHealthCheckAt() {
        return lastHealthCheckAt;
    }

    public @Nullable FiscalTerminalHealth lastHealthStatus() {
        return lastHealthStatus;
    }

    public int version() {
        return version;
    }

    private static String requireReference(String reference) {
        String stripped = Objects.requireNonNull(reference, "A terminal reference is required")
                .strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("A terminal reference cannot be blank");
        }
        return stripped;
    }

    private static Map<String, Boolean> requireBindingForCapability(
            @Nullable UUID providerBindingId, Map<String, Boolean> snapshot) {
        if (providerBindingId == null && Boolean.TRUE.equals(snapshot.get(ISSUE_FISCAL_RECEIPT))) {
            throw new IllegalArgumentException(
                    "A terminal cannot claim " + ISSUE_FISCAL_RECEIPT + " without a provider binding");
        }
        return snapshot;
    }

    private void requireStatus(FiscalTerminalStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Fiscal terminal " + id + " cannot move from " + status + " as though it were " + expected);
        }
    }
}
