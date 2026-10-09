package uz.horecaos.platform.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.printing.PrintingWorld.LineSpec;
import uz.horecaos.platform.printing.PrintingWorld.SeededOrder;
import uz.horecaos.platform.printing.application.PrintDocumentCodec;
import uz.horecaos.platform.printing.application.PrintMaintenance;
import uz.horecaos.platform.printing.application.PrinterService;
import uz.horecaos.platform.printing.application.PrinterService.PrinterDraft;
import uz.horecaos.platform.printing.application.RouteService;
import uz.horecaos.platform.printing.application.RouteService.RouteDraft;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.domain.PrinterStatus;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.render.Bitmap1;
import uz.horecaos.platform.printing.render.DocumentRenderer;
import uz.horecaos.platform.printing.render.RenderContext;
import uz.horecaos.platform.support.printing.EscPosDecoder;
import uz.horecaos.platform.support.printing.FakePrinter;
import uz.horecaos.platform.support.printing.QrReader;

/**
 * What an agent does over HTTP with a real device principal (ADR 0154, "Job lifecycle and the agent contract"):
 * claim, artifact, result -- the lease rules that make a lost agent lose no job, the refusals that keep one
 * branch's agent out of another's, and the whole path against a fake printer.
 *
 * <p>The agent is enrolled by the real handshake and granted the real {@code print-agent} role; every request
 * carries a token whose subject is the device's own. The clock is the test's: a lease, an expiry and a backoff
 * are asserted by moving it.
 */
class PrintAgentHttpTests extends PrintingSpringTest {

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcPrintingStore store;

    @Autowired
    @SuppressWarnings("NullAway")
    private PrinterService printers;

    @Autowired
    @SuppressWarnings("NullAway")
    private RouteService routes;

    @Autowired
    @SuppressWarnings("NullAway")
    private PrintMaintenance maintenance;

    @Autowired
    @SuppressWarnings("NullAway")
    private ApplicationEventPublisher events;

    @Autowired
    @SuppressWarnings("NullAway")
    private PlatformTransactionManager transactions;

    @Autowired
    @SuppressWarnings("NullAway")
    private PrintDocumentCodec codec;

    private Agent agent;
    private FakePrinter hardware;
    private FakePrintAgent fake;
    private PrinterRow counter;

    @BeforeEach
    void anAgentAndAPrinter() throws Exception {
        printingOn();
        agent = enrolAgent();
        hardware = new FakePrinter();
        fake = new FakePrintAgent(mvc, tokenFor(agent.subject()), PRINT).reaches("counter", hardware);
        // The first claim is how the platform learns the alias exists; a printer row binds to it afterwards.
        fake.claimed(1);
        counter = printers.register(
                TENANT, BRAND, LOCATION, new PrinterDraft("Counter 80", 80, agent.deviceId(), "counter"), MANAGER);
        routes.create(
                TENANT,
                BRAND,
                LOCATION,
                new RouteDraft(PrintDocumentType.ORDER_SLIP, null, counter.id(), 1, true, true),
                MANAGER);
    }

    @AfterEach
    void hardwareOff() {
        hardware.close();
    }

    private JobRow slipJob(String number) {
        SeededOrder order = world.order(
                LOCATION, number, 150_000L, false, LineSpec.of("Lagman", 2, 90_000), LineSpec.of("Cola", 1, 8_000));
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> events.publishEvent(
                        PrintingSteps.confirmed(TENANT, BRAND, LOCATION, order.orderId(), 3, clock.instant())));
        return store.listJobs(TENANT, LOCATION, List.of(), null, null, 1).getFirst();
    }

    private JobRow reload(JobRow job) {
        return store.findJob(TENANT, job.id()).orElseThrow();
    }

    // ------------------------------------------------------------- enrolment

    @Test
    @DisplayName(
            "an agent is granted exactly the print-agent role at its branch, and the kitchen's screen cannot approve one")
    void anAgentHoldsExactlyItsRole() throws Exception {
        List<String> roles = jdbc.sql("""
                        SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = :subject AND g.status = 'ACTIVE'
                        """)
                .param("subject", agent.subject())
                .query(String.class)
                .list();
        assertThat(roles).containsExactly("print-agent");
        assertThat(jdbc.sql("SELECT device_class FROM iam.device_principals WHERE id = :id")
                        .param("id", agent.deviceId())
                        .query(String.class)
                        .single())
                .isEqualTo("PRINT_AGENT");

        // The kitchen devices screen is the kitchen's: its capability opens the kitchen's classes only.
        JsonNode begun = ok(post(ENROLMENTS)
                .with(request -> {
                    request.setRemoteAddr("10.77.0.9");
                    return request;
                })
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"deviceClass\":\"PRINT_AGENT\",\"label\":\"another\"}"));
        MvcResult refused = call(postJson(
                "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                        + "/kitchen/devices/enrolments/" + begun.get("userCode").asString() + "/approve",
                MANAGER,
                "{\"displayName\":\"Wrong screen\"}"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.device_principals")
                        .query(Long.class)
                        .single())
                .as("nothing was provisioned by the refusal")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a print approval refuses a code that asked to be a kitchen display, and writes nothing")
    void aKitchenDisplayCodeCannotBeApprovedAsAnAgent() throws Exception {
        JsonNode begun = ok(post(ENROLMENTS)
                .with(request -> {
                    request.setRemoteAddr("10.77.0.10");
                    return request;
                })
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"deviceClass\":\"KITCHEN_KDS\",\"label\":\"a screen\"}"));

        MvcResult refused = call(postJson(
                PRINT + "/agents/enrolments/" + begun.get("userCode").asString() + "/approve",
                MANAGER,
                "{\"displayName\":\"A screen as an agent\"}"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.device_principals")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------- claim

    @Test
    @DisplayName("with nothing queued a claim answers an empty list and records what the agent advertised")
    void anEmptyClaimRecordsTheAdvert() throws Exception {
        assertThat(fake.claimed(5)).isEmpty();

        JsonNode agents = ok(getAs(PRINT + "/agents", MANAGER));
        assertThat(agents).hasSize(1);
        JsonNode advertised = agents.get(0).get("advertised").get(0);
        assertThat(advertised.get("key").asString()).isEqualTo("counter");
        assertThat(advertised.get("status").asString()).isEqualTo("ONLINE");
        assertThat(advertised.get("agentBuild").asString()).isEqualTo("fake-agent 1.0");
        assertThat(agents.get(0).get("boundPrinterIds").get(0).asString())
                .isEqualTo(counter.id().toString());
    }

    @Test
    @DisplayName("a claim hands out identifiers and a 60-second lease, never the paper")
    void aClaimCarriesNoPaper() throws Exception {
        JobRow job = slipJob("A-014");

        MvcResult claimed = fake.claim(5);

        JsonNode body = JSON.readTree(claimed.getResponse().getContentAsString());
        assertThat(body.get("jobs")).hasSize(1);
        JsonNode first = body.get("jobs").get(0);
        assertThat(first.get("jobId").asString()).isEqualTo(job.id().toString());
        assertThat(first.get("printerKey").asString()).isEqualTo("counter");
        assertThat(first.get("documentType").asString()).isEqualTo("ORDER_SLIP");
        assertThat(first.get("leaseToken").asString()).isNotBlank();
        assertThat(java.time.Instant.parse(first.get("leaseExpiresAt").asString()))
                .isEqualTo(clock.instant().plusSeconds(60));
        assertThat(claimed.getResponse().getContentAsString())
                .as("no address to dial and no document")
                .doesNotContain("Lagman")
                .doesNotContain("host")
                .doesNotContain("ip");
        assertThat(reload(job).status().name()).isEqualTo("CLAIMED");
        assertThat(reload(job).attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "jobs of one printer are claimed one at a time, in the order they were queued; a retry of the claim repeats the held lease")
    void oneAtATimeInOrder() throws Exception {
        JobRow first = slipJob("A-001");
        clock.advance(Duration.ofSeconds(2));
        JobRow second = slipJob("A-002");
        assertThat(first.id()).isNotEqualTo(second.id());

        List<JsonNode> firstClaim = fake.claimed(10);
        List<JsonNode> again = fake.claimed(10);

        assertThat(firstClaim).hasSize(1);
        assertThat(firstClaim.getFirst().get("jobId").asString())
                .isEqualTo(first.id().toString());
        assertThat(again)
                .as("a reply lost in transit is repeated, and the second job waits behind the first")
                .hasSize(1);
        assertThat(again.getFirst().get("jobId").asString())
                .isEqualTo(first.id().toString());
        assertThat(again.getFirst().get("leaseToken").asString())
                .isEqualTo(firstClaim.getFirst().get("leaseToken").asString());

        MvcResult done = fake.report(
                first.id(),
                UUID.fromString(firstClaim.getFirst().get("leaseToken").asString()),
                "PRINTED",
                null);
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        List<JsonNode> next = fake.claimed(10);
        assertThat(next).hasSize(1);
        assertThat(next.getFirst().get("jobId").asString())
                .isEqualTo(second.id().toString());
    }

    @Test
    @DisplayName("a printer that reports itself offline is not claimed from, and is claimed from again when it is back")
    void anOfflinePrinterIsNotClaimedFrom() throws Exception {
        slipJob("A-014");

        fake.reportsStatus("counter", "OFFLINE");
        assertThat(fake.claimed(5)).isEmpty();
        assertThat(store.findPrinter(TENANT, counter.id()).orElseThrow().lastStatus())
                .isEqualTo(PrinterStatus.OFFLINE);

        fake.reportsStatus("counter", "ONLINE");
        assertThat(fake.claimed(5)).hasSize(1);
    }

    @Test
    @DisplayName("with feature.printing off a claim hands out nothing, though the job is still there")
    void theFlagStopsTheHandOut() throws Exception {
        JobRow job = slipJob("A-014");
        printingOff();

        assertThat(fake.claimed(5)).isEmpty();
        assertThat(reload(job).status().name()).isEqualTo("QUEUED");
    }

    // -------------------------------------------------------------- the lease

    @Test
    @DisplayName(
            "a stale lease token is refused 409 and changes nothing; the right one is accepted; the same PRINTED again is a replay")
    void leaseTokensAreChecked() throws Exception {
        JobRow job = slipJob("A-014");
        JsonNode claimed = fake.claimed(1).getFirst();
        UUID lease = UUID.fromString(claimed.get("leaseToken").asString());

        MvcResult stale = fake.report(job.id(), UUID.randomUUID(), "PRINTED", null);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_LEASE");
        assertThat(reload(job).status().name())
                .as("a stale token changed nothing")
                .isEqualTo("CLAIMED");

        MvcResult accepted = fake.report(job.id(), lease, "PRINTED", null);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(accepted.getResponse().getContentAsString()).contains("APPLIED");
        JobRow printed = reload(job);
        assertThat(printed.status().name()).isEqualTo("PRINTED");
        assertThat(printed.printedAt()).isEqualTo(clock.instant());

        MvcResult replay = fake.report(job.id(), lease, "PRINTED", null);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString()).contains("REPLAYED");
        MvcResult contradiction = fake.report(job.id(), lease, "FAILED", "TRANSPORT");
        assertThat(contradiction.getResponse().getStatus())
                .as("a job that printed cannot be reported failed")
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "an agent cannot fetch the bytes of a job it does not hold the lease of, or after the lease has run out")
    void theArtifactNeedsTheLiveLease() throws Exception {
        JobRow job = slipJob("A-014");
        UUID lease =
                UUID.fromString(fake.claimed(1).getFirst().get("leaseToken").asString());

        assertThat(fake.artifact(job.id(), UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(fake.artifact(job.id(), lease).getResponse().getStatus()).isEqualTo(200);

        clock.advance(Duration.ofSeconds(61));
        assertThat(fake.artifact(job.id(), lease).getResponse().getStatus())
                .as("sixty-one seconds later the lease is dead")
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "a lapsed lease returns the job to the queue with the attempt counted, and the next print says it is a retry")
    void aLapsedLeaseIsARetry() throws Exception {
        JobRow job = slipJob("A-014");
        UUID lost = UUID.fromString(fake.claimed(1).getFirst().get("leaseToken").asString());

        clock.advance(Duration.ofSeconds(61));
        PrintMaintenance.Pass pass = maintenance.runOnce();

        assertThat(pass.leasesLapsed())
                .as("it was not the last attempt, so it is not dead")
                .isZero();
        JobRow lapsed = reload(job);
        assertThat(lapsed.status().name()).isEqualTo("QUEUED");
        assertThat(lapsed.attempt()).as("the attempt was counted at the claim").isEqualTo(1);
        assertThat(lapsed.failureCode()).isEqualTo(uz.horecaos.platform.printing.domain.FailureCode.LEASE_LAPSED);

        MvcResult late = fake.report(job.id(), lost, "PRINTED", null);
        assertThat(late.getResponse().getStatus())
                .as("the old lease is stale now")
                .isEqualTo(409);

        JsonNode again = fake.claimed(1).getFirst();
        UUID fresh = UUID.fromString(again.get("leaseToken").asString());
        assertThat(fresh).isNotEqualTo(lost);
        assertThat(reload(job).attempt()).isEqualTo(2);

        MvcResult artifact = fake.artifact(job.id(), fresh);
        Bitmap1 drawn = EscPosDecoder.decode(artifact.getResponse().getContentAsByteArray())
                .bitmap();
        PrintDocument stored = PrintingSteps.documentOf(reload(job), protection, codec);
        Bitmap1 firstTry = new DocumentRenderer()
                .render(
                        stored,
                        new RenderContext(
                                1, job.queuedAt(), clock.instant(), 5, (asset, width) -> java.util.Optional.empty()))
                .bitmap();
        Bitmap1 retry = new DocumentRenderer()
                .render(
                        stored,
                        new RenderContext(
                                2, job.queuedAt(), clock.instant(), 5, (asset, width) -> java.util.Optional.empty()))
                .bitmap();
        assertThat(drawn)
                .as("the second attempt draws the retry line")
                .isEqualTo(retry)
                .isNotEqualTo(firstTry);
    }

    @Test
    @DisplayName(
            "six failures end DEAD with an alert; each retry waits longer, and a job is not claimable before its backoff")
    void sixFailuresEndDead() throws Exception {
        JobRow job = slipJob("A-014");
        List<Long> waits = new java.util.ArrayList<>();

        for (int attempt = 1; attempt <= 6; attempt++) {
            JsonNode claimed = fake.claimed(1).getFirst();
            UUID lease = UUID.fromString(claimed.get("leaseToken").asString());
            MvcResult failed = fake.report(job.id(), lease, "FAILED", "TRANSPORT");
            assertThat(failed.getResponse().getStatus())
                    .as("attempt " + attempt)
                    .isEqualTo(200);
            JobRow after = reload(job);
            if (attempt < 6) {
                assertThat(after.status().name()).isEqualTo("QUEUED");
                waits.add(
                        Duration.between(clock.instant(), after.nextAttemptAt()).toSeconds());
                assertThat(fake.claimed(1))
                        .as("not claimable before its backoff")
                        .isEmpty();
                clock.advance(Duration.between(clock.instant(), after.nextAttemptAt()));
            } else {
                assertThat(after.status().name()).isEqualTo("DEAD");
                assertThat(after.attempt()).isEqualTo(6);
                assertThat(after.failureCode()).isEqualTo(uz.horecaos.platform.printing.domain.FailureCode.TRANSPORT);
            }
        }

        assertThat(waits).as("+5 s, +15 s, +45 s, +2 min, +5 min").containsExactly(5L, 15L, 45L, 120L, 300L);
        assertThat(alerts.calls())
                .as("one alert per dead job, keyed by the job so a second sweep cannot repeat it")
                .filteredOn(call -> call.subjectId().equals(job.id()))
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.eventClass()).isEqualTo("PRINT_JOB_DEAD");
                    assertThat(call.tenantId()).isEqualTo(TENANT);
                    assertThat(call.brandId()).isEqualTo(BRAND);
                    assertThat(call.locationId()).isEqualTo(LOCATION);
                    assertThat(call.templateKey()).isEqualTo("PRINT_JOB_DEAD");
                    assertThat(call.subjectType()).isEqualTo("PrintJob");
                    assertThat(call.subjectId()).isEqualTo(job.id());
                    assertThat(call.idempotencyKeyBase()).isEqualTo("PRINT_JOB_DEAD:PrintJob:" + job.id());
                });
        assertThat(fake.claimed(1)).as("a dead job is never claimed again").isEmpty();
    }

    @Test
    @DisplayName("a lease that lapses on the last attempt ends the job DEAD, and says so")
    void aLapseOnTheLastAttemptIsDead() throws Exception {
        JobRow job = slipJob("A-014");
        jdbc.sql("UPDATE printing.print_jobs SET attempt = 5 WHERE id = :id")
                .param("id", job.id())
                .update();
        fake.claimed(1);
        assertThat(reload(job).attempt()).isEqualTo(6);

        clock.advance(Duration.ofSeconds(61));
        PrintMaintenance.Pass pass = maintenance.runOnce();

        assertThat(pass.leasesLapsed()).isEqualTo(1);
        assertThat(reload(job).status().name()).isEqualTo("DEAD");
        assertThat(reload(job).failureCode()).isEqualTo(uz.horecaos.platform.printing.domain.FailureCode.LEASE_LAPSED);
    }

    @Test
    @DisplayName("a kitchen ticket past its expiry becomes EXPIRED and is never claimed rather than printing stale")
    void aStaleTicketExpires() throws Exception {
        routes.create(
                TENANT,
                BRAND,
                LOCATION,
                new RouteDraft(PrintDocumentType.KITCHEN_TICKET, null, counter.id(), 1, true, true),
                MANAGER);
        SeededOrder order = world.order(LOCATION, "K-001", null, false, LineSpec.of("Plov", 1, 50_000));
        UUID ticket = world.releasedTicket(order, LOCATION, "K-001", Map.of(0, PrintingWorld.GRILL), clock.instant());
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> events.publishEvent(new uz.horecaos.platform.kitchen.api.KitchenTicketReleased(
                                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant())));
        JobRow job = store.listJobs(TENANT, LOCATION, List.of("QUEUED"), null, null, 5).stream()
                .filter(found -> found.documentType() == PrintDocumentType.KITCHEN_TICKET)
                .findFirst()
                .orElseThrow();

        fake.reportsStatus("counter", "OFFLINE");
        clock.advance(Duration.ofMinutes(31));
        fake.reportsStatus("counter", "ONLINE");

        assertThat(fake.claimed(5)).as("thirty-one minutes late is stale").isEmpty();
        assertThat(reload(job).status().name()).isEqualTo("EXPIRED");
    }

    // ------------------------------------------------------ an agent knows its branch

    @Test
    @DisplayName(
            "an enrolled print agent learns which tenant, brand and branch it was approved at from the device self-read, and a revoked one is refused it")
    void anAgentReadsItsOwnBranch() throws Exception {
        MvcResult me = call(getAs("/api/v1/devices/me", agent.subject()));
        assertThat(me.getResponse().getStatus())
                .as(me.getResponse().getContentAsString())
                .isEqualTo(200);
        String body = me.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"deviceClass\":\"PRINT_AGENT\"")
                .contains(TENANT.toString())
                .contains(BRAND.toString())
                .contains(LOCATION.toString())
                .as("the self-read never returns a secret, a client id or a token")
                .doesNotContain("secret")
                .doesNotContain("clientId");

        ok(postJson(PRINT + "/agents/" + agent.deviceId() + "/revoke", MANAGER, "{\"reason\":\"machine replaced\"}"));
        assertThat(status(getAs("/api/v1/devices/me", agent.subject())))
                .as("a token that outlives a revocation stops at once")
                .isEqualTo(403);
    }

    // -------------------------------------------------------------- what is kept

    @Test
    @DisplayName(
            "a finished job's document is erased seven days after it ended and a dead job's after thirty; the row, its digest and its outcome stay")
    void theDocumentIsErasedOnSchedule() throws Exception {
        JobRow printed = slipJob("A-014");
        JobRow dying = slipJob("A-015");
        // The first claim takes the older job: print it.
        UUID lease =
                UUID.fromString(fake.claimed(1).getFirst().get("leaseToken").asString());
        assertThat(fake.report(printed.id(), lease, "PRINTED", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        Instant printedAt = clock.instant();
        // The second dies on six transport failures, a little later.
        for (int attempt = 1; attempt <= 6; attempt++) {
            JsonNode claimed = fake.claimed(1).getFirst();
            fake.report(dying.id(), UUID.fromString(claimed.get("leaseToken").asString()), "FAILED", "TRANSPORT");
            clock.advance(Duration.ofMinutes(6));
        }
        Instant diedAt = clock.instant().minus(Duration.ofMinutes(6));
        assertThat(reload(dying).status().name()).isEqualTo("DEAD");
        String digest = reload(printed).documentDigest();

        clock.set(printedAt.plus(Duration.ofDays(7)).minusSeconds(60));
        maintenance.runOnce();
        assertThat(reload(printed).protectedDocument())
                .as("a minute short of seven days after the print: kept")
                .isNotNull();

        clock.set(printedAt.plus(Duration.ofDays(7)).plusSeconds(60));
        PrintMaintenance.Pass pass = maintenance.runOnce();

        assertThat(pass.purged()).as("only the printed one is due").isEqualTo(1);
        JobRow erased = reload(printed);
        assertThat(erased.protectedDocument())
                .as("the printed job's document is gone")
                .isNull();
        assertThat(erased.status().name()).isEqualTo("PRINTED");
        assertThat(erased.documentDigest()).as("the digest says what it was").isEqualTo(digest);
        assertThat(reload(dying).protectedDocument())
                .as("a dead job is kept for thirty days, long enough to be looked at")
                .isNotNull();

        clock.set(diedAt.plus(Duration.ofDays(30)).minusSeconds(60));
        maintenance.runOnce();
        assertThat(reload(dying).protectedDocument())
                .as("a minute short of thirty days after it died: kept")
                .isNotNull();

        clock.set(diedAt.plus(Duration.ofDays(30)).plusSeconds(60));
        maintenance.runOnce();
        assertThat(reload(dying).protectedDocument())
                .as("thirty days after it died")
                .isNull();
        assertThat(reload(dying).status().name()).isEqualTo("DEAD");

        // An erased document cannot be drawn again, by the agent or for a preview.
        MvcResult preview =
                call(postJson(PRINT + "/documents/preview", MANAGER, "{\"jobId\":\"" + printed.id() + "\"}"));
        assertThat(preview.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName(
            "a kitchen ticket waiting two minutes on a printer that reports itself offline raises one alert, and the same outage raises no second")
    void anOfflinePrinterWithATicketWaitingIsAnAlert() throws Exception {
        routes.create(
                TENANT,
                BRAND,
                LOCATION,
                new RouteDraft(PrintDocumentType.KITCHEN_TICKET, null, counter.id(), 1, true, true),
                MANAGER);
        SeededOrder order = world.order(LOCATION, "K-002", null, false, LineSpec.of("Plov", 1, 50_000));
        UUID ticket = world.releasedTicket(order, LOCATION, "K-002", Map.of(0, PrintingWorld.GRILL), clock.instant());
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> events.publishEvent(new uz.horecaos.platform.kitchen.api.KitchenTicketReleased(
                                UUID.randomUUID(), TENANT, BRAND, LOCATION, ticket, order.orderId(), clock.instant())));
        fake.reportsStatus("counter", "OFFLINE");
        fake.claimed(1);

        clock.advance(Duration.ofSeconds(90));
        assertThat(maintenance.runOnce().offlineAlerts())
                .as("ninety seconds is not yet two minutes")
                .isZero();

        clock.advance(Duration.ofSeconds(40));
        assertThat(maintenance.runOnce().offlineAlerts()).isEqualTo(1);
        maintenance.runOnce();

        List<uz.horecaos.platform.support.RecordingOperationsAlertPort.Call> raised = alerts.calls().stream()
                .filter(call -> call.eventClass().equals("PRINTER_OFFLINE"))
                .filter(call -> call.subjectId().equals(counter.id()))
                .toList();
        assertThat(raised)
                .as("the sweep runs every half minute; one outage is one episode")
                .isNotEmpty();
        assertThat(raised.stream().map(call -> call.idempotencyKeyBase()).distinct())
                .as("every repeat of the same episode carries one key, which the fan-out de-duplicates")
                .hasSize(1);
        assertThat(raised.getFirst().locationId()).isEqualTo(LOCATION);
        assertThat(raised.getFirst().variables()).containsEntry("printerStatus", "OFFLINE");

        // The printer comes back: the agent's next claim says so, the ticket is handed out, and nothing more is raised.
        long before = alerts.calls().size();
        fake.reportsStatus("counter", "ONLINE");
        assertThat(fake.claimed(1))
                .as("the waiting ticket is claimed once the printer is back")
                .hasSize(1);
        clock.advance(Duration.ofMinutes(5));
        maintenance.runOnce();
        assertThat(alerts.calls().size()).as("an online printer raises nothing").isEqualTo((int) before);
    }

    // ---------------------------------------------------- late and retried paper

    @Test
    @DisplayName(
            "a job that waited behind an offline printer prints «delayed» with its queue time and no retry line; one inside the bound prints neither")
    void delayedIsNotRetried() throws Exception {
        JobRow waited = slipJob("A-014");
        fake.reportsStatus("counter", "OFFLINE");
        fake.claimed(1);
        clock.advance(Duration.ofMinutes(12));
        fake.reportsStatus("counter", "ONLINE");

        JsonNode claimed = fake.claimed(1).getFirst();
        UUID lease = UUID.fromString(claimed.get("leaseToken").asString());
        Bitmap1 drawn = EscPosDecoder.decode(
                        fake.artifact(waited.id(), lease).getResponse().getContentAsByteArray())
                .bitmap();

        PrintDocument stored = PrintingSteps.documentOf(reload(waited), protection, codec);
        DocumentRenderer renderer = new DocumentRenderer();
        RenderContext delayed =
                new RenderContext(1, waited.queuedAt(), clock.instant(), 5, (a, w) -> java.util.Optional.empty());
        RenderContext onTime =
                new RenderContext(1, waited.queuedAt(), waited.queuedAt(), 5, (a, w) -> java.util.Optional.empty());
        assertThat(drawn).isEqualTo(renderer.render(stored, delayed).bitmap());
        assertThat(drawn).isNotEqualTo(renderer.render(stored, onTime).bitmap());
        assertThat(renderer.render(stored, delayed).layout().dump())
                .contains("ЗАДЕРЖКА")
                .doesNotContain("Повторная печать");
        assertThat(reload(waited).attempt())
                .as("it printed on its first attempt")
                .isEqualTo(1);

        assertThat(codec.digest(stored))
                .as("the stored document, and its digest, are the same with and without the lines")
                .isEqualTo(reload(waited).documentDigest());
    }

    // -------------------------------------------------------- whole, with a printer

    @Test
    @DisplayName(
            "end to end against a fake printer: claim, artifact, bytes received, and the raster decoded is the preview bitmap")
    void endToEndAgainstAFakePrinter() throws Exception {
        JobRow job = slipJob("A-014");

        List<FakePrintAgent.Handled> handled = fake.handleOnce();

        assertThat(handled).hasSize(1);
        assertThat(handled.getFirst().reported()).isEqualTo("PRINTED");
        assertThat(handled.getFirst().httpStatus()).isEqualTo(200);
        hardware.awaitPages(1, Duration.ofSeconds(5));
        var page = hardware.lastPage();
        assertThat(page.initialised()).isTrue();
        assertThat(page.cut()).isTrue();
        assertThat(reload(job).status().name()).isEqualTo("PRINTED");

        MvcResult preview = mvc.perform(
                        postJson(PRINT + "/documents/preview", MANAGER, "{\"jobId\":\"" + job.id() + "\"}"))
                .andReturn();
        assertThat(preview.getResponse().getStatus()).isEqualTo(200);
        assertThat(preview.getResponse().getContentType()).isEqualTo("image/png");
        Bitmap1 previewed = Bitmap1.ofImage(javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(preview.getResponse().getContentAsByteArray())));
        assertThat(page.bitmap())
                .as("what the printer received is what the manager previewed")
                .isEqualTo(previewed);
    }

    @Test
    @DisplayName(
            "a fiscal receipt's QR on the bytes the printer received decodes to the stored URL, which is what a phone opens")
    void theReceiptQrReachesThePaper() throws Exception {
        routes.create(
                TENANT,
                BRAND,
                LOCATION,
                new RouteDraft(PrintDocumentType.FISCAL_RECEIPT, null, counter.id(), 1, true, true),
                MANAGER);
        SeededOrder order = world.order(LOCATION, "R-001", null, false, LineSpec.of("Plov", 1, 50_000));
        UUID document = world.fiscalDocument(order, "SALE", "ISSUED");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> events.publishEvent(new uz.horecaos.platform.payments.api.FiscalDocumentIssued(
                                UUID.randomUUID(), TENANT, order.orderId(), document, clock.instant())));

        fake.handleOnce();

        hardware.awaitPages(1, Duration.ofSeconds(5));
        assertThat(QrReader.read(hardware.lastPage().bitmap()))
                .contains("https://ofd.soliq.uz/epi?t=EP000000000000&r=1234567&c=20261008120501&s=987654321098");
    }

    // ------------------------------------------------------------- who is who

    @Test
    @DisplayName("a revoked agent's next claim is refused, whatever its token's remaining life")
    void aRevokedAgentIsRefused() throws Exception {
        slipJob("A-014");
        assertThat(fake.claim(1).getResponse().getStatus()).isEqualTo(200);

        JsonNode revoked = ok(postJson(
                PRINT + "/agents/" + agent.deviceId() + "/revoke", MANAGER, "{\"reason\":\"machine stolen\"}"));
        assertThat(revoked.get("revoked").asBoolean()).isTrue();

        MvcResult refused = fake.claim(1);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(fake.artifact(UUID.randomUUID(), UUID.randomUUID())
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(fake.report(UUID.randomUUID(), UUID.randomUUID(), "PRINTED", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        JsonNode again = ok(
                postJson(PRINT + "/agents/" + agent.deviceId() + "/revoke", MANAGER, "{\"reason\":\"pressed twice\"}"));
        assertThat(again.get("revoked").asBoolean())
                .as("a second press is not an error")
                .isFalse();
    }

    @Test
    @DisplayName("an agent at one branch is refused at its sibling branch and at another tenant, at the endpoint")
    void anAgentIsRefusedElsewhere() throws Exception {
        String sibling = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + SIBLING + "/print";
        String otherTenant = "/api/v1/tenants/" + PrintingWorld.OTHER_TENANT + "/brands/" + PrintingWorld.OTHER_BRAND
                + "/locations/" + PrintingWorld.OTHER_LOCATION + "/print";

        for (String elsewhere : List.of(sibling, otherTenant)) {
            FakePrintAgent stray = new FakePrintAgent(mvc, tokenFor(agent.subject()), elsewhere);
            assertThat(stray.claim(1).getResponse().getStatus())
                    .as(elsewhere + " claims")
                    .isEqualTo(403);
            assertThat(stray.artifact(UUID.randomUUID(), UUID.randomUUID())
                            .getResponse()
                            .getStatus())
                    .as(elsewhere + " artifact")
                    .isEqualTo(403);
            assertThat(stray.report(UUID.randomUUID(), UUID.randomUUID(), "PRINTED", null)
                            .getResponse()
                            .getStatus())
                    .as(elsewhere + " result")
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("a manager cannot claim or report, and an agent cannot read a printer, a route or the queue")
    void thePairsOfCapabilitiesDoNotMix() throws Exception {
        slipJob("A-014");
        FakePrintAgent manager = new FakePrintAgent(mvc, tokenFor(MANAGER), PRINT);
        assertThat(manager.claim(1).getResponse().getStatus()).isEqualTo(403);
        assertThat(manager.report(UUID.randomUUID(), UUID.randomUUID(), "PRINTED", null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        for (String read : List.of("/printers", "/routes", "/jobs", "/agents")) {
            MvcResult refused = mvc.perform(get(PRINT + read).with(tokenFor(agent.subject())))
                    .andReturn();
            assertThat(refused.getResponse().getStatus())
                    .as("an agent reading " + read)
                    .isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
        }
    }
}
