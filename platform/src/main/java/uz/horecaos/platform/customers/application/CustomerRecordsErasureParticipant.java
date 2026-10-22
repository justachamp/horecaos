package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerErasureStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerSessionStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.media.api.ObjectStorage;

/**
 * Erases the rest of what {@code customers} itself holds of an erased customer (ADR 0029): the words an operator
 * wrote on a blacklist entry, the sessions still signed in, and the favourites.
 *
 * <p>{@code CustomerErasureService} overwrites the account, its contact points and addresses and severs its
 * principal links; its own class doc named {@code blacklist_entries.reason_encrypted} as a known gap, and this closes
 * it. The reason is free text about an incident and "routinely names a third party" (V0125), so it is tombstoned
 * with the same marker the contact points get, while the entry's existence, dates and actors stay: that a customer
 * was barred, and by whom, is the tenant's record.
 *
 * <p>A session is revoked rather than left to the sweeper's seven-day grace: the principal link is severed, but a
 * bearer token already issued would otherwise keep resolving to the anonymised account until it expired.
 *
 * <p>Registered as a participant of customers' own erasure rather than folded into the service, as {@code
 * LeadErasureParticipant} is: one seam, one proof, one transaction.
 */
@Component
@ErasesCustomerData(
        name = "customer-records",
        tables = {
            "customer.blacklist_entries",
            "customer.customer_sessions",
            "customer.favourites",
            "customer.profile_corrections",
            "customer.export_requests"
        })
public class CustomerRecordsErasureParticipant implements CustomerErasureParticipant {

    private static final String TOMBSTONE = "ERASED";
    private static final String BLACKLIST_TABLE = "customer.blacklist_entries";

    private final JdbcCustomerErasureStore erasures;
    private final JdbcCustomerSessionStore sessions;
    private final JdbcExportRequestStore exportRequests;
    private final ObjectStorage storage;
    private final FieldProtection protection;
    private final Clock clock;

    public CustomerRecordsErasureParticipant(
            JdbcCustomerErasureStore erasures,
            JdbcCustomerSessionStore sessions,
            JdbcExportRequestStore exportRequests,
            ObjectStorage storage,
            FieldProtection protection,
            Clock clock) {
        this.erasures = erasures;
        this.sessions = sessions;
        this.exportRequests = exportRequests;
        this.storage = storage;
        this.protection = protection;
        this.clock = clock;
    }

    @Override
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        int reasons = erasures.eraseBlacklistReasonsOf(
                tenantId,
                customerAccountId,
                id -> tombstone(tenantId, id, "reason_encrypted"),
                id -> tombstone(tenantId, id, "lift_reason_encrypted"));
        int revoked = sessions.revokeForAccount(tenantId, customerAccountId, clock.instant());
        int favourites = erasures.deleteFavourites(tenantId, customerAccountId);
        int corrections = erasures.deleteCorrections(tenantId, customerAccountId);
        int bundles = removeExportBundles(tenantId, customerAccountId);
        return ErasureCounts.builder()
                .add(BLACKLIST_TABLE, reasons)
                .add("customer.customer_sessions", revoked)
                .add("customer.favourites", favourites)
                .add("customer.profile_corrections", corrections)
                .add("customer.export_requests", bundles)
                .build();
    }

    /**
     * Removes every data-subject export of the account that is still in object storage. A bundle is her data gathered
     * into one file, so an erasure that left one would leave the thing it exists to remove. An object that cannot be
     * deleted fails the execution (and rolls it back) rather than being skipped: the request stays PENDING and the
     * operator retries, which is the same answer a participant that throws gives everywhere else.
     */
    private int removeExportBundles(UUID tenantId, UUID accountId) {
        int removed = 0;
        for (var bundle : exportRequests.liveBundlesOf(tenantId, accountId)) {
            storage.delete(bundle.bucket(), bundle.key());
            removed += exportRequests.markBundleRemoved(tenantId, bundle.requestId(), clock.instant());
        }
        return removed;
    }

    private String tombstone(UUID tenantId, UUID entryId, String column) {
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(BLACKLIST_TABLE, column, entryId), TOMBSTONE)
                .serialize();
    }
}
