package uz.horecaos.platform.tenancy.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.domain.BusinessType;
import uz.horecaos.platform.tenancy.domain.Markets;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantProfileStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantProfileStore.PublicHoliday;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantProfileStore.TenantProfile;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Where a tenant trades, what kind of business it is, and the public holidays
 * of the countries the platform serves (ADR 0090).
 *
 * <p>A change of country is a residency decision and waits for a second
 * signature through the approval model every other governed action uses. It
 * records the market only: the tenant's currency and timezone stay what they
 * are, because orders and money already recorded in them do not change
 * country with it.
 */
@Service
public class TenantProfileService {

    private final JdbcTenantProfileStore profiles;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;

    public TenantProfileService(
            JdbcTenantProfileStore profiles, ApprovalService approvals, AuditRecorder audit, Clock clock) {
        this.profiles = profiles;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    public List<TenantProfile> all() {
        return profiles.all();
    }

    public List<PublicHoliday> holidays() {
        return profiles.holidays();
    }

    @Transactional
    public void setBusinessType(UUID tenantId, BusinessType type, ActorRef actor, String reason) {
        TenantProfile before = require(tenantId);
        if (!profiles.setBusinessType(tenantId, type)) {
            return;
        }
        audit.record(AuditFact.of("tenant.business_type.changed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("Tenant", tenantId)
                .because(reason)
                .changed(Map.of("from", before.businessType().name(), "to", type.name()))
                .usingCapability(Capability.TENANT_WRITE.code())
                .correlatedBy(tenantId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Moves a tenant to another market, once a second person has signed it.
     *
     * <p>The first call raises the approval request and answers that it is
     * waiting; the same call made again after the approval performs the change
     * and spends the signature, so one approval moves the tenant once.
     */
    @Transactional
    public CountryChange changeCountry(UUID tenantId, String countryCode, ActorRef actor, String reason) {
        TenantProfile before = require(tenantId);
        if (Markets.find(countryCode).isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "HorecaOS does not trade in %s".formatted(countryCode),
                    Map.of("countryCode", countryCode));
        }
        if (before.countryCode().equals(countryCode)) {
            return new CountryChange(CountryChange.UNCHANGED, null);
        }

        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.TENANT_COUNTRY_CHANGE.code(),
                hash(tenantId + ":" + countryCode),
                ResourceScope.tenant(tenantId),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (approval instanceof ApprovalOutcome.Declined declined) {
            return new CountryChange(CountryChange.DECLINED, declined.requestId());
        }
        if (!approval.mayProceed()) {
            UUID requestId = approval instanceof ApprovalOutcome.Pending pending ? pending.requestId() : null;
            return new CountryChange(CountryChange.AWAITING_APPROVAL, requestId);
        }
        approval.consume();

        profiles.setCountry(tenantId, countryCode);
        audit.record(AuditFact.of("tenant.country.changed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("Tenant", tenantId)
                .because(reason)
                .changed(Map.of("from", before.countryCode(), "to", countryCode))
                .usingCapability(Capability.TENANT_WRITE.code())
                .correlatedBy(tenantId.toString())
                .occurredAt(clock.instant())
                .build());
        UUID requestId = approval instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;
        return new CountryChange(CountryChange.CHANGED, requestId);
    }

    /** What a change of country did: changed, waiting for a signature, declined, or nothing to do. */
    public record CountryChange(String status, @Nullable UUID approvalRequestId) {
        public static final String CHANGED = "CHANGED";
        public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
        public static final String DECLINED = "DECLINED";
        public static final String UNCHANGED = "UNCHANGED";
    }

    // --------------------------------------------------------- holidays

    /**
     * Adds a holiday: a month and day for one that recurs every year, or a date
     * for one that moves with the lunar calendar.
     */
    @Transactional
    public UUID addHoliday(
            String countryCode,
            String name,
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable LocalDate date,
            ActorRef actor,
            String reason) {
        if (Markets.find(countryCode).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "HorecaOS does not trade in %s".formatted(countryCode));
        }
        boolean recurring = month != null && day != null && date == null;
        boolean dated = date != null && month == null && day == null;
        if (!recurring && !dated) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A holiday is a month and day every year, or one date");
        }
        if (month != null && day != null) {
            try {
                MonthDay.of(month, day);
            } catch (DateTimeException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "There is no such day in the year");
            }
        }
        UUID id = Ids.newId();
        try {
            profiles.insertHoliday(new PublicHoliday(id, countryCode, name, month, day, date), subject(actor));
        } catch (DuplicateKeyException already) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That day is already a holiday there");
        }
        Instant now = clock.instant();
        audit.record(AuditFact.of("reference.public_holiday.added", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("PublicHoliday", id)
                .because(reason)
                .changed(Map.of("countryCode", countryCode, "name", name))
                .usingCapability(Capability.PLATFORM_ADMIN.code())
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());
        return id;
    }

    @Transactional
    public void removeHoliday(UUID id, ActorRef actor, String reason) {
        PublicHoliday holiday = profiles.findHoliday(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such holiday"));
        profiles.deleteHoliday(id);
        audit.record(AuditFact.of("reference.public_holiday.removed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("PublicHoliday", id)
                .because(reason)
                .changed(Map.of("countryCode", holiday.countryCode(), "name", holiday.name()))
                .usingCapability(Capability.PLATFORM_ADMIN.code())
                .correlatedBy(id.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private TenantProfile require(UUID tenantId) {
        return profiles.find(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such tenant"));
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required", unreachable);
        }
    }
}
