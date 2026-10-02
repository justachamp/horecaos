package uz.horecaos.platform.dinein.domain;

/**
 * Who opened a table session (ADR 0143).
 *
 * <p>A column and not a status, on purpose. A claim is an ordinary session with a
 * lapse attached, so everything that means "live" -- {@code SessionStatus.live()},
 * {@code findLiveSessionAtTable}, the occupancy predicates, the guest routes'
 * status checks -- keeps meaning what it meant, and the state machine is untouched.
 * Adding a {@code CLAIMED} status would have made every one of those learn a fifth
 * value, and a missed one leaves a claim that occupies nothing or blocks nothing.
 */
public enum SessionOrigin {

    /** Opened by a person holding {@code dinein.session.manage}. The staff path, unchanged. */
    STAFF,

    /**
     * Opened by a guest holding a table's guest token and a signed-in customer
     * session. Provisional until a round the restaurant accepted is on it, or a
     * member of staff takes charge of it (ADR 0143, Decision 4).
     */
    GUEST_QR
}
