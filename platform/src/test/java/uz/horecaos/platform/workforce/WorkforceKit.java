package uz.horecaos.platform.workforce;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards;
import uz.horecaos.platform.iam.api.staff.StaffPayrollIdentity;
import uz.horecaos.platform.ordering.api.OperatorActivity;
import uz.horecaos.platform.reporting.application.AttendanceBusinessDayAdapter;
import uz.horecaos.platform.reporting.application.BusinessDayService;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.workforce.application.AttendanceSettings;
import uz.horecaos.platform.workforce.application.Branch;
import uz.horecaos.platform.workforce.application.StaffAttendanceExportAdapter;
import uz.horecaos.platform.workforce.application.StaffAttendanceSelfService;
import uz.horecaos.platform.workforce.application.StaffPeople;
import uz.horecaos.platform.workforce.application.StaffRosterService;
import uz.horecaos.platform.workforce.application.StaffShiftFactSourceImpl;
import uz.horecaos.platform.workforce.application.StaffShiftHours;
import uz.horecaos.platform.workforce.application.StaffShiftQueryService;
import uz.horecaos.platform.workforce.application.StaffShiftService;
import uz.horecaos.platform.workforce.application.StaffTodayService;
import uz.horecaos.platform.workforce.application.WorkforceAudit;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;

/**
 * The workforce module wired by hand over a real database, an advancing clock and stand-ins for the
 * neighbouring modules' ports (ADR 0156 tests).
 *
 * <p>What is real: every table, constraint and statement in {@code workforce}, the services above them,
 * the business-day adapter {@code reporting} supplies and the audit recorder. What is a stand-in, and
 * why: authorization ({@link Grants}, because the grant tables are {@code iam}'s own tests' subject),
 * the staff directory ({@link Roster}, over rows really inserted into {@code iam.staff_members} so the
 * foreign keys bite), configuration ({@link Settings}) and order attribution ({@link Orders}).
 *
 * <p>A fixture that writes a closed shift by hand would prove nothing -- the tests that care open and
 * close shifts through {@link #shifts}, moving {@link #clock} between the two, so a duration is a
 * duration and not an instant.
 */
final class WorkforceKit {

    static final UUID TENANT = UUID.fromString("018fc200-7000-7000-8000-0000000000a1");
    static final UUID OTHER_TENANT = UUID.fromString("018fc200-7000-7000-8000-0000000000a2");
    static final UUID BRAND = UUID.fromString("018fc200-7000-7000-8000-0000000000b1");
    static final UUID OTHER_BRAND = UUID.fromString("018fc200-7000-7000-8000-0000000000b2");
    static final UUID LOCATION = UUID.fromString("018fc200-7000-7000-8000-0000000000c1");
    static final UUID SECOND_LOCATION = UUID.fromString("018fc200-7000-7000-8000-0000000000c2");
    static final UUID OTHER_LOCATION = UUID.fromString("018fc200-7000-7000-8000-0000000000c3");

    static final String ZONE = "Asia/Tashkent";

    final DataSource dataSource;
    final JdbcClient jdbc;
    final MovableClock clock;
    final Settings settings = new Settings();
    final Grants grants = new Grants();
    final Roster roster = new Roster();
    final Orders orders = new Orders();

    final JdbcStaffRosterStore rosterStore;
    final JdbcStaffShiftStore shiftStore;
    final StaffPeople people;
    final StaffRosterService rota;
    final StaffShiftService shifts;
    final StaffTodayService today;
    final StaffShiftQueryService queries;
    final StaffAttendanceSelfService mine;
    final StaffShiftHours hours;
    final StaffShiftFactSourceImpl factSource;
    final StaffAttendanceExportAdapter exportPort;
    final AttendanceBusinessDayAdapter businessDay;
    final JdbcReportingStore reportingStore;
    final JdbcAuditRecorder auditRecorder;

    private WorkforceKit(TestDatabase.Handle db, Instant start) {
        this.dataSource = db.dataSource();
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = new MovableClock(start);
        JsonMapper mapper = JsonMapper.builder().build();
        this.auditRecorder = new JdbcAuditRecorder(jdbc, mapper);
        this.reportingStore = new JdbcReportingStore(jdbc);
        this.businessDay = new AttendanceBusinessDayAdapter(new BusinessDayService(reportingStore));
        this.rosterStore = new JdbcStaffRosterStore(jdbc);
        this.shiftStore = new JdbcStaffShiftStore(jdbc);
        AttendanceSettings attendance = new AttendanceSettings(settings);
        this.people = new StaffPeople(roster, roster, grants);
        WorkforceAudit audit = new WorkforceAudit(auditRecorder);
        this.rota = new StaffRosterService(rosterStore, shiftStore, people, attendance, audit, clock);
        this.shifts = new StaffShiftService(
                shiftStore, rosterStore, people, attendance, businessDay, grants, audit, clock);
        this.today = new StaffTodayService(rosterStore, shiftStore, people, attendance, businessDay, orders, clock);
        this.queries = new StaffShiftQueryService(shiftStore, people, attendance);
        this.mine = new StaffAttendanceSelfService(shiftStore, rosterStore, people, attendance, clock);
        this.hours = new StaffShiftHours(shiftStore);
        this.factSource = new StaffShiftFactSourceImpl(hours, shiftStore);
        this.exportPort = new StaffAttendanceExportAdapter(attendance, shiftStore, hours, roster);
    }

    static WorkforceKit over(TestDatabase.Handle db, Instant start) {
        return new WorkforceKit(db, start);
    }

    static Branch branch() {
        return new Branch(TENANT, BRAND, LOCATION);
    }

    static Branch secondBranch() {
        return new Branch(TENANT, BRAND, SECOND_LOCATION);
    }

    /** Empties what a test wrote and seeds two tenants, three branches and no people. */
    void reset() {
        jdbc.sql("TRUNCATE TABLE reporting.aggregate_divergences, reporting.close_runs").update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line, reporting.fact_order_tender,
                    reporting.fact_refund, reporting.agg_branch_day, reporting.agg_sla_bucket_day,
                    reporting.business_day_policies, reporting.fact_call_hour, reporting.fact_promotion_redemption,
                    reporting.fact_staff_shift, reporting.report_exports
                """).update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        settings.clear();
        grants.clear();
        roster.clear();
        orders.operators.clear();
        tenant(TENANT, "workforce-a");
        tenant(OTHER_TENANT, "workforce-b");
        brand(TENANT, BRAND, "a");
        brand(OTHER_TENANT, OTHER_BRAND, "b");
        location(TENANT, BRAND, LOCATION, "l1");
        location(TENANT, BRAND, SECOND_LOCATION, "l2");
        location(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "l3");
        settings.set("workforce.attendance.enabled", true);
    }

    private void tenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', :zone, 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).param("zone", ZONE).update();
    }

    private void brand(UUID tenantId, UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :code, 'Brand', 'ACTIVE', 0)
                """).param("id", id).param("t", tenantId).param("code", code.toUpperCase()).update();
    }

    private void location(UUID tenantId, UUID brandId, UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :b, :code, :code, 'Branch', :zone, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code.toUpperCase())
                .param("zone", ZONE)
                .update();
    }

    /**
     * A person of {@code tenantId}: a real {@code iam.staff_members} row, a Keycloak subject, and -- when
     * {@code branchLocation} is given -- a job at that branch (the grant stand-in).
     */
    Person person(UUID tenantId, String label, @Nullable UUID branchLocation, String status) {
        UUID memberId = UUID.randomUUID();
        String subject = "subject-" + label + "-" + memberId.toString().substring(0, 8);
        String reference = "S-%04d".formatted(roster.people.size() + 1 + (tenantId.equals(TENANT) ? 0 : 5000));
        jdbc.sql("""
                INSERT INTO iam.staff_members (id, tenant_id, principal_subject, display_reference, employment_status)
                VALUES (:id, :tenant, :subject, :reference, :status)
                """)
                .param("id", memberId)
                .param("tenant", tenantId)
                .param("subject", subject)
                .param("reference", reference)
                .param("status", status)
                .update();
        Person person = new Person(tenantId, memberId, subject, reference, label, status);
        roster.people.put(memberId, person);
        if (branchLocation != null) {
            grants.job(subject, branchLocation);
        }
        return person;
    }

    Person cook(String label) {
        Person person = person(TENANT, label, LOCATION, "ACTIVE");
        grants.allow(person.subject(), Capability.STAFF_SHIFT_CLOCK);
        return person;
    }

    record Person(UUID tenantId, UUID memberId, String subject, String reference, String name, String status) {}

    // ----------------------------------------------------------------------------- stand-ins

    /** A clock the test moves; every duration the services measure is lived through by moving it. */
    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** ADR 0030 resolution reduced to "this code is set to this value, at every scope". */
    static final class Settings implements ConfigurationResolver {
        private final Map<String, Object> values = new HashMap<>();

        void set(String code, Object value) {
            values.put(code, value);
        }

        void clear() {
            values.clear();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
            T value = values.containsKey(key.code()) ? (T) values.get(key.code()) : key.defaultValue();
            return new Resolved<>(
                    value,
                    new ResolutionTrace(
                            key.code(),
                            values.containsKey(key.code())
                                    ? ResolutionTrace.Source.SCOPED_VALUE
                                    : ResolutionTrace.Source.CODE_DEFAULT,
                            null,
                            List.of()));
        }

        @Override
        public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
            return resolve(key, scope).trace();
        }
    }

    /**
     * Authorization reduced to the two statements the module makes: a subject holds a capability
     * (at any scope), and a subject has a job at a branch (which is what {@code staff.self.manage} at the
     * branch's scope stands for in {@code StaffPeople}).
     */
    static final class Grants implements AuthorizationService {
        private final Map<String, Set<Capability>> held = new HashMap<>();
        private final Map<String, Set<UUID>> jobs = new HashMap<>();

        void allow(String subject, Capability... capabilities) {
            held.computeIfAbsent(subject, key -> new HashSet<>()).addAll(List.of(capabilities));
        }

        void job(String subject, UUID locationId) {
            jobs.computeIfAbsent(subject, key -> new HashSet<>()).add(locationId);
        }

        void clear() {
            held.clear();
            jobs.clear();
        }

        @Override
        public boolean has(String subject, Capability capability, ResourceScope scope) {
            if (capability == Capability.STAFF_SELF_MANAGE) {
                return scope.locationId() != null
                        && jobs.getOrDefault(subject, Set.of()).contains(scope.locationId());
            }
            if (capability == Capability.STAFF_SHIFT_CLOCK) {
                return held.getOrDefault(subject, Set.of()).contains(capability)
                        && (scope.locationId() == null
                                || jobs.getOrDefault(subject, Set.of()).contains(scope.locationId()));
            }
            return held.getOrDefault(subject, Set.of()).contains(capability);
        }

        @Override
        public void require(String subject, Capability capability, ResourceScope scope) {
            if (!has(subject, capability, scope)) {
                throw new AccessDeniedException(capability, scope);
            }
        }

        @Override
        public CapabilityView viewFor(String subject, UUID tenantId) {
            throw new UnsupportedOperationException("not needed by workforce");
        }
    }

    /** {@link StaffMemberCards} and {@link StaffDirectory} over the same people the rows were inserted for. */
    static final class Roster implements StaffMemberCards, StaffDirectory, StaffPayrollIdentity {
        final Map<UUID, Person> people = new LinkedHashMap<>();

        void clear() {
            people.clear();
        }

        @Override
        public Map<UUID, Card> cardsOf(UUID tenantId, Collection<UUID> memberIds) {
            Map<UUID, Card> cards = new LinkedHashMap<>();
            for (UUID id : memberIds) {
                Person person = people.get(id);
                if (person != null && person.tenantId().equals(tenantId)) {
                    cards.put(id, card(person));
                }
            }
            return cards;
        }

        @Override
        public List<Card> pickable(UUID tenantId) {
            return people.values().stream()
                    .filter(person -> person.tenantId().equals(tenantId) && !"ENDED".equals(person.status()))
                    .map(Roster::card)
                    .toList();
        }

        private static Card card(Person person) {
            return new Card(person.memberId(), person.subject(), person.reference(), person.name(), null, person.status());
        }

        @Override
        public @Nullable String nameOf(UUID tenantId, String subject) {
            return people.values().stream()
                    .filter(person -> person.tenantId().equals(tenantId) && person.subject().equals(subject))
                    .map(Person::name)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public Map<String, String> namesOf(UUID tenantId, Collection<String> subjects) {
            Map<String, String> names = new HashMap<>();
            subjects.forEach(subject -> {
                String name = nameOf(tenantId, subject);
                if (name != null) {
                    names.put(subject, name);
                }
            });
            return names;
        }

        @Override
        public Optional<UUID> memberIdOf(UUID tenantId, String subject) {
            return people.values().stream()
                    .filter(person -> person.tenantId().equals(tenantId) && person.subject().equals(subject))
                    .map(Person::memberId)
                    .findFirst();
        }

        @Override
        public Map<UUID, PayrollIdentity> identityOf(UUID tenantId, Collection<UUID> memberIds) {
            Map<UUID, PayrollIdentity> identities = new LinkedHashMap<>();
            for (UUID id : memberIds) {
                Person person = people.get(id);
                if (person != null && person.tenantId().equals(tenantId)) {
                    identities.put(id, new PayrollIdentity(person.name(), "E-" + person.reference()));
                }
            }
            return identities;
        }
    }

    /** Order attribution: the subjects that took an order at a branch today (the real source is {@code ordering}). */
    static final class Orders implements OperatorActivity {
        final Set<String> operators = new HashSet<>();

        @Override
        public Set<String> operatorsAt(UUID tenantId, UUID locationId, Instant from, Instant to) {
            return Set.copyOf(operators);
        }
    }

    /** Everything a test lists from the audit table, newest last. */
    List<Map<String, Object>> auditFacts(String actionPrefix) {
        return new ArrayList<>(jdbc.sql("""
                        SELECT action_code, actor_subject, reason, target_type, target_id, change_document::text AS change_document
                          FROM audit.audit_events WHERE action_code LIKE :prefix ORDER BY occurred_at, id
                        """)
                .param("prefix", actionPrefix + "%")
                .query()
                .listOfRows());
    }
}
