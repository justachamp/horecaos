package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.tenancy.api.ChannelAvailabilityChanged;
import uz.horecaos.platform.tenancy.api.ChannelSocialPlatform;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelActivated;
import uz.horecaos.platform.tenancy.api.SalesChannelArchived;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * The tenant-facing channel registry (ADR 0036).
 *
 * <p>Registering a channel is tenant CRUD by design: a tenant signs a marketplace
 * on Monday and sells on it without waiting for a release. What the tenant cannot
 * do is invent a {@link SalesChannelSystemType}, because behaviour keys on the
 * type.
 */
@Service
public class SalesChannelService {

    private final JdbcSalesChannelStore store;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /**
     * The two-argument form a caller could always construct without a Spring
     * context. Events published through it go nowhere, which is no change from
     * before this class published any — a test built this way is asserting
     * something other than the outbox and was never wired to it.
     */
    public SalesChannelService(JdbcSalesChannelStore store, Clock clock) {
        this(store, clock, event -> {});
    }

    @Autowired
    public SalesChannelService(JdbcSalesChannelStore store, Clock clock, ApplicationEventPublisher events) {
        this.store = store;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public SalesChannel create(UUID tenantId, CreateChannelCommand command) {
        SalesChannelSystemType systemType = SalesChannelSystemType.require(command.systemType());
        UUID pricePlaneChannelId = validatedPricePlane(tenantId, command.pricePlaneChannelId());

        SalesChannel channel = new SalesChannel(
                UUID.randomUUID(),
                tenantId,
                command.code(),
                systemType,
                command.displayName(),
                SalesChannel.Status.ACTIVE,
                pricePlaneChannelId,
                command.externallyPriced(),
                command.guestOrdersAllowed(),
                command.providerInstallationId(),
                // Icon, colours and social links are row 10.4a's presentation
                // set -- an edit-time concern, corrected through #update and
                // #replaceSocialLinks, the same way the location, payment and
                // fulfilment matrices are configured after creation rather
                // than at it.
                null,
                null,
                null,
                1);
        try {
            store.insert(channel, clock.instant());
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSalesChannelStore.explain(violation);
        }
        events.publishEvent(new SalesChannelActivated(
                UUID.randomUUID(),
                new TenantId(tenantId),
                channel.id(),
                clock.instant(),
                channel.code(),
                channel.systemType().name(),
                channel.version()));
        return channel;
    }

    @Transactional(readOnly = true)
    public List<SalesChannel> list(UUID tenantId) {
        return store.listForTenant(tenantId);
    }

    /**
     * The registry list enriched with the three counts row 10.4a's own table
     * names and {@link uz.horecaos.platform.tenancy.web.SalesChannelController.ChannelView}
     * did not carry until now: how many branches sell here, how many payment
     * methods are enabled, and which fulfilment modes are enabled. Four
     * queries total regardless of how many channels the tenant has — never one
     * per channel.
     */
    @Transactional(readOnly = true)
    public List<ChannelRegistrySummary> listSummaries(UUID tenantId) {
        List<SalesChannel> channels = store.listForTenant(tenantId);
        Map<UUID, Integer> locationCounts = store.locationCounts(tenantId);
        Map<UUID, Integer> paymentMethodCounts = store.enabledPaymentMethodCounts(tenantId);
        Map<UUID, List<FulfillmentMode>> fulfillmentModes = store.enabledFulfillmentModesByChannel(tenantId);
        return channels.stream()
                .map(channel -> new ChannelRegistrySummary(
                        channel,
                        locationCounts.getOrDefault(channel.id(), 0),
                        paymentMethodCounts.getOrDefault(channel.id(), 0),
                        fulfillmentModes.getOrDefault(channel.id(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public SalesChannel require(UUID tenantId, UUID channelId) {
        return store.byId(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "No sales channel %s for this tenant".formatted(channelId)));
    }

    /**
     * Corrects a channel's own editable fields — everything but the code and the
     * system type, which ADR 0036 fixes at creation because behaviour keys on
     * the type. Row 10.4a's own edit action: before this method nothing on the
     * registry could be changed once created.
     */
    @Transactional
    public SalesChannel update(UUID tenantId, UUID channelId, UpdateChannelCommand command, int expectedVersion) {
        SalesChannel channel = require(tenantId, channelId);
        if (command.pricePlaneChannelId() != null
                && command.pricePlaneChannelId().equals(channelId)) {
            throw new IllegalArgumentException("A channel cannot take its prices from itself");
        }
        UUID pricePlaneChannelId = validatedPricePlane(tenantId, command.pricePlaneChannelId());
        validatedHexColor(command.brandColorPrimary());
        validatedHexColor(command.brandColorSecondary());
        try {
            if (!store.update(
                    tenantId,
                    channelId,
                    command.displayName(),
                    pricePlaneChannelId,
                    command.externallyPriced(),
                    command.guestOrdersAllowed(),
                    command.providerInstallationId(),
                    command.icon(),
                    command.brandColorPrimary(),
                    command.brandColorSecondary(),
                    expectedVersion,
                    clock.instant())) {
                throw new TenantResourceConflictException("The channel changed since it was read");
            }
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSalesChannelStore.explain(violation);
        }
        return new SalesChannel(
                channel.id(),
                channel.tenantId(),
                channel.code(),
                channel.systemType(),
                command.displayName(),
                channel.status(),
                pricePlaneChannelId,
                command.externallyPriced(),
                command.guestOrdersAllowed(),
                command.providerInstallationId(),
                command.icon(),
                command.brandColorPrimary(),
                command.brandColorSecondary(),
                expectedVersion + 1);
    }

    /**
     * Suspends sales on an active channel without archiving it — row 10.4a's
     * {@code ACTIVE→INACTIVE} transition, declared on {@link SalesChannel.Status}
     * since ADR 0036 and unreachable until now. Reversible, unlike
     * {@link #archive}: an operator pausing a channel for the season reopens it
     * with {@link #reactivate} rather than re-registering it under a new code.
     */
    @Transactional
    public SalesChannel deactivate(UUID tenantId, UUID channelId, int expectedVersion) {
        return transitionActiveStatus(
                tenantId, channelId, SalesChannel.Status.ACTIVE, SalesChannel.Status.INACTIVE, expectedVersion);
    }

    /** The reverse of {@link #deactivate}. */
    @Transactional
    public SalesChannel reactivate(UUID tenantId, UUID channelId, int expectedVersion) {
        return transitionActiveStatus(
                tenantId, channelId, SalesChannel.Status.INACTIVE, SalesChannel.Status.ACTIVE, expectedVersion);
    }

    private SalesChannel transitionActiveStatus(
            UUID tenantId, UUID channelId, SalesChannel.Status from, SalesChannel.Status to, int expectedVersion) {
        SalesChannel channel = require(tenantId, channelId);
        if (channel.status() != from) {
            throw new TenantResourceConflictException(
                    "Channel %s is %s, not %s".formatted(channelId, channel.status(), from));
        }
        if (!store.updateStatus(tenantId, channelId, to, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        return new SalesChannel(
                channel.id(),
                channel.tenantId(),
                channel.code(),
                channel.systemType(),
                channel.displayName(),
                to,
                channel.pricePlaneChannelId(),
                channel.externallyPriced(),
                channel.guestOrdersAllowed(),
                channel.providerInstallationId(),
                channel.icon(),
                channel.brandColorPrimary(),
                channel.brandColorSecondary(),
                expectedVersion + 1);
    }

    /**
     * Retires a channel without deleting it.
     *
     * <p>Refused while another channel prices through this one, because archiving
     * the price plane would leave the dependent channel silently falling back to
     * brand prices — a price change nobody made, visible only on the receipt.
     */
    @Transactional
    public SalesChannel archive(UUID tenantId, UUID channelId, int expectedVersion) {
        SalesChannel channel = require(tenantId, channelId);
        if (store.isPricePlaneForAnother(tenantId, channelId)) {
            throw new TenantResourceConflictException(
                    "Another channel takes its prices from this one; repoint it before archiving");
        }
        if (!store.updateStatus(tenantId, channelId, SalesChannel.Status.ARCHIVED, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        int newVersion = channel.version() + 1;
        events.publishEvent(new SalesChannelArchived(
                UUID.randomUUID(), new TenantId(tenantId), channelId, clock.instant(), channel.code(), newVersion));
        return new SalesChannel(
                channel.id(),
                channel.tenantId(),
                channel.code(),
                channel.systemType(),
                channel.displayName(),
                SalesChannel.Status.ARCHIVED,
                channel.pricePlaneChannelId(),
                channel.externallyPriced(),
                channel.guestOrdersAllowed(),
                channel.providerInstallationId(),
                channel.icon(),
                channel.brandColorPrimary(),
                channel.brandColorSecondary(),
                newVersion);
    }

    /**
     * Replaces the payment matrix wholesale.
     *
     * <p>Whole-matrix and never per-cell: a matrix edited cell by cell from two
     * tabs produces a combination neither operator chose. The expected version is
     * what makes the second writer lose visibly.
     */
    @Transactional
    public void replacePaymentMethods(UUID tenantId, UUID channelId, Map<String, Boolean> matrix, int expectedVersion) {
        require(tenantId, channelId);
        try {
            if (!store.replacePaymentMethods(tenantId, channelId, matrix, expectedVersion, clock.instant())) {
                throw new TenantResourceConflictException("The channel changed since it was read");
            }
        } catch (DataIntegrityViolationException violation) {
            // Since V0175 payment_method_code is a foreign key onto the tenant's own
            // payments.payment_methods registry: a code the tenant has not
            // registered — or the frontend's own stale hard-coded set once named —
            // used to reach the database uncaught and come back as an untranslated
            // 500, the live defect row 10.4a/10.4b of the operations gap map names.
            // replaceLocations below already catches its own FK violation; this is
            // the same treatment for the matrix that did not have it.
            throw JdbcSalesChannelStore.explain(violation);
        }
        publishAvailabilityChanged(
                tenantId, channelId, ChannelAvailabilityChanged.MatrixKind.PAYMENT_METHODS, expectedVersion);
    }

    @Transactional
    public void replaceFulfillmentModes(
            UUID tenantId, UUID channelId, Map<FulfillmentMode, Boolean> matrix, int expectedVersion) {
        require(tenantId, channelId);
        if (!store.replaceFulfillmentModes(tenantId, channelId, matrix, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        publishAvailabilityChanged(
                tenantId, channelId, ChannelAvailabilityChanged.MatrixKind.FULFILLMENT_MODES, expectedVersion);
    }

    @Transactional
    public void replaceLocations(UUID tenantId, UUID channelId, List<UUID> locationIds, int expectedVersion) {
        require(tenantId, channelId);
        try {
            if (!store.replaceLocations(tenantId, channelId, locationIds, expectedVersion, clock.instant())) {
                throw new TenantResourceConflictException("The channel changed since it was read");
            }
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSalesChannelStore.explain(violation);
        }
        publishAvailabilityChanged(
                tenantId, channelId, ChannelAvailabilityChanged.MatrixKind.LOCATIONS, expectedVersion);
    }

    /**
     * Replaces a channel's social links wholesale — row 10.4a's own field,
     * never built before this wave. Whole-set and never per-link, the same
     * discipline {@link #replacePaymentMethods} and {@link #replaceLocations}
     * already use.
     *
     * <p>Never publishes {@link ChannelAvailabilityChanged}: a social link is
     * presentation, not something the serviceability resolver or a report
     * reads, so nothing downstream needs telling.
     *
     * @param links platform → destination URL. The platform is validated
     *     against {@link ChannelSocialPlatform}'s closed set and the URL
     *     against ADR 0036's own https-only rule before either ever reaches
     *     the database; {@code ck_channel_social_platform}/{@code
     *     ck_channel_social_url} in migration V0385 are the same rule again,
     *     for a row written outside this application.
     */
    @Transactional
    public void replaceSocialLinks(UUID tenantId, UUID channelId, Map<String, String> links, int expectedVersion) {
        require(tenantId, channelId);
        links.forEach((platform, url) -> {
            ChannelSocialPlatform.require(platform);
            if (url == null || !url.regionMatches(true, 0, "https://", 0, "https://".length())) {
                throw new IllegalArgumentException("Social link for \"%s\" must be an https URL".formatted(platform));
            }
        });
        try {
            if (!store.replaceSocialLinks(tenantId, channelId, links, expectedVersion, clock.instant())) {
                throw new TenantResourceConflictException("The channel changed since it was read");
            }
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSalesChannelStore.explain(violation);
        }
    }

    /**
     * {@code bumpVersion} inside each {@code replace*} store call above moved
     * the channel from {@code expectedVersion} to exactly one past it on
     * success — the same arithmetic {@code archive} above does by hand, because
     * none of those store methods hand the new value back.
     */
    private void publishAvailabilityChanged(
            UUID tenantId, UUID channelId, ChannelAvailabilityChanged.MatrixKind matrixKind, int expectedVersion) {
        events.publishEvent(new ChannelAvailabilityChanged(
                UUID.randomUUID(),
                new TenantId(tenantId),
                channelId,
                clock.instant(),
                matrixKind.name(),
                expectedVersion + 1));
    }

    @Transactional(readOnly = true)
    public ChannelMatrices matrices(UUID tenantId, UUID channelId) {
        require(tenantId, channelId);
        return new ChannelMatrices(
                store.paymentMethods(tenantId, channelId),
                store.fulfillmentModes(tenantId, channelId),
                store.locations(tenantId, channelId),
                store.socialLinks(tenantId, channelId));
    }

    /**
     * A price plane must exist, belong to this tenant, and not itself have one.
     *
     * <p>One hop, never a chain. The resolver reads
     * {@code COALESCE(price_plane_channel_id, id)} and does not recurse, so a chain
     * would resolve to the middle of it — and a cycle of two channels each pointing
     * at the other would be a configuration nobody could reason about.
     */
    private @Nullable UUID validatedPricePlane(UUID tenantId, @Nullable UUID pricePlaneChannelId) {
        if (pricePlaneChannelId == null) {
            return null;
        }
        SalesChannel plane = store.byId(tenantId, pricePlaneChannelId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "No sales channel %s for this tenant".formatted(pricePlaneChannelId)));
        if (plane.pricePlaneChannelId() != null) {
            throw new TenantResourceConflictException("A price plane may not itself take prices from another channel");
        }
        return plane.id();
    }

    private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    /**
     * The same shape {@code q-color-input} (row X.32) emits and migration
     * V0385's {@code ck_sales_channel_brand_color_*} checks re-assert at the
     * database. Validated here too so a malformed value is refused with a
     * legible message rather than the raw constraint violation.
     */
    private static void validatedHexColor(@Nullable String hex) {
        if (hex != null && !HEX_COLOR.matcher(hex).matches()) {
            throw new IllegalArgumentException("\"%s\" is not a six-digit hex colour (#rrggbb)".formatted(hex));
        }
    }

    public record CreateChannelCommand(
            String code,
            String systemType,
            String displayName,
            @Nullable UUID pricePlaneChannelId,
            boolean externallyPriced,
            boolean guestOrdersAllowed,
            @Nullable UUID providerInstallationId) {}

    /**
     * {@code code} and {@code systemType} are absent: see {@link #update}'s
     * own doc for why. {@code icon}/{@code brandColorPrimary}/{@code
     * brandColorSecondary} are row 10.4a's presentation fields, added
     * alongside the rest of a channel's own editable fields rather than as a
     * fourth matrix — there is exactly one of each per channel, unlike
     * payment methods, fulfilment modes or social links.
     */
    public record UpdateChannelCommand(
            String displayName,
            @Nullable UUID pricePlaneChannelId,
            boolean externallyPriced,
            boolean guestOrdersAllowed,
            @Nullable UUID providerInstallationId,
            @Nullable String icon,
            @Nullable String brandColorPrimary,
            @Nullable String brandColorSecondary) {}

    /** {@code socialLinks} is platform → URL, in display order (row 10.4a). */
    public record ChannelMatrices(
            Map<String, Boolean> paymentMethods,
            Map<FulfillmentMode, Boolean> fulfillmentModes,
            List<UUID> locationIds,
            Map<String, String> socialLinks) {}

    /** {@link #listSummaries}'s row: the channel plus the three counts 10.4a's table needs. */
    public record ChannelRegistrySummary(
            SalesChannel channel,
            int locationCount,
            int enabledPaymentMethodCount,
            List<FulfillmentMode> enabledFulfillmentModes) {}
}
