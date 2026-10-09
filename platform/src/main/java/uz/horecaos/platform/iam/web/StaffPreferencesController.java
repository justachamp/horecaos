package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.staff.StaffSelfAuthorized;
import uz.horecaos.platform.iam.application.staff.StaffPreferenceSurface;
import uz.horecaos.platform.iam.application.staff.StaffPreferencesService;
import uz.horecaos.platform.iam.application.staff.StaffPreferencesService.StoredPreference;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * «Мой профиль» → «Интерфейс», and what the console remembers for the person across machines (ADR 0160,
 * gap map row {@code 0.2d}): the caller's own preference documents, and only the caller's own.
 *
 * <p>Authorised by {@link StaffSelfAuthorized} for the reason {@link StaffSelfController} gives and with
 * the same contract: no handler takes a member id or a subject, and every one resolves the rows from the
 * token's subject and the path's tenant, so the capability can be turned into the power to read and
 * write the caller's own documents and no one else's. There is no manager read and no tenant-wide write;
 * an administrator has no route that reads what a colleague arranged.
 *
 * <p>A document belongs to one <em>surface</em>, a closed set owned by code; an unknown surface is a
 * {@code RESOURCE_NOT_FOUND}, and a document the surface's schema refuses is a {@code VALIDATION_FAILED}
 * naming the field. Each write is its own version ({@code If-Match}; none on the first), is rate-limited
 * per person and writes no audit fact and no event (ADR 0160 decision 6). The documents are protected at
 * rest ({@code PERSONAL}, ADR 0029) and are never logged.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/staff/me/preferences")
@Tag(name = "My preferences", description = "A staff member's own interface preferences (ADR 0160)")
public class StaffPreferencesController {

    /** A person saves a layout by hand, a toggle at a time; a loop is not that, and a debounce keeps the console well inside. */
    private static final RateLimiter.Policy WRITE_LIMIT = RateLimiter.Policy.perMinute(60);

    private final StaffPreferencesService preferences;
    private final CurrentActor currentActor;
    private final RateLimiter rateLimiter;
    private final ObjectMapper json;

    public StaffPreferencesController(
            StaffPreferencesService preferences,
            CurrentActor currentActor,
            RateLimiter rateLimiter,
            ObjectMapper json) {
        this.preferences = preferences;
        this.currentActor = currentActor;
        this.rateLimiter = rateLimiter;
        this.json = json;
    }

    @GetMapping
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Operation(
            summary = "All of my stored preference documents",
            description = "One entry per surface I have saved something for; a surface I never saved is absent "
                    + "and the console uses its default. Resolved from the signed-in subject and this "
                    + "tenant. 404 when this tenant keeps no record for the account (a HorecaOS support "
                    + "session, a device): those keep their choices in the browser. The console reads this "
                    + "once per session.")
    public OwnPreferencesResponse readMyPreferences(@PathVariable UUID tenantId) {
        Map<String, OwnPreferenceResponse> surfaces = new LinkedHashMap<>();
        for (StoredPreference stored : preferences.readAll(tenantId, subject())) {
            surfaces.put(stored.surface().name(), OwnPreferenceResponse.of(stored));
        }
        return new OwnPreferencesResponse(surfaces);
    }

    @PutMapping("/{surface}")
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Idempotent
    @Operation(
            summary = "Save my document for one surface",
            description = "`SHELL` (the default branch) or `ORDER_BOARD` (optional columns and saved views). The "
                    + "first write for a surface carries no If-Match; every later write carries the version "
                    + "last read, and a second first write, or a write from a version that has moved on, is "
                    + "`STALE_VERSION`: re-read, re-apply the one change, try once more. The document is "
                    + "replaced whole. It is validated against the surface's closed schema before anything "
                    + "is stored: an unknown key, value or surface, a column the board shows anyway, a view "
                    + "label that is a number, more than 20 views or a document over 8 KB is refused, "
                    + "naming the field. A search term, a customer, an order, a courier and an absolute "
                    + "date are never part of a saved view. Nothing is audited.")
    public ResponseEntity<OwnPreferenceResponse> saveMyPreference(
            @PathVariable UUID tenantId,
            @PathVariable String surface,
            @RequestBody SaveOwnPreferenceRequest body,
            HttpServletRequest request) {
        StaffPreferenceSurface known = known(surface);
        limit(tenantId);
        StoredPreference saved = preferences.write(
                tenantId,
                subject(),
                known,
                // The document travels as a tree: the surface's schema, not a binder, decides what an unknown key
                // means.
                body.document() == null ? null : json.valueToTree(body.document()),
                AggregateVersion.fromIfMatch(request).orElse(null));
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(saved.version()))
                .body(OwnPreferenceResponse.of(saved));
    }

    @DeleteMapping("/{surface}")
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Idempotent
    @ApiResponse(responseCode = "204", description = "Reset, or there was nothing saved to reset")
    @Operation(
            summary = "Reset one surface to its default",
            description = "Deletes my stored document for the surface; the console falls back to the code's "
                    + "default. Resetting what was never saved answers the same 204.")
    public ResponseEntity<Void> resetMyPreference(@PathVariable UUID tenantId, @PathVariable String surface) {
        StaffPreferenceSurface known = known(surface);
        limit(tenantId);
        preferences.reset(tenantId, subject(), known);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- plumbing

    private static StaffPreferenceSurface known(String surface) {
        return StaffPreferenceSurface.ofName(surface)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such preference surface"));
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private void limit(UUID tenantId) {
        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key("staff.preferences.write", tenantId.toString(), subject()), WRITE_LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many changes. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    /**
     * The body of a save. The document is one of {@link ShellPreferencesDocument} or {@link
     * OrderBoardPreferencesDocument} by the surface in the path; it travels as a tree so the schema, and not a
     * lenient binder, decides what an unknown key means.
     */
    @Classified(value = DataClass.PERSONAL, reason = "ADR 0160: the document is personal data")
    public record SaveOwnPreferenceRequest(
            @NotNull
            @Classified(DataClass.PERSONAL)
            @Schema(oneOf = {ShellPreferencesDocument.class, OrderBoardPreferencesDocument.class})
            Object document) {

        @Override
        public String toString() {
            return "SaveOwnPreferenceRequest[<redacted>]";
        }
    }

    /**
     * One surface's document, at its schema version and row version.
     *
     * <p>Classified {@code PERSONAL} (ADR 0029, ADR 0160): the document carries a saved view's label, text a
     * person typed, so an idempotent write's stored reply is sealed under the tenant's key like every other
     * reply that holds personal data, and nothing here may reach a log, an event or a metric.
     */
    @Classified(
            value = DataClass.PERSONAL,
            reason =
                    "ADR 0160: a saved view's label is text a person typed, and the platform cannot prove it is not about someone")
    public record OwnPreferenceResponse(
            @Classified(DataClass.PERSONAL)
            @Schema(oneOf = {ShellPreferencesDocument.class, OrderBoardPreferencesDocument.class})
            Object document,

            int documentSchema,
            int version) {

        static OwnPreferenceResponse of(StoredPreference stored) {
            return new OwnPreferenceResponse(stored.document(), stored.documentSchema(), stored.version());
        }

        @Override
        public String toString() {
            return "OwnPreferenceResponse[<redacted>]";
        }
    }

    /** Every surface the person has saved, keyed by the surface's name. */
    @Classified(value = DataClass.PERSONAL, reason = "ADR 0160: the documents are personal data")
    public record OwnPreferencesResponse(Map<String, OwnPreferenceResponse> surfaces) {

        @Override
        public String toString() {
            return "OwnPreferencesResponse[<redacted>]";
        }
    }
}
