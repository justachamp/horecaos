package uz.horecaos.platform.customers.infrastructure.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Customer persistence (ADR 0015).
 *
 * <p>The identity partition is passed explicitly rather than derived here,
 * because {@code NULL} and "no brand" mean different things in these queries and
 * a repository guessing which one applies is how a BRAND_ISOLATED tenant ends up
 * resolving across brands.
 */
@Repository
public class JdbcCustomerStore {

    private final JdbcClient jdbc;

    public JdbcCustomerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds the account linked to a principal inside one partition.
     *
     * <p>{@code IS NOT DISTINCT FROM} rather than {@code =}, because a null
     * partition is the normal case under TENANT_SHARED and {@code = NULL} matches
     * nothing — which would make every sign-in look like a first sign-in and
     * create a fresh account each time.
     */
    public Optional<UUID> findLinkedAccount(
            UUID tenantId, @Nullable UUID partitionBrandId, String issuer, String subject) {
        return jdbc.sql("""
                SELECT customer_account_id FROM customer.principal_links
                WHERE tenant_id = :tenantId
                  AND identity_partition_brand_id IS NOT DISTINCT FROM :partition
                  AND issuer = :issuer AND subject = :subject
                  AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("partition", partitionBrandId)
                .param("issuer", issuer)
                .param("subject", subject)
                .query(UUID.class)
                .optional();
    }

    /**
     * Every account this principal is linked to inside the tenant.
     *
     * <p>Partition-blind on purpose, unlike {@link #findLinkedAccount}. The
     * question here is "is this account the caller's", asked from a surface that
     * has no brand in its path, and a BRAND_ISOLATED tenant gives one person a
     * separate account per brand. Guessing a partition would answer no for an
     * account the caller genuinely owns.
     */
    public List<UUID> linkedAccounts(UUID tenantId, String issuer, String subject) {
        return jdbc.sql("""
                SELECT customer_account_id FROM customer.principal_links
                WHERE tenant_id = :tenantId AND issuer = :issuer AND subject = :subject
                  AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("issuer", issuer)
                .param("subject", subject)
                .query(UUID.class)
                .list();
    }

    /**
     * Follows a merge redirect to the surviving account.
     *
     * <p>Bounded rather than recursive: a cycle would otherwise hang a sign-in,
     * and a chain longer than a few hops means the merge data is wrong and should
     * surface rather than be walked.
     */
    public UUID resolveMergeTarget(UUID tenantId, UUID accountId) {
        UUID current = accountId;
        for (int hop = 0; hop < 8; hop++) {
            Optional<UUID> next = jdbc.sql("""
                    SELECT merged_into_account_id FROM customer.customer_accounts
                    WHERE id = :id AND tenant_id = :tenantId AND merged_into_account_id IS NOT NULL
                    """)
                    .param("id", current)
                    .param("tenantId", tenantId)
                    .query(UUID.class)
                    .optional();
            if (next.isEmpty()) {
                return current;
            }
            current = next.get();
        }
        throw new IllegalStateException(
                "Merge redirect for account " + accountId + " is longer than eight hops or cyclic");
    }

    /**
     * Writes a new account.
     *
     * @param policyVersion the version of the tenant's identity policy that was in
     *                      effect when this account was created, or {@code null}
     *                      when the tenant has configured no policy and the
     *                      default applied. It is read from the policy row by the
     *                      caller and passed through, never derived here: this
     *                      method used to compute it as the identity-mode enum's
     *                      ordinal plus one, which is a different fact that
     *                      happened to be the same small integer for as long as
     *                      every tenant resolved TENANT_SHARED. A version that
     *                      does not name a real governed decision cannot do the
     *                      one job the column has, which is to say what a later
     *                      policy migration is migrating from
     */
    public void insertAccount(
            UUID accountId,
            UUID tenantId,
            @Nullable UUID partitionBrandId,
            @Nullable Integer policyVersion,
            Instant now) {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (
                    id, tenant_id, identity_partition_brand_id, status,
                    identity_policy_version, created_at, updated_at)
                VALUES (:id, :tenantId, :partition, 'ACTIVE', :policyVersion, :now, :now)
                """)
                .param("id", accountId)
                .param("tenantId", tenantId)
                .param("partition", partitionBrandId)
                // Typed, because a null here is "no governed policy existed" and
                // an untyped null leaves the driver to infer a type it has no
                // information about.
                .param("policyVersion", policyVersion, Types.INTEGER)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public void insertPrincipalLink(
            UUID linkId,
            UUID tenantId,
            @Nullable UUID partitionBrandId,
            UUID accountId,
            String issuer,
            String subject,
            Instant now) {
        jdbc.sql("""
                INSERT INTO customer.principal_links (
                    id, tenant_id, identity_partition_brand_id, customer_account_id,
                    issuer, subject, status, linked_at)
                VALUES (:id, :tenantId, :partition, :accountId, :issuer, :subject, 'ACTIVE', :now)
                """)
                .param("id", linkId)
                .param("tenantId", tenantId)
                .param("partition", partitionBrandId)
                .param("accountId", accountId)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public void upsertBrandProfile(UUID profileId, UUID tenantId, UUID brandId, UUID accountId, Instant now) {
        jdbc.sql("""
                INSERT INTO customer.brand_profiles (
                    id, tenant_id, brand_id, customer_account_id, status, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :accountId, 'ACTIVE', :now, :now)
                ON CONFLICT (tenant_id, brand_id, customer_account_id) DO NOTHING
                """)
                .param("id", profileId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    // ------------------------------------------------------------ contact points

    public void insertContactPoint(
            UUID id,
            UUID tenantId,
            UUID accountId,
            String type,
            String normalizedHash,
            String encryptedValue,
            boolean isPrimary,
            Instant now) {
        jdbc.sql("""
                INSERT INTO customer.contact_points (
                    id, tenant_id, customer_account_id, type, normalized_hash,
                    encrypted_value, is_primary, created_at, updated_at)
                VALUES (:id, :tenantId, :accountId, :type, :hash, :encrypted, :isPrimary, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("type", type)
                .param("hash", normalizedHash)
                .param("encrypted", encryptedValue)
                .param("isPrimary", isPrimary)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * Writes a contact point that is verified from the moment it exists.
     *
     * <p>Separate from {@link #insertContactPoint} rather than a flag on it. That
     * one is the staff path, where a number typed off a phone call is
     * {@code UNVERIFIED} and must stay so; this one is only reachable from a
     * redeemed ADR 0015 verification grant, so the two cannot be confused at a call
     * site.
     */
    public void insertVerifiedContactPoint(
            UUID id,
            UUID tenantId,
            UUID accountId,
            String type,
            @Nullable String source,
            String normalizedHash,
            String encryptedValue,
            boolean isPrimary,
            Instant verifiedAt) {
        jdbc.sql("""
                INSERT INTO customer.contact_points (
                    id, tenant_id, customer_account_id, type, source, normalized_hash,
                    encrypted_value, verification_status, verified_at, is_primary,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :accountId, :type, :source, :hash, :encrypted, 'VERIFIED',
                    :now, :isPrimary, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("type", type)
                .param("source", source)
                .param("hash", normalizedHash)
                .param("encrypted", encryptedValue)
                .param("isPrimary", isPrimary)
                .param("now", OffsetDateTime.ofInstant(verifiedAt, ZoneOffset.UTC))
                .update();
    }

    /**
     * Promotes a contact the account already holds to verified.
     *
     * <p>Matched on the keyed hash, so the number never has to be decrypted to find
     * its own row. Scoped to one account: the same number on somebody else's
     * account is a different person's contact and stays unverified, which is the
     * whole of ADR 0015's refusal to treat a phone as an identity key.
     *
     * @return how many rows were promoted; zero when this account does not hold
     *         the number yet
     */
    public int markContactVerified(
            UUID tenantId,
            UUID accountId,
            String type,
            @Nullable String source,
            String normalizedHash,
            Instant verifiedAt) {
        return jdbc.sql("""
                UPDATE customer.contact_points
                SET verification_status = 'VERIFIED', source = :source, verified_at = :now, updated_at = :now
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND type = :type AND normalized_hash = :hash
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("type", type)
                .param("source", source)
                .param("hash", normalizedHash)
                .param("now", OffsetDateTime.ofInstant(verifiedAt, ZoneOffset.UTC))
                .update();
    }

    /**
     * Whether this account already has a primary contact of a kind.
     *
     * <p>Asked before inserting one, because {@code ux_contact_point_primary}
     * allows exactly one per account per type and a second would be rejected by the
     * index rather than by anything that could explain itself.
     */
    public boolean hasPrimaryContact(UUID tenantId, UUID accountId, String type) {
        return jdbc.sql("""
                SELECT count(*) FROM customer.contact_points
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND type = :type AND is_primary
                """)
                        .param("tenantId", tenantId)
                        .param("accountId", accountId)
                        .param("type", type)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Every account holding this contact value.
     *
     * <p>Returns a list, never a single account. Two people genuinely share a
     * household phone, so collapsing this to one result is exactly the auto-merge
     * ADR 0015 forbids.
     */
    public List<UUID> accountsWithContact(UUID tenantId, String type, String normalizedHash) {
        return jdbc.sql("""
                SELECT DISTINCT customer_account_id FROM customer.contact_points
                WHERE tenant_id = :tenantId AND type = :type AND normalized_hash = :hash
                """)
                .param("tenantId", tenantId)
                .param("type", type)
                .param("hash", normalizedHash)
                .query(UUID.class)
                .list();
    }

    public List<ContactPointRow> contactPoints(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, type, encrypted_value, normalized_hash, verification_status, is_primary
                FROM customer.contact_points
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                ORDER BY is_primary DESC, created_at
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcCustomerStore::contactPointRow)
                .list();
    }

    /**
     * One contact point by id, still scoped by tenant.
     *
     * <p>ADR 0020 resolves a recipient by the id it stored a reference to, and the
     * id arrives from another module's row. Matching on it alone would decrypt a
     * different tenant's contact.
     */
    public Optional<ContactPointRow> contactPoint(UUID tenantId, UUID contactPointId) {
        return jdbc.sql("""
                SELECT id, type, encrypted_value, normalized_hash, verification_status, is_primary
                FROM customer.contact_points
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", contactPointId)
                .query(JdbcCustomerStore::contactPointRow)
                .optional();
    }

    /** The language the customer chose, if they chose one. */
    public Optional<String> preferredLocale(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT preferred_locale FROM customer.customer_accounts
                WHERE tenant_id = :tenantId AND id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(String.class)
                .optional();
    }

    /**
     * The account row itself, for the customer reading their own profile.
     *
     * <p>{@code identity_partition_brand_id} comes back with it because it is the
     * only honest answer to "does editing this change my profile at the tenant's
     * other brands". The tenant's <em>current</em> policy does not answer that: an
     * account was partitioned when it was created and a later governed mode change
     * does not retroactively re-partition it — V0060 refuses a deployment that
     * would. The column on the row is the fact; the policy is the intent.
     */
    public Optional<AccountRow> account(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, identity_partition_brand_id, status, display_name, preferred_locale,
                       preferred_timezone, identity_policy_version, version, created_at,
                       date_of_birth_encrypted
                FROM customer.customer_accounts
                WHERE tenant_id = :tenantId AND id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcCustomerStore::accountRow)
                .optional();
    }

    private static AccountRow accountRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new AccountRow(
                row.getObject("id", UUID.class),
                row.getObject("identity_partition_brand_id", UUID.class),
                row.getString("status"),
                row.getString("display_name"),
                row.getString("preferred_locale"),
                row.getString("preferred_timezone"),
                row.getObject("identity_policy_version", Integer.class),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getString("date_of_birth_encrypted"));
    }

    /**
     * Writes the encrypted date of birth, or clears it.
     *
     * <p>Its own method rather than a fourth parameter on {@link
     * #updateAccountProfile}: that one's own javadoc is deliberate about the three
     * fields it names being the whole of what a self-service write may touch, and
     * a date of birth is staff-entered here (CustomerController requires
     * {@code CUSTOMER_MANAGE}), never customer-entered, so folding it into the
     * self-service statement would blur who may set it.
     *
     * @return rows written: 1, or 0 when the account is not this tenant's or has
     *         moved on from {@code expectedVersion}
     */
    public int updateDateOfBirth(
            UUID tenantId, UUID accountId, int expectedVersion, @Nullable String encryptedDateOfBirth, Instant now) {
        return jdbc.sql("""
                UPDATE customer.customer_accounts
                SET date_of_birth_encrypted = :dob, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :accountId AND version = :expectedVersion
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("expectedVersion", expectedVersion)
                .param("dob", encryptedDateOfBirth, Types.VARCHAR)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    // -------------------------------------------------------------- list & search

    /**
     * Where a customer grid cursor points, resolved inside the tenant's own
     * scope — mirrors {@code JdbcOrderStore#customerOrderCursor} exactly, one
     * level up: a cursor id and tenant resolve to the {@code created_at} the
     * page comparison needs, and naming an id from another tenant answers empty
     * rather than leaking whether it exists.
     */
    public Optional<Instant> accountCursor(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT created_at FROM customer.customer_accounts
                WHERE tenant_id = :tenantId AND id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query((row, number) ->
                        row.getObject("created_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    /**
     * A page of the tenant's customer accounts, newest first (frontend
     * information architecture §5.1: the CRM grid).
     *
     * <p>Carries no contact value and no address — the grid names the "never a
     * list-wide decrypt" rule this whole section is built around, and a phone
     * column here would mean decrypting every row of every page just to mask it.
     * {@code phoneHash} lets a search match a customer by phone without ever
     * decrypting one: it is the same keyed hash {@link #accountsWithContact} looks
     * up by, computed by the caller from a query that parses as a phone number,
     * and left null the rest of the time so the {@code ILIKE} branch is the only
     * one that can match.
     *
     * @param status     null for every status, or one value to filter to
     * @param nameQuery  null to skip the name search entirely
     * @param phoneHash  null to skip the phone search entirely
     */
    public List<AccountSummaryRow> listAccounts(
            UUID tenantId,
            @Nullable String status,
            @Nullable String nameQuery,
            @Nullable String phoneHash,
            @Nullable Instant beforeCreatedAt,
            @Nullable UUID beforeId,
            int limit) {
        return jdbc.sql("""
                SELECT a.id, a.status, a.display_name, a.created_at, a.version
                FROM customer.customer_accounts a
                WHERE a.tenant_id = :tenantId
                  AND a.status <> 'MERGED'
                  AND (CAST(:status AS varchar) IS NULL OR a.status = :status)
                  AND (
                    (CAST(:nameQuery AS varchar) IS NULL AND CAST(:phoneHash AS varchar) IS NULL)
                    OR (CAST(:nameQuery AS varchar) IS NOT NULL AND a.display_name ILIKE '%' || :nameQuery || '%')
                    OR (CAST(:phoneHash AS varchar) IS NOT NULL AND EXISTS (
                          SELECT 1 FROM customer.contact_points c
                          WHERE c.tenant_id = a.tenant_id AND c.customer_account_id = a.id
                            AND c.type = 'PHONE' AND c.normalized_hash = :phoneHash))
                  )
                  AND (:unbounded
                       OR (a.created_at, a.id)
                          < (CAST(:beforeCreatedAt AS timestamptz), CAST(:beforeId AS uuid)))
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("status", status, Types.VARCHAR)
                .param("nameQuery", nameQuery, Types.VARCHAR)
                .param("phoneHash", phoneHash, Types.VARCHAR)
                .param("unbounded", beforeCreatedAt == null)
                // Cast in the statement rather than typed here, mirroring
                // JdbcOrderStore#listForCustomer's identical cursor pair.
                .param(
                        "beforeCreatedAt",
                        beforeCreatedAt == null ? null : OffsetDateTime.ofInstant(beforeCreatedAt, ZoneOffset.UTC))
                .param("beforeId", beforeId == null ? null : beforeId.toString())
                .param("limit", limit)
                .query((row, number) -> new AccountSummaryRow(
                        row.getObject("id", UUID.class),
                        row.getString("status"),
                        row.getString("display_name"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        row.getInt("version")))
                .list();
    }

    /** Every non-{@code MERGED} account in the tenant. The header counter's "total". */
    public long countActive(UUID tenantId) {
        return jdbc.sql("""
                SELECT count(*) FROM customer.customer_accounts
                WHERE tenant_id = :tenantId AND status <> 'MERGED'
                """).param("tenantId", tenantId).query(Long.class).single();
    }

    /** Accounts created inside {@code [from, to)}. The header counter's "registered today". */
    public long countCreatedBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT count(*) FROM customer.customer_accounts
                WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
                """)
                .param("tenantId", tenantId)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------- merge

    /**
     * Redirects one account to another, in place (ADR 0015).
     *
     * <p>The row is never deleted — {@code merged_into_account_id} is what makes
     * every immutable order snapshot, address and consent decision the source
     * account ever wrote still resolvable, and {@link #resolveMergeTarget}
     * follows exactly this column.
     *
     * @return rows written: 1, or 0 when the source is not this tenant's, has
     *         already moved on from {@code expectedVersion}, or is already
     *         {@code MERGED}
     */
    public int mergeAccount(
            UUID tenantId, UUID sourceAccountId, UUID targetAccountId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE customer.customer_accounts
                SET status = 'MERGED', merged_into_account_id = :target, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :source AND version = :expectedVersion
                  AND status <> 'MERGED'
                """)
                .param("tenantId", tenantId)
                .param("source", sourceAccountId)
                .param("target", targetAccountId)
                .param("expectedVersion", expectedVersion)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    // -------------------------------------------------------------- blacklist

    public void insertBlacklistEntry(
            UUID id,
            UUID tenantId,
            UUID accountId,
            String encryptedReason,
            String actorType,
            String actorId,
            @Nullable Instant expiresAt,
            Instant now) {
        jdbc.sql("""
                INSERT INTO customer.blacklist_entries (
                    id, tenant_id, customer_account_id, reason_encrypted, status,
                    actor_type, actor_id, created_at, expires_at)
                VALUES (:id, :tenantId, :accountId, :reason, 'ACTIVE', :actorType, :actorId, :now, :expiresAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("reason", encryptedReason)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(
                        "expiresAt",
                        expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /**
     * Lifts the tenant's one active entry for this account, if any.
     *
     * <p>Scoped by status rather than by id: the blacklist tab shows one current
     * state, not a picker over history, so lifting is "end whatever is active now"
     * rather than "end entry X" — which also means a caller cannot lift an entry
     * that already lapsed into a later one, because there is never more than one
     * {@code ACTIVE} row at a time (the service enforces that before insert).
     *
     * @return rows written: 1, or 0 when there is no active entry to lift
     */
    public int liftActiveBlacklistEntry(
            UUID tenantId,
            UUID accountId,
            String liftedByActorType,
            String liftedByActorId,
            @Nullable String encryptedLiftReason,
            Instant now) {
        return jdbc.sql("""
                UPDATE customer.blacklist_entries
                SET status = 'LIFTED', lifted_at = :now, lifted_by_actor_type = :actorType,
                    lifted_by_actor_id = :actorId, lift_reason_encrypted = :liftReason
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("actorType", liftedByActorType)
                .param("actorId", liftedByActorId)
                .param("liftReason", encryptedLiftReason, Types.VARCHAR)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * Whether an unexpired {@code ACTIVE} entry exists right now — the
     * enforcement point's own read (ADR 0029's "check the clock on every read"
     * rather than a swept status, per the migration's own comment on
     * {@code expires_at}).
     */
    public boolean isCurrentlyBlacklisted(UUID tenantId, UUID accountId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM customer.blacklist_entries
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND status = 'ACTIVE'
                  AND (expires_at IS NULL OR expires_at > :now)
                """)
                        .param("tenantId", tenantId)
                        .param("accountId", accountId)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Every blacklist entry for this account, newest first — what the reveal call decrypts. */
    public List<BlacklistEntryRow> blacklistHistory(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT id, reason_encrypted, status, actor_type, actor_id, created_at, expires_at,
                       lifted_at, lifted_by_actor_type, lifted_by_actor_id, lift_reason_encrypted
                FROM customer.blacklist_entries
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                ORDER BY created_at DESC
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query((row, number) -> new BlacklistEntryRow(
                        row.getObject("id", UUID.class),
                        row.getString("reason_encrypted"),
                        row.getString("status"),
                        row.getString("actor_type"),
                        row.getString("actor_id"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instantOrNull(row, "expires_at"),
                        instantOrNull(row, "lifted_at"),
                        row.getString("lifted_by_actor_type"),
                        row.getString("lifted_by_actor_id"),
                        row.getString("lift_reason_encrypted")))
                .list();
    }

    private static @Nullable Instant instantOrNull(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Writes the three fields a customer may change about themselves.
     *
     * <p>Every column that decides anything is absent from this statement: not
     * {@code status}, not {@code identity_partition_brand_id}, not
     * {@code merged_into_account_id}, not {@code identity_policy_version}. A
     * self-service write that could set any of them would let a customer
     * un-suspend themselves, move their account into another brand's partition, or
     * redirect a merge — and the statement rather than a validation rule is the
     * right place to say so, because a column that is never named cannot be set by
     * a field somebody adds to a request record later.
     *
     * <p>Version-checked in the {@code WHERE}, so two tabs cannot both win.
     *
     * @return rows written: 1, or 0 when the account is not this tenant's or has
     *         moved on from {@code expectedVersion}
     */
    public int updateAccountProfile(
            UUID tenantId,
            UUID accountId,
            int expectedVersion,
            @Nullable String displayName,
            @Nullable String preferredLocale,
            @Nullable String preferredTimezone,
            Instant now) {
        return jdbc.sql("""
                UPDATE customer.customer_accounts
                SET display_name = :displayName,
                    preferred_locale = :preferredLocale,
                    preferred_timezone = :preferredTimezone,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :accountId AND version = :expectedVersion
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("expectedVersion", expectedVersion)
                .param("displayName", displayName, Types.VARCHAR)
                .param("preferredLocale", preferredLocale, Types.VARCHAR)
                .param("preferredTimezone", preferredTimezone, Types.VARCHAR)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    private static ContactPointRow contactPointRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new ContactPointRow(
                row.getObject("id", UUID.class),
                row.getString("type"),
                row.getString("encrypted_value"),
                row.getString("normalized_hash"),
                row.getString("verification_status"),
                row.getBoolean("is_primary"));
    }

    // ----------------------------------------------------------------- addresses

    public void insertAddress(
            UUID id,
            UUID tenantId,
            UUID accountId,
            String label,
            String encryptedFields,
            @Nullable String encryptedInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            String coordinateSource,
            Instant now) {
        jdbc.sql("""
                INSERT INTO customer.addresses (
                    id, tenant_id, customer_account_id, label, encrypted_fields,
                    delivery_instructions_encrypted, latitude, longitude, coordinate_source,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :accountId, :label, :fields, :instructions,
                    :latitude, :longitude, :coordinateSource, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("label", label)
                .param("fields", encryptedFields)
                .param("instructions", encryptedInstructions)
                .param("latitude", latitude)
                .param("longitude", longitude)
                .param("coordinateSource", coordinateSource)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    public List<AddressRow> addresses(UUID tenantId, UUID accountId) {
        return jdbc.sql(SELECT_ADDRESS + """
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                   AND status = 'ACTIVE'
                 ORDER BY created_at
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query(JdbcCustomerStore::addressRow)
                .list();
    }

    /**
     * One active address of one account.
     *
     * <p>The account is a predicate in the statement and never a comparison after
     * the load, for the reason {@code JdbcCustomerAddressBook} gives about the same
     * table: an address id is a UUID a client supplies, and matching on it alone
     * would let one customer read — or edit, or archive — another customer's home.
     *
     * <p>Empty covers "not yours", "archived" and "never existed" alike. Telling
     * them apart is how an id becomes probeable.
     */
    public Optional<AddressRow> address(UUID tenantId, UUID accountId, UUID addressId) {
        return jdbc.sql(SELECT_ADDRESS + """
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                   AND id = :addressId AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("addressId", addressId)
                .query(JdbcCustomerStore::addressRow)
                .optional();
    }

    /**
     * Rewrites an address in place, under an expected version.
     *
     * <p>The row id does not change, which is what makes this an update rather
     * than an insert-and-archive: ADR 0029 binds the ciphertext to
     * {@code (table, column, id)}, so re-encrypting the new document against the
     * same id is the only way the row stays readable, and a cart that already
     * copied the old address is unaffected either way.
     *
     * @return rows written: 1, or 0 when the address is not this account's active
     *         address or has moved on from {@code expectedVersion}
     */
    public int updateAddress(
            UUID tenantId,
            UUID accountId,
            UUID addressId,
            int expectedVersion,
            String label,
            String encryptedFields,
            @Nullable String encryptedInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            String coordinateSource,
            Instant now) {
        return jdbc.sql("""
                UPDATE customer.addresses
                SET label = :label,
                    encrypted_fields = :fields,
                    delivery_instructions_encrypted = :instructions,
                    latitude = :latitude,
                    longitude = :longitude,
                    coordinate_source = :coordinateSource,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND id = :addressId AND status = 'ACTIVE' AND version = :expectedVersion
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("addressId", addressId)
                .param("expectedVersion", expectedVersion)
                .param("label", label, Types.VARCHAR)
                .param("fields", encryptedFields)
                .param("instructions", encryptedInstructions, Types.VARCHAR)
                .param("latitude", latitude, Types.DOUBLE)
                .param("longitude", longitude, Types.DOUBLE)
                .param("coordinateSource", coordinateSource)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * Retires an address without deleting it.
     *
     * <p>An archive rather than a {@code DELETE}, and V0017 already decided it:
     * the status column carries {@code ARCHIVED}, and the application role is
     * granted {@code SELECT, INSERT, UPDATE} on this table and no {@code DELETE}
     * at all. Nothing points a foreign key at an address — a cart and an order each
     * hold their own encrypted copy taken when the customer chose it (V0056) — so
     * a delete would not orphan anything; what it would destroy is the row a
     * dispute about where an order went is answered from.
     *
     * <p>The ciphertext is left where it is. Erasure under ADR 0029 is a separate,
     * governed act over the whole account, and a customer tidying their address
     * list is not exercising it.
     *
     * @return rows written: 1, or 0 when the address is not this account's active
     *         address or has moved on from {@code expectedVersion}
     */
    public int archiveAddress(UUID tenantId, UUID accountId, UUID addressId, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE customer.addresses
                SET status = 'ARCHIVED', version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND id = :addressId AND status = 'ACTIVE' AND version = :expectedVersion
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("addressId", addressId)
                .param("expectedVersion", expectedVersion)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    private static final String SELECT_ADDRESS = """
            SELECT id, label, encrypted_fields, delivery_instructions_encrypted,
                   latitude, longitude, coordinate_source, version
            FROM customer.addresses""";

    /**
     * Reads the coordinate pair as objects rather than doubles.
     *
     * <p>{@code getDouble} answers 0.0 for a SQL NULL, and 0,0 is a real point in
     * the Gulf of Guinea that a courier would be sent to.
     */
    private static AddressRow addressRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new AddressRow(
                row.getObject("id", UUID.class),
                row.getString("label"),
                row.getString("encrypted_fields"),
                row.getString("delivery_instructions_encrypted"),
                (Double) row.getObject("latitude"),
                (Double) row.getObject("longitude"),
                row.getString("coordinate_source"),
                row.getInt("version"));
    }

    /**
     * Addresses a geocoding backfill should still ask a provider about.
     *
     * <p>Scoped by source rather than by "latitude IS NULL", which is the whole
     * reason the column exists: an address deliberately kept as a landmark has
     * no point and never will, and selecting on the null would re-query it on
     * every run for the life of the account.
     */
    public List<UUID> addressesAwaitingGeocoding(UUID tenantId, int limit) {
        return jdbc.sql("""
                SELECT id FROM customer.addresses
                WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                  AND coordinate_source = 'NOT_GEOCODED'
                ORDER BY created_at
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    // ------------------------------------------------------------------- consent

    public void insertConsentDecision(
            UUID id,
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            @Nullable String channel,
            String decision,
            String policyVersion,
            String source,
            @Nullable String evidenceReference,
            Instant decidedAt) {
        jdbc.sql("""
                INSERT INTO customer.consent_decisions (
                    id, tenant_id, customer_account_id, brand_id, purpose, channel,
                    decision, policy_version, source, evidence_reference, decided_at)
                VALUES (:id, :tenantId, :accountId, :brandId, :purpose, :channel,
                    :decision, :policyVersion, :source, :evidence, :decidedAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("brandId", brandId)
                .param("purpose", purpose)
                .param("channel", channel)
                .param("decision", decision)
                .param("policyVersion", policyVersion)
                .param("source", source)
                .param("evidence", evidenceReference)
                .param("decidedAt", OffsetDateTime.ofInstant(decidedAt, ZoneOffset.UTC))
                .update();
    }

    /**
     * The latest decision for one purpose in one scope.
     *
     * <p>A query over an append-only log rather than a stored current value. The
     * log is the evidence of what someone agreed to and when; a mutable "current
     * consent" column would destroy exactly that.
     */
    public Optional<ConsentRow> currentConsent(
            UUID tenantId, UUID accountId, UUID brandId, String purpose, String channel) {
        return jdbc.sql("""
                SELECT decision, policy_version, decided_at, source
                FROM customer.consent_decisions
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND purpose = :purpose
                  AND brand_id IS NOT DISTINCT FROM :brandId
                  AND channel IS NOT DISTINCT FROM :channel
                ORDER BY decided_at DESC, recorded_at DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("brandId", brandId)
                .param("purpose", purpose)
                .param("channel", channel)
                .query((row, number) -> new ConsentRow(
                        row.getString("decision"),
                        row.getString("policy_version"),
                        row.getObject("decided_at", OffsetDateTime.class).toInstant(),
                        row.getString("source")))
                .optional();
    }

    public List<ConsentHistoryRow> consentHistory(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT purpose, brand_id, channel, decision, policy_version, source, decided_at
                FROM customer.consent_decisions
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                ORDER BY decided_at DESC
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .query((row, number) -> new ConsentHistoryRow(
                        row.getString("purpose"),
                        row.getObject("brand_id", UUID.class),
                        row.getString("channel"),
                        row.getString("decision"),
                        row.getString("policy_version"),
                        row.getString("source"),
                        row.getObject("decided_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public boolean accountExists(UUID tenantId, UUID accountId) {
        return jdbc.sql("""
                SELECT count(*) FROM customer.customer_accounts
                WHERE id = :id AND tenant_id = :tenantId
                """)
                        .param("id", accountId)
                        .param("tenantId", tenantId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public record ContactPointRow(
            UUID id,
            String type,
            String encryptedValue,
            String normalizedHash,
            String verificationStatus,
            boolean isPrimary) {}

    /**
     * The account's own row.
     *
     * <p>{@code displayName} is a person's name and never reaches a log: the
     * record prints nothing.
     *
     * @param partitionBrandId null when this account is shared across the tenant's
     *                         brands, the brand when it is isolated to one. Read
     *                         from the row rather than from the tenant's current
     *                         policy, because the two disagree after a governed
     *                         mode change and only the row says where an edit lands
     * @param dateOfBirthEncrypted still ciphertext — {@code null} covers both "not
     *                         on file" and "on file, not decrypted here" alike,
     *                         which is deliberate: this record never decrypts, so
     *                         it cannot tell the two apart and must not pretend to
     */
    public record AccountRow(
            UUID id,
            @Nullable UUID partitionBrandId,
            String status,
            @Nullable String displayName,
            @Nullable String preferredLocale,
            @Nullable String preferredTimezone,
            @Nullable Integer identityPolicyVersion,
            int version,
            Instant createdAt,
            @Nullable String dateOfBirthEncrypted) {

        @Override
        public String toString() {
            return "AccountRow[id=%s, version=%d]".formatted(id, version);
        }
    }

    /**
     * One row of the CRM grid (frontend information architecture §5.1). No
     * contact value, no address — see {@link #listAccounts}'s own doc for why.
     */
    public record AccountSummaryRow(
            UUID id, String status, @Nullable String displayName, Instant createdAt, int version) {

        @Override
        public String toString() {
            return "AccountSummaryRow[id=%s, status=%s]".formatted(id, status);
        }
    }

    /**
     * One blacklist entry, still ciphertext on its two reason fields.
     *
     * <p>Never printed: {@link #toString} exists so a stray log statement prints
     * an id and a status rather than an operator's account of why somebody was
     * blacklisted.
     */
    public record BlacklistEntryRow(
            UUID id,
            String reasonEncrypted,
            String status,
            String actorType,
            String actorId,
            Instant createdAt,
            @Nullable Instant expiresAt,
            @Nullable Instant liftedAt,
            @Nullable String liftedByActorType,
            @Nullable String liftedByActorId,
            @Nullable String liftReasonEncrypted) {

        @Override
        public String toString() {
            return "BlacklistEntryRow[id=%s, status=%s]".formatted(id, status);
        }
    }

    /** Never printed: two of its columns are ciphertext and two are a doorstep. */
    public record AddressRow(
            UUID id,
            String label,
            String encryptedFields,
            @Nullable String encryptedInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            String coordinateSource,
            int version) {

        @Override
        public String toString() {
            return "AddressRow[id=%s, version=%d]".formatted(id, version);
        }
    }

    public record ConsentRow(String decision, String policyVersion, Instant decidedAt, String source) {}

    public record ConsentHistoryRow(
            String purpose,
            UUID brandId,
            String channel,
            String decision,
            String policyVersion,
            String source,
            Instant decidedAt) {}
}
