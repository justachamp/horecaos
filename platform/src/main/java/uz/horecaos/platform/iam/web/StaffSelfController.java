package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.staff.StaffSelfAuthorized;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.MemberView;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.ProfileEdit;
import uz.horecaos.platform.iam.web.StaffMemberController.StaffMemberResponse;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * «Мой профиль» → «Личные данные» (ADR 0139, gap map rows {@code 0.2c} and
 * {@code X.5}): the caller's own record, and only the caller's own.
 *
 * <p>Authorised by {@link StaffSelfAuthorized}, not by scope coverage. A person's
 * own profile belongs to no location, and a cook holds only a location grant,
 * which never covers a tenant route; so the interceptor checks that the caller
 * holds {@code staff.self.manage} at <em>some</em> scope in the path's tenant.
 * That is a weaker statement than coverage, and it is sound because of the other
 * half of the contract, which lives here: <strong>no handler takes a member id,
 * and every one resolves the row from the token's subject and the path's
 * tenant.</strong> There is no parameter through which the capability could be
 * pointed at somebody else.
 *
 * <p>What a person may change is their name, contact phone, photo and spoken and
 * interface languages. Employment, the employee number, the sign-in phone, the
 * reset email, the password and MFA are not on this surface -- the first two are
 * a manager's, the rest are Keycloak's own flows, and a body that names them is
 * simply not read. The password hands off to Keycloak.
 *
 * <p>Each write carries {@code If-Match} and an {@code Idempotency-Key}, and is
 * rate-limited per person (ADR 0033): a profile is edited by hand, so thirty a
 * minute is generous and a loop is not.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/staff/me")
@Tag(name = "My profile", description = "A staff member's own record (ADR 0139)")
public class StaffSelfController {

    /**
     * The same ceiling {@code CachedBodyRequestFilter} puts on every mutating
     * body, so a photo larger than this is refused there before it is read; the
     * constant is repeated so the documentation and the error can name it.
     */
    static final int MAX_PHOTO_BYTES = 1024 * 1024;

    private static final RateLimiter.Policy EDIT_LIMIT = RateLimiter.Policy.perMinute(30);
    private static final RateLimiter.Policy PHOTO_LIMIT = RateLimiter.Policy.perMinute(10);

    private final StaffMemberService members;
    private final CurrentActor currentActor;
    private final RateLimiter rateLimiter;

    public StaffSelfController(StaffMemberService members, CurrentActor currentActor, RateLimiter rateLimiter) {
        this.members = members;
        this.currentActor = currentActor;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Operation(
            summary = "My own record",
            description = "Resolved from the signed-in subject and this tenant; there is no member id to "
                    + "supply. 404 when this tenant keeps no record for the account (a HorecaOS support "
                    + "session, a device). The shell chip reads the name here and not from the token's "
                    + "name claim, which goes stale after an edit.")
    public ResponseEntity<StaffMemberResponse> me(@PathVariable UUID tenantId) {
        return respond(members.self(tenantId, subject()));
    }

    @PutMapping
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Idempotent
    @Operation(
            summary = "Change my own name, contact phone, languages",
            description = "Needs If-Match with my record's version. Replaces the five fields (a missing "
                    + "optional field clears it); `removePhoto` drops my photo. Employment, employee "
                    + "number, the sign-in phone, the reset email, the password and MFA are not "
                    + "editable here. The edit is on the activity log, the order detail and the People "
                    + "list at the next request: the name cache is evicted on the write.")
    public ResponseEntity<StaffMemberResponse> updateMe(
            @PathVariable UUID tenantId, @Valid @RequestBody UpdateMyProfileRequest body, HttpServletRequest request) {
        limit("staff.self.update", tenantId, EDIT_LIMIT);
        return respond(members.updateSelf(
                tenantId,
                subject(),
                (int) AggregateVersion.requireIfMatch(request),
                new ProfileEdit(
                        body.firstName(), body.lastName(), body.phone(), body.uiLocale(), body.spokenLanguages()),
                Boolean.TRUE.equals(body.removePhoto()),
                StaffMemberController.correlation()));
    }

    @PostMapping(
            path = "/photo",
            consumes = {"image/jpeg", "image/png", "image/webp", "image/avif"})
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Idempotent
    @Operation(
            summary = "Set my photo from image bytes",
            description = "The body is the image itself (Content-Type image/jpeg, image/png, image/webp or "
                    + "image/avif), at most 1 MiB. It goes through the ADR 0010 pipeline -- the real type "
                    + "and size are read from the bytes -- and is stored PRIVATE and owned by the tenant. "
                    + "The response carries a short-lived signed URL, never a public one. Needs If-Match.")
    public ResponseEntity<StaffMemberResponse> setPhoto(
            @PathVariable UUID tenantId, @RequestBody byte[] content, HttpServletRequest request) {
        limit("staff.self.photo", tenantId, PHOTO_LIMIT);
        if (content.length == 0 || content.length > MAX_PHOTO_BYTES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A photo is between 1 byte and 1 MiB",
                    Map.of("field", "photo", "maximumBytes", MAX_PHOTO_BYTES));
        }
        return respond(members.setPhoto(
                tenantId,
                subject(),
                (int) AggregateVersion.requireIfMatch(request),
                content,
                null,
                uuidOrNull(subject()),
                StaffMemberController.correlation()));
    }

    // ----------------------------------------------------------------- plumbing

    private ResponseEntity<StaffMemberResponse> respond(MemberView view) {
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(view.version())).body(StaffMemberResponse.full(view));
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private void limit(String operation, UUID tenantId, RateLimiter.Policy policy) {
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(operation, tenantId.toString(), subject()), policy);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many edits. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    private static @Nullable UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * What a person may send about themselves. Every optional field is boxed or
     * nullable: Jackson 3 answers a missing primitive with {@code MALFORMED_BODY},
     * and the console omits what it does not touch.
     */
    public record UpdateMyProfileRequest(
            @NotBlank @Size(max = 100) String firstName,
            @Size(max = 100) @Nullable String lastName,
            @Size(max = 40) @Nullable String phone,
            @Size(max = 8) @Nullable String uiLocale,
            @Nullable List<@Size(max = 8) String> spokenLanguages,
            @Nullable Boolean removePhoto) {

        @Override
        public String toString() {
            return "UpdateMyProfileRequest[<redacted>]";
        }
    }
}
