package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.api.LeadSource;
import uz.horecaos.platform.customers.application.ContactAttemptService;
import uz.horecaos.platform.customers.application.ContactAttemptService.ContactAttemptView;
import uz.horecaos.platform.customers.application.ContactAttemptService.Recording;
import uz.horecaos.platform.customers.application.LeadService;
import uz.horecaos.platform.customers.application.LeadService.LeadPage;
import uz.horecaos.platform.customers.application.LeadService.LeadView;
import uz.horecaos.platform.customers.application.LeadService.NewLeadCommand;
import uz.horecaos.platform.customers.application.LeadService.Query;
import uz.horecaos.platform.customers.application.LeadService.RevealedLeadContact;
import uz.horecaos.platform.customers.application.LeadService.Transition;
import uz.horecaos.platform.customers.domain.BlockingReason;
import uz.horecaos.platform.customers.domain.ContactDirection;
import uz.horecaos.platform.customers.domain.ContactOutcome;
import uz.horecaos.platform.customers.domain.LeadClosedReason;
import uz.horecaos.platform.customers.domain.LeadStatus;
import uz.horecaos.platform.customers.domain.NextAction;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.Cursor;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.Reach;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The call centre's lead queue (ADR 0111 §4-§7), at the two levels a grant of {@code
 * customer.lead.read}/{@code customer.lead.manage} can sit.
 *
 * <p>A grant covers only the routes whose path names its own level (ADR 0025), so the queue is
 * reached twice. The <em>brand</em> routes are the call centre's: the whole of a brand's queue,
 * unassigned leads included, where a lead is registered, handed to a branch and worked. The
 * <em>branch</em> routes are the branch's: the leads handed to that branch and no others, which it
 * reads, works and converts, but cannot hand elsewhere -- a branch does not decide who else gets a
 * lead it was given, and a lead it cannot serve moves to {@code DECLINED} with a reason rather than
 * to a different branch (ADR 0111 §6). Both are written against the same {@link Reach}, so what a
 * branch cannot see is not a row its query could return.
 *
 * <p>B2B catering enquiries are leads of source {@code B2B_CATERING_ENQUIRY} in this same queue:
 * the call centre owns them (ADR 0111's default for its fourth open input), with no quoting or
 * proposal workflow behind them -- the lead is the shape that would hold one.
 *
 * <p>Nothing in a list or a detail decrypts. The number, the name and the notes are one purpose-
 * stamped, audited read ({@code .../contact}) and need {@code customer.pii.reveal} as well.
 */
@RestController
@Validated
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Leads", description = "The call centre's callback queue and its contact journal (ADR 0111)")
public class LeadController {

    private static final Set<LeadStatus> NO_STATUSES = EnumSet.noneOf(LeadStatus.class);

    private final LeadService leads;
    private final ContactAttemptService attempts;
    private final CurrentActor currentActor;

    public LeadController(LeadService leads, ContactAttemptService attempts, CurrentActor currentActor) {
        this.leads = leads;
        this.attempts = attempts;
        this.currentActor = currentActor;
    }

    // ============================================================= the brand's queue

    @GetMapping("/leads")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's lead queue, newest first",
            description = "Cursor-paginated per ADR 0031. Never a phone number, a name or a note: the row "
                    + "carries a masked number and whether a name and notes exist. view=attention "
                    + "narrows it to what somebody owes a call -- new leads and scheduled callbacks that "
                    + "are due or about to be (customer.lead.callback_reminder_minutes).")
    public Page<LeadView> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable Set<LeadStatus> status,
            @RequestParam(required = false) @Nullable LeadSource source,
            @RequestParam(required = false) @Nullable UUID assignedLocationId,
            @RequestParam(required = false, defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) @Nullable UUID customerAccountId,
            @RequestParam(required = false) @Nullable String view,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return page(
                tenantId,
                Reach.brand(brandId),
                status,
                source,
                assignedLocationId,
                unassigned,
                customerAccountId,
                view,
                cursor,
                limit);
    }

    @PostMapping("/leads")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Register a lead by hand",
            description = "A phoned-in catering enquiry, a callback somebody asked for, a message from "
                    + "somewhere the platform does not yet listen to. The number is hashed under the "
                    + "same domain as a customer's phone, so the detail can say it is also an account's; "
                    + "that is a hint to confirm, never a link. A campaign scenario's call task is "
                    + "never registered by hand.")
    public ResponseEntity<LeadView> register(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody RegisterLeadRequest body) {
        LeadView lead = leads.register(
                new NewLeadCommand(
                        tenantId,
                        brandId,
                        body.source(),
                        body.phone(),
                        body.displayName(),
                        body.notes(),
                        body.customerAccountId(),
                        body.assignedLocationId(),
                        null,
                        null),
                actor());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(lead.version()))
                .body(lead);
    }

    @GetMapping("/leads/{leadId}")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One lead",
            description = "With the accounts and the other open leads that hold the same number, as a "
                    + "hint for the operator to confirm. Never the number itself.")
    public ResponseEntity<LeadView> detail(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID leadId) {
        return versioned(leads.find(tenantId, Reach.brand(brandId), leadId));
    }

    @PostMapping("/leads/{leadId}/transitions")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Move a lead along its status machine",
            description = "Needs If-Match with the lead's version. CONVERTED needs exactly one of an order "
                    + "and a reservation of this brand; DECLINED and LOST need a coded reason; "
                    + "CALLBACK_SCHEDULED needs a time. An edge the machine does not have is 422.")
    public ResponseEntity<LeadView> transition(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID leadId,
            @Valid @RequestBody TransitionLeadRequest body,
            HttpServletRequest request) {
        LeadView lead = leads.transition(
                tenantId, Reach.brand(brandId), leadId, body.toTransition(), version(request), actor());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(lead.version())).body(lead);
    }

    @PostMapping("/leads/{leadId}/assignment")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Hand a lead to a branch of this brand",
            description = "A branch, not an operator: there is no per-operator load to route against. "
                    + "Allowed again while the lead is open, because reassigning is manual. Needs If-Match.")
    public ResponseEntity<LeadView> assign(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID leadId,
            @Valid @RequestBody AssignLeadRequest body,
            HttpServletRequest request) {
        LeadView lead =
                leads.assign(tenantId, Reach.brand(brandId), leadId, body.locationId(), version(request), actor());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(lead.version())).body(lead);
    }

    @PostMapping("/leads/{leadId}/customer")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Identify the guest behind a lead",
            description = "The operator's confirmation of the hint the detail gives (an account holding the "
                    + "same number): the lead is linked to that account, appears on its card, and is "
                    + "erased with it. Never made by the platform on a number match alone. Allowed at "
                    + "any status; an account merged away is followed to the account it became. "
                    + "Needs If-Match with the lead's version.")
    public ResponseEntity<LeadView> linkCustomer(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID leadId,
            @Valid @RequestBody LinkLeadCustomerRequest body,
            HttpServletRequest request) {
        LeadView lead = leads.linkCustomer(
                tenantId, Reach.brand(brandId), leadId, body.customerAccountId(), version(request), actor());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(lead.version())).body(lead);
    }

    @GetMapping("/leads/{leadId}/contact")
    @RequiresCapability(value = Capability.CUSTOMER_PII_REVEAL, scope = ScopeType.BRAND)
    @Operation(
            summary = "Reveal a lead's number, name and notes",
            description = "The one decrypt a lead has. One audit fact for the call, naming the purpose, "
                    + "written before anything is decrypted. The purpose travels in the query because "
                    + "it is not personal data; the number never does.")
    public RevealedLeadContact contact(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID leadId,
            @RequestParam @NotBlank @Size(max = 200) String purpose) {
        return leads.reveal(tenantId, Reach.brand(brandId), leadId, purpose, actor());
    }

    @GetMapping("/leads/{leadId}/contact-attempts")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_READ, scope = ScopeType.BRAND)
    @Operation(summary = "The voice contacts recorded against a lead, newest first")
    public List<ContactAttemptView> attempts(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID leadId,
            @RequestParam(required = false) @Nullable Integer limit) {
        return attempts.forLead(tenantId, Reach.brand(brandId), leadId, Page.limitOrDefault(limit));
    }

    @PostMapping("/leads/{leadId}/contact-attempts")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Record a call about a lead",
            description = "Append-only: there is no way to edit or remove one afterwards, and the "
                    + "database grant forbids it. A refused attempt names why; a retried submit under "
                    + "the same attemptId is one attempt.")
    public ResponseEntity<ContactAttemptView> recordAttempt(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID leadId,
            @Valid @RequestBody ContactAttemptRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attempts.recordForLead(tenantId, Reach.brand(brandId), leadId, body.toRecording(), actor()));
    }

    // ================================================================ a branch's own

    @GetMapping("/locations/{locationId}/leads")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The leads handed to this branch, newest first",
            description = "Only leads whose assigned branch is this one -- the query is written against "
                    + "the branch, so another branch's lead is not a row it could return.")
    public Page<LeadView> listForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable Set<LeadStatus> status,
            @RequestParam(required = false) @Nullable LeadSource source,
            @RequestParam(required = false) @Nullable String view,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return page(
                tenantId, Reach.location(brandId, locationId), status, source, null, false, null, view, cursor, limit);
    }

    @GetMapping("/locations/{locationId}/leads/{leadId}")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "One lead handed to this branch; not-found for any other")
    public ResponseEntity<LeadView> detailForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID leadId) {
        return versioned(leads.find(tenantId, Reach.location(brandId, locationId), leadId));
    }

    @PostMapping("/locations/{locationId}/leads/{leadId}/transitions")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Work a lead handed to this branch",
            description = "As the brand route, for a lead this branch was handed. A branch that cannot "
                    + "serve a lead declines it with a reason; it does not pass it on.")
    public ResponseEntity<LeadView> transitionForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID leadId,
            @Valid @RequestBody TransitionLeadRequest body,
            HttpServletRequest request) {
        LeadView lead = leads.transition(
                tenantId, Reach.location(brandId, locationId), leadId, body.toTransition(), version(request), actor());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(lead.version())).body(lead);
    }

    @PostMapping("/locations/{locationId}/leads/{leadId}/customer")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Identify the guest behind a lead handed to this branch",
            description = "As the brand route, for a lead this branch was handed: the branch that rings the "
                    + "guest is the one that learns who she is.")
    public ResponseEntity<LeadView> linkCustomerForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID leadId,
            @Valid @RequestBody LinkLeadCustomerRequest body,
            HttpServletRequest request) {
        LeadView lead = leads.linkCustomer(
                tenantId,
                Reach.location(brandId, locationId),
                leadId,
                body.customerAccountId(),
                version(request),
                actor());
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(lead.version())).body(lead);
    }

    @GetMapping("/locations/{locationId}/leads/{leadId}/contact")
    @RequiresCapability(value = Capability.CUSTOMER_PII_REVEAL, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Reveal the number of a lead handed to this branch",
            description = "As the brand route: purpose-stamped, audited, and only for this branch's own leads.")
    public RevealedLeadContact contactForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID leadId,
            @RequestParam @NotBlank @Size(max = 200) String purpose) {
        return leads.reveal(tenantId, Reach.location(brandId, locationId), leadId, purpose, actor());
    }

    @GetMapping("/locations/{locationId}/leads/{leadId}/contact-attempts")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "The voice contacts recorded against a lead handed to this branch")
    public List<ContactAttemptView> attemptsForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID leadId,
            @RequestParam(required = false) @Nullable Integer limit) {
        return attempts.forLead(tenantId, Reach.location(brandId, locationId), leadId, Page.limitOrDefault(limit));
    }

    @PostMapping("/locations/{locationId}/leads/{leadId}/contact-attempts")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Record a call about a lead handed to this branch",
            description = "Append-only, as the brand route.")
    public ResponseEntity<ContactAttemptView> recordAttemptForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID leadId,
            @Valid @RequestBody ContactAttemptRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attempts.recordForLead(
                        tenantId, Reach.location(brandId, locationId), leadId, body.toRecording(), actor()));
    }

    // ===================================================================== helpers

    private Page<LeadView> page(
            UUID tenantId,
            Reach reach,
            @Nullable Set<LeadStatus> statuses,
            @Nullable LeadSource source,
            @Nullable UUID assignedLocationId,
            boolean unassigned,
            @Nullable UUID customerAccountId,
            @Nullable String view,
            @Nullable String cursor,
            @Nullable Integer limit) {
        boolean attention = false;
        if (view != null) {
            if (!"attention".equals(view)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown view \"%s\"".formatted(view));
            }
            attention = true;
        }
        int pageSize = Page.limitOrDefault(limit);
        LeadPage found = leads.list(
                tenantId,
                reach,
                new Query(
                        statuses == null ? NO_STATUSES : statuses,
                        source,
                        assignedLocationId,
                        unassigned,
                        customerAccountId,
                        attention),
                decode(cursor),
                pageSize);
        return new Page<>(found.items(), found.next() == null ? null : encode(found.next()));
    }

    private static ResponseEntity<LeadView> versioned(java.util.Optional<LeadView> lead) {
        LeadView found = lead.orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such lead"));
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(found.version()))
                .body(found);
    }

    private static int version(HttpServletRequest request) {
        return (int) AggregateVersion.requireIfMatch(request);
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String encode(Cursor cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((cursor.createdAt() + "|" + cursor.id()).getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable Cursor decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = decoded.indexOf('|');
            return new Cursor(Instant.parse(decoded.substring(0, bar)), UUID.fromString(decoded.substring(bar + 1)));
        } catch (RuntimeException unusable) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "That cursor is not one this list issued");
        }
    }

    // ===================================================================== bodies

    /**
     * @param source one of the channel sources; a campaign scenario's call task is refused
     * @param phone  as the guest gave it; it travels in the body and never in a path or a query
     * @param notes  what the operator wrote, ADR 0029 personal data, encrypted at rest
     */
    public record RegisterLeadRequest(
            @NotNull LeadSource source,
            @NotBlank @Size(max = 32) String phone,
            @Size(max = 200) @Nullable String displayName,
            @Size(max = 2000) @Nullable String notes,
            @Nullable UUID customerAccountId,
            @Nullable UUID assignedLocationId) {

        /** A record's generated {@code toString} would print the number, the name and the notes. */
        @Override
        public String toString() {
            return "RegisterLeadRequest[source=" + source + "]";
        }
    }

    /**
     * What an operator asks of the status machine.
     *
     * @param target             the status to move to
     * @param callbackDueAt      required for {@code CALLBACK_SCHEDULED}
     * @param convertedOrderId   exactly one of this and {@code convertedReservationId} for {@code CONVERTED}
     * @param closedReason       required for {@code DECLINED} and {@code LOST}
     * @param reason             an optional sentence for the audit trail; never a guest's name or number
     */
    public record TransitionLeadRequest(
            @NotNull LeadStatus target,
            @Nullable Instant callbackDueAt,
            @Nullable UUID convertedOrderId,
            @Nullable UUID convertedReservationId,
            @Nullable LeadClosedReason closedReason,
            @Size(max = 500) @Nullable String reason) {

        Transition toTransition() {
            return new Transition(
                    target, callbackDueAt, convertedOrderId, convertedReservationId, closedReason, reason);
        }
    }

    public record AssignLeadRequest(@NotNull UUID locationId) {}

    /** The account the operator confirmed the lead is. */
    public record LinkLeadCustomerRequest(@NotNull UUID customerAccountId) {}

    /**
     * @param attemptId a client-chosen id that makes a retried submit one attempt, or absent to have one minted
     * @param occurredAt when the call happened, defaulting to now
     */
    public record ContactAttemptRequest(
            @NotNull ContactDirection direction,
            @NotNull ContactOutcome outcome,
            @Nullable BlockingReason blockingReason,
            @Nullable UUID attemptId,
            @Nullable Instant occurredAt,
            @Nullable NextAction nextAction,
            @Nullable Instant nextActionAt) {

        Recording toRecording() {
            return new Recording(direction, outcome, blockingReason, attemptId, occurredAt, nextAction, nextActionAt);
        }
    }
}
