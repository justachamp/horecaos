package uz.horecaos.platform.courier.domain;

import java.util.Set;

/**
 * Shift authority, per transition (ADR 0042).
 *
 * <p>This enum is the whole of the rule and there is deliberately no "shift
 * manage" capability that grants all of it. A self-employed person decides when
 * they work, so a manager who could open a shift could create paid hours for
 * somebody who was at home, and a manager who could end a break would be
 * directing rest periods — which is the fact pattern that reclassifies the
 * engagement. What a manager may do is end service and send somebody home, with
 * a reason, and approve the hours afterwards.
 */
public enum ShiftTransition {
    OPEN(Set.of(ShiftActor.COURIER)),
    START_BREAK(Set.of(ShiftActor.COURIER)),
    END_BREAK(Set.of(ShiftActor.COURIER)),
    CLOSE(Set.of(ShiftActor.COURIER, ShiftActor.MANAGER, ShiftActor.SWEEPER)),
    APPROVE_HOURS(Set.of(ShiftActor.MANAGER));

    private final Set<ShiftActor> permitted;

    ShiftTransition(Set<ShiftActor> permitted) {
        this.permitted = permitted;
    }

    public boolean permits(ShiftActor actor) {
        return permitted.contains(actor);
    }

    public Set<ShiftActor> permitted() {
        return permitted;
    }
}
