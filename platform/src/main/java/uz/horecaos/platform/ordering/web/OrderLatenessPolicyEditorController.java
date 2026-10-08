package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyAuthoringService;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyAuthoringService.Editor;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument.ModeThresholds;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessThresholds;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The order-policy card's editor for the {@code ordering.lateness} document (ADR 0030, ADR 0031,
 * orders.md §2.7, gap map rows {@code X.39}/{@code 10.3b}): when an order counts as late, and how
 * far ahead of the promise it counts as at risk, per fulfilment mode.
 *
 * <p>Sits beside {@link OrderLatenessPolicyController}, which serves the boards the <em>resolved</em>
 * thresholds for one location. This is the other direction: the document as authored at TENANT,
 * BRAND or LOCATION scope -- with a mode's at-risk window still absent where the author left it
 * to the default, which the boards' answer cannot show -- and the write that publishes the next
 * version. Both go through {@code OrderLatenessPolicyService}, so what an editor saves is what
 * {@code GET .../orders/lateness-policy} serves the next time a board polls.
 *
 * <p>Capabilities are the ones the rest of the card already uses: {@link
 * Capability#TENANT_CONFIGURATION_READ} and {@link Capability#TENANT_CONFIGURATION_WRITE} at the
 * path's tenant, exactly as {@code OperationsConfigurationController} declares them for the
 * card's scalar fields, and for the same reason -- {@code tenantId} comes from the path alone and
 * every scope built here is pinned to it. {@code PLATFORM} scope is refused: a tenant inherits
 * the platform default, it never writes one. The write is the ADR 0031 command shape --
 * {@code Idempotency-Key}, and the version the caller last saw as {@code expectedVersion} -- with
 * the document replaced whole.
 *
 * <p>Request bodies box every optional field: Jackson 3 refuses a missing primitive as a
 * malformed body, and an editor that leaves a mode's at-risk window blank is not malformed.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/order-lateness-policy")
@Tag(
        name = "Order lateness policy editor",
        description = "The ordering.lateness document as authored per scope (ADR 0030), and its next version")
public class OrderLatenessPolicyEditorController {

    private final OrderLatenessPolicyAuthoringService authoring;
    private final CurrentActor currentActor;
    private final StaffDirectory staff;

    public OrderLatenessPolicyEditorController(
            OrderLatenessPolicyAuthoringService authoring, CurrentActor currentActor, StaffDirectory staff) {
        this.authoring = authoring;
        this.currentActor = currentActor;
        this.staff = staff;
    }

    @GetMapping
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "The lateness document in force at a scope, as the editor needs it",
            description = "Resolves ordering.lateness through the ADR 0030 chain (LOCATION, BRAND, "
                    + "TENANT, platform default) at TENANT, BRAND or LOCATION scope. Each fulfilment "
                    + "mode carries its own at-risk window -- null where it has none and takes "
                    + "atRiskDefault, the tenant's ordering.at_risk_before_minutes when one was set, "
                    + "the platform's five minutes otherwise -- plus effective, the number the boards "
                    + "actually use. The same holds for the no-promise fallback: null where the mode "
                    + "has none of its own, taking noPromiseDefault (the tenant's "
                    + "ordering.late_order_threshold_minutes when one was set, the platform's forty-five "
                    + "minutes otherwise; ADR 0150). currentVersionAtScope is the version to send back as "
                    + "expectedVersion: 0 when this exact scope has authored nothing and merely "
                    + "inherits.")
    EditorResponse readLatenessPolicyEditor(
            @PathVariable UUID tenantId,
            @RequestParam ScopeType scopeType,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {
        return withNames(tenantId, authoring.view(scopeOf(tenantId, scopeType, brandId, locationId)));
    }

    @PostMapping
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Publish the next version of the lateness document at exactly one scope",
            description = "Whole-document replace: ADR 0030 versions the document as one unit, so all "
                    + "three fulfilment modes are sent, an unset at-risk window or no-promise fallback "
                    + "as null. Never edits a "
                    + "version in force -- orders keep resolving the version they already did. "
                    + "expectedVersion must be the currentVersionAtScope a prior GET reported for this "
                    + "same scope (null or 0 when it had authored nothing), or the write is refused "
                    + "with STALE_VERSION naming both versions. At-risk and no-promise windows are "
                    + "whole minutes; every window is at most a day.")
    ResponseEntity<EditorResponse> authorLatenessPolicy(
            @PathVariable UUID tenantId, @Valid @RequestBody AuthorLatenessPolicyRequest request) {
        ResourceScope scope = scopeOf(tenantId, request.scopeType(), request.brandId(), request.locationId());
        Editor published = authoring.author(
                scope,
                request.toDocument(),
                request.expectedVersion(),
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return ResponseEntity.ok(withNames(tenantId, published));
    }

    /**
     * The editor with each rung's approver named. The trace carries the principal's id and nothing
     * else (ADR 0029); the name is looked up here, for this tenant, in one read, and goes into this
     * response only -- a principal with no member row (a support session, a device) simply has no name
     * and the console says so.
     */
    private EditorResponse withNames(UUID tenantId, Editor editor) {
        List<String> principals = editor.levels().stream()
                .map(OrderLatenessPolicyAuthoringService.Level::provenance)
                .filter(java.util.Objects::nonNull)
                .map(ResolutionTrace.Provenance::principal)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, String> names = principals.isEmpty() ? Map.of() : staff.namesOf(tenantId, principals);
        return EditorResponse.of(editor, names);
    }

    /**
     * Builds a scope pinned to {@code tenantId} from the path -- the request never supplies its
     * own tenant. {@code PLATFORM} is refused: nothing on this surface sets a platform default.
     */
    private static ResourceScope scopeOf(
            UUID tenantId, ScopeType scopeType, @Nullable UUID brandId, @Nullable UUID locationId) {
        return switch (scopeType) {
            case PLATFORM ->
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "scopeType PLATFORM is not settable from operations");
            case TENANT -> ResourceScope.tenant(tenantId);
            case BRAND -> ResourceScope.brand(tenantId, require(brandId, "brandId"));
            case LOCATION ->
                ResourceScope.location(tenantId, require(brandId, "brandId"), require(locationId, "locationId"));
        };
    }

    private static UUID require(@Nullable UUID value, String name) {
        if (value == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, name + " is required for this scopeType");
        }
        return value;
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * One fulfilment mode as authored. Every field is boxed so a body that omits one is a validation
     * refusal naming it, not a malformed-body error; only the at-risk window may legitimately be null.
     *
     * @param atRiskBeforeSeconds      null to take the default (the tenant's scalar, else five minutes);
     *                                 a whole number of minutes, 0 to a day
     * @param lateAfterSeconds         grace past the promise before an order is late, 0 to a day
     * @param noPromiseFallbackSeconds null to take the default (the tenant's
     *                                 {@code ordering.late_order_threshold_minutes}, else forty-five
     *                                 minutes; ADR 0150); otherwise how long from creation an order
     *                                 with no promise runs before it is late anyway, a whole number of
     *                                 minutes from one minute to a day
     */
    public record ModeRequest(
            @Nullable Integer atRiskBeforeSeconds,
            @NotNull Integer lateAfterSeconds,
            @Nullable Integer noPromiseFallbackSeconds) {

        ModeThresholds toThresholds() {
            return new ModeThresholds(atRiskBeforeSeconds, lateAfterSeconds, noPromiseFallbackSeconds);
        }
    }

    public record AuthorLatenessPolicyRequest(
            @NotNull ScopeType scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            @NotNull @Valid ModeRequest delivery,
            @NotNull @Valid ModeRequest pickup,
            @NotNull @Valid ModeRequest dineIn,
            @Nullable Integer expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {

        OrderLatenessDocument toDocument() {
            return new OrderLatenessDocument(delivery.toThresholds(), pickup.toThresholds(), dineIn.toThresholds());
        }
    }

    /**
     * @param atRiskBeforeSeconds          the mode's own window, null when it takes the default
     * @param effectiveAtRiskBeforeSeconds what the boards use for this mode
     * @param noPromiseFallbackSeconds     the mode's own fallback, null when it takes the default
     *                                     (ADR 0150)
     * @param effectiveNoPromiseFallbackSeconds what the boards use for this mode
     */
    public record ModeResponse(
            @Nullable Integer atRiskBeforeSeconds,
            int effectiveAtRiskBeforeSeconds,
            int lateAfterSeconds,
            @Nullable Integer noPromiseFallbackSeconds,
            int effectiveNoPromiseFallbackSeconds) {

        static ModeResponse of(ModeThresholds authored, LatenessThresholds effective) {
            return new ModeResponse(
                    authored.atRiskBeforeSeconds(),
                    effective.atRiskBeforeSeconds(),
                    authored.lateAfterSeconds(),
                    authored.noPromiseFallbackSeconds(),
                    effective.noPromiseFallbackSeconds());
        }
    }

    /**
     * @param source {@code SCALAR} when {@code ordering.at_risk_before_minutes} was set somewhere in
     *               the chain, {@code PLATFORM_DEFAULT} when not
     */
    public record AtRiskDefaultResponse(int seconds, String source) {}

    /**
     * The fallback a mode with none of its own takes (ADR 0150).
     *
     * @param source {@code SCALAR} when {@code ordering.late_order_threshold_minutes} was set somewhere
     *               in the chain, {@code PLATFORM_DEFAULT} when not
     */
    public record NoPromiseDefaultResponse(int seconds, String source) {}

    /**
     * One rung of the resolution ladder; {@code outcome} is {@code VALUE} or {@code NOT_SET}, as the
     * configuration trace names them.
     *
     * @param version         the policy version in force at this exact scope, absent when nothing was authored here
     * @param approvedByName  who approved that version, by the name the tenant knows them by; absent when the
     *                        principal has no member record in this tenant. PERSONAL (ADR 0029, ADR 0139):
     *                        this record is the reply to the idempotent POST, and the declaration is what
     *                        keeps the stored reply encrypted -- the name heuristic does not know the word
     * @param validFrom       when that version took effect
     */
    public record LevelResponse(
            ScopeType scopeType,
            String outcome,
            @Nullable Long version,

            @Classified(value = DataClass.PERSONAL, reason = "a staff member's name, read from StaffDirectory")
            @Nullable
            String approvedByName,

            @Nullable Instant validFrom) {

        static LevelResponse of(OrderLatenessPolicyAuthoringService.Level level, Map<String, String> names) {
            ResolutionTrace.Provenance provenance = level.provenance();
            return new LevelResponse(
                    level.scopeType(),
                    level.authored() ? "VALUE" : "NOT_SET",
                    provenance == null ? null : provenance.version(),
                    provenance == null || provenance.principal() == null ? null : names.get(provenance.principal()),
                    provenance == null ? null : provenance.since());
        }
    }

    /**
     * @param isPlatformDefault      no document was authored anywhere in the chain
     * @param winningScope           the scope whose document is in force, null for the platform default
     * @param policyVersion          the version of the document in force (of {@code winningScope})
     * @param currentVersionAtScope  the latest version authored at exactly the scope asked about, 0
     *                               when it inherits: send it back as {@code expectedVersion}
     */
    public record EditorResponse(
            ModeResponse delivery,
            ModeResponse pickup,
            ModeResponse dineIn,
            AtRiskDefaultResponse atRiskDefault,
            NoPromiseDefaultResponse noPromiseDefault,
            boolean isPlatformDefault,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int currentVersionAtScope,
            List<LevelResponse> inspectedLevels) {

        static EditorResponse of(Editor editor, Map<String, String> names) {
            OrderLatenessDocument document = editor.document();
            return new EditorResponse(
                    ModeResponse.of(
                            document.forMode(FulfillmentMode.DELIVERY),
                            editor.effective().delivery()),
                    ModeResponse.of(
                            document.forMode(FulfillmentMode.PICKUP),
                            editor.effective().pickup()),
                    ModeResponse.of(
                            document.forMode(FulfillmentMode.DINE_IN),
                            editor.effective().dineIn()),
                    new AtRiskDefaultResponse(
                            editor.atRiskDefault().seconds(),
                            editor.atRiskDefault().source().name()),
                    new NoPromiseDefaultResponse(
                            editor.noPromiseDefault().seconds(),
                            editor.noPromiseDefault().source().name()),
                    editor.isPlatformDefault(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels().stream()
                            .map(level -> LevelResponse.of(level, names))
                            .toList());
        }
    }
}
