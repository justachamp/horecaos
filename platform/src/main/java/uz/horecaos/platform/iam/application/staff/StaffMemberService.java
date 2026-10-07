package uz.horecaos.platform.iam.application.staff;

import java.net.URI;
import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.LocaleVocabulary;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffProfile;
import uz.horecaos.platform.iam.api.grants.GrantAuthority;
import uz.horecaos.platform.iam.api.staff.StaffMemberChanged;
import uz.horecaos.platform.iam.api.staff.StaffMemberRegistry;
import uz.horecaos.platform.iam.api.staff.StaffPhotos;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.ActiveGrant;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.GrantPlace;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant's own record of each person who works for it (ADR 0139): who they are
 * to this tenant, and what this tenant lets a manager or the person themselves
 * change about it.
 *
 * <p><strong>Three levels, one method.</strong> A tenant route, a brand route and
 * a branch route all call the same method here, passing the {@link Reach} their
 * path named. The coverage rule therefore lives in one place: a member is
 * visible at a reach when at least one of their <em>active</em> jobs sits at
 * that scope or below it, a member with no active job is visible at the tenant
 * level only, and a branch refuses to <em>change</em> anyone unless every active
 * job they hold is inside that branch. A route for a member the reach does not
 * include answers "no such member", the same answer an unknown id gets, so no
 * level is an existence oracle for the others.
 *
 * <p><strong>Self-service touches the caller's own row only.</strong> {@link
 * #self}, {@link #updateSelf} and {@link #setPhoto} take the token's subject and
 * the path's tenant and resolve the row from those; no caller-supplied member id
 * reaches them, which is the half of the {@code StaffSelfAuthorized} contract the
 * interceptor cannot enforce.
 *
 * <p><strong>Nothing personal leaves in an event.</strong> Every change is
 * published as a {@link StaffMemberChanged} whose before and after hold the
 * non-personal fields as they are and, for a personal one, only whether it is
 * set. The audit listener turns that into a change document through {@code
 * ChangeDocuments}, which redacts the keys; the values never travel (ADR 0029).
 *
 * <p>Keycloak is never written here. Its {@code firstName} and {@code lastName}
 * are set once, at account creation and at invitation acceptance, and a later
 * edit is not mirrored: a subject who works in two tenants has two profiles, and
 * a mirror would make the last tenant to edit a name the name the other
 * tenant's token shows.
 */
@Service
public class StaffMemberService implements StaffMemberRegistry {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMPLOYEE_NUMBER_LENGTH = 32;
    private static final String SET = "set";

    private static final Logger log = LoggerFactory.getLogger(StaffMemberService.class);

    static final String CREATED = "staff.member.created";
    static final String UPDATED = "staff.member.updated";
    static final String EMPLOYMENT_ENDED = "staff.member.employment_ended";
    static final String ANONYMISED = "staff.member.anonymised";

    private final JdbcStaffMemberStore store;
    private final StaffMemberCodec codec;
    private final StaffNameCache names;
    private final ApplicationEventPublisher events;
    private final AuthorizationService authorization;
    private final GrantAuthority grants;
    private final StaffPhotos photos;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final LocaleVocabulary locales;

    public StaffMemberService(
            JdbcStaffMemberStore store,
            StaffMemberCodec codec,
            StaffNameCache names,
            ApplicationEventPublisher events,
            AuthorizationService authorization,
            GrantAuthority grants,
            StaffPhotos photos,
            TransactionTemplate transactions,
            Clock clock,
            LocaleVocabulary locales) {
        this.locales = locales;
        this.store = store;
        this.codec = codec;
        this.names = names;
        this.events = events;
        this.authorization = authorization;
        this.grants = grants;
        this.photos = photos;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ================================================================== reach

    /**
     * Which members a route may see, which is only what its path named: the
     * {@code brandId} and {@code locationId} filters on a wider route narrow a
     * result and never widen a scope.
     */
    public record Reach(
            Level level, @Nullable UUID brandId, @Nullable UUID locationId) {

        public enum Level {
            TENANT,
            BRAND,
            LOCATION
        }

        /** The tenant route, optionally narrowed. */
        public static Reach tenant(@Nullable UUID brandFilter, @Nullable UUID locationFilter) {
            return new Reach(Level.TENANT, brandFilter, locationFilter);
        }

        /** The brand route, optionally narrowed to one branch. */
        public static Reach brand(UUID brandId, @Nullable UUID locationFilter) {
            return new Reach(Level.BRAND, brandId, locationFilter);
        }

        /** The branch route. */
        public static Reach location(UUID brandId, UUID locationId) {
            return new Reach(Level.LOCATION, brandId, locationId);
        }

        boolean matches(List<GrantPlace> places) {
            if (level == Level.TENANT && brandId == null && locationId == null) {
                return true;
            }
            return places.stream().anyMatch(this::matches);
        }

        private boolean matches(GrantPlace place) {
            boolean location = "LOCATION".equals(place.scopeType());
            boolean locationOk = locationId == null || (location && locationId.equals(place.scopeId()));
            boolean brandOk = brandId == null
                    || ("BRAND".equals(place.scopeType()) && brandId.equals(place.scopeId()))
                    || (location && brandId.equals(place.brandId()));
            return locationOk && brandOk;
        }

        /**
         * Whether a change by this reach is allowed to touch this member: any
         * member at the tenant level, and at a branch only one whose every
         * active job is inside that branch -- a person who also holds a job
         * elsewhere is not the branch's to change.
         */
        boolean mayChange(List<GrantPlace> places) {
            if (level == Level.TENANT) {
                return true;
            }
            if (level == Level.BRAND) {
                return false;
            }
            return !places.isEmpty()
                    && places.stream()
                            .allMatch(place -> "LOCATION".equals(place.scopeType())
                                    && Objects.equals(locationId, place.scopeId())
                                    && Objects.equals(brandId, place.brandId()));
        }

        String levelName() {
            return level.name();
        }
    }

    // ================================================================== views

    /**
     * One member as a screen shows them.
     *
     * @param phone          the contact phone in full; set only on a single-member
     *                       read, never in a list (the list carries {@code maskedPhone})
     * @param employeeNumber likewise single-member only
     * @param photoUrl       a short-lived signed URL, single-member only
     * @param accessDrift    {@code ENDED} but still holding an active job: a
     *                       drift shown at read time, not a stored flag
     */
    public record MemberView(
            UUID memberId,
            String principalSubject,
            String displayReference,
            @Nullable String firstName,
            @Nullable String lastName,
            String displayName,
            @Nullable String phone,
            @Nullable String maskedPhone,
            @Nullable String employeeNumber,
            boolean hasPhoto,
            @Nullable URI photoUrl,
            @Nullable String uiLocale,
            List<String> spokenLanguages,
            String employmentStatus,
            @Nullable LocalDate employedFrom,
            @Nullable LocalDate employedUntil,
            boolean hasActiveAccess,
            boolean accessDrift,
            int version,
            Instant updatedAt) {

        /** A record's generated {@code toString} would print a person's name and phone (ADR 0029). */
        @Override
        public String toString() {
            return "MemberView[displayReference=" + displayReference + ", status=" + employmentStatus + ", version="
                    + version + "]";
        }
    }

    /** The edit the person or a manager makes to the personal part of the record. */
    public record ProfileEdit(
            String firstName,
            @Nullable String lastName,
            @Nullable String phone,
            @Nullable String uiLocale,
            @Nullable List<String> spokenLanguages) {

        @Override
        public String toString() {
            return "ProfileEdit[<redacted>]";
        }
    }

    /** The employment half only a manager edits; the person never does. */
    public record EmploymentEdit(
            @Nullable String status,
            @Nullable String employeeNumber,
            @Nullable LocalDate employedFrom,
            @Nullable LocalDate employedUntil) {

        @Override
        public String toString() {
            return "EmploymentEdit[status=" + status + "]";
        }
    }

    public record EndOutcome(MemberView member, int revokedGrants, int remainingGrants) {}

    // ================================================================== reads

    /**
     * The members this reach can see, newest information last: names are
     * decrypted here and sorted with Russian collation, because they cannot be
     * sorted in SQL. That is the accepted cost of keeping them ciphertext, and
     * it is right for the hundreds of people a tenant has.
     */
    @Transactional(readOnly = true)
    public List<MemberView> list(UUID tenantId, Reach reach, @Nullable String status, @Nullable String query) {
        String statusFilter = status == null || status.isBlank() ? null : requireStatus(status);
        Instant now = clock.instant();
        Map<String, List<GrantPlace>> places =
                store.activeGrantPlaces(tenantId, null, now, StaffMembers.MACHINE_ROLE_CODES);
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);

        List<MemberView> views = new ArrayList<>();
        for (MemberRow row : store.listByTenant(tenantId, statusFilter)) {
            List<GrantPlace> held = places.getOrDefault(row.principalSubject(), List.of());
            if (!reach.matches(held)) {
                continue;
            }
            StaffMemberCodec.Plain plain = codec.openAll(row);
            if (!needle.isEmpty() && !nameMatches(plain, needle)) {
                continue;
            }
            views.add(view(row, plain, !held.isEmpty(), false, null));
        }
        Collator collator = Collator.getInstance(Locale.forLanguageTag("ru"));
        views.sort(Comparator.comparing(StaffMemberService::sortKey, collator)
                .thenComparing(MemberView::displayReference));
        return views;
    }

    /** One member, in full, or "no such member" when this reach does not include them. */
    @Transactional(readOnly = true)
    public MemberView detail(UUID tenantId, Reach reach, UUID memberId) {
        MemberRow row = store.find(tenantId, memberId).orElseThrow(StaffMemberService::noSuchMember);
        List<GrantPlace> held = placesOf(tenantId, row.principalSubject());
        if (!reach.matches(held)) {
            throw noSuchMember();
        }
        return fullView(row, held);
    }

    /** The caller's own record. */
    @Transactional(readOnly = true)
    public MemberView self(UUID tenantId, String subject) {
        MemberRow row = store.findBySubject(tenantId, subject).orElseThrow(StaffMemberService::noProfile);
        return fullView(row, placesOf(tenantId, subject));
    }

    // ================================================================= writes

    /**
     * A manager changes another person's record: the personal part, and the
     * employment part (status between active and on leave, dates, employee
     * number).
     *
     * @param capabilityUsed the capability the route was authorised by, recorded on the fact
     */
    @Transactional
    public MemberView updateByManager(
            UUID tenantId,
            Reach reach,
            UUID memberId,
            int expectedVersion,
            ProfileEdit profile,
            EmploymentEdit employment,
            String actorSubject,
            @Nullable String reason,
            String correlationId) {
        MemberRow current = store.findForUpdate(tenantId, memberId).orElseThrow(StaffMemberService::noSuchMember);
        List<GrantPlace> held = placesOf(tenantId, current.principalSubject());
        if (!reach.matches(held)) {
            throw noSuchMember();
        }
        if (!reach.mayChange(held)) {
            throw ApiException.insufficientCapability(Capability.STAFF_PROFILE_MANAGE.code(), "TENANT");
        }
        requireVersion(expectedVersion, current);
        String why = reason == null || reason.isBlank() ? "A manager edited the staff member's record" : reason.strip();
        return applyEdit(
                current,
                profile,
                employment,
                false,
                actorSubject,
                why,
                Capability.STAFF_PROFILE_MANAGE.code(),
                correlationId,
                held);
    }

    /** The person changes their own name, phone, languages or photo, and nothing about their employment. */
    @Transactional
    public MemberView updateSelf(
            UUID tenantId,
            String subject,
            int expectedVersion,
            ProfileEdit profile,
            boolean removePhoto,
            String correlationId) {
        MemberRow current = store.findBySubjectForUpdate(tenantId, subject).orElseThrow(StaffMemberService::noProfile);
        requireVersion(expectedVersion, current);
        return applyEdit(
                current,
                profile,
                null,
                removePhoto,
                subject,
                "The person edited their own profile",
                null,
                correlationId,
                placesOf(tenantId, subject));
    }

    /**
     * The caller sets their own photo from image bytes (ADR 0010, ADR 0139).
     *
     * <p>Not one transaction: the pipeline reads and writes an object store, and
     * a connection must not be held across that. The version is checked before
     * the upload so the common conflict costs nothing, and again inside the
     * transaction that stores the asset id, which is the one that counts. A
     * photo that loses that race, or is refused inside it, is handed straight
     * back to the pipeline to delete ({@link StaffPhotos#discard}); the photo it
     * replaces is discarded in the same transaction that replaces it, so the
     * store never keeps a picture the record has stopped showing. A reference to
     * somebody else's asset is not possible, because the asset is created here,
     * in the caller's tenant.
     */
    public MemberView setPhoto(
            UUID tenantId,
            String subject,
            int expectedVersion,
            byte[] content,
            @Nullable String originalFilename,
            @Nullable UUID actorId,
            String correlationId) {
        MemberRow before = store.findBySubject(tenantId, subject).orElseThrow(StaffMemberService::noProfile);
        requireVersion(expectedVersion, before);

        StaffPhotos.Ingested ingested = photos.ingest(tenantId, content, originalFilename, actorId);
        if (!ingested.accepted() || ingested.assetId() == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The photo was not accepted",
                    Map.of("field", "photo", "reason", String.valueOf(ingested.rejectionCode())));
        }
        UUID assetId = ingested.assetId();

        MemberView saved;
        try {
            saved = transactions.execute(status -> {
                MemberRow current =
                        store.findBySubjectForUpdate(tenantId, subject).orElseThrow(StaffMemberService::noProfile);
                requireVersion(expectedVersion, current);
                if (!photos.isPrivateTenantAsset(tenantId, assetId)) {
                    // The asset was created a moment ago in this very tenant; if the
                    // pipeline no longer vouches for it, storing it would be wrong.
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED, "The photo is not available", Map.of("field", "photo"));
                }
                MemberRow next = withPhoto(current, assetId, clock.instant());
                MemberView view = persist(
                        current,
                        next,
                        UPDATED,
                        subject,
                        "The person set their own photo",
                        null,
                        correlationId,
                        fields("photo", markerOf(current.photoAssetId() == null ? null : "photo")),
                        fields("photo", SET),
                        placesOf(tenantId, subject));
                if (current.photoAssetId() != null) {
                    photos.discard(tenantId, current.photoAssetId());
                }
                return view;
            });
        } catch (RuntimeException notStored) {
            // The upload happened outside the transaction, so a stale version or a
            // refusal inside it leaves an asset nobody references. Say so now rather
            // than leave a face in the store for a sweep that does not exist.
            discardQuietly(tenantId, assetId);
            throw notStored;
        }
        return Objects.requireNonNull(saved);
    }

    /** Best effort, in its own transaction: the failure being handled is the one worth reporting. */
    private void discardQuietly(UUID tenantId, UUID assetId) {
        try {
            transactions.executeWithoutResult(status -> photos.discard(tenantId, assetId));
        } catch (RuntimeException failed) {
            log.warn(
                    "An unreferenced staff photo asset {} of tenant {} could not be queued for deletion",
                    assetId,
                    tenantId,
                    failed);
        }
    }

    /**
     * Ends someone's employment as one act that also ends their access
     * (ADR 0139): the member becomes {@code ENDED} with an end date, then each
     * of their active jobs in this tenant is revoked through the grant
     * authority, one audited revoke per job and deliberately <em>not</em> as one
     * transaction (ADR 0039's rule for bulk mutations).
     *
     * <p>The Keycloak account is left alone: it may serve another tenant, and
     * ADR 0009 has not decided a per-user disable.
     *
     * <p>What can go wrong, and what this does about it. The caller must hold
     * {@code iam.grant.manage} at every job's scope -- checked for all of them
     * <em>before</em> anything changes, so a missing grant cannot leave a person
     * half ended. A revoke that then fails in the middle leaves an {@code
     * ENDED} member with residual access; that is the drift {@link MemberView#accessDrift}
     * shows at read time, and calling this again with the current version
     * revokes what is left.
     */
    public EndOutcome endEmployment(
            UUID tenantId,
            UUID memberId,
            int expectedVersion,
            @Nullable LocalDate employedUntil,
            String reason,
            String actorSubject,
            String correlationId) {
        MemberRow member = store.find(tenantId, memberId).orElseThrow(StaffMemberService::noSuchMember);
        if (member.principalSubject().equals(actorSubject)) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "You cannot end your own employment");
        }
        Instant now = clock.instant();
        List<ActiveGrant> held =
                store.activeGrantsOf(tenantId, member.principalSubject(), now, StaffMembers.MACHINE_ROLE_CODES);
        for (ActiveGrant grant : held) {
            authorization.require(actorSubject, Capability.IAM_GRANT_MANAGE, scopeOf(tenantId, grant.place()));
        }

        String why = reason.strip();
        transactions.executeWithoutResult(status -> {
            MemberRow current = store.findForUpdate(tenantId, memberId).orElseThrow(StaffMemberService::noSuchMember);
            requireVersion(expectedVersion, current);
            if (StaffMembers.ENDED.equals(current.employmentStatus())) {
                return;
            }
            LocalDate today = LocalDate.now(clock.withZone(tenantZone(tenantId)));
            LocalDate until = employedUntil == null ? today : employedUntil;
            if (current.employedFrom() != null && until.isBefore(current.employedFrom())) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "The end date is before the start date",
                        Map.of("field", "employedUntil"));
            }
            MemberRow next = copy(
                    current,
                    current.protectedFirstName(),
                    current.protectedLastName(),
                    current.protectedPhone(),
                    current.phoneLookupHash(),
                    current.protectedEmployeeNumber(),
                    current.employeeNumberHash(),
                    current.photoAssetId(),
                    current.uiLocale(),
                    current.spokenLanguages(),
                    StaffMembers.ENDED,
                    current.employedFrom(),
                    until,
                    now);
            persist(
                    current,
                    next,
                    EMPLOYMENT_ENDED,
                    actorSubject,
                    why,
                    Capability.STAFF_PROFILE_MANAGE.code(),
                    correlationId,
                    Map.of(
                            "employmentStatus",
                            current.employmentStatus(),
                            "employedUntil",
                            dateOrBlank(current.employedUntil())),
                    Map.of("employmentStatus", StaffMembers.ENDED, "employedUntil", until.toString()),
                    List.of());
        });

        int revoked = 0;
        String revokeReason = "Employment ended: " + why;
        for (ActiveGrant grant : held) {
            if (grants.revoke(tenantId, grant.grantId(), actorSubject, revokeReason)) {
                revoked++;
            }
        }

        MemberRow after = store.find(tenantId, memberId).orElseThrow(StaffMemberService::noSuchMember);
        List<GrantPlace> remaining = placesOf(tenantId, after.principalSubject());
        return new EndOutcome(fullView(after, remaining), revoked, remaining.size());
    }

    // ----------------------------------------------------------- the registry

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerInvited(
            UUID tenantId,
            String principalSubject,
            String firstName,
            @Nullable String lastName,
            @Nullable String phone,
            String actorSubject,
            String correlationId) {
        Instant now = clock.instant();
        UUID id = Ids.newId();
        String reference = StaffMembers.reference(store.allocateReferenceNumber(tenantId));
        Optional<StaffPhones.Phone> contact = StaffPhones.parseLeniently(phone);
        String first = firstName.strip();
        String last = lastName == null || lastName.isBlank() ? null : lastName.strip();

        MemberRow row = new MemberRow(
                id,
                tenantId,
                principalSubject,
                reference,
                codec.seal(tenantId, id, StaffMemberCodec.FIRST_NAME, first),
                codec.sealOrNull(tenantId, id, StaffMemberCodec.LAST_NAME, last),
                contact.map(p -> codec.seal(tenantId, id, StaffMemberCodec.PHONE, p.stored()))
                        .orElse(null),
                contact.map(p -> codec.phoneHash(tenantId, p.digits())).orElse(null),
                null,
                null,
                null,
                null,
                List.of(),
                StaffMembers.PENDING,
                null,
                null,
                1,
                now,
                now);
        store.insert(row);
        names.evictAfterCommit(tenantId, principalSubject);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("employmentStatus", StaffMembers.PENDING);
        after.put("firstName", SET);
        after.put("lastName", last == null ? null : SET);
        after.put("phone", contact.isPresent() ? SET : null);
        publish(
                CREATED,
                tenantId,
                actorSubject,
                null,
                id,
                "A colleague was invited with a job",
                Capability.IAM_GRANT_MANAGE.code(),
                1L,
                Map.of(),
                after,
                correlationId,
                now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void activate(
            UUID tenantId, String principalSubject, String firstName, @Nullable String lastName, String correlationId) {
        Instant now = clock.instant();
        String first = firstName.strip();
        String last = lastName == null || lastName.isBlank() ? null : lastName.strip();
        Optional<MemberRow> existing = store.findBySubjectForUpdate(tenantId, principalSubject);

        if (existing.isEmpty()) {
            UUID id = Ids.newId();
            MemberRow row = new MemberRow(
                    id,
                    tenantId,
                    principalSubject,
                    StaffMembers.reference(store.allocateReferenceNumber(tenantId)),
                    codec.seal(tenantId, id, StaffMemberCodec.FIRST_NAME, first),
                    codec.sealOrNull(tenantId, id, StaffMemberCodec.LAST_NAME, last),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    StaffMembers.ACTIVE,
                    null,
                    null,
                    1,
                    now,
                    now);
            store.insert(row);
            names.evictAfterCommit(tenantId, principalSubject);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("employmentStatus", StaffMembers.ACTIVE);
            after.put("firstName", SET);
            after.put("lastName", last == null ? null : SET);
            publish(
                    CREATED,
                    tenantId,
                    principalSubject,
                    null,
                    id,
                    "The owner set up their account",
                    null,
                    1L,
                    Map.of(),
                    after,
                    correlationId,
                    now);
            return;
        }

        MemberRow current = existing.get();
        if (!StaffMembers.PENDING.equals(current.employmentStatus())) {
            // Accepting a stale link must not resurrect someone whose employment
            // ended, and must not overwrite a name a manager already corrected.
            return;
        }
        MemberRow next = copy(
                current,
                codec.seal(tenantId, current.id(), StaffMemberCodec.FIRST_NAME, first),
                last == null ? null : codec.seal(tenantId, current.id(), StaffMemberCodec.LAST_NAME, last),
                current.protectedPhone(),
                current.phoneLookupHash(),
                current.protectedEmployeeNumber(),
                current.employeeNumberHash(),
                current.photoAssetId(),
                current.uiLocale(),
                current.spokenLanguages(),
                StaffMembers.ACTIVE,
                current.employedFrom(),
                current.employedUntil(),
                now);
        persist(
                current,
                next,
                UPDATED,
                principalSubject,
                "The invited person accepted the invitation and set up their account",
                null,
                correlationId,
                fields(
                        "employmentStatus",
                        StaffMembers.PENDING,
                        "firstName",
                        SET,
                        "lastName",
                        markerOf(current.protectedLastName())),
                fields(
                        "employmentStatus",
                        StaffMembers.ACTIVE,
                        "firstName",
                        SET,
                        "lastName",
                        markerOf(next.protectedLastName())),
                List.of());
    }

    /**
     * Creates the row for an account that predates the record, from what the
     * identity provider holds about it (ADR 0139's backfill). {@code ACTIVE}
     * when the account has a password, {@code PENDING} otherwise.
     *
     * @return false when a row already exists, which makes a second run a no-op
     */
    @Transactional
    public boolean registerFromIdentityProvider(
            UUID tenantId, String principalSubject, @Nullable StaffProfile profile, String job, String correlationId) {
        if (store.findBySubject(tenantId, principalSubject).isPresent()) {
            return false;
        }
        Instant now = clock.instant();
        UUID id = Ids.newId();
        String first = profile == null ? null : placeholderFree(profile.firstName(), profile.lastName(), true);
        String last = profile == null ? null : placeholderFree(profile.firstName(), profile.lastName(), false);
        Optional<StaffPhones.Phone> contact =
                profile == null ? Optional.empty() : StaffPhones.parseLeniently(profile.phone());
        String status = profile != null && profile.hasPassword() ? StaffMembers.ACTIVE : StaffMembers.PENDING;

        MemberRow row = new MemberRow(
                id,
                tenantId,
                principalSubject,
                StaffMembers.reference(store.allocateReferenceNumber(tenantId)),
                codec.sealOrNull(tenantId, id, StaffMemberCodec.FIRST_NAME, first),
                codec.sealOrNull(tenantId, id, StaffMemberCodec.LAST_NAME, last),
                contact.map(p -> codec.seal(tenantId, id, StaffMemberCodec.PHONE, p.stored()))
                        .orElse(null),
                contact.map(p -> codec.phoneHash(tenantId, p.digits())).orElse(null),
                null,
                null,
                null,
                null,
                List.of(),
                status,
                null,
                null,
                1,
                now,
                now);
        store.insert(row);
        // The directory may have cached the identity provider's interim name for
        // this subject while it had no row; the row's name wins from now on.
        names.evictAfterCommit(tenantId, principalSubject);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("employmentStatus", status);
        after.put("firstName", first == null ? null : SET);
        after.put("lastName", last == null ? null : SET);
        after.put("phone", contact.isPresent() ? SET : null);
        publish(
                CREATED,
                tenantId,
                null,
                job,
                id,
                "The record was backfilled from the identity provider for an account that predates it",
                null,
                1L,
                Map.of(),
                after,
                correlationId,
                now);
        return true;
    }

    /**
     * Overwrites an ended member's personal fields in place (ADR 0139, ADR
     * 0029): names, phone and employee number are nulled, the photo reference is
     * dropped and the emergency contacts are deleted. The reference and the
     * status history remain, so audit and order attribution still resolve to
     * "former staff S-0142".
     *
     * <p>Used by the retention sweeper once it is allowed to enforce, and by a
     * former employee's data-subject request on demand.
     */
    @Transactional
    public boolean anonymise(UUID tenantId, UUID memberId, String reason, String job, String correlationId) {
        Optional<MemberRow> found = store.findForUpdate(tenantId, memberId);
        if (found.isEmpty() || !StaffMembers.ENDED.equals(found.get().employmentStatus())) {
            return false;
        }
        MemberRow current = found.get();
        Instant now = clock.instant();
        MemberRow next = copy(
                current,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                current.uiLocale(),
                List.of(),
                current.employmentStatus(),
                current.employedFrom(),
                current.employedUntil(),
                now);
        int contacts = store.deleteEmergencyContactsOf(tenantId, memberId);
        if (!store.update(next, current.version())) {
            throw ApiException.staleVersion(current.version(), current.version() + 1);
        }
        if (current.photoAssetId() != null) {
            // Dropping the reference is not erasure: the picture is personal data
            // and has to leave the store too.
            photos.discard(tenantId, current.photoAssetId());
        }
        names.evictAfterCommit(tenantId, current.principalSubject());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("firstName", markerOf(current.protectedFirstName()));
        before.put("lastName", markerOf(current.protectedLastName()));
        before.put("phone", markerOf(current.protectedPhone()));
        before.put("employeeNumber", markerOf(current.protectedEmployeeNumber()));
        before.put("photo", markerOf(current.photoAssetId() == null ? null : "photo"));
        before.put("emergencyContacts", contacts == 0 ? null : SET);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("firstName", null);
        after.put("lastName", null);
        after.put("phone", null);
        after.put("employeeNumber", null);
        after.put("photo", null);
        after.put("emergencyContacts", null);
        publish(
                ANONYMISED,
                tenantId,
                null,
                job,
                memberId,
                reason,
                null,
                (long) current.version() + 1,
                before,
                after,
                correlationId,
                now);
        return true;
    }

    // ============================================================== internals

    private MemberView applyEdit(
            MemberRow current,
            ProfileEdit profile,
            @Nullable EmploymentEdit employment,
            boolean removePhoto,
            String actorSubject,
            String reason,
            @Nullable String capabilityUsed,
            String correlationId,
            List<GrantPlace> held) {
        UUID tenantId = current.tenantId();
        UUID id = current.id();
        StaffMemberCodec.Plain plain = codec.openAll(current);

        String first = requiredName(profile.firstName(), "firstName");
        String last = optionalName(profile.lastName(), "lastName");
        Optional<StaffPhones.Phone> phone = phoneOf(profile.phone());
        String uiLocale = localeOf(profile.uiLocale());
        List<String> languages = languagesOf(profile.spokenLanguages());

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();

        String firstSealed = current.protectedFirstName();
        if (!Objects.equals(first, plain.firstName())) {
            firstSealed = codec.seal(tenantId, id, StaffMemberCodec.FIRST_NAME, first);
            before.put("firstName", markerOf(current.protectedFirstName()));
            after.put("firstName", SET);
        }
        String lastSealed = current.protectedLastName();
        if (!Objects.equals(last, plain.lastName())) {
            lastSealed = last == null ? null : codec.seal(tenantId, id, StaffMemberCodec.LAST_NAME, last);
            before.put("lastName", markerOf(current.protectedLastName()));
            after.put("lastName", markerOf(lastSealed));
        }
        String phoneSealed = current.protectedPhone();
        String phoneHash = current.phoneLookupHash();
        String newPhone = phone.map(StaffPhones.Phone::stored).orElse(null);
        if (!Objects.equals(newPhone, plain.phone())) {
            phoneSealed = phone.map(p -> codec.seal(tenantId, id, StaffMemberCodec.PHONE, p.stored()))
                    .orElse(null);
            phoneHash = phone.map(p -> codec.phoneHash(tenantId, p.digits())).orElse(null);
            before.put("phone", markerOf(current.protectedPhone()));
            after.put("phone", markerOf(phoneSealed));
        }
        if (!Objects.equals(uiLocale, current.uiLocale())) {
            before.put("uiLocale", current.uiLocale());
            after.put("uiLocale", uiLocale);
        }
        if (!languages.equals(current.spokenLanguages())) {
            before.put("spokenLanguages", String.join(",", current.spokenLanguages()));
            after.put("spokenLanguages", String.join(",", languages));
        }

        UUID photo = current.photoAssetId();
        if (removePhoto && photo != null) {
            photo = null;
            before.put("photo", SET);
            after.put("photo", null);
        }

        String employeeNumberSealed = current.protectedEmployeeNumber();
        String employeeNumberHash = current.employeeNumberHash();
        String status = current.employmentStatus();
        LocalDate employedFrom = current.employedFrom();
        LocalDate employedUntil = current.employedUntil();
        if (employment != null) {
            String number = employeeNumberOf(employment.employeeNumber());
            if (!Objects.equals(number, plain.employeeNumber())) {
                employeeNumberSealed =
                        number == null ? null : codec.seal(tenantId, id, StaffMemberCodec.EMPLOYEE_NUMBER, number);
                employeeNumberHash =
                        number == null ? null : codec.employeeNumberHash(tenantId, number.toUpperCase(Locale.ROOT));
                before.put("employeeNumber", markerOf(current.protectedEmployeeNumber()));
                after.put("employeeNumber", markerOf(employeeNumberSealed));
            }
            String target = employment.status();
            if (target != null && !target.equals(status)) {
                if (!StaffMembers.ACTIVE.equals(target) && !StaffMembers.ON_LEAVE.equals(target)) {
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "The status can be set to ACTIVE or ON_LEAVE; ending employment is its own act",
                            Map.of("field", "employmentStatus"));
                }
                if (StaffMembers.PENDING.equals(status)) {
                    throw new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE, "This person has not accepted the invitation yet");
                }
                before.put("employmentStatus", status);
                after.put("employmentStatus", target);
                status = target;
            }
            LocalDate from = employment.employedFrom();
            LocalDate until = employment.employedUntil();
            if (StaffMembers.ENDED.equals(status) && until == null) {
                // An ended member keeps the date employment ended; the database
                // refuses ENDED without one, and clearing it would erase a fact.
                until = employedUntil;
            }
            if (until != null && from != null && until.isBefore(from)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "The end date is before the start date",
                        Map.of("field", "employedUntil"));
            }
            if (!Objects.equals(from, employedFrom)) {
                before.put("employedFrom", dateOrBlank(employedFrom));
                after.put("employedFrom", dateOrBlank(from));
                employedFrom = from;
            }
            if (!Objects.equals(until, employedUntil)) {
                before.put("employedUntil", dateOrBlank(employedUntil));
                after.put("employedUntil", dateOrBlank(until));
                employedUntil = until;
            }
        }

        if (before.isEmpty() && after.isEmpty()) {
            return fullView(current, held);
        }

        Instant now = clock.instant();
        MemberRow next = new MemberRow(
                id,
                tenantId,
                current.principalSubject(),
                current.displayReference(),
                firstSealed,
                lastSealed,
                phoneSealed,
                phoneHash,
                employeeNumberSealed,
                employeeNumberHash,
                photo,
                uiLocale,
                languages,
                status,
                employedFrom,
                employedUntil,
                current.version(),
                current.createdAt(),
                now);
        MemberView view = persist(
                current, next, UPDATED, actorSubject, reason, capabilityUsed, correlationId, before, after, held);
        if (current.photoAssetId() != null && photo == null) {
            photos.discard(tenantId, current.photoAssetId());
        }
        return view;
    }

    /** Writes the row, evicts the name, publishes the fact, and reads the result back. */
    private MemberView persist(
            MemberRow current,
            MemberRow next,
            String actionCode,
            String actorSubject,
            String reason,
            @Nullable String capabilityUsed,
            String correlationId,
            Map<String, Object> before,
            Map<String, Object> after,
            List<GrantPlace> held) {
        boolean written;
        try {
            written = store.update(next, current.version());
        } catch (DuplicateKeyException employeeNumberTaken) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Another staff member already has this employee number",
                    Map.of("field", "employeeNumber"));
        }
        if (!written) {
            throw ApiException.staleVersion(current.version(), current.version() + 1);
        }
        names.evictAfterCommit(current.tenantId(), current.principalSubject());
        publish(
                actionCode,
                current.tenantId(),
                actorSubject,
                null,
                current.id(),
                reason,
                capabilityUsed,
                (long) current.version() + 1,
                before,
                after,
                correlationId,
                next.updatedAt());
        MemberRow saved = store.find(current.tenantId(), current.id()).orElseThrow(StaffMemberService::noSuchMember);
        return fullView(saved, held);
    }

    private void publish(
            String actionCode,
            UUID tenantId,
            @Nullable String actorSubject,
            @Nullable String systemJob,
            UUID memberId,
            String reason,
            @Nullable String capabilityUsed,
            @Nullable Long targetVersion,
            Map<String, Object> before,
            Map<String, Object> after,
            String correlationId,
            Instant occurredAt) {
        events.publishEvent(new StaffMemberChanged(
                actionCode,
                tenantId,
                actorSubject,
                systemJob,
                memberId,
                reason,
                capabilityUsed,
                targetVersion,
                before,
                after,
                correlationId,
                occurredAt));
    }

    private MemberView fullView(MemberRow row, List<GrantPlace> held) {
        StaffMemberCodec.Plain plain = codec.openAll(row);
        URI photoUrl = row.photoAssetId() == null
                ? null
                : photos.signedReadUrl(row.tenantId(), row.photoAssetId()).orElse(null);
        return view(row, plain, !held.isEmpty(), true, photoUrl);
    }

    private MemberView view(
            MemberRow row,
            StaffMemberCodec.Plain plain,
            boolean hasActiveAccess,
            boolean full,
            @Nullable URI photoUrl) {
        String name = StaffMemberCodec.joinName(plain.firstName(), plain.lastName());
        return new MemberView(
                row.id(),
                row.principalSubject(),
                row.displayReference(),
                plain.firstName(),
                plain.lastName(),
                name != null ? name : row.displayReference(),
                full ? plain.phone() : null,
                StaffPhones.mask(plain.phone()),
                full ? plain.employeeNumber() : null,
                row.photoAssetId() != null,
                photoUrl,
                row.uiLocale(),
                row.spokenLanguages(),
                row.employmentStatus(),
                row.employedFrom(),
                row.employedUntil(),
                hasActiveAccess,
                StaffMembers.ENDED.equals(row.employmentStatus()) && hasActiveAccess,
                row.version(),
                row.updatedAt());
    }

    private List<GrantPlace> placesOf(UUID tenantId, String subject) {
        return store.activeGrantPlaces(tenantId, List.of(subject), clock.instant(), StaffMembers.MACHINE_ROLE_CODES)
                .getOrDefault(subject, List.of());
    }

    private static ResourceScope scopeOf(UUID tenantId, GrantPlace place) {
        ScopeType type = ScopeType.valueOf(place.scopeType());
        return switch (type) {
            case TENANT -> ResourceScope.tenant(tenantId);
            case BRAND -> ResourceScope.brand(tenantId, Objects.requireNonNull(place.scopeId()));
            case LOCATION ->
                ResourceScope.location(
                        tenantId, Objects.requireNonNull(place.brandId()), Objects.requireNonNull(place.scopeId()));
            case PLATFORM -> throw new IllegalStateException("A tenant job is never platform-scoped");
        };
    }

    private ZoneId tenantZone(UUID tenantId) {
        return store.tenantTimezone(tenantId).map(ZoneId::of).orElse(ZoneId.of("UTC"));
    }

    private static void requireVersion(int expected, MemberRow current) {
        if (expected != current.version()) {
            throw ApiException.staleVersion(expected, current.version());
        }
    }

    private static ApiException noSuchMember() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such staff member");
    }

    private static ApiException noProfile() {
        return new ApiException(
                ErrorCode.RESOURCE_NOT_FOUND, "There is no staff profile for this account in this tenant");
    }

    private static MemberRow copy(
            MemberRow base,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable String phone,
            @Nullable String phoneHash,
            @Nullable String employeeNumber,
            @Nullable String employeeNumberHash,
            @Nullable UUID photo,
            @Nullable String uiLocale,
            List<String> languages,
            String status,
            @Nullable LocalDate employedFrom,
            @Nullable LocalDate employedUntil,
            Instant now) {
        return new MemberRow(
                base.id(),
                base.tenantId(),
                base.principalSubject(),
                base.displayReference(),
                firstName,
                lastName,
                phone,
                phoneHash,
                employeeNumber,
                employeeNumberHash,
                photo,
                uiLocale,
                languages,
                status,
                employedFrom,
                employedUntil,
                base.version(),
                base.createdAt(),
                now);
    }

    private static MemberRow withPhoto(MemberRow base, UUID photo, Instant now) {
        return copy(
                base,
                base.protectedFirstName(),
                base.protectedLastName(),
                base.protectedPhone(),
                base.phoneLookupHash(),
                base.protectedEmployeeNumber(),
                base.employeeNumberHash(),
                photo,
                base.uiLocale(),
                base.spokenLanguages(),
                base.employmentStatus(),
                base.employedFrom(),
                base.employedUntil(),
                now);
    }

    private static String sortKey(MemberView view) {
        String last = view.lastName() == null ? "" : view.lastName();
        String first = view.firstName() == null ? "" : view.firstName();
        String key = (last + " " + first).strip();
        // A member with no name sorts after every named one, by reference.
        return key.isEmpty() ? "￿" + view.displayReference() : key;
    }

    private static boolean nameMatches(StaffMemberCodec.Plain plain, String needle) {
        String first = plain.firstName() == null ? "" : plain.firstName();
        String last = plain.lastName() == null ? "" : plain.lastName();
        String forward = (first + " " + last).toLowerCase(Locale.ROOT);
        String backward = (last + " " + first).toLowerCase(Locale.ROOT);
        return forward.contains(needle) || backward.contains(needle);
    }

    private static String requireStatus(String status) {
        if (!Set.of(StaffMembers.PENDING, StaffMembers.ACTIVE, StaffMembers.ON_LEAVE, StaffMembers.ENDED)
                .contains(status)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "status must be one of PENDING, ACTIVE, ON_LEAVE, ENDED",
                    Map.of("field", "status"));
        }
        return status;
    }

    private static String requiredName(@Nullable String value, String field) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A first name is required", Map.of("field", field));
        }
        return limited(stripped, field);
    }

    private static @Nullable String optionalName(@Nullable String value, String field) {
        String stripped = value == null ? "" : value.strip();
        return stripped.isEmpty() ? null : limited(stripped, field);
    }

    private static String limited(String value, String field) {
        if (value.length() > MAX_NAME_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That name is too long", Map.of("field", field));
        }
        return value;
    }

    private static Optional<StaffPhones.Phone> phoneOf(@Nullable String raw) {
        try {
            return StaffPhones.parse(raw);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage(), Map.of("field", "phone"));
        }
    }

    /**
     * The tag a staff member's interface language is stored as (ADR 0149): what the registry's
     * staff-interface tier calls it. A bare {@code uz}, which clients that predate the registry
     * still send, is read as {@code uz-Latn} and the member reads back the tag, never the alias.
     */
    private @Nullable String localeOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return locales.staffInterface(raw)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "uiLocale must be one of " + String.join(", ", locales.staffInterfaceTags()),
                        Map.of("field", "uiLocale")));
    }

    private static List<String> languagesOf(@Nullable List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> languages = raw.stream()
                .filter(Objects::nonNull)
                .map(language -> language.strip().toLowerCase(Locale.ROOT))
                .filter(language -> !language.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (languages.size() > StaffMembers.MAX_SPOKEN_LANGUAGES
                || languages.stream().anyMatch(language -> !language.matches("[a-z]{2,3}"))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "spokenLanguages takes up to eight two- or three-letter language codes",
                    Map.of("field", "spokenLanguages"));
        }
        return List.copyOf(languages);
    }

    private static @Nullable String employeeNumberOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String number = raw.strip();
        if (number.length() > MAX_EMPLOYEE_NUMBER_LENGTH || !number.matches("[\\p{L}\\p{N}._/\\- ]+")) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That is not a valid employee number",
                    Map.of("field", "employeeNumber"));
        }
        return number;
    }

    private static @Nullable String markerOf(@Nullable String stored) {
        return stored == null ? null : SET;
    }

    /**
     * A change-document half that may hold nulls. {@code Map.of} refuses them,
     * and "the field was unset" is evidence that must stay distinguishable from
     * "a value is hidden" (ChangeDocuments).
     */
    private static Map<String, Object> fields(@Nullable Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static String dateOrBlank(@Nullable LocalDate date) {
        return date == null ? "" : date.toString();
    }

    /**
     * Keycloak's "Pending Profile" placeholder (written to satisfy its User
     * Profile while an owner has no real name) is no name at all.
     */
    private static @Nullable String placeholderFree(@Nullable String first, @Nullable String last, boolean wantFirst) {
        if ("Pending".equals(first) && "Profile".equals(last)) {
            return null;
        }
        return wantFirst ? first : last;
    }
}
