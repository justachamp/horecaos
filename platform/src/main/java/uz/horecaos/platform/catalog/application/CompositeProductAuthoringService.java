package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.ModifierGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ModifierAttachment;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCompositeCatalogStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring for composite products (ADR 0136): combo groups and their components,
 * and the visibility and overrides a modifier attachment may carry.
 *
 * <p>Like every catalog write this edits the draft. Nothing reaches a customer
 * until {@link CatalogPublicationService} takes a snapshot, so an operator can
 * restructure a combo in the middle of service.
 *
 * <p>The constraints the record names are enforced here where the service can name
 * the offender in a refusal, and again by the schema where it can: the range
 * checks and the no-nesting triggers are the backstop, not the first line, because
 * a raw constraint violation reaches a client as a bare 409 with no way to tell
 * which rule it broke. Rules that read another row (a hidden group has exactly one
 * active option) are deliberately not refused at write time -- the group is built
 * up in steps, and the record places them at publication.
 *
 * <p>Every mutation is audited in the same transaction (ADR 0027).
 */
@Service
public class CompositeProductAuthoringService {

    /** Stable codes a client branches on, carried as the {@code findingCode} property of a 422. */
    public static final String COMBO_NESTING_FORBIDDEN = "COMBO_NESTING_FORBIDDEN";

    public static final String COMBO_GROUP_RANGE_INVALID = "COMBO_GROUP_RANGE_INVALID";
    public static final String COMBO_COMPONENT_VARIANT_NOT_ACTIVE = "COMBO_COMPONENT_VARIANT_NOT_ACTIVE";
    public static final String COMBO_COMPONENT_ALREADY_IN_GROUP = "COMBO_COMPONENT_ALREADY_IN_GROUP";
    public static final String MODIFIER_ATTACHMENT_POLICY_INVALID = "MODIFIER_ATTACHMENT_POLICY_INVALID";

    private final JdbcCompositeCatalogStore store;
    private final JdbcCatalogStore catalogStore;
    private final AuditRecorder audit;
    private final Clock clock;

    public CompositeProductAuthoringService(
            JdbcCompositeCatalogStore store, JdbcCatalogStore catalogStore, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.catalogStore = catalogStore;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------- combo groups

    /**
     * Creates a combo group on a container variant.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the variant is not this
     *         brand's or is archived; {@code UNPROCESSABLE_STATE} with {@link
     *         #COMBO_NESTING_FORBIDDEN} when the variant is already a component of
     *         another combo, or {@link #COMBO_GROUP_RANGE_INVALID} when the range
     *         cannot be completed
     */
    @Transactional
    public ComboGroup createComboGroup(NewComboGroup command, String actorSubject) {
        requireRange(command.minimumSelections(), command.maximumSelections());

        Status containerStatus = store.variantStatuses(
                        command.tenantId(), command.brandId(), Set.of(command.containerVariantId()))
                .get(command.containerVariantId());
        if (containerStatus == null || containerStatus == Status.ARCHIVED) {
            throw notFound("variant", command.containerVariantId());
        }
        if (store.isComboComponent(command.tenantId(), command.brandId(), command.containerVariantId())) {
            throw nesting("Variant %s is already a component of a combo and cannot itself be a combo container"
                    .formatted(command.containerVariantId()));
        }

        ComboGroup group = new ComboGroup(
                Ids.newId(),
                command.tenantId(),
                command.brandId(),
                command.containerVariantId(),
                command.code(),
                command.minimumSelections(),
                command.maximumSelections(),
                command.allowSameComponentMultipleTimes(),
                command.sortOrder(),
                Status.ACTIVE,
                1);
        store.insertComboGroup(group);
        // The heading the customer reads. Names live in translations, never on the
        // entity, so a missing name blocks publication rather than letting a code
        // reach a screen.
        catalogStore.upsertTranslation(
                command.tenantId(),
                command.brandId(),
                EntityType.COMBO_GROUP,
                group.id(),
                command.locale(),
                command.name(),
                null);

        audit.record(AuditFact.of("catalog.comboGroup.created", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(group.tenantId(), group.brandId()))
                .target("ComboGroup", group.id())
                .targetVersion((long) group.version())
                .because("Created a combo group")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.created(groupFields(group)))
                .correlatedBy(group.id().toString())
                .occurredAt(clock.instant())
                .build());
        return group;
    }

    /**
     * Replaces a group's mutable fields. The code and the container are the
     * group's identity and are not editable: moving a group to another container
     * would silently reprice every order line that already names it.
     */
    @Transactional
    public ComboGroup updateComboGroup(
            UUID tenantId,
            UUID brandId,
            UUID comboGroupId,
            int expectedVersion,
            ComboGroupChanges changes,
            String actorSubject) {
        requireRange(changes.minimumSelections(), changes.maximumSelections());
        ComboGroup before = store.comboGroup(tenantId, brandId, comboGroupId)
                .orElseThrow(() -> notFound("combo group", comboGroupId));

        ComboGroup after = new ComboGroup(
                before.id(),
                before.tenantId(),
                before.brandId(),
                before.containerVariantId(),
                before.code(),
                changes.minimumSelections(),
                changes.maximumSelections(),
                changes.allowSameComponentMultipleTimes(),
                changes.sortOrder(),
                changes.status(),
                before.version() + 1);
        if (!store.updateComboGroup(after, expectedVersion)) {
            throw ApiException.staleVersion(expectedVersion, currentGroupVersion(tenantId, brandId, comboGroupId));
        }

        audit.record(AuditFact.of("catalog.comboGroup.updated", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ComboGroup", comboGroupId)
                .targetVersion((long) after.version())
                .because("Changed a combo group")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(groupFields(before), groupFields(after)))
                .correlatedBy(comboGroupId.toString())
                .occurredAt(clock.instant())
                .build());
        return after;
    }

    @Transactional(readOnly = true)
    public ComboGroupDetail comboGroup(UUID tenantId, UUID brandId, UUID comboGroupId) {
        ComboGroup group = store.comboGroup(tenantId, brandId, comboGroupId)
                .orElseThrow(() -> notFound("combo group", comboGroupId));
        return detail(tenantId, brandId, List.of(group)).get(0);
    }

    /** Every group a container variant offers, with its components. */
    @Transactional(readOnly = true)
    public List<ComboGroupDetail> comboGroupsOf(UUID tenantId, UUID brandId, UUID containerVariantId) {
        return detail(tenantId, brandId, store.comboGroupsForContainer(tenantId, brandId, containerVariantId));
    }

    private List<ComboGroupDetail> detail(UUID tenantId, UUID brandId, List<ComboGroup> groups) {
        Map<UUID, List<ComboComponent>> components =
                store
                        .componentsForGroups(
                                tenantId,
                                brandId,
                                groups.stream().map(ComboGroup::id).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                ComboComponent::comboGroupId, LinkedHashMap::new, Collectors.toList()));
        return groups.stream()
                .map(group -> new ComboGroupDetail(group, components.getOrDefault(group.id(), List.of())))
                .toList();
    }

    // --------------------------------------------------------------- components

    /**
     * Adds a real variant to a combo group.
     *
     * @throws ApiException {@code UNPROCESSABLE_STATE} with {@link
     *         #COMBO_NESTING_FORBIDDEN} when the variant is itself a combo
     *         container, {@link #COMBO_COMPONENT_VARIANT_NOT_ACTIVE} when it is
     *         archived, or {@link #COMBO_COMPONENT_ALREADY_IN_GROUP}
     */
    @Transactional
    public ComboComponent addComponent(
            UUID tenantId,
            UUID brandId,
            UUID comboGroupId,
            UUID componentVariantId,
            int defaultQuantity,
            int sortOrder,
            String actorSubject) {
        requireQuantity(defaultQuantity);
        ComboGroup group = store.comboGroup(tenantId, brandId, comboGroupId)
                .orElseThrow(() -> notFound("combo group", comboGroupId));

        Status variantStatus = store.variantStatuses(tenantId, brandId, Set.of(componentVariantId))
                .get(componentVariantId);
        if (variantStatus == null) {
            throw notFound("variant", componentVariantId);
        }
        if (variantStatus == Status.ARCHIVED) {
            throw refused(
                    COMBO_COMPONENT_VARIANT_NOT_ACTIVE,
                    "Variant %s is archived and cannot be offered in a combo".formatted(componentVariantId));
        }
        if (store.isComboContainer(tenantId, brandId, componentVariantId)) {
            throw nesting("Variant %s is a combo container and cannot be a component of another combo"
                    .formatted(componentVariantId));
        }
        boolean alreadyThere = store.componentsForGroups(tenantId, brandId, List.of(group.id())).stream()
                .anyMatch(existing -> existing.componentVariantId().equals(componentVariantId));
        if (alreadyThere) {
            throw refused(
                    COMBO_COMPONENT_ALREADY_IN_GROUP,
                    "Variant %s is already offered in this combo group".formatted(componentVariantId));
        }

        ComboComponent component = new ComboComponent(
                Ids.newId(),
                tenantId,
                brandId,
                group.id(),
                componentVariantId,
                defaultQuantity,
                sortOrder,
                Status.ACTIVE,
                1);
        store.insertComboComponent(component);

        audit.record(AuditFact.of("catalog.comboComponent.added", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ComboComponent", component.id())
                .targetVersion((long) component.version())
                .because("Added a component to a combo group")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.created(componentFields(component)))
                .correlatedBy(group.id().toString())
                .occurredAt(clock.instant())
                .build());
        return component;
    }

    @Transactional
    public ComboComponent updateComponent(
            UUID tenantId,
            UUID brandId,
            UUID componentId,
            int expectedVersion,
            ComboComponentChanges changes,
            String actorSubject) {
        requireQuantity(changes.defaultQuantity());
        ComboComponent before = store.comboComponent(tenantId, brandId, componentId)
                .orElseThrow(() -> notFound("combo component", componentId));

        ComboComponent after = new ComboComponent(
                before.id(),
                before.tenantId(),
                before.brandId(),
                before.comboGroupId(),
                before.componentVariantId(),
                changes.defaultQuantity(),
                changes.sortOrder(),
                changes.status(),
                before.version() + 1);
        if (!store.updateComboComponent(after, expectedVersion)) {
            int current = store.comboComponent(tenantId, brandId, componentId)
                    .map(ComboComponent::version)
                    .orElse(0);
            throw ApiException.staleVersion(expectedVersion, current);
        }

        audit.record(AuditFact.of("catalog.comboComponent.updated", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ComboComponent", componentId)
                .targetVersion((long) after.version())
                .because("Changed a combo component")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(componentFields(before), componentFields(after)))
                .correlatedBy(before.comboGroupId().toString())
                .occurredAt(clock.instant())
                .build());
        return after;
    }

    // ---------------------------------------------------- modifier attachments

    /**
     * Attaches a modifier group to a variant (ADR 0136's nested variant-modifiers
     * need a variant to carry groups of its own; {@code variant_modifier_groups}
     * existed from V0016 with no writer).
     */
    @Transactional
    public ModifierAttachment attachModifierGroupToVariant(
            UUID tenantId, UUID brandId, UUID variantId, UUID modifierGroupId, int sortOrder, String actorSubject) {
        if (!catalogStore.entityExistsInBrand(tenantId, brandId, EntityType.VARIANT, variantId)) {
            throw notFound("variant", variantId);
        }
        if (catalogStore.modifierGroupById(tenantId, brandId, modifierGroupId).isEmpty()) {
            throw notFound("modifier group", modifierGroupId);
        }
        Optional<ModifierAttachment> existing =
                store.attachment(tenantId, brandId, AttachmentOwnerType.VARIANT, variantId, modifierGroupId);
        store.attachModifierGroupToVariant(tenantId, brandId, variantId, modifierGroupId, sortOrder);
        ModifierAttachment attached = store.attachment(
                        tenantId, brandId, AttachmentOwnerType.VARIANT, variantId, modifierGroupId)
                .orElseThrow(() -> new IllegalStateException("Attachment vanished mid-transaction"));

        Map<String, Object> beforeFields =
                existing.isPresent() ? Map.of("sortOrder", existing.get().sortOrder()) : Map.of();
        audit.record(AuditFact.of("catalog.variantModifierGroup.attached", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Variant", variantId)
                .because("Attached a modifier group to a variant")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(
                        beforeFields, Map.of("modifierGroupId", modifierGroupId.toString(), "sortOrder", sortOrder)))
                .correlatedBy(variantId.toString())
                .occurredAt(clock.instant())
                .build());
        return attached;
    }

    /**
     * Sets an attachment's visibility, fulfilment modes and overrides.
     *
     * <p>Replaces all five at once (a PUT): a client that wants to change one sends
     * the others as it read them, which is also what makes {@code expectedVersion}
     * meaningful. A null override means "use the group's own value" -- the
     * fallback -- so clearing an override is sending null, not deleting a row.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the group is not
     *         attached to the owner; {@code UNPROCESSABLE_STATE} with {@link
     *         #MODIFIER_ATTACHMENT_POLICY_INVALID} when the policy contradicts
     *         itself or the shared group's values
     */
    @Transactional
    public ModifierAttachment setAttachmentPolicy(
            UUID tenantId,
            UUID brandId,
            AttachmentOwnerType ownerType,
            UUID ownerId,
            UUID modifierGroupId,
            int expectedVersion,
            AttachmentPolicy policy,
            String actorSubject) {
        ModifierAttachment before = store.attachment(tenantId, brandId, ownerType, ownerId, modifierGroupId)
                .orElseThrow(() -> notFound("modifier group attachment", modifierGroupId));
        ModifierGroup group = catalogStore
                .modifierGroupById(tenantId, brandId, modifierGroupId)
                .orElseThrow(() -> notFound("modifier group", modifierGroupId));

        ModifierAttachment after = new ModifierAttachment(
                tenantId,
                brandId,
                ownerType,
                ownerId,
                modifierGroupId,
                before.sortOrder(),
                policy.visibility(),
                policy.modes(),
                policy.requiredOverride(),
                policy.minimumOverride(),
                policy.maximumOverride(),
                before.version() + 1);
        requirePolicyConsistent(after, group);

        if (!store.updateAttachmentPolicy(after, expectedVersion)) {
            int current = store.attachment(tenantId, brandId, ownerType, ownerId, modifierGroupId)
                    .map(ModifierAttachment::version)
                    .orElse(0);
            throw ApiException.staleVersion(expectedVersion, current);
        }

        audit.record(AuditFact.of("catalog.modifierAttachment.policySet", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target(ownerType == AttachmentOwnerType.PRODUCT ? "Product" : "Variant", ownerId)
                .because("Changed how a modifier group is offered")
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(attachmentFields(before), attachmentFields(after)))
                .correlatedBy(ownerId.toString())
                .occurredAt(clock.instant())
                .build());
        return after;
    }

    @Transactional(readOnly = true)
    public List<ModifierAttachment> attachmentsOf(
            UUID tenantId, UUID brandId, AttachmentOwnerType ownerType, UUID ownerId) {
        return store.attachmentsOf(tenantId, brandId, ownerType, ownerId);
    }

    // -------------------------------------------------------------- the rules

    private static void requireRange(int minimum, int maximum) {
        if (minimum < 0 || maximum < 1 || minimum > maximum) {
            throw refused(
                    COMBO_GROUP_RANGE_INVALID,
                    "A combo group needs a minimum of at least 0, a maximum of at least 1, and a minimum "
                            + "that does not exceed its maximum");
        }
    }

    private static void requireQuantity(int quantity) {
        if (quantity < 1 || quantity > 100) {
            throw new IllegalArgumentException("A component's default quantity must be between 1 and 100");
        }
    }

    /**
     * The attachment, as it would be after this write, against the shared group.
     *
     * <p>The schema checks each row on its own; only here is the group in sight.
     * The same arithmetic runs again in {@link CatalogValidator}, because the
     * group can change after an override was written.
     */
    private static void requirePolicyConsistent(ModifierAttachment attachment, ModifierGroup group) {
        if (attachment.modes() != null && !attachment.hidden()) {
            throw refused(
                    MODIFIER_ATTACHMENT_POLICY_INVALID,
                    "Fulfilment modes apply only to a hidden auto-selected group; a visible group is offered in "
                            + "every mode");
        }
        if (attachment.modes() != null && attachment.modes().isEmpty()) {
            throw refused(
                    MODIFIER_ATTACHMENT_POLICY_INVALID,
                    "An empty set of fulfilment modes would never apply; send null for every mode");
        }
        if (attachment.hidden() && (attachment.minimumOverride() != null || attachment.maximumOverride() != null)) {
            throw refused(
                    MODIFIER_ATTACHMENT_POLICY_INVALID,
                    "A hidden group is always exactly one auto-selected option, so a selection range on it "
                            + "would be a number nothing reads");
        }
        String problem = attachment.rangeProblem(group);
        if (problem != null) {
            throw refused(MODIFIER_ATTACHMENT_POLICY_INVALID, problem);
        }
    }

    private int currentGroupVersion(UUID tenantId, UUID brandId, UUID comboGroupId) {
        return store.comboGroup(tenantId, brandId, comboGroupId)
                .map(ComboGroup::version)
                .orElse(0);
    }

    private static Map<String, Object> groupFields(ComboGroup group) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", group.code());
        fields.put("containerVariantId", group.containerVariantId().toString());
        fields.put("minimumSelections", group.minimumSelections());
        fields.put("maximumSelections", group.maximumSelections());
        fields.put("allowSameComponentMultipleTimes", group.allowSameComponentMultipleTimes());
        fields.put("sortOrder", group.sortOrder());
        fields.put("status", group.status().name());
        return fields;
    }

    private static Map<String, Object> componentFields(ComboComponent component) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("comboGroupId", component.comboGroupId().toString());
        fields.put("componentVariantId", component.componentVariantId().toString());
        fields.put("defaultQuantity", component.defaultQuantity());
        fields.put("sortOrder", component.sortOrder());
        fields.put("status", component.status().name());
        return fields;
    }

    private static Map<String, Object> attachmentFields(ModifierAttachment attachment) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("modifierGroupId", attachment.modifierGroupId().toString());
        fields.put("visibility", attachment.visibility().name());
        fields.put(
                "applicableFulfillmentModes",
                attachment.modes() == null
                        ? null
                        : attachment.modes().stream().map(Enum::name).sorted().toList());
        fields.put("requiredOverride", attachment.requiredOverride());
        fields.put("minimumSelectionsOverride", attachment.minimumOverride());
        fields.put("maximumSelectionsOverride", attachment.maximumOverride());
        return fields;
    }

    private static ApiException notFound(String what, UUID id) {
        // One answer for "not yours" and "does not exist": a caller able to tell
        // them apart has an existence oracle for catalog ids.
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No %s %s in this brand".formatted(what, id));
    }

    private static ApiException nesting(String detail) {
        return refused(COMBO_NESTING_FORBIDDEN, detail);
    }

    private static ApiException refused(String findingCode, String detail) {
        return new ApiException(ErrorCode.UNPROCESSABLE_STATE, detail, Map.of("findingCode", findingCode));
    }

    // ------------------------------------------------------------------ shapes

    public record NewComboGroup(
            UUID tenantId,
            UUID brandId,
            UUID containerVariantId,
            String code,
            String name,
            String locale,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameComponentMultipleTimes,
            int sortOrder) {}

    public record ComboGroupChanges(
            int minimumSelections,
            int maximumSelections,
            boolean allowSameComponentMultipleTimes,
            int sortOrder,
            Status status) {}

    public record ComboComponentChanges(int defaultQuantity, int sortOrder, Status status) {}

    /**
     * @param modes null means every fulfilment mode
     */
    public record AttachmentPolicy(
            Visibility visibility,
            @Nullable Set<FulfillmentMode> modes,
            @Nullable Boolean requiredOverride,
            @Nullable Integer minimumOverride,
            @Nullable Integer maximumOverride) {}

    public record ComboGroupDetail(ComboGroup group, List<ComboComponent> components) {}
}
