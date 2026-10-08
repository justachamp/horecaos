package uz.horecaos.platform.reviews.api;

import java.util.UUID;

/**
 * Whether a brand's recovery policy announces cases to the staff chats (ADR 0159, Decision 10), for
 * {@code notifications}.
 *
 * <p>Both are off by default: an alert nobody asked for is the noise that gets a chat muted, and an
 * auto-opened case at two stars starts clocks that nobody may be watching. Breaches are counted
 * whatever these say; these decide only whether anyone is told.
 */
public interface RecoveryAlertSettings {

    /** Whether a newly opened case is announced to the chats subscribed to {@code RECOVERY_CASE_OPENED}. */
    boolean alertOnOpen(UUID tenantId, UUID brandId);

    /** Whether a breached clock is announced to the chats subscribed to {@code RECOVERY_CASE_SLA_BREACHED}. */
    boolean alertOnBreach(UUID tenantId, UUID brandId);
}
