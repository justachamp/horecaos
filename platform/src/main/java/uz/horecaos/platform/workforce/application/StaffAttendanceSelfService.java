package uz.horecaos.platform.workforce.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.EntryRow;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * A person's own attendance (ADR 0156, «Моя работа»): their open shift, their next published shifts and
 * their last few. Resolved from the token's subject and the path's tenant, never from a supplied id --
 * there is no parameter through which the capability could be pointed at somebody else.
 *
 * <p>A person sees their own shifts always, whatever the tenant's other settings: that is part of the
 * notice a tenant gives its staff before it switches the feature on (ADR 0156, third open input).
 */
@Service
public class StaffAttendanceSelfService {

    private static final int UPCOMING = 7;
    private static final int RECENT = 10;

    private final JdbcStaffShiftStore shifts;
    private final JdbcStaffRosterStore roster;
    private final StaffPeople people;
    private final AttendanceSettings settings;
    private final Clock clock;

    public StaffAttendanceSelfService(
            JdbcStaffShiftStore shifts,
            JdbcStaffRosterStore roster,
            StaffPeople people,
            AttendanceSettings settings,
            Clock clock) {
        this.shifts = shifts;
        this.roster = roster;
        this.people = people;
        this.settings = settings;
        this.clock = clock;
    }

    public record MyAttendance(@Nullable ShiftRow open, List<EntryRow> upcoming, List<ShiftRow> recent) {}

    /** Whether the tenant records hours at all: the console shows the screens only when it does. */
    public boolean enabled(UUID tenantId) {
        return settings.enabled(tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<MyAttendance> mine(UUID tenantId, String subject) {
        settings.requireEnabled(tenantId);
        Optional<UUID> memberId = people.memberIdOf(tenantId, subject);
        if (memberId.isEmpty()) {
            return Optional.empty();
        }
        UUID member = memberId.get();
        return Optional.of(new MyAttendance(
                shifts.findOpenFor(tenantId, member).orElse(null),
                roster.nextPublishedFor(tenantId, member, clock.instant(), UPCOMING),
                shifts.list(tenantId, null, null, member, null, null, null, RECENT)));
    }
}
