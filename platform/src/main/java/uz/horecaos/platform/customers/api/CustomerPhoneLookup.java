package uz.horecaos.platform.customers.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Resolving a phone number to the customer accounts that hold it (ADR 0015,
 * consumed by ADR 0064's screen-pop).
 *
 * <p>Takes the plaintext number and normalizes/hashes it internally, the same
 * way {@code CustomerProfileService.findAccountsByContact} already does for
 * the CRM grid's phone search — a caller outside this module never computes or
 * carries the ADR 0029 lookup hash itself.
 *
 * <p>Returns every match rather than one, on the same argument {@link
 * CustomerDirectory} already states: two households can share one phone, and a
 * caller that auto-picked the first result would be the auto-merge ADR 0015
 * forbids, just moved one module over. A screen-pop that gets more than one
 * match shows the most recently active one and says so, rather than guessing
 * silently.
 */
public interface CustomerPhoneLookup {

    List<CustomerAccountRef> findByPhone(UUID tenantId, String rawPhoneNumber);

    /**
     * Display-safe profile fields for a resolved account — a name, never a
     * contact value or an address. Those stay behind their own ADR 0029
     * reveal-gated endpoints; a screen-pop card is not a reveal.
     */
    Optional<CardProfile> cardProfile(UUID tenantId, UUID accountId);

    record CardProfile(UUID accountId, @Nullable String displayName) {}
}
