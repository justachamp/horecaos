package uz.horecaos.platform.support;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;

/**
 * A {@link StaffDirectory} stand-in for suites that are about something else
 * and only need a name to come back (ADR 0139).
 *
 * <p>Tenant-aware in the one way that matters to the tests that use it: a name
 * is registered <em>for a tenant</em>, and asking about the same subject under
 * another tenant answers nothing, which is the structural guarantee the real
 * port exists to give. A suite that wants the real thing -- the encrypted row,
 * the cache, the eviction -- builds the real service against a real database.
 */
public final class StaffDirectories {

    private StaffDirectories() {}

    /** A directory that knows nobody. */
    public static StaffDirectory none() {
        return new Fake();
    }

    /** A mutable directory a test registers names with. */
    public static Fake fake() {
        return new Fake();
    }

    public static final class Fake implements StaffDirectory {

        private final Map<String, String> names = new LinkedHashMap<>();
        private final Map<String, UUID> members = new LinkedHashMap<>();

        public Fake name(UUID tenantId, String subject, String name) {
            names.put(key(tenantId, subject), name);
            return this;
        }

        public Fake member(UUID tenantId, String subject, UUID memberId) {
            members.put(key(tenantId, subject), memberId);
            return this;
        }

        public void clear() {
            names.clear();
            members.clear();
        }

        @Override
        public @Nullable String nameOf(UUID tenantId, String subject) {
            return names.get(key(tenantId, subject));
        }

        @Override
        public Map<String, String> namesOf(UUID tenantId, Collection<String> subjects) {
            Map<String, String> answers = new LinkedHashMap<>();
            for (String subject : subjects) {
                String name = nameOf(tenantId, subject);
                if (name != null) {
                    answers.put(subject, name);
                }
            }
            return answers;
        }

        @Override
        public Optional<UUID> memberIdOf(UUID tenantId, String subject) {
            return Optional.ofNullable(members.get(key(tenantId, subject)));
        }

        private static String key(UUID tenantId, String subject) {
            return tenantId + "|" + subject;
        }
    }
}
