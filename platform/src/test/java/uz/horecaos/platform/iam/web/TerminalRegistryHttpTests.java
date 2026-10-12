package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.terminals.TerminalAttribution;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.web.authorization.TerminalAttributionContext;
import uz.horecaos.platform.web.cache.CacheRegistry;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0155, Decisions 6 and 7: the class-neutral registry and its device record, the tenant's Policy card, and what an action
 * taken at a screen leaves on the trail.
 */
class TerminalRegistryHttpTests extends TerminalsFixture {

    private static final String BRAND_MANAGER = "term-brand-manager";

    @Autowired
    @SuppressWarnings("NullAway")
    private AuditRecorder audit;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcKitchenStore kitchen;

    // ---------------------------------------------------------------------------------- the registry

    @Test
    @DisplayName("the registry lists every device at a branch, of any class, with the same last-seen and who is at it")
    void theRegistryIsClassNeutral() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        UUID kiosk = insertDevice("KIOSK", "Lobby kiosk");
        UUID wall = insertDevice("KITCHEN_VDU", "Grill wall");
        setPin(tablet, AZIZA, "482915");
        unlockOk(tablet, AZIZA, "482915");
        // the build rides on the next stamp: last-seen is written at most once a minute, so the clock moves on first
        advance(Duration.ofMinutes(2));
        clearCache(CacheRegistry.IAM_DEVICE_LAST_SEEN.cacheName());
        mvc.perform(get(TERMINAL_SESSION + "/roster")
                        .with(token(tablet.subject()))
                        .header("X-Device-Build", "kds-1.4"))
                .andReturn();

        JsonNode list = ok(get(TERMINALS).with(token(CENTRE_MANAGER)));

        assertThat(list).hasSize(3);
        JsonNode pass = row(list, tablet.deviceId());
        assertThat(pass.get("deviceClass").asString()).isEqualTo("KITCHEN_KDS");
        assertThat(pass.get("unlockable").asBoolean()).isTrue();
        assertThat(pass.get("attributionMode").asString()).isEqualTo("PERSON_OPTIONAL");
        assertThat(pass.get("effectiveAutoLockSeconds").asInt()).isEqualTo(300);
        assertThat(pass.get("autoLockSeconds").isNull()).isTrue();
        assertThat(pass.get("lastSeenAt").isNull()).isFalse();
        assertThat(pass.get("lastSeenBuild").asString()).isEqualTo("kds-1.4");
        assertThat(pass.get("unlockedNow").get("label").asString()).isEqualTo("Aziza K.");
        assertThat(pass.get("enrolledBy").asString())
                .as("the approver is a display name when the directory has one; a bare subject is never shown")
                .contains("Madina")
                .doesNotContain(CENTRE_MANAGER);
        JsonNode lobby = row(list, kiosk);
        assertThat(lobby.get("deviceClass").asString()).isEqualTo("KIOSK");
        assertThat(lobby.get("unlockable").asBoolean()).isFalse();
        assertThat(lobby.get("unlockedNow").isNull()).isTrue();
        assertThat(row(list, wall).get("deviceClass").asString()).isEqualTo("KITCHEN_VDU");
        assertThat(list.toString()).doesNotContain("keycloak").doesNotContain("principalSubject");
    }

    @Test
    @DisplayName(
            "terminal.read and terminal.manage are held at the branch: a cook has neither, a brand manager reads, the other branch's manager reaches nothing here")
    void theRegistryIsGatedByTheTwoCapabilities() throws Exception {
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND, TENANT);
        Tablet tablet = enrolTablet("Pass tablet");

        assertThat(mvc.perform(get(TERMINALS).with(token(AZIZA)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a line cook")
                .isEqualTo(403);
        assertThat(mvc.perform(get(TERMINALS).with(token(BRAND_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("a brand manager reads")
                .isEqualTo(200);
        MvcResult brandManagerWrites = configure(tablet, "Renamed", "DEVICE_ONLY", null, BRAND_MANAGER);
        assertThat(brandManagerWrites.getResponse().getStatus())
                .as("and does not manage")
                .isEqualTo(403);
        assertThat(mvc.perform(get(TERMINALS).with(token(NORTH_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(configure(tablet, "Renamed", "DEVICE_ONLY", null, NORTH_MANAGER)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        MvcResult wrongBranchInPath = mvc.perform(put(NORTH_PATH + "/terminals/" + tablet.deviceId())
                        .with(token(NORTH_MANAGER))
                        .header("If-Match", "W/\"1\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Mine now\",\"attributionMode\":\"DEVICE_ONLY\"}"))
                .andReturn();
        assertThat(wrongBranchInPath.getResponse().getStatus())
                .as("the North manager at the North path with the Centre's device")
                .isEqualTo(404);
    }

    @Test
    @DisplayName(
            "a manager renames a device and sets its mode and auto-lock against a version, and each change is audited as a diff")
    void configuringIsVersionedBoundedAndAudited() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");

        MvcResult noVersion = mvc.perform(put(TERMINALS + "/" + tablet.deviceId())
                        .with(token(CENTRE_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Pass tablet\",\"attributionMode\":\"PERSON_REQUIRED\"}"))
                .andReturn();
        assertThat(noVersion.getResponse().getStatus()).as("no If-Match").isEqualTo(400);

        int version = deviceVersion(tablet.deviceId());
        MvcResult changed = configure(tablet, "Grill pass", "PERSON_REQUIRED", 120, CENTRE_MANAGER);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(changed.getResponse().getContentAsString());
        assertThat(body.get("displayName").asString()).isEqualTo("Grill pass");
        assertThat(body.get("attributionMode").asString()).isEqualTo("PERSON_REQUIRED");
        assertThat(body.get("autoLockSeconds").asInt()).isEqualTo(120);
        assertThat(body.get("effectiveAutoLockSeconds").asInt()).isEqualTo(120);
        assertThat(changed.getResponse().getHeader("ETag")).isEqualTo("W/\"" + (version + 1) + "\"");

        MvcResult stale = mvc.perform(put(TERMINALS + "/" + tablet.deviceId())
                        .with(token(CENTRE_MANAGER))
                        .header("If-Match", "W/\"" + version + "\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Other\",\"attributionMode\":\"DEVICE_ONLY\"}"))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(codeOf(stale)).isEqualTo("STALE_VERSION");

        for (int outOfRange : new int[] {29, 3601}) {
            assertThat(configure(tablet, "Grill pass", "PERSON_REQUIRED", outOfRange, CENTRE_MANAGER)
                            .getResponse()
                            .getStatus())
                    .as("auto-lock %d", outOfRange)
                    .isEqualTo(400);
        }

        String fact = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                         WHERE action_code = 'terminal.device.configured'
                        """).query(String.class).single();
        assertThat(fact)
                .contains("Pass tablet")
                .contains("Grill pass")
                .contains("DEVICE_ONLY")
                .contains("PERSON_REQUIRED")
                .contains("before")
                .contains("after");
    }

    @Test
    @DisplayName("a class nobody unlocks cannot be given a person mode, by the registry or by the database")
    void onlyAnUnlockableClassMayBeGivenAPersonMode() throws Exception {
        UUID kiosk = insertDevice("KIOSK", "Lobby kiosk");
        UUID wall = insertDevice("KITCHEN_VDU", "Grill wall");

        for (UUID device : List.of(kiosk, wall)) {
            MvcResult refused = mvc.perform(put(TERMINALS + "/" + device)
                            .with(token(CENTRE_MANAGER))
                            .header("If-Match", "W/\"" + deviceVersion(device) + "\"")
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\":\"x\",\"attributionMode\":\"PERSON_OPTIONAL\"}"))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).isEqualTo(422);
            assertThatThrownBy(() -> jdbc.sql(
                                    "UPDATE iam.device_principals SET attribution_mode = 'PERSON_REQUIRED' WHERE id = :id")
                            .param("id", device)
                            .update())
                    .as("the database says the same")
                    .hasMessageContaining("ck_device_principal_attribution_class");
        }
        MvcResult renamed = mvc.perform(put(TERMINALS + "/" + kiosk)
                        .with(token(CENTRE_MANAGER))
                        .header("If-Match", "W/\"" + deviceVersion(kiosk) + "\"")
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "x-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Front door kiosk\",\"attributionMode\":\"DEVICE_ONLY\"}"))
                .andReturn();
        assertThat(renamed.getResponse().getStatus())
                .as("a kiosk can still be renamed")
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "setting a tablet back to DEVICE_ONLY ends its sessions at once: rollback restores today's attribution")
    void rollbackEndsTheSessions() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_REQUIRED");
        setPin(tablet, AZIZA, "482915");
        String session = unlockOk(tablet, AZIZA, "482915");
        boardIsEmpty();

        setMode(tablet, "DEVICE_ONLY");

        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_at IS NULL"))
                .isZero();
        assertThat(jdbc.sql("SELECT ended_reason FROM iam.terminal_sessions WHERE ended_at IS NOT NULL")
                        .query(String.class)
                        .list())
                .contains("LOCKED");
        MvcResult read = mvc.perform(
                        get(KITCHEN + "/tickets").with(token(tablet.subject())).header(SESSION, session))
                .andReturn();
        assertThat(read.getResponse().getStatus())
                .as("and the header means nothing now: the device is the actor, as it always was")
                .isNotEqualTo(403);
    }

    @Test
    @DisplayName("revoking from the registry takes a reason, writes one fact, and a second press changes nothing")
    void revokingFromTheRegistryIsAuditedOnce() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");

        MvcResult first = revoke(tablet, "Dropped in the fryer");
        MvcResult second = revoke(tablet, "Pressed twice");

        assertThat(JSON.readTree(first.getResponse().getContentAsString())
                        .get("changed")
                        .asBoolean())
                .isTrue();
        assertThat(JSON.readTree(second.getResponse().getContentAsString())
                        .get("changed")
                        .asBoolean())
                .isFalse();
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'terminal.device.revoked'"))
                .isEqualTo(1L);
        assertThat(mvc.perform(get(TERMINAL_SESSION + "/roster").with(token(tablet.subject())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the revoked tablet is refused on its very next request")
                .isEqualTo(403);
        JsonNode row = row(ok(get(TERMINALS).with(token(CENTRE_MANAGER))), tablet.deviceId());
        assertThat(row.get("status").asString()).isEqualTo("REVOKED");
        assertThat(row.get("revokedReason").asString()).isEqualTo("Dropped in the fryer");
    }

    @Test
    @DisplayName("a device's unlock history is paged newest first, and says who, when and how each ended")
    void theSessionHistoryIsPaged() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        setPin(tablet, AZIZA, "482915");
        setPin(tablet, BAKHROM, "735914");
        for (int unlock = 0; unlock < 30; unlock++) {
            advance(Duration.ofMinutes(1));
            unlockOk(tablet, unlock % 2 == 0 ? AZIZA : BAKHROM, unlock % 2 == 0 ? "482915" : "735914");
        }

        JsonNode firstPage =
                ok(get(TERMINALS + "/" + tablet.deviceId() + "/sessions").with(token(CENTRE_MANAGER)));
        assertThat(firstPage.get("sessions")).hasSize(25);
        assertThat(firstPage.get("nextCursor").isNull()).isFalse();
        JsonNode newest = firstPage.get("sessions").get(0);
        assertThat(newest.get("who").asString()).isIn("Aziza K.", "Bakhrom T.");
        assertThat(newest.get("reference").asString()).matches("S-\\d{4}");
        assertThat(newest.get("endedAt").isNull())
                .as("the newest is still open")
                .isTrue();
        assertThat(firstPage.get("sessions").get(1).get("endedReason").asString())
                .isEqualTo("SUPERSEDED");

        JsonNode secondPage = ok(get(TERMINALS + "/" + tablet.deviceId() + "/sessions")
                .param("cursor", firstPage.get("nextCursor").asString())
                .with(token(CENTRE_MANAGER)));
        assertThat(secondPage.get("sessions").size())
                .as("two earlier ones remain: the two PIN-setting sessions")
                .isEqualTo(7);
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();

        assertThat(mvc.perform(get(TERMINALS + "/" + tablet.deviceId() + "/sessions")
                                .param("cursor", "not-a-cursor")
                                .with(token(CENTRE_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(mvc.perform(get(TERMINALS + "/" + UUID.randomUUID() + "/sessions")
                                .with(token(CENTRE_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }

    // -------------------------------------------------------------------------------------- the policy

    @Test
    @DisplayName(
            "the Policy card: the five values with where each comes from, the odds beside the length, saved by the tenant's owner and not by a branch manager")
    void thePolicyCard() throws Exception {
        JsonNode defaults = ok(get(OPS + "/terminal-policy").with(token(OWNER)));
        assertThat(defaults.get("pinLength").get("value").asInt()).isEqualTo(6);
        assertThat(defaults.get("pinLength").get("source").asString()).isEqualTo("DEFAULT");
        assertThat(defaults.get("autoLockSeconds").get("value").asInt()).isEqualTo(300);
        assertThat(defaults.get("sessionMaxHours").get("value").asInt()).isEqualTo(12);
        assertThat(defaults.get("rosterDisplay").get("value").asString()).isEqualTo("NAME_INITIAL");
        assertThat(defaults.get("attributionDefault").get("value").asString()).isEqualTo("DEVICE_ONLY");
        assertThat(defaults.get("guessOdds").get(0).get("pinLength").asInt()).isEqualTo(4);
        assertThat(defaults.get("guessOdds").get(0).get("oneInNPerDay").asInt()).isEqualTo(833);
        assertThat(defaults.get("guessOdds").get(1).get("oneInNPerDay").asInt()).isEqualTo(83_333);
        assertThat(defaults.get("lockout").get("consecutiveFailures").asInt()).isEqualTo(5);
        assertThat(defaults.get("lockout").get("lockMinutes").asInt()).isEqualTo(15);
        assertThat(defaults.get("lockout").get("failuresPerDay").asInt()).isEqualTo(12);

        String body = "{\"pinLength\":4,\"autoLockSeconds\":120,\"sessionMaxHours\":8,\"rosterDisplay\":\"REFERENCE\","
                + "\"attributionDefault\":\"PERSON_OPTIONAL\"}";
        MvcResult byBranchManager = mvc.perform(put(OPS + "/terminal-policy")
                        .with(token(CENTRE_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "pol-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(byBranchManager.getResponse().getStatus())
                .as("a location manager's grant does not reach TENANT scope")
                .isEqualTo(403);
        assertThat(mvc.perform(get(OPS + "/terminal-policy").with(token(CENTRE_MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        MvcResult saved = mvc.perform(put(OPS + "/terminal-policy")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "pol-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(saved.getResponse().getStatus())
                .as(saved.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode view = JSON.readTree(saved.getResponse().getContentAsString());
        assertThat(view.get("pinLength").get("value").asInt()).isEqualTo(4);
        assertThat(view.get("pinLength").get("source").asString()).isEqualTo("TENANT");
        assertThat(view.get("attributionDefault").get("value").asString()).isEqualTo("PERSON_OPTIONAL");

        String fact = jdbc.sql("""
                        SELECT actor_subject || ' ' || change_document::text FROM audit.audit_events
                         WHERE action_code = 'terminal.policy.changed'
                        """).query(String.class).single();
        assertThat(fact)
                .as("the fact names the person who saved the card and the diff of the five")
                .contains(OWNER)
                .contains("REFERENCE")
                .contains("PERSON_OPTIONAL")
                .contains("NAME_INITIAL");
    }

    @Test
    @DisplayName(
            "every value is bounded where it is written, and the generic configuration editor neither lists nor writes the five")
    void thePolicyValuesAreBoundedAndNotTenantVisible() throws Exception {
        String[] invalid = {
            "{\"pinLength\":5,\"autoLockSeconds\":300,\"sessionMaxHours\":12,\"rosterDisplay\":\"NAME_INITIAL\",\"attributionDefault\":\"DEVICE_ONLY\"}",
            "{\"pinLength\":6,\"autoLockSeconds\":29,\"sessionMaxHours\":12,\"rosterDisplay\":\"NAME_INITIAL\",\"attributionDefault\":\"DEVICE_ONLY\"}",
            "{\"pinLength\":6,\"autoLockSeconds\":3601,\"sessionMaxHours\":12,\"rosterDisplay\":\"NAME_INITIAL\",\"attributionDefault\":\"DEVICE_ONLY\"}",
            "{\"pinLength\":6,\"autoLockSeconds\":300,\"sessionMaxHours\":0,\"rosterDisplay\":\"NAME_INITIAL\",\"attributionDefault\":\"DEVICE_ONLY\"}",
            "{\"pinLength\":6,\"autoLockSeconds\":300,\"sessionMaxHours\":17,\"rosterDisplay\":\"NAME_INITIAL\",\"attributionDefault\":\"DEVICE_ONLY\"}",
            "{\"pinLength\":6,\"autoLockSeconds\":300,\"sessionMaxHours\":12,\"rosterDisplay\":\"NICKNAME\",\"attributionDefault\":\"DEVICE_ONLY\"}",
            "{\"pinLength\":6,\"autoLockSeconds\":300,\"sessionMaxHours\":12,\"rosterDisplay\":\"NAME_INITIAL\"}"
        };
        for (String body : invalid) {
            MvcResult refused = mvc.perform(put(OPS + "/terminal-policy")
                            .with(token(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "pol-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as(body).isIn(400);
        }
        assertThat(count("SELECT count(*) FROM tenant.configuration_values WHERE key_code LIKE 'terminal.%'"))
                .as("nothing was stored by a refused save")
                .isZero();

        String keys = mvc.perform(get(OPS + "/configuration/keys").with(token(OWNER)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(keys).as("not listed").doesNotContain("terminal.");
        MvcResult write = mvc.perform(
                        post(OPS + "/configuration/keys/terminal.pin.length/values")
                                .with(token(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "gen-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"scopeType\":\"TENANT\",\"integerValue\":4,\"explicitNull\":false,\"reason\":\"sneaking\"}"))
                .andReturn();
        assertThat(write.getResponse().getStatus())
                .as("and not writable: the same answer an unregistered key gets")
                .isEqualTo(404);
    }

    @Test
    @DisplayName(
            "a newly approved tablet starts in the tenant's default mode; devices already enrolled, and classes nobody unlocks, keep DEVICE_ONLY")
    void aNewDeviceStartsInTheTenantsDefault() throws Exception {
        Tablet before = enrolTablet("Before");
        MvcResult saved = mvc.perform(put(OPS + "/terminal-policy")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "pol-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinLength\":6,\"autoLockSeconds\":300,\"sessionMaxHours\":12,"
                                + "\"rosterDisplay\":\"NAME_INITIAL\",\"attributionDefault\":\"PERSON_REQUIRED\"}"))
                .andReturn();
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);

        Tablet after = enrolTablet("After");

        assertThat(modeOf(before))
                .as("a running kitchen does not change on the day the default does")
                .isEqualTo("DEVICE_ONLY");
        assertThat(modeOf(after)).isEqualTo("PERSON_REQUIRED");
    }

    // ------------------------------------------------------------------ what an action leaves on the trail

    @Test
    @DisplayName(
            "an action taken at a terminal is attributed to the person, with the screen as evidence and in the change document")
    void anActionAtATerminalNamesThePersonAndTheScreen() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        TerminalAttribution via = new TerminalAttribution(UUID.randomUUID(), tablet.deviceId(), memberId(AZIZA), AZIZA);
        bindRequestWith(via);
        try {
            audit.record(AuditFact.of("kitchen.ticket.advanced", AuditClass.BUSINESS)
                    .by(ActorRef.user(AZIZA, null))
                    .at(ResourceScope.location(TENANT, BRAND, CENTRE))
                    .target("kitchen.ticket", UUID.randomUUID())
                    .because("Marked ready at the pass")
                    .changed(java.util.Map.of("status", java.util.Map.of("before", "STARTED", "after", "READY")))
                    .correlatedBy("corr-1")
                    .occurredAt(clock.instant())
                    .build());
            audit.record(AuditFact.of("kitchen.note.added", AuditClass.BUSINESS)
                    .by(ActorRef.user(CENTRE_MANAGER, null))
                    .at(ResourceScope.location(TENANT, BRAND, CENTRE))
                    .target("kitchen.ticket", UUID.randomUUID())
                    .because("Somebody else, in the same request")
                    .correlatedBy("corr-2")
                    .occurredAt(clock.instant())
                    .build());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        var person = jdbc.sql("""
                        SELECT actor_subject, evidence_reference, change_document::text
                          FROM audit.audit_events WHERE action_code = 'kitchen.ticket.advanced'
                        """)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single();
        assertThat(person.get(0))
                .as("the actor is the person: «Кто» shows a name")
                .isEqualTo(AZIZA);
        assertThat(person.get(1)).isEqualTo("terminal:" + via.sessionId());
        assertThat(person.get(2))
                .contains("terminalDevice")
                .contains(tablet.deviceId().toString());

        Boolean untagged = jdbc.sql("""
                        SELECT evidence_reference IS NULL AND change_document IS NULL
                          FROM audit.audit_events WHERE action_code = 'kitchen.note.added'
                        """).query(Boolean.class).single();
        assertThat(untagged)
                .as("a fact about someone else is not an action taken at the screen")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a ticket event written for the unlocked person names both the person and the screen; anyone else's event names no screen")
    void aTicketEventNamesBothThePersonAndTheScreen() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        UUID ticket = UUID.randomUUID();
        TerminalAttribution via = new TerminalAttribution(UUID.randomUUID(), tablet.deviceId(), memberId(AZIZA), AZIZA);

        // The ticket and its order are not what is under test, so the foreign keys onto them are set aside for these
        // inserts
        // only; the key that IS under test (via_device_id) is exercised below with enforcement on.
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            kitchen.recordEvent(
                    TENANT,
                    ticket,
                    null,
                    "IN_PRODUCTION",
                    "READY",
                    "STATION_ACTION",
                    "USER",
                    AZIZA,
                    null,
                    "c-0",
                    clock.instant());
            bindRequestWith(via);
            try {
                kitchen.recordEvent(
                        TENANT,
                        ticket,
                        null,
                        "IN_PRODUCTION",
                        "READY",
                        "STATION_ACTION",
                        "USER",
                        AZIZA,
                        null,
                        "c-1",
                        clock.instant());
                kitchen.recordEvent(
                        TENANT,
                        ticket,
                        null,
                        "READY",
                        "HANDED_OVER",
                        "ITEM_ROLLUP",
                        "SYSTEM_JOB",
                        "rollup",
                        null,
                        "c-2",
                        clock.instant());
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });

        List<String> byCorrelation = jdbc.sql("""
                        SELECT correlation_id || '=' || coalesce(via_device_id::text, 'none')
                          FROM kitchen.ticket_events ORDER BY correlation_id
                        """).query(String.class).list();
        assertThat(byCorrelation).containsExactly("c-0=none", "c-1=" + tablet.deviceId(), "c-2=none");
        assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE kitchen.ticket_events SET via_device_id = :other WHERE correlation_id = 'c-1'
                                """).param("other", UUID.randomUUID()).update())
                .as("the screen must be a real device of this tenant")
                .hasMessageContaining("fk_ticket_event_via_device");
    }

    // ---------------------------------------------------------------------------------------- helpers

    private MvcResult revoke(Tablet tablet, String reason) throws Exception {
        return mvc.perform(post(TERMINALS + "/" + tablet.deviceId() + "/revoke")
                        .with(token(CENTRE_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rev-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    private static JsonNode row(JsonNode list, UUID deviceId) {
        for (JsonNode row : list) {
            if (row.get("deviceId").asString().equals(deviceId.toString())) {
                return row;
            }
        }
        throw new AssertionError("device not listed: " + deviceId);
    }

    private String modeOf(Tablet tablet) {
        return jdbc.sql("SELECT attribution_mode FROM iam.device_principals WHERE id = :id")
                .param("id", tablet.deviceId())
                .query(String.class)
                .single();
    }

    private static void bindRequestWith(TerminalAttribution attribution) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        TerminalAttributionContext.bind(request, attribution);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** A device written straight into the tables, of any class. */
    private UUID insertDevice(String deviceClass, String name) {
        UUID deviceId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        String subject = "device-fixture-" + deviceId;
        PlatformRole role =
                switch (deviceClass) {
                    case "KIOSK" -> PlatformRole.KIOSK_DEVICE;
                    case "KITCHEN_VDU" -> PlatformRole.KITCHEN_VDU_DEVICE;
                    default -> PlatformRole.KITCHEN_DEVICE;
                };
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                        'ACTIVE', 'test-fixture', 'terminals test', :validFrom)
                """)
                .param("id", grantId)
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param(
                        "roleId",
                        uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer.platformRoleId(
                                role))
                .param("locationId", CENTRE)
                .param("validFrom", START.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO iam.device_principals
                    (id, tenant_id, brand_id, location_id, device_class, display_name,
                     keycloak_client_internal_id, keycloak_client_id, principal_subject,
                     grant_id, status, enrolled_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :class, :name,
                        :kcInternal, :kcClient, :subject, :grantId, 'ACTIVE', 'test-fixture-manager')
                """)
                .param("id", deviceId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", CENTRE)
                .param("class", deviceClass)
                .param("name", name)
                .param("kcInternal", "kc-internal-" + deviceId)
                .param("kcClient", "kc-client-" + deviceId)
                .param("subject", subject)
                .param("grantId", grantId)
                .update();
        return deviceId;
    }

    @SuppressWarnings("unused")
    private static List<Instant> none() {
        return new ArrayList<>();
    }
}
