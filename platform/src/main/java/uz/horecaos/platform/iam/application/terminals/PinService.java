package uz.horecaos.platform.iam.application.terminals;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.terminals.TerminalAlert;
import uz.horecaos.platform.iam.api.terminals.TerminalFact;
import uz.horecaos.platform.iam.api.terminals.TerminalLockout;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.AttributionMode;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicySource;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts.Person;
import uz.horecaos.platform.iam.application.terminals.PinRules.Refusal;
import uz.horecaos.platform.iam.application.terminals.TerminalSessionService.OpenedSession;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.ActivationRow;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.DeviceLockRow;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.PinRow;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.SessionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The PIN: set at a terminal, verified under a durable lockout, and never the thing that signs anyone in (ADR 0155).
 *
 * <p><strong>A PIN attributes; it never authenticates.</strong> It is accepted only by the {@code terminal-session}
 * endpoints, from an enrolled unlockable device that has already authenticated as itself, for a person who is eligible at
 * that device's location. It is one PIN per person per tenant, so it is the <em>session</em> that is bound to a device,
 * and a manager can neither choose nor read one. No verifier exists here for any credential that can sign a person in:
 * Keycloak stays the only one.
 *
 * <p><strong>The lockout is the database's.</strong> Every verification takes the person's {@code iam.staff_pins} row
 * {@code FOR UPDATE}, so one attempt at a time is counted per person on any number of replicas, and the counters are rows,
 * not limiter state that a restart forgets: five consecutive wrong PINs lock that person on that device for fifteen
 * minutes, twelve in a rolling day across every device lock the credential until an activation replaces it, and success
 * clears the consecutive count and never the daily one. The constants are code, not a tenant's setting: a security control
 * a tenant can weaken is not one.
 *
 * <p>A wrong PIN is committed (the counters moved) and then refused, which is why each verification returns an outcome
 * rather than throwing from inside its own transaction.
 */
@Service
public class PinService {

    /** Five consecutive wrong PINs at one device lock that person there... */
    static final int DEVICE_LOCK_AFTER = TerminalLockout.DEVICE_CONSECUTIVE_FAILURES;

    /** ...for fifteen minutes. */
    static final Duration DEVICE_LOCK = TerminalLockout.DEVICE_LOCK;

    /** Twelve wrong PINs in a rolling 24 hours, across every device, lock the credential until it is reset. */
    static final int CREDENTIAL_LOCK_AFTER = TerminalLockout.CREDENTIAL_FAILURES_PER_DAY;

    static final Duration FAILURE_WINDOW = TerminalLockout.FAILURE_WINDOW;

    /** An activation code is burned at the fifth wrong try. */
    static final int ACTIVATION_ATTEMPTS = 5;

    static final Duration SELF_ACTIVATION_LIFETIME = Duration.ofMinutes(15);
    static final Duration MANAGER_ACTIVATION_LIFETIME = Duration.ofHours(4);

    static final String LOCK_RULE_DEVICE = "DEVICE_CONSECUTIVE";
    static final String LOCK_RULE_CREDENTIAL = "DAILY_LIMIT";

    /** The metric outcome vocabulary, closed (ADR 0155, Observability). */
    public enum Outcome {
        SUCCEEDED,
        WRONG_PIN,
        LOCKED,
        NOT_ELIGIBLE
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTerminalStore store;
    private final PinDigests digests;
    private final StaffTerminalFacts staff;
    private final TerminalEligibility eligibility;
    private final TerminalSessionService sessions;
    private final TerminalPolicySource policies;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    public PinService(
            JdbcTerminalStore store,
            PinDigests digests,
            StaffTerminalFacts staff,
            TerminalEligibility eligibility,
            TerminalSessionService sessions,
            TerminalPolicySource policies,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            MeterRegistry meters,
            Clock clock) {
        this.store = store;
        this.digests = digests;
        this.staff = staff;
        this.eligibility = eligibility;
        this.sessions = sessions;
        this.policies = policies;
        this.events = events;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------ unlock

    /**
     * @param opened the session, on {@link Outcome#SUCCEEDED} only
     * @param retryAfterSeconds on {@link Outcome#LOCKED}: how long to wait, or null for a credential locked until reset
     */
    public record Unlocked(
            Outcome outcome,
            @Nullable OpenedSession opened,
            @Nullable Long retryAfterSeconds) {

        static Unlocked of(Outcome outcome) {
            return new Unlocked(outcome, null, null);
        }
    }

    /**
     * A person taps their name and types a PIN. A wrong PIN, an unknown member, an ineligible person, a person with no PIN
     * and a device nobody unlocks all answer {@code NOT_ELIGIBLE} or {@code WRONG_PIN} -- which the caller renders
     * identically -- and only a lock is distinguished, because the legitimate person needs to know to wait.
     */
    public Unlocked unlock(DevicePrincipalView device, UUID memberId, String pin) {
        Unlocked result = transactions.execute(status -> unlockIn(device, memberId, pin));
        Unlocked outcome = java.util.Objects.requireNonNull(result);
        meters.counter("horecaos.terminal.unlocks", "outcome", outcome.outcome().name())
                .increment();
        return outcome;
    }

    private Unlocked unlockIn(DevicePrincipalView device, UUID memberId, String pin) {
        Instant now = clock.instant();
        UUID tenantId = device.tenantId();
        Person person = personAtThisDevice(device, memberId);
        if (person == null) {
            return Unlocked.of(Outcome.NOT_ELIGIBLE);
        }
        store.ensurePin(tenantId, memberId, now);
        PinRow credential = store.lockPin(tenantId, memberId).orElseThrow();

        Verification verification = verify(device, person, credential, pin, now);
        if (verification.kind() != VerificationKind.OK) {
            return verification.asUnlock();
        }
        TerminalPolicy policy = policies.of(tenantId);
        OpenedSession opened =
                sessions.open(device, person, idleSecondsOf(device, policy), policy.sessionMaxHours(), correlation());
        return new Unlocked(Outcome.SUCCEEDED, opened, null);
    }

    // ------------------------------------------------------------------------------- the verification

    private enum VerificationKind {
        OK,
        NOT_SET,
        UNREADABLE,
        WRONG,
        LOCKED
    }

    private record Verification(
            VerificationKind kind, @Nullable Long retryAfterSeconds) {

        static Verification ok() {
            return new Verification(VerificationKind.OK, null);
        }

        Unlocked asUnlock() {
            return switch (kind) {
                case OK -> throw new IllegalStateException("Not a refusal");
                case NOT_SET, UNREADABLE -> Unlocked.of(Outcome.NOT_ELIGIBLE);
                case WRONG -> Unlocked.of(Outcome.WRONG_PIN);
                case LOCKED -> new Unlocked(Outcome.LOCKED, null, retryAfterSeconds);
            };
        }
    }

    /**
     * Checks one PIN against the credential the caller has locked, and moves the counters. The same rule serves an unlock and
     * a PIN change, so a change cannot be used to guess the old PIN for free.
     */
    private Verification verify(DevicePrincipalView device, Person person, PinRow credential, String pin, Instant now) {
        UUID tenantId = device.tenantId();
        UUID memberId = person.memberId();
        if ("LOCKED".equals(credential.status())) {
            return new Verification(VerificationKind.LOCKED, null);
        }
        DeviceLockRow deviceLock = store.findDeviceLock(device.id(), memberId).orElse(null);
        if (deviceLock != null && deviceLock.lockedUntil() != null && now.isBefore(deviceLock.lockedUntil())) {
            long wait =
                    Math.max(1, Duration.between(now, deviceLock.lockedUntil()).toSeconds());
            return new Verification(VerificationKind.LOCKED, wait);
        }
        if (!"ACTIVE".equals(credential.status())
                || credential.digest() == null
                || credential.pepperVersion() == null) {
            return new Verification(VerificationKind.NOT_SET, null);
        }
        String computed =
                digests.pinDigest(tenantId, memberId, credential.pinVersion(), pin, credential.pepperVersion());
        if (computed == null) {
            // Keyed under a pepper that is no longer resolvable: the PIN can never verify. An operator's mistake
            // (a rotation that dropped the previous reference too early), answered like any other refusal.
            return new Verification(VerificationKind.UNREADABLE, null);
        }
        if (PinDigests.matches(credential.digest(), computed)) {
            store.clearDeviceLock(device.id(), memberId);
            if (credential.pepperVersion() != digests.currentPepperVersion()) {
                // Re-keyed on the first successful unlock after the pepper rotates.
                store.rekey(
                        tenantId,
                        memberId,
                        java.util.Objects.requireNonNull(digests.pinDigest(
                                tenantId, memberId, credential.pinVersion(), pin, digests.currentPepperVersion())),
                        digests.currentPepperVersion(),
                        now);
            }
            return Verification.ok();
        }
        return recordFailure(device, person, credential, deviceLock, now);
    }

    private Verification recordFailure(
            DevicePrincipalView device,
            Person person,
            PinRow credential,
            @Nullable DeviceLockRow deviceLock,
            Instant now) {
        UUID tenantId = device.tenantId();
        UUID memberId = person.memberId();

        Instant openedAt = credential.windowStartedAt();
        boolean windowOpen = openedAt != null && now.isBefore(openedAt.plus(FAILURE_WINDOW));
        Instant windowStart = windowOpen && openedAt != null ? openedAt : now;
        int inWindow = (windowOpen ? credential.failuresInWindow() : 0) + 1;
        int consecutive = (deviceLock == null ? 0 : deviceLock.consecutiveFailures()) + 1;

        if (inWindow >= CREDENTIAL_LOCK_AFTER) {
            store.lockCredential(tenantId, memberId, inWindow, LOCK_RULE_CREDENTIAL, now);
            store.saveDeviceLock(tenantId, device.id(), memberId, 0, null, now);
            sessions.endMemberSessions(tenantId, memberId, "LOCKED", correlation());
            raiseLock(device, person, LOCK_RULE_CREDENTIAL, inWindow, now);
            return new Verification(VerificationKind.LOCKED, null);
        }
        store.recordFailureInWindow(tenantId, memberId, windowStart, inWindow, now);
        if (consecutive >= DEVICE_LOCK_AFTER) {
            Instant until = now.plus(DEVICE_LOCK);
            store.saveDeviceLock(tenantId, device.id(), memberId, 0, until, now);
            raiseLock(device, person, LOCK_RULE_DEVICE, consecutive, now);
            return new Verification(VerificationKind.LOCKED, DEVICE_LOCK.toSeconds());
        }
        store.saveDeviceLock(tenantId, device.id(), memberId, consecutive, null, now);
        return new Verification(VerificationKind.WRONG, null);
    }

    /**
     * A lock raises a SECURITY fact and an operations alert (ADR 0155): the fifth consecutive failure and the twelfth in a
     * day only, never each wrong attempt, so an attacker cannot fill the audit log. The alert names the member's {@code
     * display_reference} and the device, never the person.
     */
    private void raiseLock(DevicePrincipalView device, Person person, String rule, int count, Instant now) {
        String correlation = correlation();
        meters.counter("horecaos.terminal.pin.locks", "rule", rule).increment();
        Map<String, Object> after = facts("rule", rule, "failures", count, "deviceName", device.displayName());
        events.publishEvent(fact(
                "terminal.unlock.refused",
                device,
                null,
                "iam.staff_member",
                person.memberId(),
                "Wrong PIN: " + rule,
                Map.of(),
                after,
                correlation,
                now));
        events.publishEvent(fact(
                "terminal.pin.locked",
                device,
                null,
                "iam.staff_member",
                person.memberId(),
                "PIN locked: " + rule,
                Map.of("locked", false),
                after,
                correlation,
                now));
        events.publishEvent(new TerminalAlert(
                TerminalAlert.CREDENTIAL_LOCKED,
                device.tenantId(),
                device.brandId(),
                device.locationId(),
                person.memberId(),
                "StaffMember",
                device.id(),
                device.displayName(),
                person.displayReference(),
                rule,
                now,
                Ids.newId()));
    }

    // ------------------------------------------------------------------------------ activation

    /** Why an activation did not set a PIN. */
    public enum ActivationRefusal {
        /** Unknown, expired, spent or burned code, an ineligible person, or a device nobody unlocks: all one answer. */
        REFUSED,
        /** The code was right and the PIN is refused by the entry rules; the code is not spent. */
        PIN_RULE
    }

    public record Activated(
            @Nullable OpenedSession opened,
            @Nullable ActivationRefusal refusal,
            @Nullable Refusal rule) {

        static Activated refused() {
            return new Activated(null, ActivationRefusal.REFUSED, null);
        }
    }

    /**
     * A person with no PIN, or a locked one, types the single-use code they were given and a new PIN twice (the shell checks
     * the twin); success opens a session. The code is single-use, expires, and is burned at the fifth wrong try.
     */
    public Activated activate(DevicePrincipalView device, UUID memberId, String code, String newPin) {
        Activated result = transactions.execute(status -> activateIn(device, memberId, code, newPin));
        Activated outcome = java.util.Objects.requireNonNull(result);
        meters.counter(
                        "horecaos.terminal.unlocks",
                        "outcome",
                        outcome.opened() != null ? Outcome.SUCCEEDED.name() : Outcome.NOT_ELIGIBLE.name())
                .increment();
        return outcome;
    }

    private Activated activateIn(DevicePrincipalView device, UUID memberId, String code, String newPin) {
        Instant now = clock.instant();
        UUID tenantId = device.tenantId();
        Person person = personAtThisDevice(device, memberId);
        if (person == null) {
            return Activated.refused();
        }
        store.ensurePin(tenantId, memberId, now);
        PinRow credential = store.lockPin(tenantId, memberId).orElseThrow();

        ActivationRow activation = store.pendingActivation(tenantId, memberId).orElse(null);
        if (activation == null) {
            return Activated.refused();
        }
        if (!now.isBefore(activation.expiresAt())) {
            store.setActivationStatus(activation.id(), "EXPIRED");
            return Activated.refused();
        }
        String computed = digests.activationDigest(tenantId, memberId, code.strip());
        if (!PinDigests.matches(activation.codeDigest(), computed)) {
            int tries = activation.failedAttempts() + 1;
            if (tries >= ACTIVATION_ATTEMPTS) {
                store.countWrongActivationTry(activation.id(), tries);
                store.setActivationStatus(activation.id(), "BURNED");
            } else {
                store.countWrongActivationTry(activation.id(), tries);
            }
            return Activated.refused();
        }

        TerminalPolicy policy = policies.of(tenantId);
        Refusal rule =
                PinRules.check(newPin, policy.pinLength(), staff.pinTails(tenantId, memberId, policy.pinLength()));
        if (rule != null) {
            // A weak PIN is the person's mistake, not a guess at the code: the code stays good for the next try.
            return new Activated(null, ActivationRefusal.PIN_RULE, rule);
        }

        int pinVersion = credential.pinVersion() + 1;
        String digest = digests.pinDigest(tenantId, memberId, pinVersion, newPin, digests.currentPepperVersion());
        store.setPin(
                tenantId,
                memberId,
                java.util.Objects.requireNonNull(digest),
                digests.currentPepperVersion(),
                pinVersion,
                policy.pinLength(),
                device.id(),
                activation.issuedVia().equals("SELF") ? "SELF" : activation.issuedBy(),
                now);
        store.consumeActivation(activation.id(), device.id(), now);
        store.clearAllDeviceLocks(tenantId, memberId);

        events.publishEvent(fact(
                "terminal.pin.set",
                device,
                person.subject(),
                "iam.staff_member",
                memberId,
                "PIN set at " + device.displayName() + " with an activation code",
                facts("pinStatus", credential.status()),
                facts("pinStatus", "ACTIVE", "digits", policy.pinLength(), "issuedVia", activation.issuedVia()),
                correlation(),
                now));
        OpenedSession opened =
                sessions.open(device, person, idleSecondsOf(device, policy), policy.sessionMaxHours(), correlation());
        return new Activated(opened, null, null);
    }

    // -------------------------------------------------------------------------------- change

    public record Changed(
            @Nullable Outcome refusal,
            @Nullable Refusal rule,
            @Nullable Long retryAfterSeconds) {

        static Changed done() {
            return new Changed(null, null, null);
        }
    }

    /**
     * Changing a PIN needs the old one and an unlocked session: the live session names the person, and the old PIN is
     * checked under the same lockout as an unlock, so a change is not a free place to guess it.
     */
    public Changed change(DevicePrincipalView device, SessionRow session, String currentPin, String newPin) {
        Changed result = transactions.execute(status -> changeIn(device, session, currentPin, newPin));
        return java.util.Objects.requireNonNull(result);
    }

    private Changed changeIn(DevicePrincipalView device, SessionRow session, String currentPin, String newPin) {
        Instant now = clock.instant();
        UUID tenantId = device.tenantId();
        Person person = personAtThisDevice(device, session.memberId());
        if (person == null) {
            return new Changed(Outcome.NOT_ELIGIBLE, null, null);
        }
        store.ensurePin(tenantId, person.memberId(), now);
        PinRow credential = store.lockPin(tenantId, person.memberId()).orElseThrow();
        Verification verification = verify(device, person, credential, currentPin, now);
        if (verification.kind() != VerificationKind.OK) {
            Unlocked refusal = verification.asUnlock();
            return new Changed(refusal.outcome(), null, refusal.retryAfterSeconds());
        }
        TerminalPolicy policy = policies.of(tenantId);
        Refusal rule = PinRules.check(
                newPin, policy.pinLength(), staff.pinTails(tenantId, person.memberId(), policy.pinLength()));
        if (rule != null) {
            return new Changed(null, rule, null);
        }
        int pinVersion = credential.pinVersion() + 1;
        String digest =
                digests.pinDigest(tenantId, person.memberId(), pinVersion, newPin, digests.currentPepperVersion());
        store.setPin(
                tenantId,
                person.memberId(),
                java.util.Objects.requireNonNull(digest),
                digests.currentPepperVersion(),
                pinVersion,
                policy.pinLength(),
                device.id(),
                "SELF",
                now);
        events.publishEvent(fact(
                "terminal.pin.changed",
                device,
                person.subject(),
                "iam.staff_member",
                person.memberId(),
                "PIN changed at " + device.displayName(),
                facts("pinVersion", credential.pinVersion()),
                facts("pinVersion", pinVersion, "digits", policy.pinLength()),
                correlation(),
                now));
        return Changed.done();
    }

    // ---------------------------------------------------------------------- codes, resets, state

    /** How an activation code was issued: by the person from their own profile, or by a manager. */
    public enum ActivationVia {
        SELF(SELF_ACTIVATION_LIFETIME),
        MANAGER(MANAGER_ACTIVATION_LIFETIME);

        private final Duration lifetime;

        ActivationVia(Duration lifetime) {
            this.lifetime = lifetime;
        }

        public Duration lifetime() {
            return lifetime;
        }
    }

    /** The code, shown once to whoever issued it. */
    public record IssuedCode(String code, Instant expiresAt, ActivationVia via) {

        @Override
        public String toString() {
            return "IssuedCode[code=<redacted>, expiresAt=" + expiresAt + ", via=" + via + "]";
        }
    }

    /**
     * Issues a single-use activation code for a member: the person's own, from «Мой профиль», valid fifteen minutes, or a
     * manager's, from the person card, valid four hours. A second pending code supersedes the first. The code is returned once;
     * only its keyed digest is stored.
     */
    public IssuedCode issueActivation(
            UUID tenantId, UUID memberId, String issuerSubject, ActivationVia via, @Nullable String reason) {
        IssuedCode issued = transactions.execute(status -> {
            Instant now = clock.instant();
            Person person = staff.person(tenantId, memberId).orElseThrow(PinService::noSuchMember);
            store.ensurePin(tenantId, memberId, now);
            store.lockPin(tenantId, memberId);
            store.supersedePendingActivations(tenantId, memberId);
            String code = newCode();
            Instant expires = now.plus(via.lifetime());
            store.insertActivation(
                    Ids.newId(),
                    tenantId,
                    memberId,
                    digests.activationDigest(tenantId, memberId, code),
                    issuerSubject,
                    via.name(),
                    expires,
                    now);
            events.publishEvent(new TerminalFact(
                    "terminal.pin.activation_issued",
                    tenantId,
                    null,
                    null,
                    issuerSubject,
                    "iam.staff_member",
                    memberId,
                    null,
                    reason == null || reason.isBlank() ? "A PIN activation code was issued" : reason.strip(),
                    via == ActivationVia.MANAGER ? "staff.profile.manage" : "staff.self.manage",
                    null,
                    Map.of(),
                    facts("via", via.name(), "member", person.displayReference()),
                    correlation(),
                    now));
            return new IssuedCode(code, expires, via);
        });
        return java.util.Objects.requireNonNull(issued);
    }

    /**
     * A manager clears the credential and ends the person's terminal sessions; the person then activates again. The manager
     * never sees or chooses a PIN.
     */
    public void reset(UUID tenantId, UUID memberId, String actorSubject, String reason) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            Person person = staff.person(tenantId, memberId).orElseThrow(PinService::noSuchMember);
            store.ensurePin(tenantId, memberId, now);
            PinRow before = store.lockPin(tenantId, memberId).orElseThrow();
            store.clearPin(tenantId, memberId, now);
            store.supersedePendingActivations(tenantId, memberId);
            store.clearAllDeviceLocks(tenantId, memberId);
            String correlation = correlation();
            sessions.endMemberSessions(tenantId, memberId, "PIN_RESET", correlation);
            events.publishEvent(new TerminalFact(
                    "terminal.pin.reset",
                    tenantId,
                    null,
                    null,
                    actorSubject,
                    "iam.staff_member",
                    memberId,
                    null,
                    reason.strip(),
                    "staff.profile.manage",
                    null,
                    facts("pinStatus", before.status()),
                    facts("pinStatus", "NOT_SET", "member", person.displayReference()),
                    correlation,
                    now));
        });
    }

    /** What a screen shows of a credential: never a digest, never a length that identifies a person. */
    public record PinState(
            String status,
            @Nullable Instant setAt,
            @Nullable Instant lockedAt,
            @Nullable PendingCode pendingCode) {}

    public record PendingCode(String issuedVia, @Nullable String issuedBy, Instant expiresAt) {}

    public PinState state(UUID tenantId, UUID memberId) {
        Instant now = clock.instant();
        PinRow pin = store.findPin(tenantId, memberId).orElse(null);
        ActivationRow pending = store.pendingActivation(tenantId, memberId)
                .filter(row -> now.isBefore(row.expiresAt()))
                .orElse(null);
        return new PinState(
                pin == null ? "NOT_SET" : pin.status(),
                pin == null ? null : pin.setAt(),
                pin == null ? null : pin.lockedAt(),
                pending == null ? null : new PendingCode(pending.issuedVia(), pending.issuedBy(), pending.expiresAt()));
    }

    /** The roster's per-person state: whether they have to be offered «Задать PIN». */
    public Map<UUID, String> pinStates(UUID tenantId, java.util.List<UUID> memberIds) {
        Map<UUID, String> states = new LinkedHashMap<>();
        memberIds.forEach(id -> states.put(id, "NOT_SET"));
        store.pinStates(tenantId, memberIds).forEach(state -> states.put(state.memberId(), state.status()));
        return states;
    }

    // --------------------------------------------------------------------------------- helpers

    /**
     * The member as a person at this device, or null: a member who does not exist, is not active, is not eligible at this
     * device's location, or a device of a class nobody unlocks or in {@code DEVICE_ONLY}. One answer for all of them.
     */
    private @Nullable Person personAtThisDevice(DevicePrincipalView device, UUID memberId) {
        if (!device.deviceClass().unlockable()
                || AttributionMode.valueOf(device.attributionMode()) == AttributionMode.DEVICE_ONLY) {
            return null;
        }
        Optional<Person> person = staff.person(device.tenantId(), memberId);
        return person.filter(found -> eligibility.eligible(found, device)).orElse(null);
    }

    private static int idleSecondsOf(DevicePrincipalView device, TerminalPolicy policy) {
        return device.autoLockSeconds() != null ? device.autoLockSeconds() : policy.autoLockSeconds();
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    private static String correlation() {
        return Ids.newId().toString();
    }

    private static ApiException noSuchMember() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such staff member");
    }

    private static TerminalFact fact(
            String action,
            DevicePrincipalView device,
            @Nullable String actorSubject,
            String targetType,
            UUID targetId,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            String correlation,
            Instant now) {
        return new TerminalFact(
                action,
                device.tenantId(),
                device.locationId(),
                device.brandId(),
                actorSubject,
                targetType,
                targetId,
                device.id(),
                reason,
                null,
                null,
                before,
                after,
                correlation,
                now);
    }

    private static Map<String, Object> facts(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** For tests: the set of reasons a PIN is refused, so a screen can be checked for a sentence per reason. */
    public static Set<Refusal> refusals() {
        return Set.of(Refusal.values());
    }
}
