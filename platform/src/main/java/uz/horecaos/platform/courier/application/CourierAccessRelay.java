package uz.horecaos.platform.courier.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.courier.application.port.CourierAccessMessenger;
import uz.horecaos.platform.courier.application.port.CourierAccessMessenger.AccessMessage;
import uz.horecaos.platform.courier.application.port.CourierAccessMessenger.Outcome;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierAccessStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierAccessStore.Row;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffProfile;

/**
 * Texts queued courier access links (ADR 0178).
 *
 * <p>{@code PasswordResetRelay}'s shape and for the same reasons: claim, then send, then record -- three
 * steps, never one transaction. The claim pushes the row a lease into the future, so a slow gateway holds no
 * lock and a crashed replica's claim simply comes due again. The token is made here, texted, and only its hash
 * recorded; if recording fails after the text went, the row comes due again and a second text carries a new,
 * working link, which beats a courier holding a link the platform cannot match.
 *
 * <p>The courier's number is read from the identity provider for each send and kept in no variable longer
 * than the send. Logs carry counts and codes, never a number or a link.
 *
 * <p>With no messenger bean (no gateway connected) nothing is attempted and nothing is used up: the row waits,
 * its {@code last_error_code} says {@code SMS_NOT_CONNECTED}, and the console says so.
 */
@Component
public class CourierAccessRelay {

    private static final Logger log = LoggerFactory.getLogger(CourierAccessRelay.class);

    /** After this many real attempts a failing send stops retrying; the manager asks again. */
    static final int MAX_ATTEMPTS = 6;

    static final Duration LEASE = Duration.ofMinutes(5);

    /** How long to wait when no gateway is connected, so a deployment gaining one is not kept waiting long. */
    static final Duration UNCONNECTED_WAIT = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcCourierAccessStore store;
    private final JdbcCourierStore couriers;
    private final StaffAccounts accounts;
    private final ObjectProvider<CourierAccessMessenger> messenger;
    private final AuditRecorder audit;
    private final Clock clock;
    private final String linkOrigin;

    public CourierAccessRelay(
            JdbcCourierAccessStore store,
            JdbcCourierStore couriers,
            StaffAccounts accounts,
            ObjectProvider<CourierAccessMessenger> messenger,
            AuditRecorder audit,
            Clock clock,
            @Value(
                            "${horecaos.courier.access.link-origin:${horecaos.frontends.operations-origin:http://localhost:4200}}")
                    String linkOrigin) {
        this.store = store;
        this.couriers = couriers;
        this.accounts = accounts;
        this.messenger = messenger;
        this.audit = audit;
        this.clock = clock;
        this.linkOrigin = linkOrigin.endsWith("/") ? linkOrigin.substring(0, linkOrigin.length() - 1) : linkOrigin;
    }

    @Scheduled(
            initialDelayString = "${horecaos.courier.access.initial-delay:PT45S}",
            fixedDelayString = "${horecaos.courier.access.interval:PT30S}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The courier access relay could not run", failure);
        }
    }

    /** @return how many links this pass texted, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        List<Row> claimed = store.claimDue(now, LEASE, 20);
        int sent = 0;
        for (Row row : claimed) {
            try {
                if (deliver(row, now)) {
                    sent++;
                }
            } catch (RuntimeException failure) {
                // One invitation's failure must not stop the rest; the lease brings this one back.
                log.error(
                        "A courier access invitation could not be processed ({})",
                        failure.getClass().getSimpleName());
            }
        }
        if (!claimed.isEmpty()) {
            log.info("Courier access relay: {} claimed, {} sent", claimed.size(), sent);
        }
        return sent;
    }

    private boolean deliver(Row row, Instant now) {
        CourierAccessMessenger gateway = messenger.getIfAvailable();
        if (gateway == null) {
            store.markRetry(row.id(), row.attempts(), now.plus(UNCONNECTED_WAIT), "SMS_NOT_CONNECTED", false);
            return false;
        }
        Optional<CourierRow> courier =
                couriers.findCourier(row.tenantId(), row.courierId()).filter(found -> "ACTIVE".equals(found.status()));
        if (courier.isEmpty()) {
            store.markFailed(row.id(), row.attempts(), "COURIER_GONE");
            return false;
        }
        Optional<String> phone;
        try {
            phone = phoneOf(courier.get().principalSubject());
        } catch (RuntimeException unavailable) {
            retry(row, now, "IDENTITY_UNAVAILABLE");
            return false;
        }
        if (phone.isEmpty()) {
            store.markFailed(row.id(), row.attempts(), "NO_PHONE_NUMBER");
            return false;
        }

        String token = newToken();
        String link = linkOrigin + "/courier-access#token=" + token;
        Outcome outcome = gateway.send(new AccessMessage(
                row.tenantId(),
                row.id(),
                row.kind(),
                phone.get(),
                link,
                row.locale(),
                CourierAccessText.render(row.kind(), row.locale(), link, CourierAccessService.LINK_LIFETIME),
                CourierAccessService.LINK_LIFETIME));
        switch (outcome.status()) {
            case ACCEPTED -> {
                Instant expiresAt = now.plus(CourierAccessService.LINK_LIFETIME);
                if (store.markSent(row.id(), row.attempts(), CourierAccessService.hash(token), expiresAt, now)) {
                    record(row, "courier.access.sent", Map.of("kind", row.kind()), now);
                    return true;
                }
                // Voided or replaced while the text was on its way: the link in the courier's hand matches
                // nothing, which is the safe direction.
                return false;
            }
            case REFUSED -> {
                store.markFailed(row.id(), row.attempts(), codeOf(outcome, "SMS_REFUSED"));
                record(row, "courier.access.send_failed", Map.of("reasonCode", codeOf(outcome, "SMS_REFUSED")), now);
                return false;
            }
            case UNAVAILABLE -> {
                retry(row, now, codeOf(outcome, "SMS_UNAVAILABLE"));
                return false;
            }
        }
        return false;
    }

    private void retry(Row row, Instant now, String code) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            store.markFailed(row.id(), row.attempts(), code);
            record(row, "courier.access.send_failed", Map.of("reasonCode", code), now);
            return;
        }
        // 1, 2, 4, 8, 16 minutes: a gateway outage is not hammered, and a short one costs a few minutes.
        Duration backoff = Duration.ofMinutes(1L << Math.min(row.attempts() - 1, 4));
        store.markRetry(row.id(), row.attempts(), now.plus(backoff), code, true);
    }

    private static String codeOf(Outcome outcome, String fallback) {
        String code = outcome.reasonCode();
        return code == null || code.isBlank() ? fallback : code;
    }

    /** The number the identity provider holds for this account: the phone attribute, else a numeric user name. */
    private Optional<String> phoneOf(String subject) {
        Optional<String> profile =
                accounts.profile(subject).map(StaffProfile::phone).filter(number -> !number.isBlank());
        if (profile.isPresent()) {
            return profile;
        }
        return accounts.find(subject)
                .map(StaffAccounts.StaffAccount::username)
                .filter(username -> username.matches("\\+?[0-9]{9,15}"));
    }

    private void record(Row row, String action, Map<String, Object> facts, Instant now) {
        audit.record(AuditFact.of(action, AuditClass.SECURITY)
                .by(ActorRef.systemJob("courier-access-relay"))
                .at(ResourceScope.tenant(row.tenantId()))
                .target("courier", row.courierId())
                .because("A courier's access link was processed by the relay (ADR 0178)")
                .changed(ChangeDocuments.created(withInvitation(facts, row)))
                .correlatedBy(row.courierId().toString())
                .occurredAt(now)
                .build());
    }

    private static Map<String, Object> withInvitation(Map<String, Object> facts, Row row) {
        Map<String, Object> all = new LinkedHashMap<>(facts);
        all.put("invitationId", row.id().toString());
        return all;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
