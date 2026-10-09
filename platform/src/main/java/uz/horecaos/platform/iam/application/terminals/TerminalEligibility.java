package uz.horecaos.platform.iam.application.terminals;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.RosterDisplay;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts.Person;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.Candidate;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * Who may unlock a terminal (ADR 0155, Eligibility and the roster): nobody is listed by hand.
 *
 * <p>A person may unlock a terminal when they are an {@code ACTIVE} staff member and hold, at that terminal's location,
 * every capability the device class names for attribution -- for the kitchen tablet, {@code kitchen.ticket.advance}.
 * That is the spec's "which jobs may unlock it", derived from the grants rather than maintained twice, and asked of the
 * same {@link AuthorizationService} every other check uses, so a grant at brand or tenant scope counts and a revoked grant
 * stops counting on the next request.
 *
 * <p>The roster is that list as labels. It is a display, so it is cached for thirty seconds as an ADR 0033 registered
 * accelerator; the unlock, the activation and the interceptor never read the cache -- they ask {@link #eligible}.
 */
@Component
public class TerminalEligibility {

    private final JdbcTerminalStore store;
    private final StaffTerminalFacts staff;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final Cache rosterCache;

    public TerminalEligibility(
            JdbcTerminalStore store,
            StaffTerminalFacts staff,
            AuthorizationService authorization,
            Clock clock,
            CacheManager caches) {
        this.store = store;
        this.staff = staff;
        this.authorization = authorization;
        this.clock = clock;
        Cache cache = caches.getCache(CacheRegistry.TERMINAL_ROSTER.cacheName());
        this.rosterCache =
                cache != null ? cache : new org.springframework.cache.concurrent.ConcurrentMapCache("unused");
    }

    /** The capabilities a person must hold at the terminal's location to be listed and to unlock it. */
    static Set<Capability> attributionSet(DevicePrincipalClass deviceClass) {
        return switch (deviceClass) {
            case KITCHEN_KDS -> EnumSet.of(Capability.KITCHEN_TICKET_ADVANCE);
            // A class nobody unlocks has no set: ADR 0155 gives each future class its own, in the record that adds it.
            case KITCHEN_VDU, KIOSK -> Set.of();
        };
    }

    /** Whether this person may unlock this device now. Computed from the grants every time; never from the roster. */
    public boolean eligible(Person person, DevicePrincipalView device) {
        if (!device.deviceClass().unlockable() || !person.active()) {
            return false;
        }
        Set<Capability> required = attributionSet(device.deviceClass());
        if (required.isEmpty()) {
            return false;
        }
        ResourceScope location = ResourceScope.location(device.tenantId(), device.brandId(), device.locationId());
        return required.stream().allMatch(capability -> authorization.has(person.subject(), capability, location));
    }

    /** One entry of the roster: an opaque id and what the screen shows. */
    public record Entry(UUID memberId, String label) {}

    /** The people listed on the terminal, ordered by label. */
    public List<Entry> roster(DevicePrincipalView device, RosterDisplay display) {
        String key = device.tenantId() + "|" + device.locationId() + "|" + device.deviceClass() + "|" + display;
        @SuppressWarnings("unchecked")
        List<Entry> cached = rosterCache.get(key, () -> compute(device, display));
        return cached == null ? compute(device, display) : cached;
    }

    private List<Entry> compute(DevicePrincipalView device, RosterDisplay display) {
        List<Candidate> candidates =
                store.candidatesAt(device.tenantId(), device.brandId(), device.locationId(), clock.instant());
        Map<UUID, Person> people = staff.persons(
                device.tenantId(), candidates.stream().map(Candidate::memberId).toList());
        List<Entry> entries = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Person person = people.get(candidate.memberId());
            if (person == null || !eligible(person, device)) {
                continue;
            }
            entries.add(new Entry(person.memberId(), labelOf(person, display)));
        }
        entries.sort(java.util.Comparator.comparing(Entry::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.memberId().toString()));
        return List.copyOf(entries);
    }

    /**
     * What a colleague reads as on a shared screen: first name and the initial of the last name, or the non-personal
     * {@code S-0142}. Never a phone, photo or employee number. A person whose account holds no name yet reads as their
     * reference, which is also what the staff list does.
     */
    public static String labelOf(Person person, RosterDisplay display) {
        if (display == RosterDisplay.REFERENCE) {
            return person.displayReference();
        }
        String name = StaffTerminalFacts.nameWithInitial(person.firstName(), person.lastName());
        return name == null ? person.displayReference() : name;
    }
}
