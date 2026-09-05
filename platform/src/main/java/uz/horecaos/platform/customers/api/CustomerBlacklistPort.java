package uz.horecaos.platform.customers.api;

import java.util.UUID;

/**
 * Whether an account is currently refused for placing an order (frontend
 * information architecture §5.2).
 *
 * <p>{@code CustomerIdentityService#resolve} was, until this port existed, the
 * only enforcement point {@code CustomerBlacklistService} had: it refuses a
 * blacklisted principal a session at sign-in, so nothing downstream has to
 * remember to ask. That does not cover a principal who is already holding a
 * session — a long-lived token minted before an operator blacklisted the
 * account — so ordering needs its own read at the point an order is actually
 * taken, the same shape {@link CustomerDirectory} exists for so that ordering
 * never has to reach across a schema for an answer only the customers module
 * owns. The precedent is {@code uz.horecaos.platform.loyalty.api.ReferralGrantPort}:
 * one narrow, single-purpose port added for exactly one caller outside its
 * owning module.
 *
 * <p>One query, no reason, no reveal. The reason a blacklist entry carries is
 * personal data an operator wrote (ADR 0029) and a checkout gate has no
 * audited purpose to decrypt it for — it only needs a yes or no, the same
 * split {@link CustomerBlacklistService#isCurrentlyBlacklisted} already draws
 * for its own caller.
 */
public interface CustomerBlacklistPort {

    /**
     * Whether this account currently carries an active, unexpired blacklist
     * entry — checked against the clock on every call, never a cached or
     * swept status, because a lifted or expired entry must stop refusing the
     * instant it lifts or expires.
     */
    boolean isCurrentlyBlacklisted(UUID tenantId, UUID accountId);
}
