package uz.horecaos.platform.storefrontapps.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppBrandAuthorisationView;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppCatalogueEntry;
import uz.horecaos.platform.storefrontapps.application.StorefrontAppViews.StorefrontAppStanding;
import uz.horecaos.platform.storefrontapps.domain.AuthorisationStatus;
import uz.horecaos.platform.storefrontapps.domain.StorefrontAppStatus;
import uz.horecaos.platform.storefrontapps.infrastructure.persistence.JdbcStorefrontAppStore;
import uz.horecaos.platform.storefrontapps.infrastructure.persistence.JdbcStorefrontAppStore.AppRow;
import uz.horecaos.platform.storefrontapps.infrastructure.persistence.JdbcStorefrontAppStore.AuthorisationRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant choosing its storefront (ADR 0070): authorising a registered app to serve one of
 * its brands, and withdrawing that authorisation.
 *
 * <p>Both are a tenant act, capability-gated at the endpoint and audited here in the same
 * transaction as the change (ADR 0027). Revoking needs no propagation step: the identity
 * check reads the authorisation row on every storefront request and nothing caches it, so
 * the next request after the commit is already refused.
 */
@Service
public class StorefrontAppAuthorisationService {

    private final JdbcStorefrontAppStore store;
    private final AuditRecorder audit;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;
    private final String contractVersion;

    public StorefrontAppAuthorisationService(
            JdbcStorefrontAppStore store,
            AuditRecorder audit,
            TransactionTemplate unitOfWork,
            Clock clock,
            @Value("${horecaos.api.version}") String contractVersion) {
        this.store = store;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.contractVersion = contractVersion;
    }

    /** Every app this brand could choose, beside what it has decided about each. */
    public List<StorefrontAppCatalogueEntry> catalogue(UUID tenantId, UUID brandId) {
        return store.catalogueForBrand(tenantId, brandId).stream()
                .map(row -> {
                    AppRow app = row.app();
                    AuthorisationRow authorisation = row.authorisation();
                    return new StorefrontAppCatalogueEntry(
                            app.id(),
                            app.name(),
                            app.vendor(),
                            app.clientType(),
                            app.firstParty(),
                            app.status(),
                            StorefrontAppRegistryService.conformanceOf(app, contractVersion),
                            standing(authorisation),
                            authorisation == null ? null : authorisation.grantedAt(),
                            authorisation == null ? null : authorisation.grantedBy(),
                            authorisation == null ? null : authorisation.revokedAt(),
                            authorisation == null ? null : authorisation.version());
                })
                .toList();
    }

    /**
     * Authorises an app for a brand, or authorises it again after the brand withdrew it.
     *
     * <p>Only an ACTIVE app can be authorised: a suspended one is refused for everybody and a
     * retired one never serves again, so granting either would be a promise nothing keeps.
     */
    public StorefrontAppBrandAuthorisationView authorise(
            UUID tenantId, UUID brandId, UUID appId, ActorRef actor, String reason) {
        AppRow app = store.findApp(appId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such storefront app"));
        if (app.status() != StorefrontAppStatus.ACTIVE) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This app is " + app.status().name().toLowerCase() + " and cannot be authorised");
        }
        Instant now = clock.instant();
        Optional<AuthorisationRow> existing = store.findAuthorisation(tenantId, brandId, appId);
        if (existing.isPresent() && existing.get().status() == AuthorisationStatus.ACTIVE) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This brand has already authorised this app");
        }
        try {
            unitOfWork.executeWithoutResult(status -> {
                if (existing.isPresent()) {
                    if (!store.reactivateAuthorisation(
                            tenantId, brandId, appId, existing.get().version(), actor.subject(), now)) {
                        throw new ApiException(
                                ErrorCode.RESOURCE_CONFLICT, "This authorisation was changed by someone else; reload");
                    }
                } else {
                    store.insertAuthorisation(new AuthorisationRow(
                            Ids.newId(),
                            tenantId,
                            brandId,
                            appId,
                            AuthorisationStatus.ACTIVE,
                            actor.subject(),
                            now,
                            null,
                            null,
                            0));
                }
                record(
                        "storefront_app.authorised",
                        tenantId,
                        brandId,
                        appId,
                        actor,
                        reason,
                        now,
                        fields(
                                "authorisation",
                                existing.map(row -> row.status().name()).orElse(null)),
                        fields("authorisation", AuthorisationStatus.ACTIVE.name()));
            });
        } catch (DuplicateKeyException raced) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This brand has already authorised this app");
        }
        return view(tenantId, brandId, appId);
    }

    /** Withdraws a brand's authorisation, against the version the caller read. */
    public StorefrontAppBrandAuthorisationView revoke(
            UUID tenantId, UUID brandId, UUID appId, long expectedVersion, ActorRef actor, String reason) {
        AuthorisationRow existing = store.findAuthorisation(tenantId, brandId, appId)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This brand has never authorised this app"));
        if (existing.status() != AuthorisationStatus.ACTIVE) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This authorisation is already revoked");
        }
        if (existing.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, existing.version());
        }
        Instant now = clock.instant();
        unitOfWork.executeWithoutResult(status -> {
            if (!store.revokeAuthorisation(tenantId, brandId, appId, expectedVersion, actor.subject(), now)) {
                throw new ApiException(
                        ErrorCode.RESOURCE_CONFLICT, "This authorisation was changed by someone else; reload");
            }
            record(
                    "storefront_app.revoked",
                    tenantId,
                    brandId,
                    appId,
                    actor,
                    reason,
                    now,
                    fields("authorisation", AuthorisationStatus.ACTIVE.name()),
                    fields("authorisation", AuthorisationStatus.REVOKED.name()));
        });
        return view(tenantId, brandId, appId);
    }

    private StorefrontAppBrandAuthorisationView view(UUID tenantId, UUID brandId, UUID appId) {
        AuthorisationRow row = store.findAuthorisation(tenantId, brandId, appId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such authorisation"));
        return new StorefrontAppBrandAuthorisationView(
                appId,
                tenantId,
                brandId,
                standing(row),
                row.grantedBy(),
                row.grantedAt(),
                row.revokedBy(),
                row.revokedAt(),
                row.version());
    }

    private static StorefrontAppStanding standing(@Nullable AuthorisationRow row) {
        if (row == null) {
            return StorefrontAppStanding.NOT_AUTHORISED;
        }
        return row.status() == AuthorisationStatus.ACTIVE
                ? StorefrontAppStanding.AUTHORISED
                : StorefrontAppStanding.REVOKED;
    }

    private void record(
            String actionCode,
            UUID tenantId,
            UUID brandId,
            UUID appId,
            ActorRef actor,
            String reason,
            Instant now,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(AuditFact.of(actionCode, AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("StorefrontApp", appId)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(appId + "/" + brandId)
                .occurredAt(now)
                .build());
    }

    private static Map<String, Object> fields(String key, @Nullable Object value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(key, value);
        return fields;
    }
}
