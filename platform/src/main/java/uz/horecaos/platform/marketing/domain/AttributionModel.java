package uz.horecaos.platform.marketing.domain;

/**
 * Who is credited with a goal event (ADR 0112): the first or the most recent contact
 * in the attribution window.
 *
 * <p>Two, and only two. A restaurant's shortest funnel is a contact and the next order,
 * and the bank reference's "lead/application" model has no equivalent here. Anything
 * richer (multi-touch, time-decay) is a decision about measurement this record does not
 * make, and a marketer arriving from a mature CVM platform will ask for it.
 */
public enum AttributionModel {

    /** Credit the first scenario or campaign that contacted the guest in the window. */
    FIRST_TOUCH,

    /** Credit the most recent one before the goal event. */
    LAST_TOUCH
}
