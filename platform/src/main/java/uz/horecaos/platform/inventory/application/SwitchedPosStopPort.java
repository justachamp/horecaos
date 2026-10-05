package uz.horecaos.platform.inventory.application;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.inventory.api.AvailabilityStopPort;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService.StockPositionView;

/**
 * The POS poll's two writers (ADR 0141, rollback switch three): a stop with stops being read, the
 * position boolean once they are not.
 *
 * <p>With {@code inventory.stops.read_enabled} on, a POS "out of stock" reading writes a {@code POS}
 * stop and "back in stock" ends only that binding's own ({@link AvailabilityStopService}) -- the
 * arrangement that stops a POS from un-86'ing a dish an operator stopped. After the decommission
 * nothing reads a stop, so a new POS stop would be a row doing nothing; the poll goes back to
 * flipping the boolean of a {@code BINARY} item, as it did before stops existed, and the
 * materialisation run has already turned its active {@code POS} stops on such items into {@code
 * binary_available = false}, so the next "back in stock" reading lifts them as it always did. A POS
 * stop on an item that is not {@code BINARY} has no boolean to land on: it was on the run's report
 * and is not written here.
 *
 * <p>{@link Primary} so the poll gets this and not the stop service it wraps; the stop service stays
 * injectable by its own class everywhere else.
 */
@Component
@Primary
public class SwitchedPosStopPort implements AvailabilityStopPort {

    private static final Logger log = LoggerFactory.getLogger(SwitchedPosStopPort.class);

    private final AvailabilityStopService stops;
    private final InventoryService inventory;
    private final StopReadSwitch readSwitch;

    public SwitchedPosStopPort(AvailabilityStopService stops, InventoryService inventory, StopReadSwitch readSwitch) {
        this.stops = stops;
        this.inventory = inventory;
        this.readSwitch = readSwitch;
    }

    @Override
    public void placePosStop(UUID tenantId, UUID brandId, UUID locationId, UUID variantId, UUID bindingId) {
        if (readSwitch.readsEnabled(tenantId, brandId)) {
            stops.placePosStop(tenantId, brandId, locationId, variantId, bindingId);
            return;
        }
        setPosition(tenantId, locationId, variantId, false);
    }

    @Override
    public boolean liftPosStop(UUID tenantId, UUID locationId, UUID variantId, UUID bindingId) {
        if (readSwitch.readsEnabled(tenantId)) {
            return stops.liftPosStop(tenantId, locationId, variantId, bindingId);
        }
        // Its own row first, so turning stops back on never resurrects a POS stop the POS has
        // since said is over; then the position, which is what is read now.
        boolean ended = stops.liftPosStop(tenantId, locationId, variantId, bindingId);
        boolean flipped = setPosition(tenantId, locationId, variantId, true);
        return ended || flipped;
    }

    private boolean setPosition(UUID tenantId, UUID locationId, UUID variantId, boolean available) {
        Optional<StockPositionView> position = inventory.findStockPosition(tenantId, locationId, variantId);
        if (position.isEmpty()) {
            // The port's contract for a mapping that points at nothing stocked here.
            throw new IllegalArgumentException("Variant " + variantId + " is not stocked at this location");
        }
        if (position.get().trackingMode() != TrackingMode.BINARY) {
            log.debug(
                    "A POS reading for a {} item cannot land on a position; stops are switched off",
                    position.get().trackingMode());
            return false;
        }
        if (Boolean.valueOf(available).equals(position.get().binaryAvailable())) {
            return false;
        }
        inventory.setAvailability(
                tenantId, locationId, variantId, available, AvailabilityStopService.POS_REASON, null, StopSource.POS);
        return true;
    }
}
