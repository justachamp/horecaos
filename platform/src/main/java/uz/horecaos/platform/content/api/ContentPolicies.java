package uz.horecaos.platform.content.api;

import java.util.Set;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;

/**
 * The content module's ADR 0030 policy documents (ADR 0157): both brand-scoped, both version 1.
 *
 * <p>Published through ADR 0030's {@code PolicyAuthor}, which audits and evicts its own cache.
 */
public final class ContentPolicies {

    /**
     * Tightening only. A brand may make completeness mandatory; it cannot loosen the gates.
     *
     * <p>There is deliberately no {@code requireApproval} field in Release 1: a field that parses and
     * does nothing is worse than no field, so a four-eyes path (ADR 0027's approval service,
     * {@code content.publish} as the approving capability, a different principal as approver) is
     * added with the first tenant that asks for it. Nothing a banner does spends money or sends a
     * message.
     */
    public static final PolicyKey<PublishingPolicy> PUBLISHING = new PolicyKey<>(
            "content.publishing",
            PublishingPolicy.class,
            Set.of(ScopeType.BRAND),
            "content",
            false,
            "Whether publishing needs text in every one of the brand's languages and not only its default.");

    /**
     * Which editorial kinds the brand has chosen to run. Effective only with the
     * {@code content.editorial.enabled} entitlement: both must say yes.
     */
    public static final PolicyKey<EditorialPolicy> EDITORIAL = new PolicyKey<>(
            "content.editorial",
            EditorialPolicy.class,
            Set.of(ScopeType.BRAND),
            "content",
            false,
            "Which editorial kinds (news, gallery, recipes, vacancies) the brand runs. All off by default.");

    /**
     * How the brand's storefront lays out its category navigation (ADR 0157 Release 4, ADR 0183 D17): {@code STANDARD}, or
     * {@code MARKETPLACE} (a compact category strip and an all-categories overlay). Brand-scoped, default {@code STANDARD},
     * carried on the storefront's existing content read. Dark: no brand has it set until a tenant that runs a marketplace
     * asks, and a {@code STANDARD} brand is served exactly the document it was served before.
     */
    public static final PolicyKey<StorefrontLayoutPolicy> STOREFRONT_LAYOUT = new PolicyKey<>(
            "storefront.layout",
            StorefrontLayoutPolicy.class,
            Set.of(ScopeType.BRAND),
            "content",
            false,
            "The storefront's category layout: STANDARD (today's) or MARKETPLACE (compact navigation and an "
                    + "all-categories overlay). STANDARD by default.");

    private ContentPolicies() {}

    /**
     * {@code storefront.layout} v1.
     *
     * @param layout {@code STANDARD} or {@code MARKETPLACE}; anything else, and null, is read as {@code STANDARD}, so a
     *               document a future release writes can never break today's storefront
     */
    public record StorefrontLayoutPolicy(
            @org.jspecify.annotations.Nullable String layout) {

        public static final String STANDARD = "STANDARD";
        public static final String MARKETPLACE = "MARKETPLACE";

        public static StorefrontLayoutPolicy platformDefault() {
            return new StorefrontLayoutPolicy(STANDARD);
        }

        public boolean isMarketplace() {
            return MARKETPLACE.equals(layout);
        }

        /** The layout this document means: {@code MARKETPLACE} exactly, else {@code STANDARD}. */
        public String effective() {
            return isMarketplace() ? MARKETPLACE : STANDARD;
        }

        public boolean isValid() {
            return STANDARD.equals(layout) || MARKETPLACE.equals(layout);
        }
    }

    /**
     * {@code content.publishing} v1.
     *
     * @param requireAllBrandLocales boxed, with a stated default of false, because a missing
     *                               primitive in a request body is a 400 under Jackson 3
     */
    public record PublishingPolicy(
            @org.jspecify.annotations.Nullable Boolean requireAllBrandLocales) {

        public static PublishingPolicy platformDefault() {
            return new PublishingPolicy(Boolean.FALSE);
        }

        public boolean allBrandLocalesRequired() {
            return Boolean.TRUE.equals(requireAllBrandLocales);
        }
    }

    /** {@code content.editorial} v1: all four kinds off until the tenant turns one on. */
    public record EditorialPolicy(
            @org.jspecify.annotations.Nullable Boolean news,
            @org.jspecify.annotations.Nullable Boolean gallery,
            @org.jspecify.annotations.Nullable Boolean recipes,
            @org.jspecify.annotations.Nullable Boolean vacancies) {

        public static EditorialPolicy platformDefault() {
            return new EditorialPolicy(Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
        }

        public boolean newsOn() {
            return Boolean.TRUE.equals(news);
        }

        public boolean galleryOn() {
            return Boolean.TRUE.equals(gallery);
        }

        public boolean recipesOn() {
            return Boolean.TRUE.equals(recipes);
        }

        public boolean vacanciesOn() {
            return Boolean.TRUE.equals(vacancies);
        }
    }
}
