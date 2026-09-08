package uz.horecaos.platform.commercial.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.api.UsageMovement;
import uz.horecaos.platform.media.api.MediaAssetAvailable;

/**
 * The caller {@link UsageMeter} was missing for {@code media.storage_bytes_included}
 * (ADR 0021): a listener here, in {@code commercial}, rather than a call inside
 * {@code MediaAssetService} — the direction {@code media} would otherwise have to
 * depend on {@code commercial.api} closes a cycle {@code ModularArchitectureTests}
 * catches, because {@code commercial} already depends on {@code tenancy.api} (see
 * {@link TenancyUsageMeterTrigger}'s own Javadoc) and {@code tenancy} in turn
 * depends on {@code media.api} for its onboarding readiness check
 * ({@code OnboardingStepHandlers$MediaReadinessValidate}). A direct edge from
 * {@code media} back to {@code commercial} would complete
 * {@code commercial -> tenancy -> media -> commercial}; listening for the fact
 * {@code media} already publishes reaches the same outcome without it, and
 * {@code commercial} depending on {@code media.api} directly introduces no cycle
 * of its own, since nothing {@code media} depends on depends back on {@code
 * commercial} or {@code tenancy}.
 *
 * <p>Metered on {@link MediaAssetAvailable}, never on the upload request: a
 * declared size at {@code MediaAssetService#requestUpload} is a client's claim,
 * and only the verification worker's object-store read settles what actually
 * exists. Nothing should count against a tenant's allowance until the bytes are
 * confirmed to exist — the same reasoning that keeps the asset itself unavailable
 * until this same fact is published.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, the phase every cross-module trigger
 * in this codebase uses: the availability fact and the usage fact that it
 * happened commit together.
 *
 * <p>Idempotent by the same mechanism every {@link UsageMeter} caller relies on:
 * the asset's own id is stable and never reused as a source event id, so a
 * redelivered fact collapses to the row already recorded rather than a second one.
 */
@Component
public class MediaUsageMeterTrigger {

    private final UsageMeter usage;

    public MediaUsageMeterTrigger(UsageMeter usage) {
        this.usage = usage;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onMediaAssetAvailable(MediaAssetAvailable event) {
        usage.record(UsageMovement.of(
                event.tenantId(),
                EntitlementKeys.MEDIA_STORAGE_BYTES_INCLUDED,
                event.verifiedSizeBytes(),
                "media.MediaAssetAvailable",
                event.assetId().value().toString(),
                event.occurredAt()));
    }
}
