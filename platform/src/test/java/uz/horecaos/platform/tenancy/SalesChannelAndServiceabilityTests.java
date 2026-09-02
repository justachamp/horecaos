package uz.horecaos.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.LocationCapacityPort;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityReason;
import uz.horecaos.platform.tenancy.api.TenantCreated;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.SalesChannelService;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService;
import uz.horecaos.platform.tenancy.application.ServiceabilityService;
import uz.horecaos.platform.tenancy.application.StorefrontChannelSeeder;
import uz.horecaos.platform.tenancy.application.TenantResourceConflictException;
import uz.horecaos.platform.tenancy.application.TenantResourceNotFoundException;
import uz.horecaos.platform.tenancy.domain.channel.ServiceMode;
import uz.horecaos.platform.tenancy.domain.channel.WeeklySchedule;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;

/**
 * ADR 0036: sales channels and location serviceability.
 *
 * <p>Against a real PostgreSQL, because most of what is being asserted only
 * exists there: the closed {@code system_type} set, the composite foreign keys
 * that refuse a cross-tenant channel or a cross-brand schedule, the mandatory
 * reason on a manual close, and the row lock that settles two checkouts racing
 * for the last capacity slot.
 *
 * <p>The location is in {@code Asia/Tashkent} (UTC+5) throughout and every
 * instant in these tests is written in UTC, so the local time under test is five
 * hours later than the literal. That is deliberate: a resolver that quietly
 * compared UTC against local opening hours would pass a suite written in UTC and
 * fail in production.
 */
class SalesChannelAndServiceabilityTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    /** 2026-08-21 is a Friday. 07:00 UTC is 12:00 in Tashkent. */
    private static final Instant FRIDAY_NOON_LOCAL = Instant.parse("2026-08-21T07:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private DataSource dataSource;
    private JdbcSalesChannelStore channelStore;
    private JdbcServiceabilityStore serviceabilityStore;
    private SalesChannelService channels;
    private ServiceScheduleService schedules;
    private ServiceabilityService serviceability;

    private UUID storefront;
    private UUID scheduleId;
    private Clock clock;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for sales channel and serviceability tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.variants, catalog.products, "
                        + "catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = Clock.fixed(FRIDAY_NOON_LOCAL, ZoneOffset.UTC);
        channelStore = new JdbcSalesChannelStore(jdbc);
        serviceabilityStore = new JdbcServiceabilityStore(jdbc);
        channels = new SalesChannelService(channelStore, clock);
        CurrentActor actor =
                () -> new AuthenticatedActor(UUID.randomUUID().toString(), java.util.Set.of("tenant-admin"), Map.of());
        schedules = new ServiceScheduleService(
                serviceabilityStore,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                actor,
                clock);
        serviceability = new ServiceabilityService(serviceabilityStore, clock);

        seedTenancy();
        storefront = channels.create(TENANT, createCommand("STOREFRONT", "WEB")).id();
        openForBusiness();
    }

    // ------------------------------------------------------------ the registry

    @Test
    @DisplayName("the system_type set is closed: an operator cannot invent a channel type")
    void theSystemTypeSetIsClosed() {
        // Refused in code, so nothing keys behaviour on a name an operator typed.
        assertThat(catchThrowable(() -> channels.create(TENANT, createCommand("MARKET", "MARKETPLACE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADR 0036");

        // And refused at the database, so a row inserted outside the application
        // cannot carry a type the code has no branch for. Both matter: the check
        // constraint is what makes the enum a fact rather than a convention.
        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name)
                VALUES (:id, :tenantId, 'MARKET', 'MARKETPLACE', 'Marketplace')
                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", TENANT)
                        .update()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The premise: every name the ADR does own inserts.
        for (SalesChannelSystemType type : SalesChannelSystemType.values()) {
            assertThat(channels.create(TENANT, createCommand("CH_" + type.name(), type.name()))
                            .systemType())
                    .isEqualTo(type);
        }
    }

    @Test
    @DisplayName("a new tenant is seeded with the STOREFRONT channel its publications already name")
    void aNewTenantIsSeededWithItsStorefrontChannel() {
        UUID freshTenant = UUID.randomUUID();
        insertTenant(freshTenant, "fresh-tenant");
        assertThat(channelStore.byCode(freshTenant, "STOREFRONT"))
                .as("the premise: nothing has seeded it yet")
                .isEmpty();

        var seeder = new StorefrontChannelSeeder(channelStore, clock);
        seeder.on(tenantCreated(freshTenant));

        // Without this a freshly created tenant could author a menu and not
        // publish it: catalog.publications.channel defaults to STOREFRONT and is
        // now a foreign key to this registry.
        assertThat(channelStore.byCode(freshTenant, "STOREFRONT")).get().satisfies(channel -> {
            assertThat(channel.systemType()).isEqualTo(SalesChannelSystemType.WEB);
            assertThat(channel.sellable()).isTrue();
        });

        // A replayed event must not collide with the unique code.
        seeder.on(tenantCreated(freshTenant));
        assertThat(channels.list(freshTenant)).hasSize(1);
    }

    @Test
    @DisplayName("a tenant runs several channels of one type, each with its own price plane")
    void severalChannelsOfOneTypeCoexist() {
        var tezkor = channels.create(TENANT, createCommand("UZUM_TEZKOR", "AGGREGATOR"));
        var yandex = channels.create(TENANT, createCommand("YANDEX_EDA", "AGGREGATOR"));

        // The reason the registry is tenant data rather than a code enum: a tenant
        // signing a second marketplace must not need a schema change.
        assertThat(tezkor.id()).isNotEqualTo(yandex.id());
        assertThat(channels.list(TENANT)).extracting(SalesChannel::code).contains("UZUM_TEZKOR", "YANDEX_EDA");
    }

    @Test
    @DisplayName("a price plane is one hop and never a chain")
    void aPricePlaneIsOneHop() {
        var hall = channels.create(TENANT, createCommand("HALL", "POS"));
        var qr = channels.create(
                TENANT,
                new SalesChannelService.CreateChannelCommand("QR", "QR_TABLE", "QR", hall.id(), false, true, null));

        assertThat(qr.pricingChannelId())
                .as("QR takes the hall's prices without duplicating a price book")
                .isEqualTo(hall.id());
        assertThat(hall.pricingChannelId())
                .as("a channel with no plane prices as itself")
                .isEqualTo(hall.id());

        // A chain would resolve to the middle of itself, because the resolver
        // follows exactly one hop and does not recurse.
        assertThat(catchThrowable(() -> channels.create(
                        TENANT,
                        new SalesChannelService.CreateChannelCommand(
                                "KIOSK", "KIOSK", "Kiosk", qr.id(), false, true, null))))
                .isInstanceOf(TenantResourceConflictException.class);
    }

    @Test
    @DisplayName("a cross-tenant channel id cannot be bound to this tenant's location")
    void aCrossTenantChannelIsRefusedByTheDatabase() {
        UUID foreign =
                channels.create(OTHER_TENANT, createCommand("FOREIGN", "WEB")).id();

        // The composite foreign key matches (tenant_id, channel_id). Matching on
        // the channel id alone would have accepted this silently.
        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id)
                VALUES (:tenantId, :channelId, :locationId)
                """)
                        .param("tenantId", TENANT)
                        .param("channelId", foreign)
                        .param("locationId", LOCATION)
                        .update()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // And the lookup does not find it either, so nothing downstream can act
        // on a channel id that came from another tenant.
        assertThat(channelStore.byId(TENANT, foreign)).isEmpty();
    }

    @Test
    @DisplayName("an archived channel keeps its history and cannot take a new cart")
    void anArchivedChannelIsRetiredNotDeleted() {
        var kiosk = channels.create(TENANT, createCommand("KIOSK", "KIOSK"));
        publish("KIOSK");

        var archived = channels.archive(TENANT, kiosk.id(), kiosk.version());

        assertThat(archived.status()).isEqualTo(SalesChannel.Status.ARCHIVED);
        assertThat(archived.sellable()).isFalse();
        // Still readable, because a historical order carrying this channel must
        // still render. A delete would make that order unattributable.
        assertThat(channelStore.byId(TENANT, kiosk.id())).isPresent();
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.publications WHERE channel = 'KIOSK'")
                        .query(Long.class)
                        .single())
                .as("the publication that named it is untouched")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("archiving a channel another one prices through is refused")
    void aPricePlaneInUseCannotBeArchived() {
        var hall = channels.create(TENANT, createCommand("HALL", "POS"));
        channels.create(
                TENANT,
                new SalesChannelService.CreateChannelCommand("QR", "QR_TABLE", "QR", hall.id(), false, true, null));

        // Otherwise QR silently falls back to brand prices — a price change nobody
        // made, visible only on the receipt.
        assertThat(catchThrowable(() -> channels.archive(TENANT, hall.id(), hall.version())))
                .isInstanceOf(TenantResourceConflictException.class);
    }

    @Test
    @DisplayName("a publication cannot name a channel that is not registered")
    void aPublicationChannelMustBeRegistered() {
        // ADR 0036 correcting ADR 0016. Free text meant a typo published a menu to
        // a channel nobody would ever see, and nothing failed.
        assertThat(catchThrowable(() -> publish("TYPO"))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------- the eight-rule resolver

    @Test
    @DisplayName("rule 1: an inactive channel is not enabled")
    void anInactiveChannelIsRefusedFirst() {
        jdbc.sql("UPDATE tenant.sales_channels SET status = 'INACTIVE' WHERE id = :id")
                .param("id", storefront)
                .update();

        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.CHANNEL_NOT_ENABLED);
    }

    @Test
    @DisplayName("rule 2: a location the channel does not serve is not enabled")
    void aLocationOffTheChannelIsRefused() {
        channels.replaceLocations(TENANT, storefront, List.of(), currentVersion(storefront));

        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.CHANNEL_NOT_ENABLED);
    }

    @Test
    @DisplayName("rule 3: a fulfilment mode the channel does not carry is unavailable")
    void anUnlistedFulfilmentModeIsRefused() {
        channels.replaceFulfillmentModes(
                TENANT, storefront, Map.of(FulfillmentMode.PICKUP, true), currentVersion(storefront));

        // Absent means unavailable, so a half-configured channel is visibly broken
        // rather than quietly permissive.
        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.FULFILMENT_MODE_UNAVAILABLE);
    }

    @Test
    @DisplayName("rule 4: a manual close beats the schedule and says so")
    void aManualCloseBeatsTheSchedule() {
        schedules.changeServiceState(
                TENANT,
                BRAND,
                LOCATION,
                new ServiceScheduleService.ChangeServiceStateCommand(
                        ServiceMode.FORCE_CLOSED, "EQUIPMENT_FAILURE", "Fryer down", null));

        var answer = resolve(FRIDAY_NOON_LOCAL);

        assertThat(answer.available()).isFalse();
        assertThat(answer.reason()).isEqualTo(ServiceabilityReason.MANUALLY_CLOSED);
        // No expiry was given, so there is no computable reopening instant.
        // Guessing one would promise a time the manager never agreed to.
        assertThat(answer.nextAvailableAt()).isNull();
    }

    @Test
    @DisplayName("a timetable belonging to another brand cannot be rewritten or closed")
    void anotherBrandsTimetableIsNotWritable() {
        // The attacker holds SERVICEABILITY_MANAGE in their own brand and names it
        // in the URL, so the capability check passes. All they supply is the
        // victim's schedule id -- an opaque UUID, but a stable one that travels
        // through support tickets, exports and migration tooling.
        assertThatThrownBy(() -> schedules.replaceRules(
                        OTHER_TENANT,
                        OTHER_BRAND,
                        scheduleId,
                        List.of(new WeeklySchedule.Rule(1, LocalTime.of(3, 0), LocalTime.of(4, 0)))))
                .as("rewriting another brand's opening hours must be refused")
                .isInstanceOf(TenantResourceNotFoundException.class);

        assertThatThrownBy(() -> schedules.closeForDay(
                        OTHER_TENANT,
                        OTHER_BRAND,
                        scheduleId,
                        LocalDate.of(2026, 8, 21),
                        "Closed",
                        "not mine to close"))
                .as("shutting another brand's branches for the day must be refused")
                .isInstanceOf(TenantResourceNotFoundException.class);

        // And the victim's timetable is untouched: still open at Friday noon.
        assertThat(resolve(FRIDAY_NOON_LOCAL).available()).isTrue();
    }

    @Test
    @DisplayName("operations Settings 10.2 can read what the four write-only endpoints already persisted")
    void theServiceSummaryReadsComposeFromExistingWrites() {
        // The premise every settings-screen read below depends on: nothing here
        // is written by this test, only by openForBusiness() in setUp -- these
        // are additive reads over what the controller's existing PUT/POST
        // endpoints already write, per LocationServiceOperationsController's own
        // GET /service-summary doc comment.
        assertThat(schedules.currentState(TENANT, LOCATION).mode())
                .as("no manual override has been set")
                .isEqualTo(ServiceMode.FOLLOW_SCHEDULE);

        var bound = schedules.scheduleFor(TENANT, LOCATION, FulfillmentMode.DELIVERY);
        assertThat(bound).isPresent();
        assertThat(bound.get().scheduleId()).isEqualTo(scheduleId);
        assertThat(bound.get().schedule().rules()).hasSize(7);
        assertThat(schedules.scheduleFor(TENANT, LOCATION, FulfillmentMode.PICKUP))
                .as("pickup was never bound in openForBusiness()")
                .isEmpty();

        assertThat(schedules.schedulesForBrand(TENANT, BRAND))
                .as("the picker 10.2's Hours tab offers")
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.name()).isEqualTo("Standard hours");
                    assertThat(summary.id()).isEqualTo(scheduleId);
                    assertThat(summary.boundLocationCount())
                            .as("exactly the one location openForBusiness() bound")
                            .isEqualTo(1L);
                });

        assertThat(schedules.scheduleDetail(TENANT, BRAND, scheduleId))
                .as("the '\"Standard hours\" is used by N other locations' banner's data")
                .isPresent()
                .get()
                .satisfies(detail -> {
                    assertThat(detail.name()).isEqualTo("Standard hours");
                    assertThat(detail.schedule().rules()).hasSize(7);
                    assertThat(detail.boundLocationCount()).isEqualTo(1L);
                });
        assertThat(schedules.scheduleDetail(OTHER_TENANT, OTHER_BRAND, scheduleId))
                .as("another tenant naming this schedule id must not resolve it")
                .isEmpty();

        assertThat(schedules.preparationBands(TENANT, LOCATION))
                .as("none written yet")
                .isEmpty();
        schedules.replacePreparationBands(
                TENANT,
                BRAND,
                LOCATION,
                List.of(new JdbcServiceabilityStore.Band(
                        FulfillmentMode.DELIVERY, null, LocalTime.of(18, 0), LocalTime.of(21, 0), 25, 1)));
        assertThat(schedules.preparationBands(TENANT, LOCATION)).singleElement().satisfies(band -> {
            assertThat(band.durationMinutes()).isEqualTo(25);
            assertThat(band.mode()).isEqualTo(FulfillmentMode.DELIVERY);
        });

        assertThat(schedules.openCapacityHolds(TENANT, LOCATION)).isZero();
    }

    @Test
    @DisplayName("rule 5: a dated exception beats the weekly rule")
    void aDatedExceptionBeatsTheWeeklyRule() {
        schedules.closeForDay(TENANT, BRAND, scheduleId, LocalDate.of(2026, 8, 21), "Navruz", "Public holiday");

        var answer = resolve(FRIDAY_NOON_LOCAL);

        assertThat(answer.reason()).isEqualTo(ServiceabilityReason.CLOSED_BY_EXCEPTION);
        // Saturday's window, in local time: 2026-08-22 09:00 Tashkent = 04:00 UTC.
        assertThat(answer.nextAvailableAt()).isEqualTo(Instant.parse("2026-08-22T04:00:00Z"));
    }

    @Test
    @DisplayName("rule 6: outside the weekly window, with the next opening")
    void outsideHoursReportsTheNextOpening() {
        // 2026-08-21T02:00Z is 07:00 in Tashkent, two hours before opening.
        var answer = resolve(Instant.parse("2026-08-21T02:00:00Z"));

        assertThat(answer.reason()).isEqualTo(ServiceabilityReason.OUTSIDE_SERVICE_HOURS);
        assertThat(answer.nextAvailableAt()).isEqualTo(Instant.parse("2026-08-21T04:00:00Z"));
        // Closed now and cannot pre-order are different facts, and the merchant
        // wants the first without the second.
        assertThat(answer.acceptsScheduledOrders()).isTrue();
    }

    @Test
    @DisplayName("rule 7: no live publication on this channel")
    void aChannelWithNoLiveMenuIsRefused() {
        jdbc.sql("UPDATE catalog.publications SET status = 'RETIRED' WHERE channel = 'STOREFRONT'")
                .update();

        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.NO_LIVE_MENU);
    }

    @Test
    @DisplayName("rule 8: at the concurrent-order ceiling")
    void aFullKitchenIsRefused() {
        schedules.setCapacity(TENANT, BRAND, LOCATION, 1);
        transactionally(() -> serviceability.claimCapacity(TENANT, BRAND, LOCATION, UUID.randomUUID()));

        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.AT_CAPACITY);
    }

    @Test
    @DisplayName("with all eight rules satisfied the location is available")
    void anOpenLocationIsAvailable() {
        var answer = resolve(FRIDAY_NOON_LOCAL);

        // The premise every refusal test above depends on. Without it each of them
        // would pass on any refusal at all, including the wrong one.
        assertThat(answer.available()).isTrue();
        assertThat(answer.reason()).isNull();
    }

    @Test
    @DisplayName("a window that ends after midnight is open at 23:00 and 01:00, shut at 03:00")
    void anAfterMidnightWindowIsHandled() {
        // Saturday 18:00 to Sunday 02:00, local. Stored as one row whose closing
        // time is before its opening time.
        schedules.replaceRules(
                TENANT,
                BRAND,
                scheduleId,
                List.of(new WeeklySchedule.Rule(6, LocalTime.of(18, 0), LocalTime.of(2, 0))));

        // Saturday 23:00 Tashkent = 18:00Z; Sunday 01:00 Tashkent = Saturday 20:00Z;
        // Sunday 03:00 Tashkent = Saturday 22:00Z.
        assertThat(resolve(Instant.parse("2026-08-22T18:00:00Z")).available()).isTrue();
        assertThat(resolve(Instant.parse("2026-08-22T20:00:00Z")).available()).isTrue();
        // A naive range compares as 18:00 <= t < 02:00, which is empty, and the
        // branch reads as shut all evening. This is the assertion that would break.
        assertThat(resolve(Instant.parse("2026-08-22T22:00:00Z")).reason())
                .isEqualTo(ServiceabilityReason.OUTSIDE_SERVICE_HOURS);
    }

    @Test
    @DisplayName("FORCE_OPEN beats hours and exceptions but never the menu or the ceiling")
    void forceOpenSkipsOnlyRulesFiveAndSix() {
        schedules.closeForDay(TENANT, BRAND, scheduleId, LocalDate.of(2026, 8, 21), "Navruz", "Public holiday");
        schedules.changeServiceState(
                TENANT,
                BRAND,
                LOCATION,
                new ServiceScheduleService.ChangeServiceStateCommand(
                        ServiceMode.FORCE_OPEN, "MANAGER_OVERRIDE", "Trading anyway", null));

        // Beats both the exception and the hours.
        assertThat(resolve(FRIDAY_NOON_LOCAL).available()).isTrue();
        assertThat(resolve(Instant.parse("2026-08-21T02:00:00Z")).available()).isTrue();

        // But a manager deciding to be open cannot conjure a menu.
        jdbc.sql("UPDATE catalog.publications SET status = 'RETIRED' WHERE channel = 'STOREFRONT'")
                .update();
        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.NO_LIVE_MENU);

        // Nor override the kitchen ceiling.
        publish("STOREFRONT");
        schedules.setCapacity(TENANT, BRAND, LOCATION, 1);
        transactionally(() -> serviceability.claimCapacity(TENANT, BRAND, LOCATION, UUID.randomUUID()));
        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.AT_CAPACITY);
    }

    @Test
    @DisplayName("an elapsed expiry reopens the branch with no operator action and no job")
    void anElapsedExpiryReturnsToTheSchedule() {
        Instant reopensAt = FRIDAY_NOON_LOCAL.plus(Duration.ofHours(1));
        schedules.changeServiceState(
                TENANT,
                BRAND,
                LOCATION,
                new ServiceScheduleService.ChangeServiceStateCommand(
                        ServiceMode.FORCE_CLOSED, "EQUIPMENT_FAILURE", "Fryer down", reopensAt));

        var whileClosed = resolve(FRIDAY_NOON_LOCAL);
        assertThat(whileClosed.reason()).isEqualTo(ServiceabilityReason.MANUALLY_CLOSED);
        assertThat(whileClosed.nextAvailableAt())
                .as("the branch is inside its opening hours at 13:00, so the expiry is the answer")
                .isEqualTo(reopensAt);

        // Nothing ran in between. The row still says FORCE_CLOSED; it is read as
        // elapsed. A scheduled job that failed would leave a network closed with a
        // cause indistinguishable from an outage.
        assertThat(jdbc.sql("SELECT mode FROM tenant.location_service_state " + "WHERE location_id = :id")
                        .param("id", LOCATION)
                        .query(String.class)
                        .single())
                .isEqualTo("FORCE_CLOSED");
        assertThat(resolve(reopensAt).available()).isTrue();
    }

    @Test
    @DisplayName("an expiry after closing time reopens with the schedule, not with the expiry")
    void anExpiryPastClosingTimeDefersToTheSchedule() {
        // Closed until 23:30 local (18:30Z) on a branch whose hours end at 23:00.
        Instant expiry = Instant.parse("2026-08-21T18:30:00Z");
        schedules.changeServiceState(
                TENANT,
                BRAND,
                LOCATION,
                new ServiceScheduleService.ChangeServiceStateCommand(
                        ServiceMode.FORCE_CLOSED, "EQUIPMENT_FAILURE", "Fryer down", expiry));

        // A close until 23:30 on a branch that shuts at 23:00 does not reopen at
        // 23:30. Saturday 09:00 Tashkent = 04:00Z is the first moment both the
        // override and the timetable agree.
        assertThat(resolve(FRIDAY_NOON_LOCAL).nextAvailableAt()).isEqualTo(Instant.parse("2026-08-22T04:00:00Z"));
    }

    @Test
    @DisplayName("a manual close without a reason is refused by the service and the database")
    void aManualCloseAlwaysCarriesAReason() {
        assertThat(catchThrowable(() -> schedules.changeServiceState(
                        TENANT,
                        BRAND,
                        LOCATION,
                        new ServiceScheduleService.ChangeServiceStateCommand(
                                ServiceMode.FORCE_CLOSED, null, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        // Enforced by the schema too, because a close with no reason is exactly the
        // support conversation this table exists to end, and a second writer would
        // not remember the rule.
        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO tenant.location_service_state (
                    location_id, tenant_id, brand_id, mode)
                VALUES (:locationId, :tenantId, :brandId, 'FORCE_CLOSED')
                """)
                        .param("locationId", OTHER_LOCATION)
                        .param("tenantId", TENANT)
                        .param("brandId", OTHER_BRAND)
                        .update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a service-state change is an audit fact naming the actor and the reason")
    void aServiceStateChangeIsAudited() {
        schedules.changeServiceState(
                TENANT,
                BRAND,
                LOCATION,
                new ServiceScheduleService.ChangeServiceStateCommand(
                        ServiceMode.FORCE_CLOSED, "EQUIPMENT_FAILURE", "Fryer down", null));

        assertThat(jdbc.sql("SELECT reason FROM audit.audit_events "
                                + "WHERE action_code = 'location.service_state.changed'")
                        .query(String.class)
                        .list())
                .as("\"who closed this branch and why\" must have an answer years later")
                .containsExactly("EQUIPMENT_FAILURE");
    }

    @Test
    @DisplayName("a schedule of another brand cannot be bound to this location")
    void aCrossBrandBindingIsRefusedByTheDatabase() {
        UUID otherBrandSchedule = schedules.createSchedule(
                TENANT,
                OTHER_BRAND,
                new ServiceScheduleService.CreateScheduleCommand(
                        "Other brand hours",
                        true,
                        List.of(new WeeklySchedule.Rule(5, LocalTime.of(9, 0), LocalTime.of(23, 0)))));

        // The binding's foreign key matches (tenant_id, brand_id, schedule_id), so
        // one brand's Ramadan timetable can never silently govern another's.
        assertThat(catchThrowable(
                        () -> schedules.bind(TENANT, BRAND, LOCATION, FulfillmentMode.DELIVERY, otherBrandSchedule)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------- promised duration

    @Test
    @DisplayName("the preparation promise takes the band, then the longest line override")
    void thePreparationPromiseIsTheLongest() {
        schedules.replacePreparationBands(
                TENANT,
                BRAND,
                LOCATION,
                List.of(
                        new JdbcServiceabilityStore.Band(null, null, LocalTime.of(9, 0), LocalTime.of(17, 0), 25, 0),
                        // The Friday rush band, narrower and higher priority.
                        new JdbcServiceabilityStore.Band(
                                FulfillmentMode.DELIVERY, 5, LocalTime.of(11, 0), LocalTime.of(14, 0), 45, 10)));

        assertThat(resolve(FRIDAY_NOON_LOCAL).preparationMinutes())
                .as("a Friday rush quotes 45 minutes rather than 25")
                .isEqualTo(45);

        // A pizza that takes 40 minutes does not become 20 because the quiet-hours
        // band says so — and equally the 45-minute rush is not shortened by a
        // 30-minute item.
        assertThat(serviceability.preparationMinutes(
                        TENANT, LOCATION, FulfillmentMode.DELIVERY, FRIDAY_NOON_LOCAL, List.of(30, 60)))
                .isEqualTo(60);
        assertThat(serviceability.preparationMinutes(
                        TENANT, LOCATION, FulfillmentMode.DELIVERY, FRIDAY_NOON_LOCAL, List.of(30)))
                .isEqualTo(45);
    }

    // -------------------------------------------------------------- the ceiling

    @Test
    @DisplayName("two checkouts racing for the last slot settle at one; the loser gets AT_CAPACITY")
    void concurrentCheckoutsSettleAtOne() throws Exception {
        schedules.setCapacity(TENANT, BRAND, LOCATION, 1);

        CountDownLatch firstHasClaimed = new CountDownLatch(1);
        CountDownLatch secondHasStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<LocationCapacityPort.CapacityOutcome> first =
                    pool.submit(() -> transactionTemplate().execute(status -> {
                        var outcome = serviceability.claimCapacity(TENANT, BRAND, LOCATION, UUID.randomUUID());
                        firstHasClaimed.countDown();
                        // Stay uncommitted while the second transaction attempts its
                        // claim. Without the row lock the second would count zero
                        // open holds — the first is invisible until it commits — and
                        // both would claim the last slot.
                        awaitQuietly(secondHasStarted);
                        sleepQuietly();
                        return outcome;
                    }));

            firstHasClaimed.await(5, TimeUnit.SECONDS);

            Future<LocationCapacityPort.CapacityOutcome> second =
                    pool.submit(() -> transactionTemplate().execute(status -> {
                        secondHasStarted.countDown();
                        return serviceability.claimCapacity(TENANT, BRAND, LOCATION, UUID.randomUUID());
                    }));

            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(LocationCapacityPort.CapacityOutcome.CLAIMED);
            assertThat(second.get(20, TimeUnit.SECONDS))
                    .as("settled by the database, not by a number either read a second earlier")
                    .isEqualTo(LocationCapacityPort.CapacityOutcome.AT_CAPACITY);
        } finally {
            pool.shutdownNow();
        }

        assertThat(serviceabilityStore.openCapacityHolds(TENANT, LOCATION)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a retried checkout re-claims its own slot rather than a second one")
    void claimingTwiceForOneOrderTakesOneSlot() {
        schedules.setCapacity(TENANT, BRAND, LOCATION, 1);
        UUID orderId = UUID.randomUUID();

        assertThat(transactionally(() -> serviceability.claimCapacity(TENANT, BRAND, LOCATION, orderId)))
                .isEqualTo(LocationCapacityPort.CapacityOutcome.CLAIMED);
        assertThat(transactionally(() -> serviceability.claimCapacity(TENANT, BRAND, LOCATION, orderId)))
                .as("otherwise a retry reports the kitchen busier than it is")
                .isEqualTo(LocationCapacityPort.CapacityOutcome.CLAIMED);

        assertThat(serviceabilityStore.openCapacityHolds(TENANT, LOCATION)).isEqualTo(1L);
    }

    @Test
    @DisplayName("a released slot frees the kitchen again")
    void releasingASlotFreesTheKitchen() {
        schedules.setCapacity(TENANT, BRAND, LOCATION, 1);
        UUID orderId = UUID.randomUUID();
        transactionally(() -> serviceability.claimCapacity(TENANT, BRAND, LOCATION, orderId));

        assertThat(resolve(FRIDAY_NOON_LOCAL).reason()).isEqualTo(ServiceabilityReason.AT_CAPACITY);
        assertThat(serviceability.releaseCapacity(TENANT, orderId)).isTrue();
        assertThat(resolve(FRIDAY_NOON_LOCAL).available()).isTrue();
    }

    @Test
    @DisplayName("a location with no ceiling is never refused for capacity")
    void anUncappedLocationIsNeverAtCapacity() {
        for (int i = 0; i < 5; i++) {
            assertThat(transactionally(() -> serviceability.claimCapacity(TENANT, BRAND, LOCATION, UUID.randomUUID())))
                    .isEqualTo(LocationCapacityPort.CapacityOutcome.CLAIMED);
        }
        assertThat(resolve(FRIDAY_NOON_LOCAL).available()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private Serviceability resolve(Instant at) {
        return serviceability.resolve(TENANT, BRAND, LOCATION, storefront, FulfillmentMode.DELIVERY, at);
    }

    private int currentVersion(UUID channelId) {
        return channelStore.byId(TENANT, channelId).orElseThrow().version();
    }

    private static TenantCreated tenantCreated(UUID tenantId) {
        return new TenantCreated(
                UUID.randomUUID(),
                new TenantId(tenantId),
                FRIDAY_NOON_LOCAL,
                "fresh-tenant",
                "Legal",
                "Display",
                "UZS",
                "Asia/Tashkent",
                "ACTIVE",
                "TENANT_SHARED");
    }

    private static SalesChannelService.CreateChannelCommand createCommand(String code, String type) {
        return new SalesChannelService.CreateChannelCommand(code, type, code, null, false, true, null);
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private <T> T transactionally(java.util.function.Supplier<T> work) {
        return transactionTemplate().execute(status -> work.get());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly() {
        try {
            // Long enough for the second transaction to reach the lock. If the lock
            // were not taken it would already have counted zero and claimed.
            Thread.sleep(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Makes the location sellable: channel bound, mode enabled, hours, and a live menu. */
    private void openForBusiness() {
        channels.replaceLocations(TENANT, storefront, List.of(LOCATION), currentVersion(storefront));
        channels.replaceFulfillmentModes(
                TENANT,
                storefront,
                Map.of(FulfillmentMode.DELIVERY, true, FulfillmentMode.PICKUP, true),
                currentVersion(storefront));

        // 09:00 to 23:00 local, every day.
        scheduleId = schedules.createSchedule(
                TENANT,
                BRAND,
                new ServiceScheduleService.CreateScheduleCommand(
                        "Standard hours",
                        true,
                        java.util.stream.IntStream.rangeClosed(1, 7)
                                .mapToObj(day -> new WeeklySchedule.Rule(day, LocalTime.of(9, 0), LocalTime.of(23, 0)))
                                .toList()));
        schedules.bind(TENANT, BRAND, LOCATION, FulfillmentMode.DELIVERY, scheduleId);
        publish("STOREFRONT");
    }

    private void publish(String channel) {
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("channel", channel)
                .update();
    }

    private UUID catalogId;

    private void seedTenancy() {
        insertTenant(TENANT, "channel-tenant");
        insertTenant(OTHER_TENANT, "other-tenant");

        insertBrand(BRAND, TENANT, "MAIN", "main");
        insertBrand(OTHER_BRAND, TENANT, "OTHER", "other");
        insertLocation(LOCATION, BRAND, "MAIN01", "main-01");
        insertLocation(OTHER_LOCATION, OTHER_BRAND, "OTHER01", "other-01");

        catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID id, UUID tenantId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void insertLocation(UUID id, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', :zone, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", slug)
                .param("zone", TASHKENT.getId())
                .update();
    }
}
