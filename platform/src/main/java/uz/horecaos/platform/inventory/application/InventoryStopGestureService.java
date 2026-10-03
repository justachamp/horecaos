package uz.horecaos.platform.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.catalog.api.ChannelOfferingLookup;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.inventory.api.BusinessDayWindows;
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.CreateStop;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopOutcome;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopsFrozenException;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;

/**
 * One gesture, many variants (ADR 0141): a bulk stop of up to {@value #MAX_ITEMS}
 * variants, or a product-level stop written as a group of variant stops, all under
 * one {@code group_id}.
 *
 * <p>Deliberately not transactional, and deliberately not part of {@link
 * AvailabilityStopService}: each variant is applied through that service as an ordinary
 * proxied call, so it opens and settles its own transaction and one variant's failure
 * can never roll back the others' successes — ADR 0039's argument, and the contract
 * {@code InventoryBulkAvailabilityService} already keeps for the position toggle. A
 * frozen tenant is the one exception: that is a refusal of the whole gesture, not of
 * an item, so it is raised once, before anything is written.
 *
 * <p>A product-level stop is the variants the product has <em>at that moment</em>. A
 * variant added to the product afterwards is not covered (ADR 0141, Accepted
 * trade-offs); stopping at {@code BRAND} scope per variant is how a brand covers
 * what does not exist yet.
 */
@Service
public class InventoryStopGestureService {

    /** ADR 0039's cap, the same one the position bulk toggle reuses. */
    public static final int MAX_ITEMS = InventoryBulkAvailabilityService.MAX_ITEMS;

    private static final Logger log = LoggerFactory.getLogger(InventoryStopGestureService.class);

    private final AvailabilityStopService stops;
    private final @Nullable ChannelOfferingLookup catalog;
    private final BusinessDayWindows businessDays;
    private final Clock clock;

    public InventoryStopGestureService(
            AvailabilityStopService stops,
            @Nullable ChannelOfferingLookup catalog,
            BusinessDayWindows businessDays,
            Clock clock) {
        this.stops = stops;
        this.catalog = catalog;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /**
     * The shape of one gesture. Exactly one of {@code variantIds} and {@code productId}
     * names what to stop.
     *
     * @param untilEndOfTradingDay stop until the tenant's business day ends rather than
     *     indefinitely; mutually exclusive with {@code endsAt}
     */
    public record Gesture(
            UUID tenantId,
            UUID brandId,
            StopScopeType scopeType,
            @Nullable UUID locationId,
            @Nullable UUID menuId,
            @Nullable UUID channelId,
            @Nullable List<UUID> variantIds,
            @Nullable UUID productId,
            String reasonCode,
            @Nullable Instant endsAt,
            boolean untilEndOfTradingDay,
            StopSource source,
            String actorSubject,
            Capability capability) {}

    public enum ItemStatus {
        APPLIED,
        FAILED
    }

    /**
     * @param changed false for an {@code APPLIED} item means an identical stop was already in force
     * @param problemCode set only when {@code FAILED}: {@code INVALID}, {@code TARGET_NOT_FOUND} or {@code UNEXPECTED_FAILURE}
     */
    public record ItemOutcome(
            UUID variantId,
            ItemStatus status,
            boolean changed,
            @Nullable UUID stopId,
            @Nullable String problemCode) {}

    public record GestureResult(UUID groupId, List<ItemOutcome> items) {}

    /**
     * @throws IllegalArgumentException when the gesture itself is malformed: no variants (or a
     *     product with none), more than {@value #MAX_ITEMS}, both a list and a product, or both
     *     an explicit end and "end of trading day"
     * @throws StopsFrozenException when creation is frozen (the one refusal of the whole gesture)
     */
    public GestureResult apply(Gesture gesture) {
        // Refusals of the gesture itself are raised once, here, rather than reported as the
        // same failure on every item: a scope the platform refuses, a source that is only
        // reserved, an end already in the past.
        if (!gesture.scopeType().writable()) {
            throw new IllegalArgumentException(
                    "A " + gesture.scopeType() + " stop is not supported; stop the channel the device runs on instead");
        }
        if (!gesture.source().writable()) {
            throw new IllegalArgumentException("A " + gesture.source() + " stop is reserved and cannot be written yet");
        }
        if (gesture.endsAt() != null && !gesture.endsAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("A stop must end in the future");
        }
        List<UUID> variantIds = variantsOf(gesture);
        if (variantIds.isEmpty()) {
            throw new IllegalArgumentException("A stop names at least one variant");
        }
        if (variantIds.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("A stop names at most " + MAX_ITEMS + " variants");
        }
        if (gesture.untilEndOfTradingDay() && gesture.endsAt() != null) {
            throw new IllegalArgumentException("Name an end or the end of the trading day, not both");
        }
        Instant endsAt = gesture.endsAt();
        if (gesture.untilEndOfTradingDay()) {
            endsAt = businessDays.endOfBusinessDay(gesture.tenantId(), clock.instant());
        }

        UUID groupId = Ids.newId();
        List<ItemOutcome> outcomes = new ArrayList<>(variantIds.size());
        for (UUID variantId : variantIds) {
            outcomes.add(applyOne(gesture, variantId, endsAt, groupId));
        }
        return new GestureResult(groupId, outcomes);
    }

    private List<UUID> variantsOf(Gesture gesture) {
        List<UUID> named = gesture.variantIds();
        UUID productId = gesture.productId();
        boolean hasList = named != null && !named.isEmpty();
        boolean hasProduct = productId != null;
        if (hasList == hasProduct) {
            throw new IllegalArgumentException("Name either variantIds or a productId");
        }
        if (named != null && hasList) {
            return named.stream().distinct().toList();
        }
        if (catalog == null || productId == null) {
            throw new IllegalArgumentException("A product cannot be expanded here");
        }
        return catalog.variantIdsOfProduct(gesture.tenantId(), gesture.brandId(), productId);
    }

    private ItemOutcome applyOne(Gesture gesture, UUID variantId, @Nullable Instant endsAt, UUID groupId) {
        try {
            StopOutcome outcome = stops.stop(new CreateStop(
                    gesture.tenantId(),
                    gesture.brandId(),
                    variantId,
                    gesture.scopeType(),
                    gesture.locationId(),
                    gesture.menuId(),
                    gesture.channelId(),
                    gesture.source(),
                    null,
                    gesture.reasonCode(),
                    endsAt,
                    groupId,
                    gesture.actorSubject(),
                    gesture.capability()));
            StopRow stop = outcome.stop();
            return new ItemOutcome(variantId, ItemStatus.APPLIED, outcome.changed(), stop.id(), null);
        } catch (StopsFrozenException frozen) {
            throw frozen;
        } catch (AvailabilityStopService.StopTargetNotFoundException notFound) {
            return new ItemOutcome(variantId, ItemStatus.FAILED, false, null, "TARGET_NOT_FOUND");
        } catch (IllegalArgumentException invalid) {
            return new ItemOutcome(variantId, ItemStatus.FAILED, false, null, "INVALID");
        } catch (DataAccessException | IllegalStateException unexpected) {
            // One variant's failure must never stop the rest of the gesture, nor be
            // reported as a silent success.
            log.error("Stop for variant {} failed unexpectedly", variantId, unexpected);
            return new ItemOutcome(variantId, ItemStatus.FAILED, false, null, "UNEXPECTED_FAILURE");
        }
    }
}
