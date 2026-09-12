package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One bonus grant's unspent remainder (ADR 0095): the SUM of the grant's own
 * {@code BONUS_GRANT} entry and every entry naming it as {@code grantId} —
 * a spend or the lapse that already drew it down.
 */
public record BonusGrantBalance(
        UUID grantId, long grantedMinor, long remainingMinor, String currency, Instant expiresAt, String reason) {}
