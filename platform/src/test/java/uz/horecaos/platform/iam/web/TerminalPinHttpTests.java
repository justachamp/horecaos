package uz.horecaos.platform.iam.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.EmploymentEdit;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.ProfileEdit;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.Reach;
import uz.horecaos.platform.iam.application.terminals.PinService;
import uz.horecaos.platform.iam.application.terminals.PinService.Outcome;
import uz.horecaos.platform.iam.application.terminals.PinService.Unlocked;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0155, Decisions 3 and 4, over HTTP: the credential, the single-use activation code, the entry rules, and a lockout that
 * lives in the database. The clock is the test's, so a fifteen-minute lock, a code that lapses and a day that rolls over are
 * durations asserted against a clock that moves.
 */
class TerminalPinHttpTests extends TerminalsFixture {

    @Autowired
    @SuppressWarnings("NullAway")
    private PinService pins;

    @Autowired
    @SuppressWarnings("NullAway")
    private DeviceEnrolmentPort devices;

    @Autowired
    @SuppressWarnings("NullAway")
    private MeterRegistry meters;

    // ------------------------------------------------------------------------- the activation code

    @Test
    @DisplayName("an activation code is single-use, and the manager who issued it never sees the PIN")
    void aCodeIsSingleUse() throws Exception {
        Tablet tablet = tablet();
        String code = issueCode(AZIZA, CENTRE_MANAGER);

        MvcResult first = activate(tablet, AZIZA, code, "482915");
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(first.getResponse().getHeader("Cache-Control")).contains("no-store");

        MvcResult second = activate(tablet, AZIZA, code, "735914");
        assertThat(second.getResponse().getStatus()).isEqualTo(422);
        assertThat(codeOf(second)).isEqualTo("TERMINAL_ACTIVATION_REFUSED");
        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus())
                .as("the first PIN stands: the second use changed nothing")
                .isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM iam.staff_pin_activations")
                        .query(String.class)
                        .list())
                .containsExactly("CONSUMED");
        assertThat(jdbc.sql("SELECT activated_by FROM iam.staff_pins WHERE staff_member_id = :id")
                        .param("id", memberId(AZIZA))
                        .query(String.class)
                        .single())
                .as("the manager is recorded as having issued the code, not as having chosen the PIN")
                .isEqualTo(CENTRE_MANAGER);
    }

    @Test
    @DisplayName("a code lapses: fifteen minutes when the person asked for it, four hours when a manager did")
    void aCodeExpires() throws Exception {
        Tablet tablet = tablet();

        MvcResult mine = mvc.perform(post(OPS + "/staff/me/pin-activations").with(token(AZIZA)))
                .andReturn();
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(mine.getResponse().getHeader("Cache-Control")).contains("no-store");
        String selfCode = JSON.readTree(mine.getResponse().getContentAsString())
                .get("code")
                .asString();
        String managerCode = issueCode(BAKHROM, CENTRE_MANAGER);

        advance(Duration.ofMinutes(16));
        MvcResult lapsed = activate(tablet, AZIZA, selfCode, "482915");
        assertThat(lapsed.getResponse().getStatus())
                .as("16 minutes after the person's own request")
                .isEqualTo(422);
        assertThat(jdbc.sql("SELECT status FROM iam.staff_pin_activations WHERE issued_via = 'SELF'")
                        .query(String.class)
                        .single())
                .isEqualTo("EXPIRED");

        assertThat(activate(tablet, BAKHROM, managerCode, "735914")
                        .getResponse()
                        .getStatus())
                .as("the manager's code is still good at +16 minutes")
                .isEqualTo(200);

        String late = issueCode(CENTRE_MANAGER, OWNER);
        advance(Duration.ofHours(4).plusMinutes(1));
        assertThat(activate(tablet, CENTRE_MANAGER, late, "159357")
                        .getResponse()
                        .getStatus())
                .as("and lapses at four hours")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("a code is burned at the fifth wrong try, and a second code supersedes the first")
    void aCodeBurnsAndIsSuperseded() throws Exception {
        Tablet tablet = tablet();
        String burned = issueCode(AZIZA, CENTRE_MANAGER);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(activate(tablet, AZIZA, "00000000", "482915")
                            .getResponse()
                            .getStatus())
                    .as("wrong try %d", attempt)
                    .isEqualTo(422);
        }
        assertThat(activate(tablet, AZIZA, burned, "482915").getResponse().getStatus())
                .as("the right code, now that it has been burned")
                .isEqualTo(422);
        assertThat(jdbc.sql("SELECT status FROM iam.staff_pin_activations")
                        .query(String.class)
                        .single())
                .isEqualTo("BURNED");

        String first = issueCode(BAKHROM, CENTRE_MANAGER);
        String second = issueCode(BAKHROM, CENTRE_MANAGER);
        assertThat(activate(tablet, BAKHROM, first, "735914").getResponse().getStatus())
                .as("a second pending code supersedes the first")
                .isEqualTo(422);
        assertThat(activate(tablet, BAKHROM, second, "735914").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(jdbc.sql(
                                "SELECT status FROM iam.staff_pin_activations WHERE staff_member_id = :id ORDER BY created_at")
                        .param("id", memberId(BAKHROM))
                        .query(String.class)
                        .list())
                .containsExactly("SUPERSEDED", "CONSUMED");
    }

    @Test
    @DisplayName(
            "an activation at a tablet nobody unlocks, or for a person who may not unlock it, is refused and the code is untouched")
    void anIneligiblePersonOrDeviceCannotActivate() throws Exception {
        Tablet tablet = tablet();
        String code = issueCode(FINANCE, OWNER);

        MvcResult refused = activate(tablet, FINANCE, code, "482915");
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(jdbc.sql("SELECT failed_attempts FROM iam.staff_pin_activations")
                        .query(Integer.class)
                        .single())
                .as("not a wrong try: the person was never eligible")
                .isZero();

        setMode(tablet, "DEVICE_ONLY");
        String cook = issueCode(AZIZA, CENTRE_MANAGER);
        assertThat(activate(tablet, AZIZA, cook, "482915").getResponse().getStatus())
                .as("a DEVICE_ONLY tablet takes no PIN")
                .isEqualTo(422);
        assertThat(count("SELECT count(*) FROM iam.staff_pins WHERE status = 'ACTIVE'"))
                .isZero();
    }

    // ----------------------------------------------------------------------------------- entry rules

    @Test
    @DisplayName("a weak PIN is refused with the reason stated, and the code stays good for the next try")
    void theEntryRulesRefuseWeakPins() throws Exception {
        setPhoneAndEmployeeNumber();
        Tablet tablet = tablet();
        String code = issueCode(AZIZA, CENTRE_MANAGER);

        assertThat(reasonOf(activate(tablet, AZIZA, code, "123456"))).isEqualTo("SEQUENCE");
        assertThat(reasonOf(activate(tablet, AZIZA, code, "000000"))).isEqualTo("REPEATED_DIGIT");
        assertThat(reasonOf(activate(tablet, AZIZA, code, "121212"))).isEqualTo("COMMON");
        assertThat(reasonOf(activate(tablet, AZIZA, code, "12ab56"))).isEqualTo("NOT_DIGITS");
        assertThat(reasonOf(activate(tablet, AZIZA, code, "48291")))
                .as("one digit short of the tenant's six")
                .isEqualTo("WRONG_LENGTH");
        assertThat(reasonOf(activate(tablet, AZIZA, code, "234876")))
                .as("the last six digits of her own phone, +998 90 123 48 76")
                .isEqualTo("PERSONAL_NUMBER");
        assertThat(jdbc.sql("SELECT failed_attempts FROM iam.staff_pin_activations")
                        .query(Integer.class)
                        .single())
                .as("a weak PIN is not a wrong code")
                .isZero();
        assertThat(activate(tablet, AZIZA, code, "482915").getResponse().getStatus())
                .as("the same code, now with an acceptable PIN")
                .isEqualTo(200);

        String bakhromCode = issueCode(BAKHROM, CENTRE_MANAGER);
        assertThat(reasonOf(activate(tablet, BAKHROM, bakhromCode, "770412")))
                .as("the last six digits of his employee number, EMP-770412")
                .isEqualTo("PERSONAL_NUMBER");
        assertThat(activate(tablet, BAKHROM, bakhromCode, "234876")
                        .getResponse()
                        .getStatus())
                .as("another person's phone digits are nothing to him")
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "the tenant's length applies to the next PIN set, and an existing PIN keeps the length it was set with")
    void theLengthIsThePoliciesAtTheTimeOfSetting() throws Exception {
        Tablet tablet = tablet();
        setPin(tablet, AZIZA, "482915");

        putPolicy(4, "NAME_INITIAL");
        String code = issueCode(BAKHROM, CENTRE_MANAGER);
        assertThat(reasonOf(activate(tablet, BAKHROM, code, "735914")))
                .as("a six-digit PIN under a four-digit policy")
                .isEqualTo("WRONG_LENGTH");
        assertThat(activate(tablet, BAKHROM, code, "7359").getResponse().getStatus())
                .isEqualTo(200);

        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus())
                .as("her six-digit PIN still opens it")
                .isEqualTo(200);
        assertThat(unlock(tablet, BAKHROM, "7359").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT pin_length FROM iam.staff_pins WHERE staff_member_id = :id")
                        .param("id", memberId(BAKHROM))
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("no endpoint lets a manager set or read a PIN: the state shows a status and a date, never a digest")
    void aManagerNeitherSetsNorReadsAPin() throws Exception {
        Tablet tablet = tablet();
        setPin(tablet, AZIZA, "482915");

        MvcResult state = mvc.perform(
                        get(OPS + "/staff/members/" + memberId(AZIZA) + "/pin").with(token(OWNER)))
                .andReturn();
        assertThat(state.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(state.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.propertyNames()).doesNotContain("pin", "digest", "pinDigest", "code");

        MvcResult withAPin = mvc.perform(post(OPS + "/staff/members/" + memberId(AZIZA) + "/pin-activations")
                        .with(token(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Forgot it\",\"pin\":\"111222\"}"))
                .andReturn();
        assertThat(JSON.readTree(withAPin.getResponse().getContentAsString()).propertyNames())
                .as("a PIN in the body is not read by anything: the response is a code and a lapse")
                .containsExactlyInAnyOrder("code", "expiresAt", "issuedVia");

        MvcResult cook = mvc.perform(post(OPS + "/staff/members/" + memberId(BAKHROM) + "/pin-activations")
                        .with(token(AZIZA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"I want his PIN\"}"))
                .andReturn();
        assertThat(cook.getResponse().getStatus())
                .as("a colleague without staff.profile.manage")
                .isEqualTo(403);
        MvcResult otherBranch = mvc.perform(post(OPS + "/brands/" + BRAND + "/locations/" + NORTH + "/staff/members/"
                                + memberId(AZIZA) + "/pin-activations")
                        .with(token(NORTH_MANAGER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Not my branch\"}"))
                .andReturn();
        assertThat(otherBranch.getResponse().getStatus())
                .as("a person who does not work at the manager's branch is not theirs to issue a code for")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------- lockout

    @Test
    @DisplayName(
            "five consecutive wrong PINs lock that person on that device for fifteen minutes, and another device is untouched")
    void theDeviceLockIsPerPersonPerDeviceAndLastsFifteenMinutes() throws Exception {
        Tablet first = tablet();
        Tablet second = tablet();
        setPin(first, AZIZA, "482915");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(unlock(first, AZIZA, "999111").getResponse().getStatus())
                    .as("wrong PIN %d", attempt)
                    .isEqualTo(403);
        }
        MvcResult fifth = unlock(first, AZIZA, "999111");
        assertThat(fifth.getResponse().getStatus()).isEqualTo(423);
        assertThat(codeOf(fifth)).isEqualTo("TERMINAL_PIN_LOCKED");
        assertThat(JSON.readTree(fifth.getResponse().getContentAsString())
                        .get("retryAfterSeconds")
                        .asInt())
                .isEqualTo(900);

        MvcResult rightButLocked = unlock(first, AZIZA, "482915");
        assertThat(rightButLocked.getResponse().getStatus())
                .as("the right PIN during the lock")
                .isEqualTo(423);
        assertThat(unlock(second, AZIZA, "482915").getResponse().getStatus())
                .as("the second tablet is not locked by the first")
                .isEqualTo(200);

        advance(Duration.ofMinutes(14));
        assertThat(unlock(first, AZIZA, "482915").getResponse().getStatus()).isEqualTo(423);
        advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(unlock(first, AZIZA, "482915").getResponse().getStatus())
                .as("fifteen minutes later")
                .isEqualTo(200);

        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'terminal.unlock.refused'"))
                .as("the fifth consecutive failure, and not each wrong attempt")
                .isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'terminal.pin.locked'"))
                .isEqualTo(1L);
        assertThat(alerts.calls())
                .as("one alert, naming the reference and the device and no person")
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.eventClass()).isEqualTo("TERMINAL_CREDENTIAL_LOCKED");
                    assertThat(call.variables().get("staffReference")).matches("S-\\d{4}");
                    assertThat(call.variables().toString())
                            .doesNotContain("Aziza")
                            .doesNotContain("Karimova");
                });
    }

    @Test
    @DisplayName(
            "twelve wrong PINs in a day lock the credential until it is reset, success never clears the daily count, and a day rolls over")
    void theDailyLockIsAcrossDevicesAndSurvivesSuccess() throws Exception {
        Tablet tablet = tablet();
        setPin(tablet, AZIZA, "482915");

        // Four wrong, then a success, three times over: never five in a row on the device, yet twelve in the day.
        for (int round = 1; round <= 3; round++) {
            for (int wrong = 1; wrong <= 4; wrong++) {
                MvcResult result = unlock(tablet, AZIZA, "999111");
                if (round == 3 && wrong == 4) {
                    assertThat(result.getResponse().getStatus())
                            .as("the twelfth wrong PIN of the day")
                            .isEqualTo(423);
                    assertThat(JSON.readTree(result.getResponse().getContentAsString())
                                    .has("retryAfterSeconds"))
                            .as("a locked credential has no time at which it lifts")
                            .isFalse();
                } else {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                }
            }
            if (round < 3) {
                assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus())
                        .as("success clears the consecutive count and never the daily one")
                        .isEqualTo(200);
            }
        }

        assertThat(jdbc.sql(
                                "SELECT status || '/' || coalesce(pin_digest, 'none') || '/' || failures_in_window FROM iam.staff_pins WHERE staff_member_id = :id")
                        .param("id", memberId(AZIZA))
                        .query(String.class)
                        .single())
                .as("locked, no digest left to attack, and exactly twelve counted")
                .isEqualTo("LOCKED/none/12");
        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus()).isEqualTo(423);
        advance(Duration.ofDays(2));
        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus())
                .as("a locked credential does not lift by waiting")
                .isEqualTo(423);
        assertThat(alerts.calls()).extracting(call -> call.eventClass()).containsOnly("TERMINAL_CREDENTIAL_LOCKED");
        assertThat(alerts.calls().get(alerts.calls().size() - 1).variables()).containsEntry("lockRule", "DAILY_LIMIT");

        // A manager's reset, and she activates again.
        assertThat(mvc.perform(post(OPS + "/staff/members/" + memberId(AZIZA) + "/pin-reset")
                                .with(token(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reset-" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Locked herself out\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
        assertThat(jdbc.sql("SELECT status FROM iam.staff_pins WHERE staff_member_id = :id")
                        .param("id", memberId(AZIZA))
                        .query(String.class)
                        .single())
                .isEqualTo("NOT_SET");
        setPin(tablet, AZIZA, "735914");
        assertThat(unlock(tablet, AZIZA, "735914").getResponse().getStatus()).isEqualTo(200);
        assertThat(unlock(tablet, AZIZA, "482915").getResponse().getStatus())
                .as("the old PIN is gone for good")
                .isEqualTo(403);
    }

    @Test
    @DisplayName(
            "the daily window slides: eleven wrong PINs, a day, and the next wrong one is the first of a new window")
    void theDailyWindowSlides() throws Exception {
        Tablet seed = insertTablet("Window", CENTRE, "PERSON_OPTIONAL");
        setPin(seed, AZIZA, "482915");

        // Eleven wrong PINs at eleven different tablets: never five in a row at one, and one short of the day's twelve.
        List<Tablet> elsewhere = new ArrayList<>();
        for (int device = 0; device < 12; device++) {
            elsewhere.add(insertTablet("Window " + device, CENTRE, "PERSON_OPTIONAL"));
        }
        for (int wrong = 0; wrong < 11; wrong++) {
            assertThat(unlock(elsewhere.get(wrong), AZIZA, "999111")
                            .getResponse()
                            .getStatus())
                    .isEqualTo(403);
        }
        assertThat(failuresInWindow()).isEqualTo(11);

        advance(Duration.ofHours(25));
        assertThat(unlock(elsewhere.get(11), AZIZA, "999111").getResponse().getStatus())
                .as("the twelfth wrong PIN, but a day later: not a lock")
                .isEqualTo(403);
        assertThat(failuresInWindow()).as("a new window, not a twelfth").isEqualTo(1);
        assertThat(unlock(seed, AZIZA, "482915").getResponse().getStatus()).isEqualTo(200);
    }

    private int failuresInWindow() {
        return jdbc.sql("SELECT failures_in_window FROM iam.staff_pins WHERE staff_member_id = :id")
                .param("id", memberId(AZIZA))
                .query(Integer.class)
                .single();
    }

    @Test
    @DisplayName(
            "forty parallel wrong attempts are counted exactly: the lock comes at twelve and no attempt is lost or doubled")
    void parallelAttemptsAreCountedExactly() throws Exception {
        Tablet seed = tablet();
        setPin(seed, AZIZA, "482915");
        List<DevicePrincipalView> devicesInUse = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Tablet inserted = insertTablet("Parallel " + i, CENTRE, "PERSON_OPTIONAL");
            devicesInUse.add(devices.find(TENANT, inserted.deviceId()).orElseThrow());
        }

        List<Outcome> outcomes = hammer(devicesInUse, 4, "999111");

        assertThat(outcomes).hasSize(40);
        assertThat(outcomes.stream()
                        .filter(outcome -> outcome == Outcome.WRONG_PIN)
                        .count())
                .as("twelve attempts count, and the twelfth is answered as the lock it caused")
                .isEqualTo(11L);
        assertThat(outcomes.stream()
                        .filter(outcome -> outcome == Outcome.LOCKED)
                        .count())
                .isEqualTo(29L);
        assertThat(jdbc.sql(
                                "SELECT status || '/' || failures_in_window FROM iam.staff_pins WHERE staff_member_id = :id")
                        .param("id", memberId(AZIZA))
                        .query(String.class)
                        .single())
                .isEqualTo("LOCKED/12");
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'terminal.pin.locked'"))
                .as("locked once, not once per racing thread")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("five parallel wrong attempts at one device lock it at exactly five")
    void parallelAttemptsAtOneDeviceLockAtFive() throws Exception {
        Tablet seed = tablet();
        setPin(seed, AZIZA, "482915");
        DevicePrincipalView device = devices.find(TENANT, seed.deviceId()).orElseThrow();

        List<Outcome> outcomes = hammer(List.of(device), 8, "999111");

        assertThat(outcomes.stream()
                        .filter(outcome -> outcome == Outcome.WRONG_PIN)
                        .count())
                .as("four wrong, then the fifth locks")
                .isEqualTo(4L);
        assertThat(outcomes.stream()
                        .filter(outcome -> outcome == Outcome.LOCKED)
                        .count())
                .isEqualTo(4L);
        assertThat(jdbc.sql("SELECT failures_in_window FROM iam.staff_pins WHERE staff_member_id = :id")
                        .param("id", memberId(AZIZA))
                        .query(Integer.class)
                        .single())
                .as("the attempts refused by the lock are not counted")
                .isEqualTo(5);
        assertThat(jdbc.sql("SELECT locked_until IS NOT NULL FROM iam.terminal_pin_locks WHERE device_id = :id")
                        .param("id", seed.deviceId())
                        .query(Boolean.class)
                        .single())
                .as("the lock is a row, so a restart does not forgive a guesser")
                .isTrue();
    }

    // ----------------------------------------------------------------------------- «Мой профиль»

    @Test
    @DisplayName(
            "the person's own profile shows the state of their PIN, who issued a waiting code, and their last twenty unlocks")
    void myPinShowsTheStateAndTheLastTwentyUnlocks() throws Exception {
        Tablet tablet = tablet();
        Tablet other = tablet();
        String waiting = issueCode(AZIZA, CENTRE_MANAGER);
        JsonNode before = ok(get(OPS + "/staff/me/pin").with(token(AZIZA)));
        assertThat(before.get("status").asString()).isEqualTo("NOT_SET");
        assertThat(before.get("pending").get("issuedVia").asString()).isEqualTo("MANAGER");
        assertThat(before.get("pending").get("issuedBy").asString())
                .as("she is told that a code was issued, and by whom")
                .isEqualTo("Madina Yusupova");

        assertThat(activate(tablet, AZIZA, waiting, "482915").getResponse().getStatus())
                .isEqualTo(200);
        for (int unlock = 0; unlock < 22; unlock++) {
            advance(Duration.ofMinutes(1));
            unlockOk(unlock % 2 == 0 ? other : tablet, AZIZA, "482915");
        }

        MvcResult mine =
                mvc.perform(get(OPS + "/staff/me/pin").with(token(AZIZA))).andReturn();
        assertThat(mine.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode body = JSON.readTree(mine.getResponse().getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("pending").isNull()).isTrue();
        assertThat(body.get("unlocks")).hasSize(20);
        assertThat(body.get("unlocks").get(0).get("endedAt").isNull())
                .as("the newest is the one still open")
                .isTrue();
        assertThat(body.get("unlocks").get(1).get("endedReason").asString()).isEqualTo("SUPERSEDED");
        assertThat(body.get("unlocks").get(0).get("deviceName").asString()).isNotBlank();

        advance(Duration.ofMinutes(6));
        JsonNode later = ok(get(OPS + "/staff/me/pin").with(token(AZIZA)));
        assertThat(later.get("unlocks").get(0).get("endedReason").asString())
                .as("a session nobody ended is named by how it lapsed, not left «open» for ever")
                .isEqualTo("IDLE");
    }

    // ---------------------------------------------------- nothing secret is logged, stored or measured

    @Test
    @DisplayName(
            "no PIN, code, session token or digest appears in a log line, an audit fact, an idempotency record or a metric")
    void nothingSecretLeavesTheDatabaseTableItBelongsTo() throws Exception {
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            Tablet tablet = tablet();
            String code = issueCode(AZIZA, CENTRE_MANAGER);
            MvcResult activated = activate(tablet, AZIZA, code, "482915");
            String token = JSON.readTree(activated.getResponse().getContentAsString())
                    .get("sessionToken")
                    .asString();
            unlock(tablet, AZIZA, "999111");
            String openSession = unlockOk(tablet, AZIZA, "482915");
            mvc.perform(post(TERMINAL_SESSION + "/pin-changes")
                            .with(token(tablet.subject()))
                            .header(SESSION, openSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPin\":\"482915\",\"newPin\":\"735914\"}"))
                    .andReturn();
            for (int wrong = 0; wrong < 5; wrong++) {
                unlock(tablet, AZIZA, "314159");
            }
            // the log is read before the test looks at the digests itself: the JDBC driver logs what a SELECT returns
            List<ILoggingEvent> events;
            synchronized (logs) {
                events = List.copyOf(logs.list);
            }
            String digest = jdbc.sql("SELECT pin_digest FROM iam.staff_pins WHERE staff_member_id = :id")
                    .param("id", memberId(AZIZA))
                    .query(String.class)
                    .single();
            String codeDigest = jdbc.sql("SELECT code_digest FROM iam.staff_pin_activations")
                    .query(String.class)
                    .single();
            String tokenDigest = jdbc.sql("SELECT token_digest FROM iam.terminal_sessions LIMIT 1")
                    .query(String.class)
                    .single();

            List<String> secrets = List.of(
                    "482915", "735914", "314159", "999111", code, token, openSession, digest, codeDigest, tokenDigest);
            // Everything this application logs at any level, and everything anyone logs at INFO or above (what
            // production
            // emits). The test profile also turns the JDBC driver and Spring JDBC to DEBUG, which echo every bound
            // parameter and returned column -- that is the database's own content, not a log line anyone ships.
            String logText = events.stream()
                    .filter(event -> event.getLoggerName().startsWith("uz.horecaos")
                            || event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO))
                    .map(event -> event.getFormattedMessage() + " " + event.getThrowableProxy())
                    .collect(Collectors.joining("\n"));
            String audit = auditText();
            String idempotency = jdbc.sql(
                            "SELECT coalesce(string_agg(row_to_json(i)::text, ' '), '') FROM platform.idempotency_records i")
                    .query(String.class)
                    .single();
            String metricTags = meters.getMeters().stream()
                    .map(meter -> meter.getId().getName() + meter.getId().getTags())
                    .collect(Collectors.joining("\n"));

            for (String secret : secrets) {
                assertThat(logText)
                        .as("log, %s", secret.length() > 8 ? "a long secret" : secret)
                        .doesNotContain(secret);
                assertThat(audit).as("audit trail").doesNotContain(secret);
                assertThat(idempotency).as("idempotency records").doesNotContain(secret);
                assertThat(metricTags).as("metrics").doesNotContain(secret);
            }
            assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'terminal.pin.changed'"))
                    .as("the change is on the trail, with no value in it")
                    .isEqualTo(1L);
            assertThat(new TerminalSessionController.UnlockRequest(UUID.randomUUID(), "482915").toString())
                    .doesNotContain("482915");
            assertThat(new TerminalSessionController.ActivationRequest(UUID.randomUUID(), "12345678", "482915")
                            .toString())
                    .doesNotContain("12345678")
                    .doesNotContain("482915");
            assertThat(new TerminalSessionController.PinChangeRequest("482915", "735914").toString())
                    .doesNotContain("482915")
                    .doesNotContain("735914");
            assertThat(new TerminalSessionController.SessionResponse(
                                    token,
                                    new TerminalSessionController.RosterMember(UUID.randomUUID(), "Aziza K.", "ACTIVE"),
                                    300,
                                    clock.instant())
                            .toString())
                    .doesNotContain(token);
            assertThat(new StaffPinController.CodeResponse("12345678", clock.instant(), "SELF").toString())
                    .doesNotContain("12345678");
        } finally {
            releaseLogs(logs);
        }
    }

    // --------------------------------------------------------------------------------------- helpers

    private Tablet tablet() throws Exception {
        Tablet tablet =
                enrolTablet("Pass tablet " + UUID.randomUUID().toString().substring(0, 4));
        setMode(tablet, "PERSON_OPTIONAL");
        return tablet;
    }

    private static String reasonOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.has("reason") ? body.get("reason").asString() : "";
    }

    private void setPhoneAndEmployeeNumber() {
        tx.executeWithoutResult(status -> {
            members.updateByManager(
                    TENANT,
                    Reach.tenant(null, null),
                    memberId(AZIZA),
                    versionOf(AZIZA),
                    new ProfileEdit("Aziza", "Karimova", "+998 90 123 48 76", null, null),
                    new EmploymentEdit(null, null, null, null),
                    OWNER,
                    "fixture",
                    "corr-fixture");
        });
        tx.executeWithoutResult(status -> {
            members.updateByManager(
                    TENANT,
                    Reach.tenant(null, null),
                    memberId(BAKHROM),
                    versionOf(BAKHROM),
                    new ProfileEdit("Bakhrom", "Tursunov", null, null, null),
                    new EmploymentEdit(null, "EMP-770412", null, null),
                    OWNER,
                    "fixture",
                    "corr-fixture");
        });
    }

    private int versionOf(String subject) {
        return jdbc.sql("SELECT version FROM iam.staff_members WHERE id = :id")
                .param("id", memberId(subject))
                .query(Integer.class)
                .single();
    }

    private void putPolicy(int length, String display) throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                OPS + "/terminal-policy")
                        .with(token(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "pol-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pinLength\":" + length + ",\"autoLockSeconds\":300,\"sessionMaxHours\":12,"
                                + "\"rosterDisplay\":\"" + display + "\",\"attributionDefault\":\"DEVICE_ONLY\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /** {@code perDevice} wrong attempts at each device, all at once, for one person. */
    private List<Outcome> hammer(List<DevicePrincipalView> devicesInUse, int perDevice, String pin) throws Exception {
        UUID member = memberId(AZIZA);
        int threads = devicesInUse.size() * perDevice;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 16));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (DevicePrincipalView device : devicesInUse) {
            for (int attempt = 0; attempt < perDevice; attempt++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    Unlocked result = pins.unlock(device, member, pin);
                    return result.outcome();
                }));
            }
        }
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get());
        }
        pool.shutdown();
        return outcomes;
    }

    /** Root-logger capture, snapshotted under the appender's lock (CI runs the suite concurrently). */
    private static ListAppender<ILoggingEvent> captureLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        previousLevel = root.getLevel();
        root.setLevel(Level.ALL);
        return appender;
    }

    private static Level previousLevel = Level.INFO;

    private static void releaseLogs(ListAppender<ILoggingEvent> appender) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        root.setLevel(previousLevel);
        appender.stop();
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> none() {
        return Map.of();
    }
}
