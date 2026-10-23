package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLoyaltyBindingStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLoyaltyBindingStore.Row;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore;

/**
 * {@code pos}'s answer to {@code loyalty.api.PosLoyaltyBalancePort} (ADR 0171): the only place a binding id
 * becomes a point-of-sale adapter call for a customer's bonus balance, and the only place a {@link
 * ProviderOutcome} becomes loyalty's own {@link PosLoyaltyBalancePort.Result}.
 *
 * <p>Nothing here decides anything about points. It resolves a binding to its adapter and configuration,
 * asks, and translates. A vendor with no adapter in this build, or an adapter that does not implement the
 * wallet operations, answers {@code NOT_SUPPORTED} -- which the {@code PosAdapter} defaults produce for
 * Clopos without this class knowing the vendor exists. The credential is resolved inside the gateway at call
 * time and never passes through here.
 */
@Service
public class PosLoyaltyBalanceBridge implements PosLoyaltyBalancePort {

    private final JdbcPosLoyaltyBindingStore bindings;
    private final JdbcPosBindingConfiguration configuration;
    private final PosAdapterRegistry adapters;
    private final JdbcPosMappingStore mappings;
    private final Clock clock;

    public PosLoyaltyBalanceBridge(
            JdbcPosLoyaltyBindingStore bindings,
            JdbcPosBindingConfiguration configuration,
            PosAdapterRegistry adapters,
            JdbcPosMappingStore mappings,
            Clock clock) {
        this.bindings = bindings;
        this.configuration = configuration;
        this.adapters = adapters;
        this.mappings = mappings;
        this.clock = clock;
    }

    @Override
    public List<SyncBinding> bindingsOf(UUID tenantId, UUID brandId) {
        return bindings.bindingsOfBrand(tenantId, brandId).stream()
                .map(row -> toBinding(tenantId, brandId, row))
                .toList();
    }

    @Override
    public @Nullable SyncBinding binding(UUID tenantId, UUID bindingId) {
        Optional<BindingRef> ref = configuration.bindingRef(tenantId, bindingId);
        if (ref.isEmpty()) {
            return null;
        }
        return bindings.find(tenantId, bindingId)
                .map(row -> toBinding(tenantId, ref.get().brandId(), row))
                .orElse(null);
    }

    @Override
    public java.util.OptionalLong setSyncMode(UUID tenantId, UUID bindingId, String mode, long expectedVersion) {
        return bindings.setConfigurationValue(tenantId, bindingId, SYNC_MODE_KEY, mode, expectedVersion);
    }

    @Override
    public List<TenantBinding> syncingBindings() {
        return bindings.enabledBindings().stream()
                .map(tenantRow -> new TenantBinding(
                        tenantRow.tenantId(),
                        tenantRow.brandId(),
                        toBinding(tenantRow.tenantId(), tenantRow.brandId(), tenantRow.row())))
                .filter(tenantBinding -> tenantBinding.binding().syncing())
                .toList();
    }

    @Override
    public Optional<UUID> recordMatch(UUID tenantId, UUID bindingId, UUID accountId, String externalCustomerId) {
        Optional<BindingRef> ref = configuration.bindingRef(tenantId, bindingId);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        return mappings.createDiscovered(
                tenantId,
                ref.get().installationId(),
                bindingId,
                MappingEntityType.LOYALTY_CUSTOMER,
                accountId,
                externalCustomerId,
                clock.instant());
    }

    @Override
    public TillCustomerPage listTillCustomers(UUID tenantId, UUID bindingId, @Nullable String cursor) {
        return call(
                tenantId,
                bindingId,
                (adapter, context) -> {
                    PosAdapter.TillCustomersRead read = adapter.listTillCustomers(context, cursor);
                    return new TillCustomerPage(
                            result(read.outcome()),
                            read.customers().stream()
                                    .map(customer -> new TillCustomer(customer.externalCustomerId(), customer.phone()))
                                    .toList(),
                            read.nextCursor());
                },
                new TillCustomerPage(Result.NOT_SUPPORTED, List.of(), null));
    }

    @Override
    public TillBalance readBalance(UUID tenantId, UUID bindingId, String externalCustomerId) {
        return call(
                tenantId,
                bindingId,
                (adapter, context) -> {
                    PosAdapter.TillBalanceRead read = adapter.readBalance(context, externalCustomerId);
                    return new TillBalance(
                            result(read.outcome()), read.balanceMinor(), read.currency(), read.observedAt());
                },
                new TillBalance(Result.NOT_SUPPORTED, null, null, null));
    }

    @Override
    public TillMovementPage readMovements(
            UUID tenantId, UUID bindingId, @Nullable String externalCustomerId, @Nullable String cursor) {
        return call(
                tenantId,
                bindingId,
                (adapter, context) -> {
                    PosAdapter.TillMovementsRead read = adapter.readMovements(context, externalCustomerId, cursor);
                    return new TillMovementPage(
                            result(read.outcome()),
                            read.movements().stream()
                                    .map(movement -> new TillMovement(
                                            movement.externalMovementId(),
                                            movement.externalCustomerId(),
                                            movement.kind() == PosAdapter.TillMovementKind.EARN
                                                    ? MovementKind.EARN
                                                    : MovementKind.SPEND,
                                            movement.amountMinor(),
                                            movement.occurredAt()))
                                    .toList(),
                            read.nextCursor());
                },
                new TillMovementPage(Result.NOT_SUPPORTED, List.of(), null));
    }

    @Override
    public Result writeBalance(
            UUID tenantId,
            UUID bindingId,
            String externalCustomerId,
            long balanceMinor,
            long ledgerVersion,
            String idempotencyKey) {
        return call(
                tenantId,
                bindingId,
                (adapter, context) -> result(
                        adapter.writeBalance(context, externalCustomerId, balanceMinor, ledgerVersion, idempotencyKey)),
                Result.NOT_SUPPORTED);
    }

    // ------------------------------------------------------------------ plumbing

    private interface Call<T> {
        T run(PosAdapter adapter, PosContext context);
    }

    private <T> T call(UUID tenantId, UUID bindingId, Call<T> call, T whenThereIsNoAdapter) {
        Optional<BindingRef> ref = configuration.bindingRef(tenantId, bindingId);
        if (ref.isEmpty()) {
            return whenThereIsNoAdapter;
        }
        Optional<PosAdapter> adapter = adapters.forProvider(ref.get().providerType());
        if (adapter.isEmpty()) {
            return whenThereIsNoAdapter;
        }
        Map<String, String> config = configuration.resolve(ref.get()).orElse(Map.of());
        PosContext context = new PosContext(
                tenantId, ref.get().installationId(), bindingId, null, config, "loyalty-sync-" + bindingId);
        return call.run(adapter.get(), context);
    }

    private static Result result(ProviderOutcome outcome) {
        return switch (outcome.status()) {
            case SUCCESS -> Result.OK;
            case RETRYABLE -> Result.RETRYABLE;
            case UNCERTAIN -> Result.UNCERTAIN;
            case REJECTED -> "NOT_SUPPORTED".equals(outcome.errorCode()) ? Result.NOT_SUPPORTED : Result.REJECTED;
        };
    }

    private SyncBinding toBinding(UUID tenantId, UUID brandId, Row row) {
        CapabilityState state =
                row.supportLevel() == null ? CapabilityState.UNASSESSED : CapabilityState.valueOf(row.supportLevel());
        BindingRef ref = new BindingRef(
                row.bindingId(),
                row.installationId(),
                tenantId,
                ProviderCategory.POS,
                row.providerType(),
                brandId,
                row.locationId());
        Map<String, String> config = configuration.resolve(ref).orElse(Map.of());
        return new SyncBinding(
                row.bindingId(),
                row.installationId(),
                row.providerType(),
                row.locationId(),
                row.bindingStatus(),
                row.version(),
                state,
                row.enabled(),
                row.limits(),
                config);
    }
}
