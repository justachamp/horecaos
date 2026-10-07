package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.api.MarketingConfigurationKeys;
import uz.horecaos.platform.marketing.application.ContactPolicyService;
import uz.horecaos.platform.marketing.application.ContactPolicyService.OverrideRequest;
import uz.horecaos.platform.marketing.domain.ContactPeriod;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcContactPolicyStore.OverrideRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's contact policy: the platform's bounds, and the tighter rules a tenant has
 * added (ADR 0112 Decision 4).
 *
 * <p>An override may make a brand quieter and never louder. A request that would loosen
 * anything is refused with the number it exceeded, and the same bound is a CHECK on the
 * table, so a request that somehow got past the service would meet the database. Every
 * write carries a stated reason, and every refusal the policy later produces carries its
 * own, on the scenario decision it blocked.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/contact-policy")
@Tag(
        name = "Marketing contact policy",
        description = "Tighter caps and wider quiet hours, per channel and purpose (ADR 0112)")
public class ContactPolicyController {

    private final ContactPolicyService policy;
    private final ConfigurationResolver configuration;
    private final CurrentActor currentActor;

    public ContactPolicyController(
            ContactPolicyService policy, ConfigurationResolver configuration, CurrentActor currentActor) {
        this.policy = policy;
        this.configuration = configuration;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "The platform bounds and every override this brand has set",
            description = "The bounds are what an override is measured against: a cap above its ceiling, or "
                    + "quiet hours that start later or end earlier than the platform's, is a loosening and "
                    + "is refused.")
    public ResponseEntity<PolicyResponse> read(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(new PolicyResponse(
                new PlatformBounds(
                        EngagementPolicy.DEFAULT_QUIET_START,
                        EngagementPolicy.DEFAULT_QUIET_END,
                        ContactPeriod.DAILY.platformCeiling(),
                        ContactPeriod.WEEKLY.platformCeiling(),
                        ContactPeriod.ROLLING_7D.platformCeiling(),
                        ContactPeriod.ROLLING_30D.platformCeiling()),
                policy.list(tenantId, brandId).stream()
                        .map(OverrideResponse::of)
                        .toList()));
    }

    @PutMapping("/overrides")
    @RequiresCapability(value = Capability.MARKETING_CONTACT_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Set or replace one override",
            description = "Without an If-Match header this creates the override for the channel, purpose "
                    + "and period named in the body, and is refused if one already exists. With one, it "
                    + "replaces the override at that version.")
    public ResponseEntity<OverrideResponse> set(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody OverrideBody body,
            HttpServletRequest request) {
        Integer expected =
                request.getHeader("If-Match") == null ? null : (int) AggregateVersion.requireIfMatch(request);
        OverrideRow row = policy.set(
                tenantId,
                brandId,
                new OverrideRequest(
                        body.channel(),
                        body.campaignPurpose(),
                        body.period(),
                        body.capCount(),
                        body.quietHoursStart(),
                        body.quietHoursEnd(),
                        body.statedReason()),
                expected,
                actor(),
                actorId(),
                correlationId());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(row.version())).body(OverrideResponse.of(row));
    }

    @DeleteMapping("/overrides/{channel}/{campaignPurpose}/{period}")
    @RequiresCapability(value = Capability.MARKETING_CONTACT_POLICY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Remove an override",
            description = "Returns the brand to the platform bound for that channel, purpose and period. "
                    + "Removing a rule is as attributable as adding one, so a reason is required.")
    public ResponseEntity<Void> remove(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String channel,
            @PathVariable String campaignPurpose,
            @PathVariable String period,
            @RequestParam @NotBlank @Size(max = 500) String reason) {
        policy.remove(tenantId, brandId, channel, campaignPurpose, period, actor(), reason, correlationId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/defaults")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "The ADR 0030 values scenarios read, resolved for this brand",
            description = "The channel priority order (a ranking of campaign purposes that decides a tie "
                    + "between a scenario step and a broadcast), the in-app show cap per day, and the "
                    + "control-group percentage the authoring form offers. Read-only here; they are set "
                    + "through the configuration API.")
    public ResponseEntity<DefaultsResponse> defaults(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        String priority = configuration
                .resolve(MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER, ResourceScope.tenant(tenantId))
                .value();
        Integer showCap = configuration
                .resolve(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY, ResourceScope.brand(tenantId, brandId))
                .value();
        Integer controlPercent = configuration
                .resolve(
                        MarketingConfigurationKeys.SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT,
                        ResourceScope.tenant(tenantId))
                .value();
        return ResponseEntity.ok(new DefaultsResponse(
                priority == null || priority.isBlank()
                        ? List.of()
                        : java.util.Arrays.stream(priority.split(","))
                                .map(String::strip)
                                .filter(word -> !word.isEmpty())
                                .toList(),
                showCap == null ? 3 : showCap,
                controlPercent == null ? 10 : controlPercent));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private UUID actorId() {
        String subject = currentActor.get().subject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This principal has no identifier that can be recorded as an author");
        }
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    public record OverrideBody(
            @NotBlank String channel,
            @NotBlank @Size(max = 64) String campaignPurpose,
            @NotBlank String period,
            @Nullable Integer capCount,
            @Nullable LocalTime quietHoursStart,
            @Nullable LocalTime quietHoursEnd,
            @NotBlank @Size(max = 500) String statedReason) {}

    public record OverrideResponse(
            String channel,
            String campaignPurpose,
            String period,
            @Nullable Integer capCount,
            @Nullable LocalTime quietHoursStart,
            @Nullable LocalTime quietHoursEnd,
            String statedReason,
            UUID updatedBy,
            int version,
            Instant updatedAt) {

        static OverrideResponse of(OverrideRow row) {
            return new OverrideResponse(
                    row.channel(),
                    row.campaignPurpose(),
                    row.periodKind(),
                    row.capCount(),
                    row.quietHoursStart(),
                    row.quietHoursEnd(),
                    row.statedReason(),
                    row.updatedBy(),
                    row.version(),
                    row.updatedAt());
        }
    }

    /** The numbers an override may only move inwards from. */
    public record PlatformBounds(
            LocalTime quietHoursStartNoLaterThan,
            LocalTime quietHoursEndNoEarlierThan,
            int dailyCapCeiling,
            int weeklyCapCeiling,
            int rolling7DayCapCeiling,
            int rolling30DayCapCeiling) {}

    public record PolicyResponse(PlatformBounds platform, List<OverrideResponse> overrides) {}

    public record DefaultsResponse(
            List<String> channelPriorityOrder, int inAppShowCapPerDay, int controlGroupPercentDefault) {}
}
