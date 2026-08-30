package uz.horecaos.platform.notifications.domain;

import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactMethod;

/**
 * How a message physically reaches someone (ADR 0020).
 *
 * <p>Only {@link #SMS} is wired. The others are declared because the schema and
 * the template key both carry a channel and an open string there would let a typo
 * create a channel nobody sends on, but {@link #isWired} is what the eligibility
 * path actually asks. A tenant authoring an EMAIL template gets a clear refusal
 * rather than a message that is created, resolved, and then never sent.
 */
public enum NotificationChannel {
    SMS(true, ContactMethod.PHONE),
    EMAIL(false, ContactMethod.EMAIL),
    PUSH(false, null),
    MESSAGING_APP(false, null);

    private final boolean wired;
    private final ContactMethod contactMethod;

    NotificationChannel(boolean wired, ContactMethod contactMethod) {
        this.wired = wired;
        this.contactMethod = contactMethod;
    }

    /** Whether an adapter exists for this channel in this release. */
    public boolean isWired() {
        return wired;
    }

    /** The ADR 0015 contact kind this channel addresses, or null where none applies. */
    public ContactMethod contactMethod() {
        return contactMethod;
    }
}
