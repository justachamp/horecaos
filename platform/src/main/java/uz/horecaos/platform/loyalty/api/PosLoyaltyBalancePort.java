package uz.horecaos.platform.loyalty.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How loyalty asks a point of sale about a customer's bonus balance, without being able to name a
 * point-of-sale type (ADR 0171, row 6.3b).
 *
 * <p>The shape of {@code fiscal.api.PartnerFiscalizationPort}: declared here by the module that needs
 * the answer, implemented in {@code pos}, so {@code loyalty} never imports a POS type and {@code pos}
 * imports only this package. Every type below is loyalty's own, which is why there is a {@code Result}
 * here and not a {@code ProviderOutcome}.
 *
 * <p><strong>What crosses this seam, and what does not.</strong> A binding id, a till customer id, a
 * balance, a movement. Nothing about a platform customer is sent to the till (ADR 0171 Decision 7), no
 * account is created from a till record, and the phone a {@link TillCustomer} carries is for one use --
 * finding the platform account -- and is hidden from {@code toString} so it cannot reach a log by
 * accident.
 *
 * <p>A vendor without the capability answers {@link Result#NOT_SUPPORTED} and {@link
 * #capabilityState} reports it as {@link CapabilityState#UNASSESSED} or {@link
 * CapabilityState#UNSUPPORTED}: reported honestly, never as working.
 */
public interface PosLoyaltyBalancePort {

    /** What the vendor ceiling says about {@code LOYALTY_BALANCE_SYNC}. */
    enum CapabilityState {
        SUPPORTED,
        PARTIAL,
        UNSUPPORTED,
        /** Nobody has assessed this vendor, so the database refuses to enable the capability on it. */
        UNASSESSED;

        /** Whether the capability may be enabled on a binding of this vendor at all. */
        public boolean configurable() {
            return this == SUPPORTED || this == PARTIAL;
        }
    }

    /** How a call to the till ended, in loyalty's terms. */
    enum Result {
        OK,
        /** The till refused on business grounds; asking again changes nothing. */
        REJECTED,
        /** A transport or provider fault, safe to try again later under the same key. */
        RETRYABLE,
        /** The till may or may not have acted: resolve it with a read, never with a second write. */
        UNCERTAIN,
        /** The vendor has no such operation. */
        NOT_SUPPORTED
    }

    /**
     * Every point-of-sale binding of one brand, with the state of the capability at its vendor and whether
     * it is enabled on the binding. Includes bindings that cannot sync at all: a tenant is told that.
     */
    List<SyncBinding> bindingsOf(UUID tenantId, UUID brandId);

    /** One binding of this tenant, or null when it is not a POS binding of this tenant. */
    @Nullable
    SyncBinding binding(UUID tenantId, UUID bindingId);

    /**
     * Every active POS binding, across tenants, that has {@code LOYALTY_BALANCE_SYNC} enabled and can sync: what the
     * scheduler walks. Cross-tenant by design, the way a retention sweep is; every call it makes afterwards carries the
     * tenant it returned.
     */
    List<TenantBinding> syncingBindings();

    record TenantBinding(UUID tenantId, UUID brandId, SyncBinding binding) {}

    /**
     * Records the ADR 0026 mapping ({@code LOYALTY_CUSTOMER}, source {@code DISCOVERED}) pairing a till customer
     * with a points account.
     *
     * @return the mapping's id, or empty when either side is already claimed on this binding -- reported by the
     *         caller, never overwritten
     */
    Optional<UUID> recordMatch(UUID tenantId, UUID bindingId, UUID accountId, String externalCustomerId);

    TillCustomerPage listTillCustomers(UUID tenantId, UUID bindingId, @Nullable String cursor);

    TillBalance readBalance(UUID tenantId, UUID bindingId, String externalCustomerId);

    TillMovementPage readMovements(
            UUID tenantId, UUID bindingId, @Nullable String externalCustomerId, @Nullable String cursor);

    /** Sets the till's balance to {@code balanceMinor}, the platform's number at {@code ledgerVersion}. */
    Result writeBalance(
            UUID tenantId,
            UUID bindingId,
            String externalCustomerId,
            long balanceMinor,
            long ledgerVersion,
            String idempotencyKey);

    /**
     * @param enabled whether {@code LOYALTY_BALANCE_SYNC} is enabled on this binding (and so whether it
     *                syncs); false for a vendor that cannot be given it
     * @param limits  the ceiling row's sub-properties (reads_balance, credits, idempotency ...), empty when
     *                the vendor is unassessed
     * @param configuration the binding's non-sensitive configuration (installation merged with the binding's
     *                override), for the keys loyalty reads: {@code loyalty.sync.mode}. Never a credential
     */
    record SyncBinding(
            UUID bindingId,
            UUID installationId,
            String providerType,
            @Nullable UUID locationId,
            String bindingStatus,
            CapabilityState state,
            boolean enabled,
            Map<String, Object> limits,
            Map<String, String> configuration) {

        public SyncBinding {
            limits = Map.copyOf(limits == null ? Map.of() : limits);
            configuration = Map.copyOf(configuration == null ? Map.of() : configuration);
        }

        /** Whether this binding is syncing now: capable, enabled and active. */
        public boolean syncing() {
            return enabled && state.configurable() && "ACTIVE".equals(bindingStatus);
        }
    }

    /** A till customer. The phone is used once, to find an account, and is never stored, logged or shown. */
    record TillCustomer(String externalCustomerId, @Nullable String phone) {

        @Override
        public String toString() {
            return "TillCustomer[external=" + externalCustomerId + "]";
        }
    }

    record TillCustomerPage(
            Result result,
            List<TillCustomer> customers,
            @Nullable String nextCursor) {

        public TillCustomerPage {
            customers = List.copyOf(customers == null ? List.of() : customers);
        }
    }

    record TillBalance(
            Result result,
            @Nullable Long balanceMinor,
            @Nullable String currency,
            @Nullable Instant observedAt) {}

    enum MovementKind {
        EARN,
        SPEND
    }

    record TillMovement(
            String externalMovementId,
            String externalCustomerId,
            MovementKind kind,
            long amountMinor,
            @Nullable Instant occurredAt) {}

    record TillMovementPage(
            Result result,
            List<TillMovement> movements,
            @Nullable String nextCursor) {

        public TillMovementPage {
            movements = List.copyOf(movements == null ? List.of() : movements);
        }
    }
}
