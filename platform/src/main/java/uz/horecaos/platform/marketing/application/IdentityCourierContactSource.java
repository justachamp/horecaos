package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.marketing.api.CourierContactSource;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore;

/**
 * A courier's number, read from the identity provider when a message needs it (ADR 0182, D15;
 * ADR 0029; ADR 0116).
 *
 * <p>A courier is created phone-first, so the identity provider holds the number as their login and
 * the platform holds it nowhere. This source asks for it and forgets it: {@link #addressable} reads
 * the numbers of the targeted couriers in one call to say which of them have a usable one, and
 * returns identifiers only; {@link #number} reads one for one message. Neither stores, logs or
 * audits a number. Both record the purpose and who or what asked, as an audit fact that names how
 * many couriers and never which numbers.
 *
 * <p><strong>Dark until the owner says it may be used.</strong> {@link #isWired} is the deployment
 * setting {@code horecaos.couriers.broadcast.contact-use-cleared}, false by default: whether a
 * courier's own number may be used for dispatch SMS is the owner's answer to give (ADR 0042 treats a
 * courier as self-employed, ADR 0146 holds courier SMS refused until it is recorded). Until then a
 * broadcast is refused with {@code COURIER_CONTACTS_NOT_WIRED}, and nothing here reads anything.
 *
 * <p><strong>Only a number this platform sends to.</strong> A courier whose identity-provider number
 * is not an Uzbek mobile number in one of the forms people write it is treated as having none. The
 * restriction is the same anti-fraud one the verification path applies to customers (an open
 * destination is how SMS pumping works), and a dispatcher's broadcast is not exempt from it.
 */
@Component
public class IdentityCourierContactSource implements CourierContactSource {

    private static final Pattern UZBEK_MOBILE = Pattern.compile("\\+998\\d{9}");

    private final StaffAccounts accounts;
    private final JdbcCourierBroadcastStore couriers;
    private final AuditRecorder audit;
    private final Clock clock;
    private final boolean contactUseCleared;

    public IdentityCourierContactSource(
            StaffAccounts accounts,
            JdbcCourierBroadcastStore couriers,
            AuditRecorder audit,
            Clock clock,
            @Value("${horecaos.couriers.broadcast.contact-use-cleared:false}") boolean contactUseCleared) {
        this.accounts = accounts;
        this.couriers = couriers;
        this.audit = audit;
        this.clock = clock;
        this.contactUseCleared = contactUseCleared;
    }

    @Override
    public boolean isWired() {
        return contactUseCleared;
    }

    @Override
    public Set<UUID> addressable(UUID tenantId, Collection<UUID> courierIds, String purpose) {
        if (!contactUseCleared || courierIds.isEmpty()) {
            return Set.of();
        }
        Map<UUID, String> subjects = couriers.principalSubjects(tenantId, courierIds);
        Map<String, String> numbers = read(subjects.values(), purpose);

        Set<UUID> addressable = new HashSet<>();
        subjects.forEach((courierId, subject) -> {
            if (usable(numbers.get(subject)) != null) {
                addressable.add(courierId);
            }
        });
        record(
                "MARKETING_COURIER_CONTACTS_CHECKED",
                tenantId,
                null,
                "Checked which of %d couriers have a number the platform sends to (%d do), for %s"
                        .formatted(courierIds.size(), addressable.size(), purpose));
        return Set.copyOf(addressable);
    }

    @Override
    public Optional<String> number(UUID tenantId, UUID courierId, String purpose) {
        if (!contactUseCleared) {
            return Optional.empty();
        }
        String subject = couriers.principalSubjects(tenantId, java.util.List.of(courierId))
                .get(courierId);
        if (subject == null) {
            return Optional.empty();
        }
        Optional<String> number = Optional.ofNullable(
                usable(read(java.util.List.of(subject), purpose).get(subject)));
        record(
                "MARKETING_COURIER_CONTACT_REVEALED",
                tenantId,
                courierId,
                "Read a courier's number to send them one message, for " + purpose);
        return number;
    }

    private Map<String, String> read(Collection<String> subjects, String purpose) {
        if (subjects.isEmpty()) {
            return Map.of();
        }
        try {
            return new LinkedHashMap<>(accounts.phones(subjects, purpose));
        } catch (StaffAccounts.DirectoryUnavailableException unavailable) {
            // Nothing is known about any courier; the caller must fail visibly and not conclude that
            // nobody has a number. The class only: a client exception can echo an address.
            throw new UnavailableException(
                    "The identity provider could not be asked for couriers' numbers", unavailable);
        }
    }

    /**
     * The number in the form the gateway takes ({@code +998} and nine digits), or null for anything this
     * platform does not send to.
     */
    static @Nullable String usable(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 9) {
            digits = "998" + digits;
        }
        String e164 = "+" + digits;
        return UZBEK_MOBILE.matcher(e164).matches() ? e164 : null;
    }

    private void record(String action, UUID tenantId, @Nullable UUID courierId, String reason) {
        AuditFact.Builder fact = AuditFact.of(action, AuditClass.BUSINESS)
                .by(ActorRef.systemJob("courier-broadcast"))
                .at(ResourceScope.tenant(tenantId))
                .because(reason)
                .correlatedBy(correlationId())
                .occurredAt(clock.instant());
        if (courierId != null) {
            fact.target("Courier", courierId);
        }
        audit.record(fact.build());
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
