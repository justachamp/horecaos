package uz.horecaos.platform.storefrontapps.application;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppClientType;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppIdentity;
import uz.horecaos.platform.storefrontapps.domain.AppOrigin;
import uz.horecaos.platform.storefrontapps.domain.AuthorisationStatus;
import uz.horecaos.platform.storefrontapps.domain.StorefrontAppStatus;
import uz.horecaos.platform.storefrontapps.infrastructure.persistence.JdbcStorefrontAppStore;
import uz.horecaos.platform.storefrontapps.infrastructure.persistence.JdbcStorefrontAppStore.AppRow;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The decision the app tier makes about one storefront request (ADR 0070): which app is
 * asking, whether it is who it says it is as far as its client type can show, and whether
 * the tenant's brand lets it serve.
 *
 * <p>It authenticates the <em>app</em> and leaves the customer exactly as anonymous or as
 * signed-in as they were. The pre-account browse paths stay open to a customer who has no
 * session; what changes is that the platform can tell who is asking, meter it, and revoke it.
 *
 * <p><strong>Public and confidential clients are held to different things, and the
 * difference is stated rather than hidden.</strong> A public client has no secret to check,
 * so what the platform verifies is the request's {@code Origin} against the allowlist the
 * app registered. That is attribution and revocability, not authentication — any non-browser
 * caller can write an {@code Origin} header — and no response here claims otherwise. A
 * confidential client's secret is resolved through its ADR 0028 reference and compared in
 * constant time.
 *
 * <p>The order is the order of what a stranger may learn. The app is identified first, then
 * authenticated (origin or secret), and only then asked about the tenant: an unauthenticated
 * caller holding a guessed app id never learns whether some brand has authorised it.
 *
 * <p>Nothing is cached: the registry row and the authorisation row are read on every call,
 * so a suspension or a revocation takes effect on the next request (ADR 0033).
 */
@Service
public class StorefrontAppGate {

    private static final Logger LOG = LoggerFactory.getLogger(StorefrontAppGate.class);

    private final JdbcStorefrontAppStore store;
    private final SecretResolver secrets;
    private final StorefrontAppMetrics metrics;
    private final boolean required;

    public StorefrontAppGate(
            JdbcStorefrontAppStore store,
            SecretResolver secrets,
            StorefrontAppMetrics metrics,
            @Value("${horecaos.storefront.app-identity.required:false}") boolean required) {
        this.store = store;
        this.secrets = secrets;
        this.metrics = metrics;
        this.required = required;
    }

    /**
     * What a request carried, and which tenant and brand its path names.
     *
     * <p>{@code tenantId} is null for the few storefront paths that name none — resolving a
     * hostname, searching pickup points, the dine-in guest session — where only the app itself
     * can be checked. {@code brandId} is null where the path names a tenant but no brand.
     */
    public record Request(
            @Nullable String appId,
            @Nullable String secret,
            @Nullable String origin,
            @Nullable String referer,
            @Nullable UUID tenantId,
            @Nullable UUID brandId) {

        /** Redacted: a record's default toString would print the presented secret. */
        @Override
        public String toString() {
            return "Request[appId=" + appId + ", tenantId=" + tenantId + ", brandId=" + brandId + "]";
        }
    }

    /** The answer. {@link Unattributed} is rollout stage one's tolerance and nothing else. */
    public sealed interface Verdict {

        record Attributed(StorefrontAppIdentity identity) implements Verdict {}

        record Unattributed() implements Verdict {}

        record Refused(ErrorCode code, String detail) implements Verdict {}
    }

    public Verdict check(Request request) {
        if (request.appId() == null || request.appId().isBlank()) {
            if (required) {
                return refuse(
                        ErrorCode.APP_IDENTITY_REQUIRED,
                        "This request names no storefront app. Send the app id in the X-Storefront-App-Id header.");
            }
            metrics.unattributed();
            return new Verdict.Unattributed();
        }

        UUID appId;
        try {
            appId = UUID.fromString(request.appId().strip());
        } catch (IllegalArgumentException notAnId) {
            return refuse(ErrorCode.APP_UNREGISTERED, "The storefront app id is not a registered app.");
        }
        Optional<AppRow> found = store.findApp(appId);
        if (found.isEmpty()) {
            return refuse(ErrorCode.APP_UNREGISTERED, "The storefront app id is not a registered app.");
        }
        AppRow app = found.get();
        if (app.status() != StorefrontAppStatus.ACTIVE) {
            return refuse(
                    ErrorCode.APP_SUSPENDED,
                    "This storefront app is "
                            + app.status().name().toLowerCase(java.util.Locale.ROOT)
                            + " and is not serving any tenant.");
        }

        boolean authenticated;
        if (app.clientType() == StorefrontAppClientType.PUBLIC) {
            Optional<String> origin = AppOrigin.fromRequestValue(request.origin())
                    .or(() -> request.origin() == null || request.origin().isBlank()
                            ? AppOrigin.fromReferer(request.referer())
                            : Optional.empty());
            if (origin.isEmpty() || !app.originAllowlist().contains(origin.get())) {
                return refuse(
                        ErrorCode.APP_ORIGIN_MISMATCH,
                        "This request's origin is not one of the origins this storefront app registered.");
            }
            authenticated = false;
        } else {
            if (!secretMatches(app, request.secret())) {
                return refuse(
                        ErrorCode.APP_SECRET_INVALID,
                        "A confidential storefront app must send its secret in X-Storefront-App-Secret.");
            }
            authenticated = true;
        }

        if (request.tenantId() != null) {
            Optional<AuthorisationStatus> standing = request.brandId() != null
                    ? store.brandStanding(request.tenantId(), request.brandId(), appId)
                    : store.tenantStanding(request.tenantId(), appId);
            if (standing.isEmpty()) {
                return refuse(ErrorCode.APP_NOT_AUTHORISED, "This tenant has not authorised this storefront app.");
            }
            if (standing.get() == AuthorisationStatus.REVOKED) {
                return refuse(ErrorCode.APP_REVOKED, "This tenant has revoked this storefront app's authorisation.");
            }
        }

        metrics.attributed(app.clientType());
        return new Verdict.Attributed(new StorefrontAppIdentity(app.id(), app.name(), app.clientType(), authenticated));
    }

    private boolean secretMatches(AppRow app, @Nullable String presented) {
        if (presented == null || presented.isBlank() || app.secretReference() == null) {
            return false;
        }
        try {
            SecretValue stored = secrets.resolve(SecretReference.parse(app.secretReference()));
            // SecretValue.equals compares in time independent of how much matched. The resolved
            // value is the resolver's own and shared; it is read, never disposed.
            return stored.equals(SecretValue.of(presented));
        } catch (SecretResolver.SecretNotFoundException | IllegalArgumentException unresolvable) {
            // A confidential app whose reference has no value behind it cannot be served, and that
            // is the platform's fault, not the caller's. Say so where an operator will see it —
            // the app id only, never the reference's value, and nothing the caller sent.
            LOG.error("Storefront app {} has a secret reference that does not resolve", app.id());
            return false;
        }
    }

    private Verdict refuse(ErrorCode code, String detail) {
        metrics.refused(code);
        return new Verdict.Refused(code, detail);
    }
}
