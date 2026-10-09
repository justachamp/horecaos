package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.kitchen.application.KitchenTicketService.ItemOutcome;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.domain.RoutingLevel;
import uz.horecaos.platform.kitchen.domain.TicketItemStatus;
import uz.horecaos.platform.kitchen.domain.TicketStatus;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketCountsRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketItemRow;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.TicketRow;
import uz.horecaos.platform.support.SignedInStaff;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0155, Decision 5, over HTTP with a real kitchen tablet and real staff: a session is a row that names the person at a
 * tablet that has already authenticated as itself, and the authorization at the tablet is the intersection of the two.
 *
 * <p>The kitchen's ticket service is a stand-in, because a ticket needs an order, a routing rule and a catalogue none of which
 * is under test. What is under test is the wiring: which request carries which session, who the board action is attributed to,
 * and what the device and the person may each do.
 */
class TerminalSessionHttpTests extends TerminalsFixture {

    private static final UUID TICKET = UUID.fromString("018fe155-2000-7000-8000-0000000000e1");
    private static final UUID ITEM = UUID.fromString("018fe155-2000-7000-8000-0000000000e2");
    private static final UUID STATION = UUID.fromString("018fe155-2000-7000-8000-0000000000e3");

    @BeforeEach
    void kitchenStandIn() {
        reset(tickets);
        TicketRow ticket = ticket(TicketStatus.IN_PRODUCTION);
        TicketItemRow item = item(TicketItemStatus.STARTED);
        when(tickets.ticketOfItem(eq(TENANT), any())).thenReturn(ticket);
        when(tickets.board(eq(TENANT), eq(CENTRE), anyList(), anyInt())).thenReturn(List.of());
        when(tickets.counts(eq(TENANT), eq(CENTRE), anyList())).thenReturn(new TicketCountsRow(0, 0, 0, 0, 0));
        when(tickets.start(eq(TENANT), any(), anyString(), any())).thenReturn(new ItemOutcome(true, item, ticket));
        when(tickets.ready(eq(TENANT), any(), anyString(), any())).thenReturn(new ItemOutcome(true, item, ticket));
        when(tickets.orderProgressWired()).thenReturn(true);
        clearCache("terminal.roster");
        clearCache("iam.not_a_device");
        clearCache("iam.device_last_seen");
    }

    // ------------------------------------------------------------------------ refusals before anything else

    @Test
    @DisplayName(
            "a terminal-session token is never a bearer, and the header means nothing without the device's own token")
    void aSessionTokenIsNotACredential() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        String session = setPin(tablet, AZIZA, "482915");

        MvcResult asBearer = mvc.perform(get(TERMINAL_SESSION + "/roster").header("Authorization", "Bearer " + session))
                .andReturn();
        assertThat(asBearer.getResponse().getStatus())
                .as("presented as Authorization the token is just a string the decoder does not know")
                .isEqualTo(401);

        MvcResult headerOnly =
                mvc.perform(get(KITCHEN + "/tickets").header(SESSION, session)).andReturn();
        assertThat(headerOnly.getResponse().getStatus())
                .as("the header with no bearer at all never reaches a handler")
                .isEqualTo(401);

        MvcResult staffWithHeader = mvc.perform(
                        get(KITCHEN + "/tickets").with(token(CENTRE_MANAGER)).header(SESSION, session))
                .andReturn();
        assertThat(staffWithHeader.getResponse().getStatus())
                .as("a staff token is not the device's own: refused before the handler runs")
                .isEqualTo(403);
        assertThat(codeOf(staffWithHeader)).isEqualTo("TERMINAL_SESSION_ENDED");

        MvcResult staffUnlocking = unlockAs(CENTRE_MANAGER, AZIZA, "482915");
        assertThat(staffUnlocking.getResponse().getStatus())
                .as("a staff token calling unlocks")
                .isEqualTo(403);
        assertThat(codeOf(staffUnlocking)).isEqualTo("INSUFFICIENT_CAPABILITY");
        MvcResult ownerUnlocking = unlockAs(OWNER, AZIZA, "482915");
        assertThat(ownerUnlocking.getResponse().getStatus()).isEqualTo(403);

        Tablet other = enrolTablet("Second tablet");
        setMode(other, "PERSON_OPTIONAL");
        MvcResult elsewhere = mvc.perform(
                        get(KITCHEN + "/tickets").with(token(other.subject())).header(SESSION, session))
                .andReturn();
        assertThat(elsewhere.getResponse().getStatus())
                .as("a session is valid only from the device it was issued to")
                .isEqualTo(403);
        assertThat(codeOf(elsewhere)).isEqualTo("TERMINAL_SESSION_ENDED");
    }

    // -------------------------------------------------------------------------------------------- the roster

    @Test
    @DisplayName("the roster is the people eligible at this tablet's branch, as labels, and nothing else about them")
    void theRosterIsDerivedFromGrantsAndShowsOnlyLabels() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");

        JsonNode dark = roster(tablet);
        assertThat(dark.get("attributionMode").asString()).isEqualTo("DEVICE_ONLY");
        assertThat(dark.get("members")).as("a DEVICE_ONLY tablet shows no one").isEmpty();

        setMode(tablet, "PERSON_OPTIONAL");
        MvcResult result = mvc.perform(get(TERMINAL_SESSION + "/roster").with(token(tablet.subject())))
                .andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode roster = JSON.readTree(result.getResponse().getContentAsString());
        List<String> labels = labelsOf(roster);
        assertThat(labels)
                .as("an active member holding kitchen.ticket.advance at this branch: the cooks and the manager, "
                        + "never finance (no kitchen job) nor the other branch's cook")
                .containsExactly("Aziza K.", "Bakhrom T.", "Madina Y.");
        assertThat(roster.get("pinLength").asInt()).isEqualTo(6);
        assertThat(roster.get("members").get(0).get("pinState").asString()).isEqualTo("NOT_SET");
        assertThat(result.getResponse().getContentAsString())
                .as("no phone, no employee number, no Keycloak subject, no photo")
                .doesNotContain("998")
                .doesNotContain("term-")
                .doesNotContain("phone")
                .doesNotContain("photo");

        // The tenant's alternative: the non-personal reference.
        putPolicy(OWNER, 6, 300, 12, "REFERENCE", "DEVICE_ONLY");
        clearCache("terminal.roster");
        assertThat(labelsOf(roster(tablet)))
                .allSatisfy(label -> assertThat(label).matches("S-\\d{4}"));

        // A person who goes on leave, and one whose job at this branch is revoked, leave the list.
        putPolicy(OWNER, 6, 300, 12, "NAME_INITIAL", "DEVICE_ONLY");
        jdbc.sql("UPDATE iam.staff_members SET employment_status = 'ON_LEAVE' WHERE id = :id")
                .param("id", memberId(BAKHROM))
                .update();
        grants.revoke(TENANT, grantId(AZIZA), OWNER, "left the kitchen");
        clearCache("terminal.roster");
        assertThat(labelsOf(roster(tablet))).containsExactly("Madina Y.");
    }

    // ---------------------------------------------------------------------------------- unlock and refusal

    @Test
    @DisplayName(
            "a wrong PIN, an unknown member, an ineligible person and a person with no PIN are refused in the same words")
    void everyRefusalLooksTheSame() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        setPin(tablet, AZIZA, "482915");

        MvcResult wrong = unlock(tablet, AZIZA, "999111");
        MvcResult noPin = unlock(tablet, BAKHROM, "482915");
        MvcResult ineligible = unlock(tablet, FINANCE, "482915");
        MvcResult unknown = mvc.perform(post(TERMINAL_SESSION + "/unlocks")
                        .with(token(tablet.subject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + UUID.randomUUID() + "\",\"pin\":\"482915\"}"))
                .andReturn();

        for (MvcResult refusal : List.of(wrong, noPin, ineligible, unknown)) {
            assertThat(refusal.getResponse().getStatus()).isEqualTo(403);
            assertThat(codeOf(refusal)).isEqualTo("TERMINAL_UNLOCK_REFUSED");
            assertThat(JSON.readTree(refusal.getResponse().getContentAsString())
                            .get("detail")
                            .asString())
                    .isEqualTo(JSON.readTree(wrong.getResponse().getContentAsString())
                            .get("detail")
                            .asString());
        }
        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus())
                .as("the right PIN opens it")
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "a DEVICE_ONLY tablet does not unlock anyone: a stolen device credential cannot be used to guess PINs there")
    void aDeviceOnlyTabletRefusesToUnlock() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        setPin(tablet, AZIZA, "482915");
        setMode(tablet, "DEVICE_ONLY");

        MvcResult result = unlock(tablet, AZIZA, "482915");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(result)).isEqualTo("TERMINAL_UNLOCK_REFUSED");
        assertThat(count("SELECT count(*) FROM iam.terminal_pin_locks"))
                .as("and nothing was counted against her")
                .isZero();
    }

    // ---------------------------------------------------------------------------------- the intersection

    @Test
    @DisplayName(
            "the person's grants narrow what the tablet may do: a session outlives neither the cook's job nor her PIN's worth")
    void authorizationIsTheIntersectionOfTheDeviceAndThePerson() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_REQUIRED");
        String session = setPin(tablet, AZIZA, "482915");

        MvcResult started = startItem(tablet, session);
        assertThat(started.getResponse().getStatus())
                .as(started.getResponse().getContentAsString())
                .isEqualTo(200);

        grants.revoke(TENANT, grantId(AZIZA), OWNER, "no longer works the kitchen");

        MvcResult after = startItem(tablet, session);
        assertThat(after.getResponse().getStatus())
                .as("the device still holds kitchen.ticket.advance; the person no longer does")
                .isEqualTo(403);
        assertThat(codeOf(after)).isEqualTo("TERMINAL_PERSON_NOT_AUTHORIZED");
        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus())
                .as("and a revoked cook's PIN is not a way back in")
                .isEqualTo(403);

        // And the reverse: a person who holds more than the device is held to the device's own ceiling.
        String managerCode = issueCode(CENTRE_MANAGER, OWNER);
        String managerSession = JSON.readTree(activate(tablet, CENTRE_MANAGER, managerCode, "735914")
                        .getResponse()
                        .getContentAsString())
                .get("sessionToken")
                .asString();
        MvcResult release = mvc.perform(post(KITCHEN + "/tickets/" + TICKET + "/release")
                        .with(token(tablet.subject()))
                        .header(SESSION, managerSession)
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "r-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"reasonCode\":\"RUSH\"}"))
                .andReturn();
        assertThat(release.getResponse().getStatus())
                .as("a manager's PIN does not give the tablet the manager's powers")
                .isEqualTo(403);
        assertThat(codeOf(release)).isEqualTo("INSUFFICIENT_CAPABILITY");
    }

    // ------------------------------------------------------------------------------------- attribution

    @Test
    @DisplayName(
            "PERSON_REQUIRED refuses a start without a session and attributes it with one; DEVICE_ONLY ignores the header")
    void theModeDecidesWhoIsTheActor() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_REQUIRED");
        String session = setPin(tablet, AZIZA, "482915");

        MvcResult without = startItem(tablet, null);
        assertThat(without.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(without)).isEqualTo("TERMINAL_UNLOCK_REQUIRED");

        MvcResult read = mvc.perform(get(KITCHEN + "/tickets").with(token(tablet.subject())))
                .andReturn();
        assertThat(read.getResponse().getStatus())
                .as("a read needs no one unlocked")
                .isEqualTo(200);

        assertThat(startItem(tablet, session).getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(tickets).start(eq(TENANT), eq(ITEM), actor.capture(), any());
        assertThat(actor.getValue())
                .as("the actor the kitchen records is the cook's subject, the string her console actions carry")
                .isEqualTo(AZIZA);

        // The same header on a DEVICE_ONLY tablet is ignored, and the device is the actor, exactly as today.
        setMode(tablet, "DEVICE_ONLY");
        reset(tickets);
        when(tickets.ticketOfItem(eq(TENANT), any())).thenReturn(ticket(TicketStatus.IN_PRODUCTION));
        when(tickets.start(eq(TENANT), any(), anyString(), any()))
                .thenReturn(new ItemOutcome(true, item(TicketItemStatus.STARTED), ticket(TicketStatus.IN_PRODUCTION)));
        assertThat(startItem(tablet, session).getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<String> deviceActor = ArgumentCaptor.forClass(String.class);
        verify(tickets).start(eq(TENANT), eq(ITEM), deviceActor.capture(), any());
        assertThat(deviceActor.getValue()).isEqualTo(tablet.subject());

        // PERSON_OPTIONAL with no header is the device too.
        setMode(tablet, "PERSON_OPTIONAL");
        reset(tickets);
        when(tickets.ticketOfItem(eq(TENANT), any())).thenReturn(ticket(TicketStatus.IN_PRODUCTION));
        when(tickets.start(eq(TENANT), any(), anyString(), any()))
                .thenReturn(new ItemOutcome(true, item(TicketItemStatus.STARTED), ticket(TicketStatus.IN_PRODUCTION)));
        assertThat(startItem(tablet, null).getResponse().getStatus()).isEqualTo(200);
        ArgumentCaptor<String> optional = ArgumentCaptor.forClass(String.class);
        verify(tickets).start(eq(TENANT), eq(ITEM), optional.capture(), any());
        assertThat(optional.getValue()).isEqualTo(tablet.subject());
    }

    @Test
    @DisplayName(
            "every place the kitchen board records an actor reads the attributed one: a source scan that fails if one still reads the device")
    void everyRecordingSiteOnTheBoardReadsTheAttributedActor() throws Exception {
        String source =
                Files.readString(Path.of("src/main/java/uz/horecaos/platform/kitchen/web/KitchenBoardController.java"));

        long deviceReads = source.lines()
                .filter(line -> line.contains("currentActor.get().subject()"))
                .count();
        long attributedReads = source.lines()
                .filter(line -> line.contains("currentActor.attributed().subject()"))
                .count();

        assertThat(attributedReads)
                .as("start, ready, recall, release, release-schedule and hand-over each record who acted")
                .isGreaterThanOrEqualTo(6);
        assertThat(deviceReads)
                .as("the only three places the board still asks who the DEVICE is are authorization questions: "
                        + "which wall is calling, and whether the caller may override a release schedule or a "
                        + "release of a ticket held for payment (ADR 0168)")
                .isEqualTo(3);
    }

    // ------------------------------------------------------------------------------- one live session

    @Test
    @DisplayName(
            "unlocking elsewhere ends the first session, and the second person at a tablet ends the first person's")
    void oneLiveSessionPerDeviceAndPerPerson() throws Exception {
        Tablet first = insertTablet("First", CENTRE, "PERSON_OPTIONAL");
        Tablet second = insertTablet("Second", CENTRE, "PERSON_OPTIONAL");
        setPin(first, AZIZA, "482915");
        setPin(first, BAKHROM, "735914");

        String aziza = unlockOk(first, AZIZA, "482915");
        String azizaElsewhere = unlockOk(second, AZIZA, "482915");

        assertThat(reasonOfEndedSessions())
                .as("her first session ended when she unlocked elsewhere")
                .contains("SUPERSEDED");
        assertThat(touch(first, aziza).getResponse().getStatus())
                .as("the token she held at the first tablet is dead")
                .isEqualTo(403);
        assertThat(touch(second, azizaElsewhere).getResponse().getStatus()).isEqualTo(204);

        String bakhrom = unlockOk(second, BAKHROM, "735914");
        assertThat(touch(second, azizaElsewhere).getResponse().getStatus())
                .as("the next person at the second tablet ends the first person's session there")
                .isEqualTo(403);
        assertThat(touch(second, bakhrom).getResponse().getStatus()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_at IS NULL"))
                .as("at most one live session per device and per person")
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'terminal.session.opened'"))
                .isEqualTo(5L);
    }

    // ------------------------------------------------------------------------------ the idle lock

    @Test
    @DisplayName("a board that only polls cannot keep a session alive; a touch or a mutating call moves the deadline")
    void thePollingBoardDoesNotKeepTheTerminalUnlocked() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_REQUIRED");
        setPin(tablet, AZIZA, "482915");
        String session = unlockOk(tablet, AZIZA, "482915");

        // The timed refresh carries the header every ten seconds and does not count.
        for (int poll = 1; poll <= 29; poll++) {
            advance(Duration.ofSeconds(10));
            assertThat(poll(tablet, session).getResponse().getStatus())
                    .as("poll %d at +%ds", poll, poll * 10)
                    .isEqualTo(200);
        }
        advance(Duration.ofSeconds(11));
        MvcResult lockedPoll = poll(tablet, session);
        assertThat(lockedPoll.getResponse().getStatus())
                .as("301 seconds after the unlock the polls have been sending the header throughout, and it is locked")
                .isEqualTo(403);
        assertThat(codeOf(lockedPoll)).isEqualTo("TERMINAL_SESSION_ENDED");
        MvcResult lockedMutation = startItem(tablet, session);
        assertThat(lockedMutation.getResponse().getStatus()).isEqualTo(403);
        assertThat(codeOf(lockedMutation))
                .as("PERSON_REQUIRED: the next mutating request is refused with the unlock prompt")
                .isEqualTo("TERMINAL_UNLOCK_REQUIRED");
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_reason = 'IDLE'"))
                .isEqualTo(1L);

        // A touch moves the deadline.
        String again = unlockOk(tablet, AZIZA, "482915");
        advance(Duration.ofSeconds(250));
        assertThat(touch(tablet, again).getResponse().getStatus()).isEqualTo(204);
        advance(Duration.ofSeconds(250));
        assertThat(poll(tablet, again).getResponse().getStatus())
                .as("500s after the unlock but 250s after the touch")
                .isEqualTo(200);
        advance(Duration.ofSeconds(60));
        assertThat(poll(tablet, again).getResponse().getStatus()).isEqualTo(403);

        // A mutating call moves it too, once the last activity is more than fifteen seconds old.
        String third = unlockOk(tablet, AZIZA, "482915");
        advance(Duration.ofSeconds(250));
        assertThat(startItem(tablet, third).getResponse().getStatus()).isEqualTo(200);
        advance(Duration.ofSeconds(250));
        assertThat(poll(tablet, third).getResponse().getStatus())
                .as("250s after the start, which counted")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("terminal.session.max_hours ends the session whatever its traffic")
    void theMaximumLengthEndsASessionWhateverItsTraffic() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        MvcResult configured = configure(tablet, "Pass tablet", "PERSON_OPTIONAL", 3600, CENTRE_MANAGER);
        assertThat(configured.getResponse().getStatus()).isEqualTo(200);
        putPolicy(OWNER, 6, 300, 2, "NAME_INITIAL", "DEVICE_ONLY");
        setPin(tablet, AZIZA, "482915");
        String session = unlockOk(tablet, AZIZA, "482915");

        advance(Duration.ofMinutes(59));
        assertThat(touch(tablet, session).getResponse().getStatus()).isEqualTo(204);
        advance(Duration.ofMinutes(59));
        assertThat(touch(tablet, session).getResponse().getStatus())
                .as("1h58m in, busy the whole time")
                .isEqualTo(204);
        advance(Duration.ofMinutes(3));
        assertThat(touch(tablet, session).getResponse().getStatus())
                .as("past two hours")
                .isEqualTo(403);
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_reason = 'MAX_AGE'"))
                .isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------- revocation and endings

    @Test
    @DisplayName("revoking the tablet ends its sessions and the next request fails, by either screen's revoke")
    void revokingATabletEndsItsSessions() throws Exception {
        Tablet byRegistry = enrolTablet("Registry tablet");
        Tablet byKitchen = enrolTablet("Kitchen tablet");
        setMode(byRegistry, "PERSON_OPTIONAL");
        setMode(byKitchen, "PERSON_OPTIONAL");
        setPin(byRegistry, AZIZA, "482915");
        setPin(byKitchen, BAKHROM, "735914");
        String one = unlockOk(byRegistry, AZIZA, "482915");
        String two = unlockOk(byKitchen, BAKHROM, "735914");

        assertThat(mvc.perform(post(TERMINALS + "/" + byRegistry.deviceId() + "/revoke")
                                .with(token(CENTRE_MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rev-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Tablet dropped\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(post(KITCHEN + "/devices/" + byKitchen.deviceId() + "/revoke")
                                .with(token(CENTRE_MANAGER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "rev-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Replaced\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        assertThat(touch(byRegistry, one).getResponse().getStatus()).isEqualTo(403);
        assertThat(touch(byKitchen, two).getResponse().getStatus()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_reason = 'REVOKED'"))
                .as("both ended, whichever revoke was used")
                .isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_at IS NULL"))
                .isZero();
    }

    @Test
    @DisplayName("ending a person's employment ends their sessions in the same transaction; a password reset does not")
    void endingEmploymentEndsTheSessions() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        setMode(tablet, "PERSON_OPTIONAL");
        setPin(tablet, AZIZA, "482915");
        unlockOk(tablet, AZIZA, "482915");
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_at IS NULL"))
                .isEqualTo(1L);

        int version = jdbc.sql("SELECT version FROM iam.staff_members WHERE id = :id")
                .param("id", memberId(AZIZA))
                .query(Integer.class)
                .single();
        SignedInStaff.run(
                OWNER,
                () -> members.endEmployment(
                        TENANT, memberId(AZIZA), version, null, "left the restaurant", OWNER, "corr-end"));

        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_reason = 'EMPLOYMENT_ENDED'"))
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM iam.terminal_sessions WHERE ended_at IS NULL"))
                .isZero();
        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus()).isEqualTo(403);
    }

    // ----------------------------------------------------------------------- what the database refuses

    @Test
    @DisplayName(
            "the database refuses a session whose location is not its device's, or whose device or person is another tenant's")
    void theKeysRefuseAMisplacedSession() throws Exception {
        Tablet tablet = insertTablet("Centre tablet", CENTRE, "PERSON_OPTIONAL");
        String otherMember = "term-other-member";
        UUID foreignMember = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.staff_members (id, tenant_id, principal_subject, display_reference, employment_status)
                VALUES (:id, :tenantId, :subject, 'S-0001', 'ACTIVE')
                """)
                .param("id", foreignMember)
                .param("tenantId", OTHER_TENANT)
                .param("subject", otherMember)
                .update();

        assertThatThrownBy(() -> insertSession(TENANT, tablet.deviceId(), memberId(AZIZA), NORTH, "a"))
                .as("the session's location is its device's location")
                .hasMessageContaining("fk_terminal_session_device");
        assertThatThrownBy(() -> insertSession(OTHER_TENANT, tablet.deviceId(), foreignMember, CENTRE, "b"))
                .as("a device of another tenant")
                .hasMessageContaining("fk_terminal_session_device");
        assertThatThrownBy(() -> insertSession(TENANT, tablet.deviceId(), foreignMember, CENTRE, "c"))
                .as("a person of another tenant")
                .hasMessageContaining("fk_terminal_session_member");

        insertSession(TENANT, tablet.deviceId(), memberId(AZIZA), CENTRE, "d");
        assertThatThrownBy(() -> insertSession(TENANT, tablet.deviceId(), memberId(BAKHROM), CENTRE, "e"))
                .as("one live session per device")
                .hasMessageContaining("uq_terminal_session_live_device");
        Tablet another = insertTablet("Another", CENTRE, "PERSON_OPTIONAL");
        assertThatThrownBy(() -> insertSession(TENANT, another.deviceId(), memberId(AZIZA), CENTRE, "f"))
                .as("one live session per person")
                .hasMessageContaining("uq_terminal_session_live_member");
    }

    // -------------------------------------------------------------------------------- last seen

    @Test
    @DisplayName(
            "any authenticated request from a device stamps its last-seen once a minute per node, with the build it reports")
    void lastSeenIsStampedByTheDevicesOwnRequests() throws Exception {
        Tablet tablet = enrolTablet("Pass tablet");
        assertThat(lastSeen(tablet)).as("never seen yet").isNull();

        mvc.perform(get(TERMINAL_SESSION + "/roster")
                        .with(token(tablet.subject()))
                        .header("X-Device-Build", "kds-2026.10.1"))
                .andReturn();
        Instant stamped = lastSeen(tablet);
        assertThat(stamped).isNotNull();
        assertThat(jdbc.sql("SELECT last_seen_build FROM iam.device_principals WHERE id = :id")
                        .param("id", tablet.deviceId())
                        .query(String.class)
                        .single())
                .isEqualTo("kds-2026.10.1");

        jdbc.sql("UPDATE iam.device_principals SET last_seen_at = :old WHERE id = :id")
                .param("old", Instant.parse("2026-10-01T00:00:00Z").atOffset(java.time.ZoneOffset.UTC))
                .param("id", tablet.deviceId())
                .update();
        mvc.perform(get(TERMINAL_SESSION + "/roster").with(token(tablet.subject())))
                .andReturn();
        assertThat(lastSeen(tablet))
                .as("a second request inside the minute does not write again")
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));

        clearCache("iam.device_last_seen");
        mvc.perform(get("/api/v1/devices/me").with(token(tablet.subject()))).andReturn();
        assertThat(lastSeen(tablet))
                .as("the self-read declares no capability and still counts: it is a kiosk's presence signal")
                .isAfter(Instant.parse("2026-10-01T00:00:00Z"));

        mvc.perform(get(KITCHEN + "/tickets").with(token(CENTRE_MANAGER))).andReturn();
        assertThat(count("SELECT count(*) FROM iam.device_principals WHERE last_seen_at IS NOT NULL"))
                .as("a staff request stamps no device")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------------------------- helpers

    private MvcResult unlockAs(String subject, String memberSubject, String pin) throws Exception {
        return mvc.perform(post(TERMINAL_SESSION + "/unlocks")
                        .with(token(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + memberId(memberSubject) + "\",\"pin\":\"" + pin + "\"}"))
                .andReturn();
    }

    private MvcResult startItem(Tablet tablet, @Nullable String session) throws Exception {
        var request = post(KITCHEN + "/ticket-items/" + ITEM + "/start")
                .with(token(tablet.subject()))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "start-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (session != null) {
            request = request.header(SESSION, session);
        }
        return mvc.perform(request).andReturn();
    }

    /** The board's timed refresh: a GET that carries the session header and is not user activity. */
    private MvcResult poll(Tablet tablet, String session) throws Exception {
        return mvc.perform(
                        get(KITCHEN + "/tickets").with(token(tablet.subject())).header(SESSION, session))
                .andReturn();
    }

    private void putPolicy(String as, int length, int autoLock, int maxHours, String display, String mode)
            throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                OPS + "/terminal-policy")
                        .with(token(as))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "pol-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinLength\":" + length + ",\"autoLockSeconds\":" + autoLock
                                + ",\"sessionMaxHours\":" + maxHours + ",\"rosterDisplay\":\"" + display
                                + "\",\"attributionDefault\":\"" + mode + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private static List<String> labelsOf(JsonNode roster) {
        List<String> labels = new java.util.ArrayList<>();
        roster.get("members").forEach(member -> labels.add(member.get("label").asString()));
        return labels;
    }

    private List<String> reasonOfEndedSessions() {
        return jdbc.sql("SELECT ended_reason FROM iam.terminal_sessions WHERE ended_reason IS NOT NULL")
                .query(String.class)
                .list();
    }

    private @Nullable Instant lastSeen(Tablet tablet) {
        return jdbc.sql("SELECT last_seen_at FROM iam.device_principals WHERE id = :id")
                .param("id", tablet.deviceId())
                .query(java.time.OffsetDateTime.class)
                .optional()
                .map(java.time.OffsetDateTime::toInstant)
                .orElse(null);
    }

    private void insertSession(UUID tenantId, UUID deviceId, UUID memberId, UUID locationId, String token) {
        jdbc.sql("""
                INSERT INTO iam.terminal_sessions
                    (id, tenant_id, device_id, staff_member_id, location_id, token_digest, opened_at,
                     last_activity_at, idle_seconds, hard_expires_at)
                VALUES (:id, :tenantId, :deviceId, :memberId, :locationId, :digest, :now, :now, 300, :later)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .param("memberId", memberId)
                .param("locationId", locationId)
                .param("digest", "digest-" + token)
                .param("now", START.atOffset(java.time.ZoneOffset.UTC))
                .param("later", START.plus(Duration.ofHours(1)).atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private TicketRow ticket(TicketStatus status) {
        Instant now = clock.instant();
        return new TicketRow(
                TICKET,
                TENANT,
                BRAND,
                CENTRE,
                UUID.randomUUID(),
                "A-014",
                "PICKUP",
                "TELEGRAM",
                status,
                ReleaseMode.AUTO_ON_CONFIRM,
                null,
                now,
                null,
                now.plusSeconds(600),
                null,
                null,
                null,
                1,
                1,
                now);
    }

    private TicketItemRow item(TicketItemStatus status) {
        return new TicketItemRow(
                ITEM,
                TENANT,
                TICKET,
                CENTRE,
                UUID.randomUUID(),
                STATION,
                BigDecimal.ONE,
                RoutingLevel.BRAND_ROLE,
                status,
                null,
                null,
                null,
                1,
                clock.instant());
    }

    @SuppressWarnings("unused")
    private static PlatformRole unusedRole() {
        return PlatformRole.KITCHEN_DEVICE;
    }
}
