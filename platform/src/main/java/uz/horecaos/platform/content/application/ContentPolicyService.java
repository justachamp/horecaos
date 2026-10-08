package uz.horecaos.platform.content.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.content.api.ContentPolicies;
import uz.horecaos.platform.content.api.ContentPolicies.EditorialPolicy;
import uz.horecaos.platform.content.api.ContentPolicies.PublishingPolicy;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The brand's two content policies (ADR 0157, Entitlements, policy and configuration keys), over
 * ADR 0030's author, which audits each publication and evicts its own cache.
 *
 * <p>Both are brand-scoped documents at version 1. {@code content.publishing} tightens only (it can
 * demand text in every one of the brand's languages and cannot loosen a gate); {@code
 * content.editorial} is the tenant's choice of which of the four editorial kinds to run, effective
 * only with the {@code content.editorial.enabled} entitlement -- both must say yes.
 */
@Service
public class ContentPolicyService {

    private final ContentEnvironment environment;
    private final PolicyAuthor author;

    public ContentPolicyService(ContentEnvironment environment, PolicyAuthor author) {
        this.environment = environment;
        this.author = author;
    }

    /**
     * The policies in force, the version each save is checked against, and whether the plan lets the
     * editorial tier exist at all.
     */
    public record Policies(
            PublishingPolicy publishing,
            int publishingVersion,
            EditorialPolicy editorial,
            int editorialVersion,
            boolean editorialEntitled,
            boolean videoEntitled) {}

    @Transactional(readOnly = true)
    public Policies get(UUID tenantId, UUID brandId) {
        ResourceScope scope = ResourceScope.brand(tenantId, brandId);
        return new Policies(
                environment.publishingPolicy(tenantId, brandId),
                author.currentVersion(ContentPolicies.PUBLISHING, scope),
                environment.editorialPolicy(tenantId, brandId),
                author.currentVersion(ContentPolicies.EDITORIAL, scope),
                environment.editorialEntitled(tenantId),
                environment.videoEntitled(tenantId));
    }

    @Transactional
    public Policies setPublishing(
            UUID tenantId, UUID brandId, String actor, int expectedVersion, PublishingPolicy document, String reason) {
        requireBrand(tenantId, brandId);
        author.author(
                ContentPolicies.PUBLISHING,
                ResourceScope.brand(tenantId, brandId),
                new PublishingPolicy(document.allBrandLocalesRequired()),
                expectedVersion,
                ActorRef.user(actor, null),
                reason);
        return get(tenantId, brandId);
    }

    @Transactional
    public Policies setEditorial(
            UUID tenantId, UUID brandId, String actor, int expectedVersion, EditorialPolicy document, String reason) {
        requireBrand(tenantId, brandId);
        author.author(
                ContentPolicies.EDITORIAL,
                ResourceScope.brand(tenantId, brandId),
                new EditorialPolicy(
                        document.newsOn(), document.galleryOn(), document.recipesOn(), document.vacanciesOn()),
                expectedVersion,
                ActorRef.user(actor, null),
                reason);
        return get(tenantId, brandId);
    }

    private void requireBrand(UUID tenantId, UUID brandId) {
        if (!environment.brand().brandExists(tenantId, brandId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand");
        }
    }
}
