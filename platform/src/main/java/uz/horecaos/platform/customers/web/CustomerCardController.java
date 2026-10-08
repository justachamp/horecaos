package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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
import uz.horecaos.platform.customers.application.ContactAttemptService;
import uz.horecaos.platform.customers.application.ContactAttemptService.ContactAttemptView;
import uz.horecaos.platform.customers.application.CustomerCardAssemblyService;
import uz.horecaos.platform.customers.application.CustomerCardAssemblyService.CustomerCard;
import uz.horecaos.platform.customers.domain.BlockingReason;
import uz.horecaos.platform.customers.domain.ContactDirection;
import uz.horecaos.platform.customers.domain.ContactOutcome;
import uz.horecaos.platform.customers.domain.NextAction;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * One guest's card and the voice journal beside it (ADR 0111 §2, §7, §8).
 *
 * <p>The card is the screen the console's customer detail is built around, and opening it is the
 * act the decision makes auditable: every call to {@code GET .../card} writes one {@code
 * customer.card.viewed} fact for the actor, the account and the purpose, in the same transaction as
 * the read. The existing {@code GET .../customers/{accountId}} profile read is unchanged and is not
 * the card -- it is the header a screen asks for to render a name, and it is also what the New order
 * screen calls to look a caller up, which is not "opening a card" and would drown the one fact that
 * means something.
 *
 * <p>Nothing here returns a number, an address or a message's text. The card shows that a text was
 * sent, to which channel, with what outcome; what a guest wrote in a review and what an operator
 * wrote in a lead's notes stay behind the screens that reveal them.
 */
@RestController
@Validated
@RequestMapping("/api/v1/tenants/{tenantId}/customers/{accountId}")
@Tag(name = "Customer card", description = "One guest's history across the platform, and the call journal (ADR 0111)")
public class CustomerCardController {

    /** Recorded on the card-view fact when the caller states none. */
    static final String DEFAULT_PURPOSE = "Customer card opened";

    private final CustomerCardAssemblyService cards;
    private final ContactAttemptService attempts;
    private final CurrentActor currentActor;

    public CustomerCardController(
            CustomerCardAssemblyService cards, ContactAttemptService attempts, CurrentActor currentActor) {
        this.cards = cards;
        this.attempts = attempts;
        this.currentActor = currentActor;
    }

    @GetMapping("/card")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "Open one guest's card",
            description = "The guest's status, whether she is blacklisted right now, her leads, and a "
                    + "history merged by time from the messages sent to her, the campaigns that reached "
                    + "her, the promotions she redeemed, the reviews she left and the calls an operator "
                    + "made or took -- each owned and read from the module that holds it. Writes one "
                    + "audit fact per call. `before` pages further back: pass the previous page's "
                    + "nextBefore.")
    public CustomerCard card(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @RequestParam(required = false) @Nullable Instant before,
            @RequestParam(required = false) @Nullable Integer limit,
            @RequestParam(required = false) @Nullable @Size(max = 200) String purpose) {
        return cards.open(
                tenantId,
                accountId,
                before,
                Page.limitOrDefault(limit),
                purpose == null || purpose.isBlank() ? DEFAULT_PURPOSE : purpose,
                actor());
    }

    @GetMapping("/contact-attempts")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "Every voice contact about this guest, newest first",
            description =
                    "Against the account itself and against any lead linked to it. Ids, codes and " + "instants only.")
    public List<ContactAttemptView> attempts(
            @PathVariable UUID tenantId,
            @PathVariable UUID accountId,
            @RequestParam(required = false) @Nullable Instant before,
            @RequestParam(required = false) @Nullable Integer limit) {
        return attempts.forCustomer(tenantId, accountId, before, Page.limitOrDefault(limit));
    }

    @PostMapping("/contact-attempts")
    @RequiresCapability(value = Capability.CUSTOMER_LEAD_MANAGE, mutating = true)
    @Operation(
            summary = "Record a call with a guest who is already an account",
            description = "Append-only, as a lead's. The brand is the brand's own line the call came in on "
                    + "or went out from, named in the body because an account is the tenant's.")
    public ResponseEntity<ContactAttemptView> record(
            @PathVariable UUID tenantId, @PathVariable UUID accountId, @Valid @RequestBody RecordAttemptRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attempts.recordForCustomer(tenantId, body.brandId(), accountId, body.toRecording(), actor()));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /** {@link LeadController.ContactAttemptRequest} plus the brand a customer-bound call belongs to. */
    public record RecordAttemptRequest(
            @NotNull UUID brandId,
            @NotNull ContactDirection direction,
            @NotNull ContactOutcome outcome,
            @Nullable BlockingReason blockingReason,
            @Nullable UUID attemptId,
            @Nullable Instant occurredAt,
            @Nullable NextAction nextAction,
            @Nullable Instant nextActionAt) {

        ContactAttemptService.Recording toRecording() {
            return new ContactAttemptService.Recording(
                    direction, outcome, blockingReason, attemptId, occurredAt, nextAction, nextActionAt);
        }
    }
}
