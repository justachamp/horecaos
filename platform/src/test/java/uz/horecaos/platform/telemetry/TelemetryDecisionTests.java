package uz.horecaos.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService.CoarseCourier;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.Geohash;
import uz.horecaos.platform.telemetry.domain.LivePositionRules;
import uz.horecaos.platform.telemetry.domain.TrackRetentionFloor;
import uz.horecaos.platform.telemetry.domain.TrackRetentionFloor.Outcome;
import uz.horecaos.platform.telemetry.infrastructure.startup.StreamChannelRegistryCheck;

/**
 * The decisions ADR 0045 makes, tested where they live rather than through a
 * database (ADR 0045).
 *
 * <p>Everything here is a rule somebody argued about: the derived retention
 * floor, the channel catalogue's declarations, the accuracy floor and the
 * staleness bound, and the structural claim that a signal carries no personal
 * data. None of them needs PostgreSQL, and a test that needed one would be a
 * slower way of asking a question about arithmetic.
 */
class TelemetryDecisionTests {

    // ---------------------------------------------------------- the retention floor

    @Test
    @DisplayName("thirty days clears the pilot's floor, and the floor moves with the calendar")
    void theFloorIsDerivedFromTheSettlementCalendar() {
        // ADR 0045's own worked example: a 7-day settlement period and a 7-day
        // dispute window put the floor at 14, and the configured 30 clears it
        // with room for a longer calendar nobody has asked for yet.
        assertThat(TrackRetentionFloor.floorDays(7, 7)).isEqualTo(14);
        assertThat(TrackRetentionFloor.check("configured", TrackRetentionFloor.CONFIGURED_TRACK_RETENTION_DAYS, 7, 7)
                        .outcome())
                .isEqualTo(Outcome.WITHIN_FLOOR);

        // The point of deriving it: finance lengthening the settlement period to a
        // calendar month moves the floor, and the same 30 that was comfortable
        // becomes a breach the next start refuses.
        assertThat(TrackRetentionFloor.floorDays(31, 14)).isEqualTo(45);
        assertThat(TrackRetentionFloor.check("configured", 30, 31, 14).refusesStartup())
                .isTrue();
    }

    @Test
    @DisplayName("the 72 hours of ADR 0045's first draft is refused, and the reason is in the message")
    void theFirstDraftsSeventyTwoHoursIsBelowTheFloor() {
        TrackRetentionFloor.Verdict verdict = TrackRetentionFloor.check("The code default", 3, 7, 7);

        assertThat(verdict.outcome()).isEqualTo(Outcome.BELOW_FLOOR);
        assertThat(verdict.refusesStartup()).isTrue();
        assertThat(verdict.explanation())
                .as("an operator reading a startup failure should learn why the floor exists")
                .contains("settlement period 7")
                .contains("statement dispute window 7")
                .contains("looks like evidence");
    }

    @Test
    @DisplayName("a retention far past the dispute window is reported, and does not refuse a start")
    void anExcessiveRetentionIsReportedRatherThanRefused() {
        TrackRetentionFloor.Verdict verdict = TrackRetentionFloor.check("Tenant x", 180, 7, 7);

        // A movement archive is the failure ADR 0029's PERSONAL_SENSITIVE class
        // exists to prevent, and it deserves to be seen. It is not a startup
        // failure, because a legal or contractual obligation could legitimately
        // require it and refusing to boot would be the platform overruling that.
        assertThat(verdict.outcome()).isEqualTo(Outcome.ABOVE_REVIEW_CEILING);
        assertThat(verdict.isProblem()).isTrue();
        assertThat(verdict.refusesStartup()).isFalse();
    }

    @Test
    void aCalendarWithNoPeriodOrNoDisputeWindowIsNotACalendar() {
        assertThatThrownBy(() -> TrackRetentionFloor.floorDays(0, 7)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrackRetentionFloor.floorDays(7, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // --------------------------------------------------------- the channel catalogue

    @ParameterizedTest
    @EnumSource(StreamChannel.class)
    @DisplayName("every channel names a capability, a scope, and a cadence cap")
    void everyChannelDeclaresItsAuthorizationAndItsCost(StreamChannel channel) {
        assertThat(channel.capability())
                .as("a channel with no capability is an unauthorized broadcast with a registry entry")
                .isNotNull();
        assertThat(channel.scopeTypes()).isNotEmpty();
        assertThat(channel.cadenceCap()).isPositive();
        assertThat(channel.source()).isNotBlank();
    }

    @Test
    void theStartupCheckPassesForTheShippedCatalogue() {
        StreamChannelRegistryCheck.verify();
    }

    @Test
    @DisplayName("the courier map is the only channel gated on courier.position.read")
    void thePositionChannelIsGatedOnThePositionCapability() {
        assertThat(StreamChannel.COURIER_POSITIONS.capability()).isEqualTo(Capability.COURIER_POSITION_READ);
        assertThat(StreamChannel.COURIER_POSITIONS.scopeTypes()).containsExactly(ScopeType.LOCATION);

        // The track reveal is never a stream. A capability granted per person for
        // one declared purpose cannot be the resting state of a held connection.
        assertThat(List.of(StreamChannel.values()))
                .as("no channel streams a stored track")
                .noneMatch(channel -> channel.capability() == Capability.COURIER_TRACK_REVEAL);
    }

    @Test
    @DisplayName("there is no customer channel, which is what bounds the connection count")
    void noChannelIsReachableWithoutAStaffCapability() {
        // The sizing property that made SSE affordable on one machine: the fleet
        // is bounded by staff count because customers do not stream. A channel
        // gated only by a customer-ownership declaration would be a
        // customer-facing stream by another name. Every current stream names a
        // capability that at least one tenant staff role actually holds.
        assertThat(List.of(StreamChannel.values()))
                .allMatch(channel -> List.of(PlatformRole.values()).stream()
                        .filter(role -> role != PlatformRole.PLATFORM_ADMIN)
                        .anyMatch(role -> role.grants(channel.capability())));
    }

    @Test
    void anUnregisteredChannelIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> StreamChannel.require("courier_tracks"))
                .isInstanceOf(StreamChannel.UnknownChannelException.class)
                .hasMessageContaining("ADR 0045");
        assertThat(StreamChannel.find("ORDER_QUEUE")).contains(StreamChannel.ORDER_QUEUE);
        assertThat(StreamChannel.find("order_queue")).contains(StreamChannel.ORDER_QUEUE);
    }

    // ------------------------------------------------------------ capability placement

    @Test
    @DisplayName("courier.track.reveal is in no bundle at all, including the superuser's")
    void theTrackRevealIsGrantedPerPersonOrNotAtAll() {
        assertThat(List.of(PlatformRole.values()))
                .as("""
                        ADR 0045: a bundle is standing access, and a reveal is meant to be an act
                        somebody had to ask for and that leaves an audit entry behind it.""")
                .noneMatch(role -> role.grants(Capability.COURIER_TRACK_REVEAL));
    }

    @Test
    void theLiveMapIsLocationScopedAndNotCrossTenant() {
        assertThat(PlatformRole.COURIER_DISPATCHER.grants(Capability.COURIER_POSITION_READ))
                .isTrue();
        assertThat(PlatformRole.LOCATION_MANAGER.grants(Capability.COURIER_POSITION_READ))
                .isTrue();
        assertThat(PlatformRole.PLATFORM_SUPPORT.grants(Capability.COURIER_POSITION_READ))
                .as("cross-tenant support with a standing fleet map is not what ADR 0045 decided")
                .isFalse();
        assertThat(PlatformRole.SUPPORT_AGENT.grants(Capability.COURIER_POSITION_READ))
                .as("ADR 0045 names dispatch and the branch, and a tenant-wide map is wider")
                .isFalse();
    }

    // ------------------------------------------------------ no personal data on a topic

    @Test
    @DisplayName("no coordinate, accuracy, or battery value is reachable from the signal payload")
    void aSignalCarriesNothingADataSubjectWouldRecognise() {
        // The structural check rather than a reading of the record: a field added
        // later called `lastKnownLatitude` fails here without anybody remembering
        // that this rule exists.
        assertThat(ClassificationScanner.scan(RealtimeSignal.class, "RealtimeSignal"))
                .as("""
                        ADR 0032 forbids anything above INTERNAL on any topic, and ADR 0045's
                        signal is the record a moving courier produces. A replica reads the live
                        row it already has access to; the coordinate never leaves the database.""")
                .isEmpty();

        // Belt and braces on the two names the heuristic would not catch, because
        // "acc" and "batteryPercent" are the fields somebody would be tempted to
        // add to save a read.
        assertThat(List.of(RealtimeSignal.class.getRecordComponents()))
                .noneMatch(component -> component.getName().toLowerCase(Locale.ROOT).contains("battery")
                        || component.getName().toLowerCase(Locale.ROOT).contains("accuracy")
                        || component.getName().toLowerCase(Locale.ROOT).contains("speed"));
    }

    @Test
    @DisplayName("the courier map's coarse rows carry no coordinate at all")
    void aCourierWhoCannotBeDrawnIsNotDrawnApproximately() {
        // The alternative somebody will propose is a lower-resolution pin. It is
        // worse than nothing: it looks like a position, it is treated as one, and
        // the courier it sends somebody to is a kilometre away.
        assertThat(List.of(CoarseCourier.class.getRecordComponents()))
                .noneMatch(component -> component.getName().toLowerCase(Locale.ROOT).contains("lat")
                        || component.getName().toLowerCase(Locale.ROOT).contains("lon")
                        || component.getName().toLowerCase(Locale.ROOT).contains("geohash"));
    }

    // ------------------------------------------------------------------- the map rules

    @Test
    @DisplayName("a reconnecting handset's backlog never walks the pin backwards")
    void anObservationOlderThanTenMinutesNeverMovesTheMap() {
        Instant now = Instant.parse("2026-08-23T12:00:00Z");

        assertThat(LivePositionRules.freshEnoughForTheMap(now.minusSeconds(30), now))
                .isTrue();
        assertThat(LivePositionRules.freshEnoughForTheMap(now.minusSeconds(9 * 60), now))
                .isTrue();
        assertThat(LivePositionRules.freshEnoughForTheMap(now.minusSeconds(11 * 60), now))
                .isFalse();

        // A handset with a wrong clock is the other direction of the same failure:
        // accepted once, it pins the courier in the future until real time catches
        // up, because every later reading then looks older.
        assertThat(LivePositionRules.freshEnoughForTheMap(now.plusSeconds(3600), now))
                .isFalse();
    }

    @Test
    void aFixWorseThanAHundredMetresIsKeptAndNotDrawn() {
        assertThat(LivePositionRules.drawable(12.0)).isTrue();
        assertThat(LivePositionRules.drawable(100.0)).isTrue();
        assertThat(LivePositionRules.drawable(101.0)).isFalse();
        assertThat(LivePositionRules.drawable(900.0)).isFalse();
    }

    @Test
    void theOnDutyGateSeesIdleCouriersAndTheAssignmentGateDoesNot() {
        // The trade-off ADR 0045 accepts in the open: the dispatcher board must
        // see idle couriers to assign them, so assignment-gating breaks the
        // capability outright rather than narrowing it.
        assertThat(CollectionGate.ON_DUTY.collects(0)).isTrue();
        assertThat(CollectionGate.ON_ASSIGNMENT.collects(0)).isFalse();
        assertThat(CollectionGate.ON_ASSIGNMENT.collects(1)).isTrue();
        assertThat(CollectionGate.find("on_assignment")).contains(CollectionGate.ON_ASSIGNMENT);
        assertThat(CollectionGate.find("everywhere")).isEmpty();
    }

    // ------------------------------------------------------------------ geohash, scope

    @Test
    @DisplayName("a five-character geohash locates a window to a district and no narrower")
    void theCleartextGeohashIsCoarse() {
        // Two points two kilometres apart in central Tashkent. The lookup value
        // is allowed to collide, because its only job is finding candidate rows
        // for a time-bounded reveal without decrypting the whole day.
        String centre = Geohash.encode5(41.311081, 69.240562);
        assertThat(centre).hasSize(5).matches("[0-9b-hjkmnp-z]{5}");

        assertThat(Geohash.distanceMeters(41.311081, 69.240562, 41.311081, 69.240562))
                .isZero();
        assertThat(Geohash.distanceMeters(41.311081, 69.240562, 41.326000, 69.228000))
                .isBetween(1500, 2500);
    }

    @Test
    void aScopeKeyCannotWidenPastTheConnectionItWasOpenedOn() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID somebodyElsesBranch = UUID.randomUUID();

        assertThat(ScopeKey.location(location).canonical()).isEqualTo("LOCATION:" + location);
        assertThat(ScopeKey.parse("LOCATION:" + location)).isEqualTo(ScopeKey.location(location));

        assertThat(ScopeKey.location(location)
                        .authorizationScope(tenant, brand, location)
                        .type())
                .isEqualTo(ScopeType.LOCATION);

        // The attack this closes: an operator authorized at their own branch
        // sending a neighbouring branch's identifier in the scope parameter.
        assertThatThrownBy(() -> ScopeKey.location(somebodyElsesBranch).authorizationScope(tenant, brand, location))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was not opened at");
    }

    @Test
    void noStreamIsTakenAtPlatformScope() {
        // A platform-wide stream is a cross-tenant broadcast: one tenant's
        // changes arriving on another tenant's screen.
        assertThatThrownBy(() -> new ScopeKey(ScopeType.PLATFORM, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScopeKey.parse("PLATFORM:" + UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSignalIsRefusedAtAScopeItsChannelDoesNotCarry() {
        UUID tenant = UUID.randomUUID();

        assertThatThrownBy(() -> RealtimeSignal.of(
                        tenant,
                        StreamChannel.COURIER_POSITIONS,
                        ScopeKey.tenant(tenant),
                        "COURIER",
                        UUID.randomUUID(),
                        null,
                        Instant.now()))
                .as("a tenant-wide courier map would show one branch's fleet to every branch")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
