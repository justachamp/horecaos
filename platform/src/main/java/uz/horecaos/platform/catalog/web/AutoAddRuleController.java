package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.catalog.application.AutoAddRuleAuthoringService;
import uz.horecaos.platform.catalog.application.AutoAddRuleAuthoringService.RuleCommand;
import uz.horecaos.platform.catalog.application.AutoAddRuleAuthoringService.RuleDetail;
import uz.horecaos.platform.catalog.domain.AutoAddRules.AutoAddRule;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Band;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Kind;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Mode;
import uz.horecaos.platform.catalog.domain.AutoAddRules.Trigger;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Authoring for cart-injection rules (ADR 0158, gap-map row 4.9): plain, product-triggered and
 * portion-band rules that the cart evaluates, on the server, for every entry path that builds a cart.
 *
 * <p>Capabilities are the codes that already guard the sibling catalog resources: {@code
 * catalog.read} at brand scope for the reads, {@code catalog.author} for create, replace and
 * archive, and {@code catalog.publish} (on the publication endpoint, not here) for the republish that
 * makes a rule live. No new capability. Every mutating route requires an {@code Idempotency-Key}
 * through its capability declaration and a replace or an archive requires {@code If-Match} (ADR 0031);
 * each answer carries the new version as an {@code ETag}.
 *
 * <p>Mounted on the catalog authoring base path, under the control-plane document group (ADR 0057).
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog/auto-add-rules")
@Tag(name = "Auto-add rules", description = "Cart injection: packaging, cutlery and gift lines added by a rule")
public class AutoAddRuleController {

    private final AutoAddRuleAuthoringService authoring;
    private final CurrentActor currentActor;

    public AutoAddRuleController(AutoAddRuleAuthoringService authoring, CurrentActor currentActor) {
        this.authoring = authoring;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's auto-add rules, every status",
            description = "Draft, active and archived rules in display order. Only active rules reach "
                    + "customers, and only with the next publication.")
    public List<AutoAddRuleResponse> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return authoring.list(tenantId, brandId).stream()
                .map(AutoAddRuleResponse::of)
                .toList();
    }

    @PostMapping
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Create an auto-add rule as a draft",
            description = "Three kinds: PLAIN adds a fixed quantity of one variant whenever the order's "
                    + "context matches; PRODUCT_TRIGGERED adds a quantity of one variant per step of "
                    + "trigger units in the basket; PORTION_BAND totals the basket's portions and adds the "
                    + "variant and quantity of the band that holds the total. A rule may condition on the "
                    + "order's fulfilment mode, the channel's system type (cart-built channels only: an "
                    + "aggregator or POS order cannot carry a rule), optionally the location, and the "
                    + "basket's contents, and on nothing else. A rule is created as a draft and activated "
                    + "with a replace that says so. Refused 409 when the code is taken; 422 with "
                    + "findingCode AUTO_ADD_RULE_INVALID, AUTO_ADD_CHANNEL_NOT_ALLOWED or "
                    + "AUTO_ADD_BANDS_OVERLAP for a rule that cannot be stored.")
    public ResponseEntity<AutoAddRuleResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody AutoAddRuleCreateRequest request) {
        RuleDetail created =
                authoring.create(tenantId, brandId, request.code(), request.kind(), request.toCommand(), subject());
        return respond(created);
    }

    @GetMapping("/{ruleId}")
    @RequiresCapability(value = Capability.CATALOG_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One auto-add rule with its triggers, bands, locations and customer text")
    public ResponseEntity<AutoAddRuleResponse> read(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID ruleId) {
        return respond(authoring.get(tenantId, brandId, ruleId));
    }

    @PutMapping("/{ruleId}")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace a rule's fields and its trigger, band and location sets",
            description = "Requires If-Match carrying the rule's version; a stale version is refused with "
                    + "STALE_VERSION. The code and the kind are the rule's identity and are not editable. "
                    + "The three child sets are replaced as sets, in the same transaction as the version "
                    + "bump and the audit fact. status DRAFT or ACTIVE; an active rule reaches customers "
                    + "with the next publication. An archived rule is not edited.")
    public ResponseEntity<AutoAddRuleResponse> replace(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody AutoAddRuleReplaceRequest request,
            HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        return respond(authoring.replace(tenantId, brandId, ruleId, expected, request.toCommand(), subject()));
    }

    @PostMapping("/{ruleId}/archive")
    @RequiresCapability(value = Capability.CATALOG_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Archive a rule",
            description = "Requires If-Match. The rule leaves the next publication; the row stays so any "
                    + "published snapshot can be traced to it. Nothing is deleted.")
    public ResponseEntity<AutoAddRuleResponse> archive(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID ruleId,
            HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        return respond(authoring.archive(tenantId, brandId, ruleId, expected, subject()));
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private static ResponseEntity<AutoAddRuleResponse> respond(RuleDetail detail) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(detail.rule().version()))
                .body(AutoAddRuleResponse.of(detail));
    }

    // -------------------------------------------------------------------------- shapes

    /** One trigger node: exactly one of the three ids. */
    public record AutoAddTriggerBody(
            @Nullable UUID variantId,
            @Nullable UUID productId,
            @Nullable UUID categoryId) {}

    /** One portion band: {@code portionsFrom <= portions < portionsTo}, no upper bound when {@code portionsTo} is null. */
    public record AutoAddBandBody(
            @NotNull BigDecimal portionsFrom,
            @Nullable BigDecimal portionsTo,
            @NotNull UUID addVariantId,
            @NotNull BigDecimal quantity) {}

    /**
     * What the author states about a rule. Every optional field is boxed: Jackson 3 refuses a body that
     * omits a primitive, and the console omits what the author left alone.
     */
    public record AutoAddRuleCreateRequest(
            @NotBlank @Size(max = 64) String code,
            @NotNull Kind kind,
            @Nullable Mode mode,
            @Nullable UUID addVariantId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal triggerThreshold,
            @Nullable Boolean repeatPerMultiple,
            @Nullable BigDecimal minimumQuantity,
            @Nullable BigDecimal maximumQuantity,
            @Nullable Boolean mandatory,
            @NotNull @Size(min = 1, max = 3) List<String> fulfillmentModes,
            @NotNull @Size(min = 1, max = 9) List<String> channelSystemTypes,
            @Nullable Integer sortOrder,
            @Nullable @Size(max = 50) List<AutoAddTriggerBody> triggers,
            @Nullable @Size(max = 20) List<AutoAddBandBody> bands,
            @Nullable @Size(max = 200) List<UUID> locationIds,
            @Nullable Map<String, String> customerText) {

        RuleCommand toCommand() {
            return commandOf(
                    mode,
                    null,
                    addVariantId,
                    quantity,
                    triggerThreshold,
                    repeatPerMultiple,
                    minimumQuantity,
                    maximumQuantity,
                    mandatory,
                    fulfillmentModes,
                    channelSystemTypes,
                    sortOrder,
                    triggers,
                    bands,
                    locationIds,
                    customerText);
        }
    }

    /** A replace states the whole rule again; the code and the kind are not part of it. */
    public record AutoAddRuleReplaceRequest(
            @Nullable Mode mode,
            @Nullable @Pattern(regexp = "DRAFT|ACTIVE") String status,
            @Nullable UUID addVariantId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal triggerThreshold,
            @Nullable Boolean repeatPerMultiple,
            @Nullable BigDecimal minimumQuantity,
            @Nullable BigDecimal maximumQuantity,
            @Nullable Boolean mandatory,
            @NotNull @Size(min = 1, max = 3) List<String> fulfillmentModes,
            @NotNull @Size(min = 1, max = 9) List<String> channelSystemTypes,
            @Nullable Integer sortOrder,
            @Nullable @Size(max = 50) List<AutoAddTriggerBody> triggers,
            @Nullable @Size(max = 20) List<AutoAddBandBody> bands,
            @Nullable @Size(max = 200) List<UUID> locationIds,
            @Nullable Map<String, String> customerText) {

        RuleCommand toCommand() {
            return commandOf(
                    mode,
                    status == null ? null : Status.valueOf(status),
                    addVariantId,
                    quantity,
                    triggerThreshold,
                    repeatPerMultiple,
                    minimumQuantity,
                    maximumQuantity,
                    mandatory,
                    fulfillmentModes,
                    channelSystemTypes,
                    sortOrder,
                    triggers,
                    bands,
                    locationIds,
                    customerText);
        }
    }

    private static RuleCommand commandOf(
            @Nullable Mode mode,
            @Nullable Status status,
            @Nullable UUID addVariantId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal triggerThreshold,
            @Nullable Boolean repeatPerMultiple,
            @Nullable BigDecimal minimumQuantity,
            @Nullable BigDecimal maximumQuantity,
            @Nullable Boolean mandatory,
            List<String> fulfillmentModes,
            List<String> channelSystemTypes,
            @Nullable Integer sortOrder,
            @Nullable List<AutoAddTriggerBody> triggers,
            @Nullable List<AutoAddBandBody> bands,
            @Nullable List<UUID> locationIds,
            @Nullable Map<String, String> customerText) {
        List<Trigger> triggerValues = new ArrayList<>();
        if (triggers != null) {
            for (AutoAddTriggerBody trigger : triggers) {
                try {
                    triggerValues.add(new Trigger(trigger.variantId(), trigger.productId(), trigger.categoryId()));
                } catch (IllegalArgumentException wrongNodeCount) {
                    throw new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE,
                            wrongNodeCount.getMessage(),
                            Map.of("findingCode", AutoAddRuleAuthoringService.AUTO_ADD_RULE_INVALID));
                }
            }
        }
        List<Band> bandValues = new ArrayList<>();
        if (bands != null) {
            for (AutoAddBandBody band : bands) {
                bandValues.add(new Band(band.portionsFrom(), band.portionsTo(), band.addVariantId(), band.quantity()));
            }
        }
        return new RuleCommand(
                mode == null ? Mode.INJECT : mode,
                status,
                addVariantId,
                quantity,
                triggerThreshold,
                Boolean.TRUE.equals(repeatPerMultiple),
                minimumQuantity == null ? BigDecimal.ONE : minimumQuantity,
                maximumQuantity == null ? new BigDecimal("20") : maximumQuantity,
                Boolean.TRUE.equals(mandatory),
                new LinkedHashSet<>(fulfillmentModes),
                new LinkedHashSet<>(channelSystemTypes),
                sortOrder == null ? 0 : sortOrder,
                triggerValues,
                bandValues,
                locationIds == null ? Set.of() : new LinkedHashSet<>(locationIds),
                customerText == null ? Map.of() : customerText);
    }

    /** A rule as the console reads it. */
    public record AutoAddRuleResponse(
            UUID ruleId,
            String code,
            String kind,
            String mode,
            String status,
            @Nullable UUID addVariantId,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal triggerThreshold,
            boolean repeatPerMultiple,
            BigDecimal minimumQuantity,
            BigDecimal maximumQuantity,
            boolean mandatory,
            List<String> fulfillmentModes,
            List<String> channelSystemTypes,
            int sortOrder,
            int version,
            List<AutoAddTriggerBody> triggers,
            List<AutoAddBandBody> bands,
            List<UUID> locationIds,
            Map<String, String> customerText) {

        static AutoAddRuleResponse of(RuleDetail detail) {
            AutoAddRule rule = detail.rule();
            return new AutoAddRuleResponse(
                    rule.id(),
                    rule.code(),
                    rule.kind().name(),
                    rule.mode().name(),
                    rule.status().name(),
                    rule.addVariantId(),
                    rule.quantity(),
                    rule.triggerThreshold(),
                    rule.repeatPerMultiple(),
                    rule.minimumQuantity(),
                    rule.maximumQuantity(),
                    rule.mandatory(),
                    rule.fulfillmentModes().stream().sorted().toList(),
                    rule.channelSystemTypes().stream().sorted().toList(),
                    rule.sortOrder(),
                    rule.version(),
                    rule.triggers().stream()
                            .map(trigger -> new AutoAddTriggerBody(
                                    trigger.variantId(), trigger.productId(), trigger.categoryId()))
                            .toList(),
                    rule.bands().stream()
                            .map(band -> new AutoAddBandBody(
                                    band.portionsFrom(), band.portionsTo(), band.addVariantId(), band.quantity()))
                            .toList(),
                    rule.locationIds().stream().sorted().toList(),
                    detail.customerText());
        }
    }
}
