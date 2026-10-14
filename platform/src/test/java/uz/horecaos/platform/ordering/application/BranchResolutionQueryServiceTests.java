package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort;
import uz.horecaos.platform.ordering.application.BranchResolutionQueryService.BranchResolution;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityReason;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;

/**
 * ADR 0177, row {@code 10.3b}: a pre-order is resolved at the time it is promised for, and when no branch is
 * open then {@code ordering.preorder_branch_resolution} picks among the closed ones.
 *
 * <p>The ports are hand-written fakes that record the instant they are asked about, so the claim "the
 * resolver asks about the promised time" is something the test can see, not something it assumes. What would
 * still pass if the reader ignored the key: the immediate-order cases. What would not: every case that
 * names a rule and a promised time.
 */
class BranchResolutionQueryServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID NEAR = UUID.randomUUID();
    private static final UUID FAR = UUID.randomUUID();
    private static final UUID SOON = UUID.randomUUID();
    private static final UUID SOONEST_BUT_NO_PREORDERS = UUID.randomUUID();
    private static final UUID NO_POINT = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final Instant PROMISED = Instant.parse("2026-10-15T06:00:00Z");
    private static final GeoPoint CUSTOMER = new GeoPoint(41.30, 69.24);

    /** Per branch: what the schedule says at an instant. A branch absent here is closed with no next opening. */
    private final Map<UUID, Function<Instant, Serviceability>> schedule = new HashMap<>();

    private final List<Instant> serviceabilityAskedAt = new ArrayList<>();
    private final List<Instant> zonesAskedAt = new ArrayList<>();

    private final SalesChannel channel = new SalesChannel(
            UUID.randomUUID(),
            TENANT,
            "call-centre",
            SalesChannelSystemType.CALL_CENTRE,
            "Call centre",
            SalesChannel.Status.ACTIVE,
            null,
            false,
            false,
            null,
            null,
            null,
            null,
            1);

    private final SalesChannelLookup channels = new SalesChannelLookup() {
        @Override
        public Optional<SalesChannel> byId(UUID tenantId, UUID channelId) {
            return Optional.of(channel);
        }

        @Override
        public Optional<SalesChannel> byCode(UUID tenantId, String code) {
            return Optional.of(channel);
        }

        @Override
        public Set<String> enabledPaymentMethodCodes(UUID tenantId, UUID channelId) {
            return Set.of();
        }
    };

    private final ServiceabilityResolver serviceability = (tenantId, brandId, locationId, channelId, mode, at) -> {
        serviceabilityAskedAt.add(at);
        Function<Instant, Serviceability> rule = schedule.get(locationId);
        return rule == null
                ? Serviceability.refused(ServiceabilityReason.OUTSIDE_SERVICE_HOURS, null, true)
                : rule.apply(at);
    };

    /** Branches in the delivery zone, in the order the port returns them (the zone ranking is by priority). */
    private final List<BranchResolutionPort.DeliveryBranchMatch> zoneMatches = new ArrayList<>();

    private final BranchResolutionPort port = new BranchResolutionPort() {
        @Override
        public List<DeliveryBranchMatch> deliveryCandidates(UUID tenantId, UUID brandId, GeoPoint point, Instant at) {
            zonesAskedAt.add(at);
            return zoneMatches;
        }

        @Override
        public List<PickupBranchCandidate> pickupCandidates(UUID tenantId, UUID brandId) {
            return zoneMatches.stream()
                    .map(match -> new PickupBranchCandidate(match.locationId(), match.displayName()))
                    .toList();
        }
    };

    private BranchResolutionQueryService service(Map<String, Object> config) {
        return new BranchResolutionQueryService(
                port,
                serviceability,
                channels,
                mock(JdbcOrderStore.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new FakeConfigurationResolver(config));
    }

    private static BranchResolutionPort.DeliveryBranchMatch match(
            UUID id, String name, @Nullable Double lat, @Nullable Double lon) {
        return new BranchResolutionPort.DeliveryBranchMatch(id, name, UUID.randomUUID(), 1, 10, 5_000, lat, lon);
    }

    private static List<UUID> order(BranchResolution resolution) {
        return resolution.candidates().stream()
                .map(BranchResolutionQueryService.BranchCandidate::locationId)
                .toList();
    }

    /** Four closed branches, in zone-rank order NEAR, FAR, SOON, SOONEST_BUT_NO_PREORDERS; NEAR is nearest to the customer. */
    private void everyBranchClosedAtThePromisedTime() {
        zoneMatches.clear();
        zoneMatches.add(match(FAR, "Far", 41.40, 69.40));
        zoneMatches.add(match(NEAR, "Near", 41.301, 69.241));
        zoneMatches.add(match(SOON, "Soon", 41.35, 69.30));
        zoneMatches.add(match(SOONEST_BUT_NO_PREORDERS, "Soonest, no pre-orders", 41.31, 69.25));
        schedule.put(
                FAR,
                at -> Serviceability.refused(
                        ServiceabilityReason.OUTSIDE_SERVICE_HOURS, PROMISED.plusSeconds(3 * 3600), true));
        schedule.put(
                NEAR,
                at -> Serviceability.refused(
                        ServiceabilityReason.OUTSIDE_SERVICE_HOURS, PROMISED.plusSeconds(5 * 3600), true));
        schedule.put(
                SOON,
                at -> Serviceability.refused(
                        ServiceabilityReason.OUTSIDE_SERVICE_HOURS, PROMISED.plusSeconds(3600), true));
        schedule.put(
                SOONEST_BUT_NO_PREORDERS,
                at -> Serviceability.refused(
                        ServiceabilityReason.OUTSIDE_SERVICE_HOURS, PROMISED.plusSeconds(60), false));
    }

    @Test
    @DisplayName("an order for now asks the zones and the schedule about now, and keeps the order it was ranked in")
    void anOrderForNowIsAskedAboutNow() {
        everyBranchClosedAtThePromisedTime();

        BranchResolution resolution =
                service(Map.of()).resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, CUSTOMER, "call-centre");

        assertThat(zonesAskedAt).containsOnly(NOW);
        assertThat(serviceabilityAskedAt).containsOnly(NOW);
        assertThat(order(resolution))
                .as("no pre-order rule applies to an order for now: the zone ranking stands")
                .containsExactly(FAR, NEAR, SOON, SOONEST_BUT_NO_PREORDERS);
        assertThat(resolution.proposedLocationId())
                .as("none is open, so the top-ranked one is proposed, as it always was")
                .isEqualTo(FAR);
    }

    @Test
    @DisplayName("a pre-order asks the zones and the schedule about the promised time, not about now")
    void aPreOrderIsAskedAboutThePromisedTime() {
        everyBranchClosedAtThePromisedTime();

        service(Map.of()).resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, CUSTOMER, "call-centre", PROMISED);

        assertThat(zonesAskedAt).containsOnly(PROMISED);
        assertThat(serviceabilityAskedAt).containsOnly(PROMISED);
    }

    @Test
    @DisplayName("a time that is not in the future is an order for now")
    void aTimeInThePastIsNow() {
        everyBranchClosedAtThePromisedTime();

        service(Map.of())
                .resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, CUSTOMER, "call-centre", NOW.minusSeconds(60));

        assertThat(zonesAskedAt).containsOnly(NOW);
        assertThat(serviceabilityAskedAt).containsOnly(NOW);
    }

    @Test
    @DisplayName("a branch closed now and open at the promised time is a candidate like any other, and is proposed")
    void aBranchOpenThenIsProposedEvenIfItIsClosedNow() {
        everyBranchClosedAtThePromisedTime();
        schedule.put(
                SOON,
                at -> at.equals(PROMISED)
                        ? Serviceability.available(true, 25)
                        : Serviceability.refused(ServiceabilityReason.OUTSIDE_SERVICE_HOURS, PROMISED, true));

        BranchResolution resolution = service(Map.of(OrderingKeys.RESOLUTION, "BY_DISTANCE"))
                .resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, CUSTOMER, "call-centre", PROMISED);

        assertThat(resolution.proposedLocationId()).isEqualTo(SOON);
        assertThat(order(resolution).get(0))
                .as("the open one first, whatever its zone rank or distance")
                .isEqualTo(SOON);
    }

    @Test
    @DisplayName(
            "BY_OPENING_TIME puts the branch that opens soonest first among the closed ones, and one that takes no pre-order last")
    void byOpeningTimeRanksTheClosedOnesBySoonestToOpen() {
        everyBranchClosedAtThePromisedTime();

        BranchResolution resolution = service(Map.of(OrderingKeys.RESOLUTION, "BY_OPENING_TIME"))
                .resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, CUSTOMER, "call-centre", PROMISED);

        assertThat(order(resolution))
                .as("SOON opens in an hour, FAR in three, NEAR in five; the branch that opens first of all will not "
                        + "take a pre-order into a closed slot, so it cannot be the answer")
                .containsExactly(SOON, FAR, NEAR, SOONEST_BUT_NO_PREORDERS);
        assertThat(resolution.proposedLocationId()).isEqualTo(SOON);
    }

    @Test
    @DisplayName("BY_DISTANCE puts the branch nearest the delivery point first among the closed ones")
    void byDistanceRanksTheClosedOnesByDistance() {
        everyBranchClosedAtThePromisedTime();

        BranchResolution resolution = service(Map.of(OrderingKeys.RESOLUTION, "BY_DISTANCE"))
                .resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, CUSTOMER, "call-centre", PROMISED);

        assertThat(order(resolution)).startsWith(NEAR);
        assertThat(resolution.proposedLocationId()).isEqualTo(NEAR);
        assertThat(resolution.candidates().get(0).distanceMeters())
                .as("the distance is shown with the candidate")
                .isBetween(50.0, 300.0);
        assertThat(order(resolution).get(3))
                .as("and the branch that takes no pre-order sorts behind those that do, near or not")
                .isEqualTo(SOONEST_BUT_NO_PREORDERS);
    }

    @Test
    @DisplayName("a branch with no point of its own is the last to be picked by distance")
    void aBranchWithNoPointRanksLastByDistance() {
        everyBranchClosedAtThePromisedTime();
        zoneMatches.add(match(NO_POINT, "No point", null, null));
        schedule.put(
                NO_POINT,
                at -> Serviceability.refused(
                        ServiceabilityReason.OUTSIDE_SERVICE_HOURS, PROMISED.plusSeconds(10), true));

        BranchResolution resolution = service(Map.of(OrderingKeys.RESOLUTION, "BY_DISTANCE"))
                .resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, CUSTOMER, "call-centre", PROMISED);

        assertThat(order(resolution).get(3)).isEqualTo(NO_POINT);
        assertThat(resolution.candidates().get(3).distanceMeters()).isNull();
    }

    @Test
    @DisplayName("a pickup has no customer point, so BY_DISTANCE falls back to the soonest to open")
    void aPickupFallsBackFromDistanceToOpeningTime() {
        everyBranchClosedAtThePromisedTime();

        BranchResolution resolution = service(Map.of(OrderingKeys.RESOLUTION, "BY_DISTANCE"))
                .resolve(TENANT, BRAND, FulfillmentMode.PICKUP, null, "call-centre", PROMISED);

        assertThat(order(resolution)).containsExactly(SOON, FAR, NEAR, SOONEST_BUT_NO_PREORDERS);
        assertThat(resolution.candidates())
                .allSatisfy(candidate -> assertThat(candidate.distanceMeters()).isNull());
    }

    /** The key's code, spelled out so a rename shows up here. */
    private static final class OrderingKeys {
        static final String RESOLUTION =
                uz.horecaos.platform.ordering.api.OrderingConfigurationKeys.PREORDER_BRANCH_RESOLUTION_CODE;

        private OrderingKeys() {}
    }
}
