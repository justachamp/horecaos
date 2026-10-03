package uz.horecaos.platform.iam.application.staff;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;

/**
 * {@link StaffDirectory} over the tenant's own staff member record (ADR 0139).
 *
 * <p>The order of an answer is: the tenant's cache, then the tenant's row (the
 * name, or the non-personal {@code display_reference} when the row has none),
 * and only for a subject with <em>no row at all</em> the identity provider's
 * interim copy of the name. That last step is the rollout fallback ADR 0139
 * names, and it is narrower than the lookup it replaces: it is attempted only
 * for a subject who holds or held a staff job in this very tenant. Asking
 * about a colleague of another tenant, or about a HorecaOS support person, is
 * answered with nothing -- the interim read answered with whatever Keycloak
 * held, for anybody, which is the tenant-blindness this port exists to end.
 *
 * <p>A failed integrity check on a stored name is not an exception here: the
 * audit log and the order detail must still render. The member shows by its
 * reference, and the failure is logged without a value by {@link StaffMemberCodec}.
 */
@Service
class StaffDirectoryService implements StaffDirectory {

    private static final Logger log = LoggerFactory.getLogger(StaffDirectoryService.class);

    private final JdbcStaffMemberStore store;
    private final StaffMemberCodec codec;
    private final StaffNameCache cache;
    private final StaffAccounts accounts;

    StaffDirectoryService(
            JdbcStaffMemberStore store, StaffMemberCodec codec, StaffNameCache cache, StaffAccounts accounts) {
        this.store = store;
        this.codec = codec;
        this.cache = cache;
        this.accounts = accounts;
    }

    @Override
    public @Nullable String nameOf(UUID tenantId, String subject) {
        return namesOf(tenantId, List.of(subject)).get(subject);
    }

    @Override
    public Map<String, String> namesOf(UUID tenantId, Collection<String> subjects) {
        Map<String, String> answers = new LinkedHashMap<>();
        Set<String> misses = new LinkedHashSet<>();
        for (String subject : subjects) {
            if (subject == null || subject.isBlank() || answers.containsKey(subject)) {
                continue;
            }
            String cached = cache.get(tenantId, subject);
            if (cached != null) {
                answers.put(subject, cached);
            } else {
                misses.add(subject);
            }
        }
        if (misses.isEmpty()) {
            return answers;
        }

        Set<String> withRow = new LinkedHashSet<>();
        for (MemberRow row : store.findBySubjects(tenantId, misses)) {
            withRow.add(row.principalSubject());
            String name = codec.displayName(row);
            String shown = name != null ? name : row.displayReference();
            answers.put(row.principalSubject(), shown);
            cache.put(tenantId, row.principalSubject(), shown);
        }

        for (String subject : misses) {
            if (withRow.contains(subject)) {
                continue;
            }
            String fallback = identityProviderName(tenantId, subject);
            if (fallback != null) {
                answers.put(subject, fallback);
                cache.put(tenantId, subject, fallback);
            }
        }
        return answers;
    }

    @Override
    public Optional<UUID> memberIdOf(UUID tenantId, String subject) {
        return store.findBySubject(tenantId, subject).map(MemberRow::id);
    }

    /**
     * The rollout fallback: Keycloak's "First Last", for a staff subject of this
     * tenant whose row has not been backfilled yet. Removed, with the cache
     * entry it fills, once every environment reports zero unbacked active
     * subjects.
     */
    private @Nullable String identityProviderName(UUID tenantId, String subject) {
        if (!store.hasEverHeldStaffJob(tenantId, subject, StaffMembers.MACHINE_ROLE_CODES)) {
            return null;
        }
        try {
            return accounts.displayName(subject).orElse(null);
        } catch (RuntimeException unavailable) {
            // A Keycloak outage must not blank a screen the tenant's own record
            // would have answered; this subject has no record yet, so it simply
            // shows by id for now, as it did before the fallback.
            log.warn("The identity provider could not name a staff subject of tenant {}", tenantId);
            return null;
        }
    }
}
