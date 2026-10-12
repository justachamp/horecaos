package uz.horecaos.platform.content.application;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.content.api.ContentConfigurationKeys;
import uz.horecaos.platform.content.api.ContentEntitlements;
import uz.horecaos.platform.content.api.ContentPolicies;
import uz.horecaos.platform.content.api.ContentPolicies.EditorialPolicy;
import uz.horecaos.platform.content.api.ContentPolicies.PublishingPolicy;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandContentFacts;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.api.ApiException;

/**
 * What content asks the rest of the platform, in one place (ADR 0157).
 *
 * <p>Brand languages, the brand's two policies, the plan's two entitlements, the tenant's channels,
 * the brand's effective link allowlist and the video cap. Each is a port of another module; nothing
 * here reads another module's tables. Kept apart from the gates and the assembler so that both ask
 * the same questions the same way, and a test can answer them without a Spring context.
 */
@Component
public class ContentEnvironment {

    /**
     * The channel types that have a surface for brand content (ADR 0157, Decision 3). A {@code QR_TABLE}
     * channel shows the menu only, and {@code KIOSK}, {@code AGGREGATOR}, {@code POS} and
     * {@code CALL_CENTRE} are refused as targets until a decision gives them a surface.
     */
    public static final Set<SalesChannelSystemType> CONTENT_SURFACES = Set.of(
            SalesChannelSystemType.WEB,
            SalesChannelSystemType.IOS,
            SalesChannelSystemType.ANDROID,
            SalesChannelSystemType.TELEGRAM);

    private final BrandLocaleLookup brandLocales;
    private final PolicyResolver policies;
    private final SalesChannelLookup channels;
    private final BrandContentFacts brandFacts;
    private final List<EntitlementGate> entitlements;
    private final ConfigurationResolver configuration;
    private final JdbcContentStore store;

    public ContentEnvironment(
            BrandLocaleLookup brandLocales,
            PolicyResolver policies,
            SalesChannelLookup channels,
            BrandContentFacts brandFacts,
            List<EntitlementGate> entitlements,
            ConfigurationResolver configuration,
            JdbcContentStore store) {
        this.brandLocales = brandLocales;
        this.policies = policies;
        this.channels = channels;
        this.brandFacts = brandFacts;
        this.entitlements = entitlements;
        this.configuration = configuration;
        this.store = store;
    }

    /** The brand's languages and its default: what "every locale this brand serves" means. */
    public TenantLocaleSet locales(UUID tenantId, UUID brandId) {
        return brandLocales.brandLocaleSet(tenantId, brandId);
    }

    public PublishingPolicy publishingPolicy(UUID tenantId, UUID brandId) {
        return policies.resolve(ContentPolicies.PUBLISHING, ResourceScope.brand(tenantId, brandId))
                .map(resolved -> resolved.document())
                .orElseGet(PublishingPolicy::platformDefault);
    }

    public EditorialPolicy editorialPolicy(UUID tenantId, UUID brandId) {
        return policies.resolve(ContentPolicies.EDITORIAL, ResourceScope.brand(tenantId, brandId))
                .map(resolved -> resolved.document())
                .orElseGet(EditorialPolicy::platformDefault);
    }

    /**
     * Whether the tenant's plan grants a feature. Asked of {@code checkGranted} and not
     * {@code checkFeature}: the latter answers "should the platform act on this", which is true for
     * an ungranted feature under the meter-only default, and these two switches are off until the
     * platform owner turns them on. An empty answer from every gate (a key the catalogue does not
     * know, or no gate at all) is read as <em>not entitled</em>.
     */
    public boolean entitled(UUID tenantId, String entitlementKey) {
        for (EntitlementGate gate : entitlements) {
            Optional<EntitlementGate.Answer> answer = gate.checkGranted(tenantId, entitlementKey);
            if (answer.isPresent()) {
                return answer.get().entitled();
            }
        }
        return false;
    }

    /** @throws ApiException {@code ENTITLEMENT_REQUIRED} (403) when the plan does not include the feature */
    public void requireEntitled(UUID tenantId, String entitlementKey) {
        if (!entitled(tenantId, entitlementKey)) {
            throw ApiException.entitlementRequired(entitlementKey);
        }
    }

    public boolean editorialEntitled(UUID tenantId) {
        return entitled(tenantId, ContentEntitlements.EDITORIAL_ENABLED);
    }

    public boolean videoEntitled(UUID tenantId) {
        return entitled(tenantId, ContentEntitlements.VIDEO_ENABLED);
    }

    /** The tenant's channels that are not archived and have a content surface, by code. */
    public Map<String, SalesChannel> contentChannels(UUID tenantId) {
        Map<String, SalesChannel> byCode = new LinkedHashMap<>();
        for (SalesChannel channel : channels.activeChannels(tenantId)) {
            if (CONTENT_SURFACES.contains(channel.systemType())) {
                byCode.put(channel.code(), channel);
            }
        }
        return byCode;
    }

    /** Any non-archived channel of the tenant by code, content surface or not. */
    public Optional<SalesChannel> channel(UUID tenantId, String code) {
        return channels.byCode(tenantId, code).filter(c -> c.status() != SalesChannel.Status.ARCHIVED);
    }

    /**
     * The hosts a link may point at for this brand: what the author added, plus the tenant's
     * <em>verified</em> channel hostnames, read and never copied, so an unverified or released
     * hostname cannot be linked to and a released one stops being linked on the next read.
     */
    public Set<String> effectiveAllowlist(UUID tenantId, UUID brandId) {
        Set<String> hosts = new HashSet<>();
        store.allowedHosts(tenantId, brandId).forEach(row -> hosts.add(row.host()));
        hosts.addAll(brandFacts.verifiedChannelHostnames(tenantId));
        return hosts;
    }

    public int videoCap(UUID tenantId, UUID brandId) {
        Integer cap = configuration.value(
                ContentConfigurationKeys.VIDEO_MAX_LIVE_PER_BRAND, ResourceScope.brand(tenantId, brandId));
        return cap == null ? ContentConfigurationKeys.DEFAULT_VIDEO_MAX_LIVE_PER_BRAND : cap;
    }

    public int revisionMediaRetentionDays(UUID tenantId) {
        Integer days = configuration.value(
                ContentConfigurationKeys.REVISION_MEDIA_RETENTION_DAYS, ResourceScope.tenant(tenantId));
        return days == null ? ContentConfigurationKeys.DEFAULT_REVISION_MEDIA_RETENTION_DAYS : days;
    }

    public BrandContentFacts brand() {
        return brandFacts;
    }
}
