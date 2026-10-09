package uz.horecaos.platform.tenancy.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.ApproveEnrolment;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.PendingEnrolmentView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.KioskDeviceLookup;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskDeviceStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskDeviceStore.BindingRow;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskDeviceStore.BranchSummary;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskDeviceStore.ChannelChoice;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Pairing, listing, re-binding and revoking a kiosk, and the one read a paired kiosk makes (ADR 0162).
 *
 * <p>A kiosk is an ADR 0079 device principal of class {@link DevicePrincipalClass#KIOSK} plus a row binding it
 * to one KIOSK sales channel at the one branch it is enrolled at. Everything about the principal (Keycloak
 * client, grant, revocation) is {@code iam}'s, reached through {@link DeviceEnrolmentPort}; what this class
 * owns is the decision that is specifically tenancy's: which channel the screen sells through, and whether
 * that channel is one a customer-facing device may sit on at that branch.
 *
 * <p>Approval takes the class and nothing else: the role a kiosk holds follows the approved class inside {@code
 * iam}, so this module cannot name one, and a code that asked to be a kitchen display is refused here by name
 * rather than approved with the kiosk's role. The principal is given nothing to order with.
 */
@Service
public class KioskDeviceService implements KioskDeviceLookup {

    /** ADR 0162's counter: only the outcome, never a tenant, branch or device. */
    public static final String PAIRINGS = "horecaos.kiosk.pairings";

    /** ADR 0162's gauge: active kiosks across the platform. */
    public static final String DEVICES_ACTIVE = "horecaos.kiosk.devices.active";

    private final DeviceEnrolmentPort enrolment;
    private final JdbcKioskDeviceStore store;
    private final JdbcSalesChannelStore channelStore;
    private final SalesChannelLookup channels;
    private final ServiceabilityResolver serviceability;
    private final StaffDirectory staff;
    private final ConfigurationResolver configuration;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final Clock clock;

    public KioskDeviceService(
            DeviceEnrolmentPort enrolment,
            JdbcKioskDeviceStore store,
            JdbcSalesChannelStore channelStore,
            SalesChannelLookup channels,
            ServiceabilityResolver serviceability,
            StaffDirectory staff,
            ConfigurationResolver configuration,
            AuditRecorder audit,
            MeterRegistry meters,
            Clock clock) {
        this.enrolment = enrolment;
        this.store = store;
        this.channelStore = channelStore;
        this.channels = channels;
        this.serviceability = serviceability;
        this.staff = staff;
        this.configuration = configuration;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
        Gauge.builder(DEVICES_ACTIVE, () -> enrolment.activeCount(DevicePrincipalClass.KIOSK))
                .description("Active kiosk device principals")
                .register(meters);
    }

    // ----------------------------------------------------------------- the manager's side

    /** What a pending code claims. A code that is unknown, spent or expired is the one 404. */
    public PendingEnrolmentView pending(String userCode, String approverSubject) {
        return enrolment
                .peek(userCode, approverSubject)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment with this code"));
    }

    /** The KIOSK channels a manager may pair a kiosk to at this branch: active, and selling here. */
    public List<ChannelChoice> channelsAt(UUID tenantId, UUID locationId) {
        return store.kioskChannelsAt(tenantId, locationId);
    }

    /**
     * Pairs the kiosk showing {@code userCode} to {@code channelId} at this branch, in one transaction with the
     * principal it creates.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for an unknown or expired code; {@code DEVICE_CLASS_MISMATCH}
     *                      for a code that asked to be anything but a kiosk, naming both classes; {@code
     *                      KIOSK_CHANNEL_NOT_AVAILABLE} for a channel that is not an active KIOSK channel selling
     *                      at this branch; {@code UNPROCESSABLE_STATE} while the tenant's flag is off
     */
    @Transactional
    public KioskDeviceView approve(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String userCode,
            String displayName,
            UUID channelId,
            String approverSubject) {

        requireEnabled(tenantId);
        PendingEnrolmentView pending = enrolment.peek(userCode, approverSubject).orElse(null);
        if (pending == null) {
            count("EXPIRED");
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment with this code");
        }
        if (pending.requestedClass() != DevicePrincipalClass.KIOSK) {
            count("REFUSED_CLASS");
            throw new ApiException(
                    ErrorCode.DEVICE_CLASS_MISMATCH,
                    "A device that asked to be %s cannot be approved as %s here"
                            .formatted(pending.requestedClass(), DevicePrincipalClass.KIOSK),
                    Map.of(
                            "requestedClass", pending.requestedClass().name(),
                            "approvedClass", DevicePrincipalClass.KIOSK.name()));
        }
        SalesChannel channel = requireKioskChannelAt(tenantId, channelId, locationId);

        DevicePrincipalView device = enrolment.approve(
                new ApproveEnrolment(
                        userCode,
                        ResourceScope.location(tenantId, brandId, locationId),
                        DevicePrincipalClass.KIOSK,
                        displayName),
                approverSubject);
        Instant now = clock.instant();
        try {
            store.insert(tenantId, brandId, locationId, device.id(), channelId, now);
        } catch (DataIntegrityViolationException refused) {
            // The composite keys said no after the service said yes: the binding changed between the read and the
            // write. Rolled back with the principal this transaction created.
            throw channelNotAvailable(channelId, refused);
        }
        count("APPROVED");

        audit.record(AuditFact.of("kiosk.device.enrolled", AuditClass.SECURITY)
                .by(ActorRef.user(approverSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("iam.device_principal", device.id())
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because("ADR 0162 kiosk pairing: " + displayName)
                .changed(ChangeDocuments.created(Map.of(
                        "requestedClass", pending.requestedClass().name(),
                        "approvedClass", device.deviceClass().name(),
                        "channel", channel.code(),
                        "displayName", displayName,
                        "requestedLabel", pending.requestedLabel() == null ? "" : pending.requestedLabel())))
                .usingCapability("kiosk.device.manage")
                .correlatedBy(device.id().toString())
                .occurredAt(now)
                .build());

        String approverName = nameOf(tenantId, approverSubject);
        return viewOf(
                device,
                store.find(tenantId, device.id()).orElse(null),
                approverName == null ? Map.of() : Map.of(approverSubject, approverName));
    }

    /** The branch's kiosks, active and revoked alike, with the channel each sells through and when it was last seen. */
    public List<KioskDeviceView> list(UUID tenantId, UUID brandId, UUID locationId) {
        List<DevicePrincipalView> kiosks = kiosksAt(tenantId, brandId, locationId);
        Map<UUID, BindingRow> bindings = store.forLocation(tenantId, locationId).stream()
                .collect(Collectors.toMap(BindingRow::deviceId, row -> row));
        Set<String> subjects = new java.util.LinkedHashSet<>();
        kiosks.forEach(device -> {
            subjects.add(device.enrolledBy());
            if (device.revokedBy() != null) {
                subjects.add(device.revokedBy());
            }
        });
        Map<String, String> names = staff.namesOf(tenantId, subjects);
        List<KioskDeviceView> views = new ArrayList<>();
        for (DevicePrincipalView device : kiosks) {
            views.add(viewOf(device, bindings.get(device.id()), names));
        }
        return views;
    }

    /**
     * Renames a kiosk and/or moves it to another KIOSK channel active at the same branch, against the version the
     * form was opened at ({@code 0} for a kiosk the registry shows as not configured, which this binds).
     */
    @Transactional
    public KioskDeviceView rebind(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID deviceId,
            String displayName,
            UUID channelId,
            int expectedVersion,
            String actorSubject) {

        DevicePrincipalView device = kioskAt(tenantId, brandId, locationId, deviceId);
        if (!"ACTIVE".equals(device.status())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "A revoked kiosk cannot be changed");
        }
        BindingRow before = store.find(tenantId, deviceId).orElse(null);
        int currentVersion = before == null ? 0 : before.version();
        if (currentVersion != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, currentVersion);
        }
        SalesChannel channel = requireKioskChannelAt(tenantId, channelId, locationId);

        Instant now = clock.instant();
        boolean renamed = !device.displayName().equals(displayName);
        boolean moved = before == null || !before.channelId().equals(channelId);
        if (!renamed && !moved) {
            return viewOf(device, before, Map.of());
        }
        if (renamed) {
            enrolment.rename(tenantId, deviceId, displayName);
        }
        try {
            if (before == null) {
                store.insert(tenantId, brandId, locationId, deviceId, channelId, now);
            } else if (moved) {
                if (!store.rebind(tenantId, deviceId, channelId, expectedVersion, now)) {
                    throw ApiException.staleVersion(expectedVersion, expectedVersion + 1);
                }
            } else if (!store.touch(tenantId, deviceId, expectedVersion, now)) {
                throw ApiException.staleVersion(expectedVersion, expectedVersion + 1);
            }
        } catch (DataIntegrityViolationException refused) {
            throw channelNotAvailable(channelId, refused);
        }

        Map<String, Object> beforeDoc = new LinkedHashMap<>();
        beforeDoc.put("displayName", device.displayName());
        beforeDoc.put("channel", before == null ? null : before.channelCode());
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("displayName", displayName);
        afterDoc.put("channel", channel.code());
        audit.record(AuditFact.of("kiosk.device.rebound", AuditClass.SECURITY)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("iam.device_principal", deviceId)
                .targetVersion((long) expectedVersion + 1)
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because("ADR 0162 kiosk " + (moved ? "re-bound to channel " + channel.code() : "renamed"))
                .changed(ChangeDocuments.diff(beforeDoc, afterDoc))
                .usingCapability("kiosk.device.manage")
                .correlatedBy(deviceId.toString())
                .occurredAt(now)
                .build());

        DevicePrincipalView after = enrolment.find(tenantId, deviceId).orElse(device);
        return viewOf(after, store.find(tenantId, deviceId).orElse(null), Map.of());
    }

    /**
     * Revokes a kiosk: its grant is revoked first (the next request is refused) and its Keycloak client disabled
     * (the next token mint fails). Idempotent -- a second press is not a second security event.
     */
    @Transactional
    public boolean revoke(
            UUID tenantId, UUID brandId, UUID locationId, UUID deviceId, String reason, String revokerSubject) {
        kioskAt(tenantId, brandId, locationId, deviceId);
        boolean revoked = enrolment.revoke(deviceId, revokerSubject, reason);
        if (revoked) {
            audit.record(AuditFact.of("kiosk.device.revoked", AuditClass.SECURITY)
                    .by(ActorRef.user(revokerSubject, null))
                    .at(ResourceScope.location(tenantId, brandId, locationId))
                    .target("iam.device_principal", deviceId)
                    .outcome(AuditFact.Outcome.SUCCEEDED)
                    .because(reason)
                    .changed(ChangeDocuments.change("revoked", false, true))
                    .usingCapability("kiosk.device.manage")
                    .correlatedBy(deviceId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        return revoked;
    }

    // ------------------------------------------------------------------ the kiosk's side

    @Override
    public Optional<KioskBinding> bindingOf(UUID tenantId, UUID deviceId) {
        if (!enabled(tenantId)) {
            return Optional.empty();
        }
        return store.find(tenantId, deviceId)
                .map(row ->
                        new KioskBinding(row.channelId(), row.channelCode(), row.channelName(), row.channelStatus()));
    }

    /**
     * The calling kiosk's configuration: the channel it sells through, the branch and whether it is open, the
     * fulfilment modes and payment methods the channel's matrix enables, and the price plane's channel code.
     * Nothing personal and no secret, from tables ADR 0036 owns.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the caller is not an active kiosk of this branch,
     *                      {@code UNPROCESSABLE_STATE} when it has no binding row ("not configured")
     */
    public KioskConfiguration configuration(UUID tenantId, UUID brandId, UUID locationId, String callerSubject) {
        DevicePrincipalView device = enrolment
                .activeDeviceOf(callerSubject)
                .filter(candidate -> candidate.deviceClass() == DevicePrincipalClass.KIOSK)
                .filter(candidate -> candidate.tenantId().equals(tenantId)
                        && candidate.brandId().equals(brandId)
                        && candidate.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Not a kiosk of this branch"));
        BindingRow binding = store.find(tenantId, device.id())
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This kiosk is not configured"));
        SalesChannel channel = channels.byId(tenantId, binding.channelId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This kiosk is not configured"));
        BranchSummary branch = store.branch(tenantId, brandId, locationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such branch"));

        List<String> modes = channelStore.fulfillmentModes(tenantId, channel.id()).entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(entry -> entry.getKey().name())
                .toList();
        List<String> payments = channels.enabledPaymentMethodCodes(tenantId, channel.id()).stream()
                .sorted()
                .toList();
        String priceChannelCode = channel.pricePlaneChannelId() == null
                ? channel.code()
                : channels.byId(tenantId, channel.pricePlaneChannelId())
                        .map(SalesChannel::code)
                        .orElse(channel.code());

        // The branch's state for the first fulfilment mode the channel enables, in a fixed order: a kiosk is a
        // place to order, and its open or closed is a branch fact that does not differ per mode in practice. The
        // resolver is the one answer to "can this be ordered" (ADR 0036); this asks it and adds nothing.
        Instant now = clock.instant();
        Serviceability state = null;
        for (FulfillmentMode mode :
                new FulfillmentMode[] {FulfillmentMode.DINE_IN, FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY}) {
            if (modes.contains(mode.name())) {
                state = serviceability.resolve(tenantId, brandId, locationId, channel.id(), mode, now);
                break;
            }
        }
        return new KioskConfiguration(
                new KioskChannel(
                        channel.id(),
                        channel.code(),
                        channel.displayName(),
                        channel.status().name()),
                new KioskBranch(
                        branch.name(),
                        branch.timezone(),
                        state != null && state.available(),
                        state == null || state.reason() == null
                                ? null
                                : state.reason().name(),
                        state == null ? null : state.nextAvailableAt()),
                modes,
                payments,
                priceChannelCode,
                channel.version());
    }

    // ------------------------------------------------------------------------ helpers

    private void requireEnabled(UUID tenantId) {
        if (!enabled(tenantId)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Kiosk pairing is not enabled for this tenant (feature.kiosk_devices)");
        }
    }

    private boolean enabled(UUID tenantId) {
        return Boolean.TRUE.equals(configuration
                .resolve(ConfigurationKeys.FEATURE_KIOSK_DEVICES, ResourceScope.tenant(tenantId))
                .value());
    }

    private SalesChannel requireKioskChannelAt(UUID tenantId, UUID channelId, UUID locationId) {
        SalesChannel channel = channels.byId(tenantId, channelId).orElse(null);
        if (channel == null
                || channel.systemType() != SalesChannelSystemType.KIOSK
                || !channel.sellable()
                || !store.channelSellsAt(tenantId, channelId, locationId)) {
            throw channelNotAvailable(channelId, null);
        }
        return channel;
    }

    private static ApiException channelNotAvailable(UUID channelId, @Nullable RuntimeException cause) {
        ApiException failure = new ApiException(
                ErrorCode.KIOSK_CHANNEL_NOT_AVAILABLE,
                "That is not an active KIOSK channel selling at this branch",
                Map.of("channelId", channelId.toString()));
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    private DevicePrincipalView kioskAt(UUID tenantId, UUID brandId, UUID locationId, UUID deviceId) {
        return enrolment
                .find(tenantId, deviceId)
                .filter(device -> device.deviceClass() == DevicePrincipalClass.KIOSK
                        && device.brandId().equals(brandId)
                        && device.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such kiosk"));
    }

    private List<DevicePrincipalView> kiosksAt(UUID tenantId, UUID brandId, UUID locationId) {
        return enrolment.list(tenantId, locationId).stream()
                .filter(device -> device.deviceClass() == DevicePrincipalClass.KIOSK
                        && device.brandId().equals(brandId))
                .toList();
    }

    private @Nullable String nameOf(UUID tenantId, String subject) {
        return staff.nameOf(tenantId, subject);
    }

    private KioskDeviceView viewOf(
            DevicePrincipalView device, @Nullable BindingRow binding, Map<String, String> names) {
        return new KioskDeviceView(
                device.id(),
                device.locationId(),
                device.displayName(),
                device.status(),
                binding != null
                        ? new KioskChannel(
                                binding.channelId(),
                                binding.channelCode(),
                                binding.channelName(),
                                binding.channelStatus())
                        : null,
                binding != null ? binding.version() : 0,
                // The registry flags a kiosk that cannot sell: no binding row at all (a defect, or an approval that
                // half-committed), a channel since archived or switched off, or a binding withdrawn at its branch.
                binding == null || !"ACTIVE".equals(binding.channelStatus()) || !binding.bindingActive(),
                names.get(device.enrolledBy()),
                device.enrolledAt(),
                device.revokedBy() == null ? null : names.get(device.revokedBy()),
                device.revokedAt(),
                device.revokedReason(),
                device.lastSeenAt(),
                device.lastSeenBuild());
    }

    private void count(String outcome) {
        meters.counter(PAIRINGS, "outcome", outcome).increment();
    }

    // ------------------------------------------------------------------------ views

    /** A KIOSK channel as a kiosk and a manager see it. */
    public record KioskChannel(UUID channelId, String code, String displayName, String status) {}

    /**
     * A kiosk in the registry.
     *
     * @param channel      the channel it sells through, null for a kiosk with no binding row
     * @param version      the {@code If-Match} the next change is sent with; 0 when there is no binding row yet
     * @param needsAttention the kiosk cannot sell: unbound, or its channel or binding is no longer active
     * @param enrolledBy   the approver's display name, for display only; null when the directory has none
     */
    public record KioskDeviceView(
            UUID deviceId,
            UUID locationId,
            String displayName,
            String status,
            @Nullable KioskChannel channel,
            int version,
            boolean needsAttention,
            @Nullable String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason,
            @Nullable Instant lastSeenAt,
            @Nullable String lastSeenBuild) {}

    /** A branch as its kiosk reads it. */
    public record KioskBranch(
            String name,
            String timezone,
            boolean open,
            @Nullable String closedReason,
            @Nullable Instant nextOpenAt) {}

    /** {@code GET .../kiosk/configuration}: everything a kiosk may learn, and nothing it could order with. */
    public record KioskConfiguration(
            KioskChannel channel,
            KioskBranch branch,
            List<String> fulfillmentModes,
            List<String> paymentMethods,
            String priceChannelCode,
            int version) {}
}
