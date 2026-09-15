package uz.horecaos.platform.loyalty.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.AdjustmentCommand;
import uz.horecaos.platform.loyalty.application.LoyaltyQueryService;

/**
 * {@link LoyaltyOperationsController#adjust}, against mocked collaborators —
 * matching {@code OrderLatenessPolicyControllerTests}' own hand-rolled style
 * rather than the full HTTP stack, since what this proves is which of two
 * in-process values the handler reaches for.
 *
 * <p>Wave P40 adversarial review, finding high/1: {@code adjust()} used to
 * build the ADR 0027 audit {@code ActorRef} from a client-supplied {@code
 * actorSubject} field on the request body, so any caller holding {@code
 * LOYALTY_ADJUST} could write a false name into the audit trail for a balance
 * movement somebody else made. {@code actorSubject} stays on the wire — {@code
 * OpenApiContractTests} refuses to drop a published required field — but
 * {@code adjust()} never reads it; the actor is always {@link CurrentActor} —
 * the authenticated caller.
 */
class LoyaltyOperationsControllerTests {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID BRAND_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    /** The subject on the authenticated caller's own token — never client-supplied. */
    private static final String AUTHENTICATED_SUBJECT = "018fd700-4000-7000-8000-0000000000e1";

    @Test
    @SuppressWarnings("unchecked")
    void theAuditActorIsAlwaysTheAuthenticatedCallerNeverAValueFromTheRequestBody() {
        LoyaltyAdjustmentService adjustments = mock(LoyaltyAdjustmentService.class);
        CurrentActor currentActor = () -> new AuthenticatedActor(AUTHENTICATED_SUBJECT, Set.of(), Map.of());
        LoyaltyOperationsController controller =
                new LoyaltyOperationsController(mock(LoyaltyQueryService.class), adjustments, currentActor);

        when(adjustments.adjust(any(AdjustmentCommand.class))).thenReturn(new ApprovalOutcome.NotRequired());

        // A forged actorSubject, deliberately different from AUTHENTICATED_SUBJECT: the
        // field is still on the wire (OpenApiContractTests refuses to drop a published
        // required field), so the regression this guards against is the server reading
        // it back rather than ignoring it.
        LoyaltyOperationsController.AdjustmentRequest request = new LoyaltyOperationsController.AdjustmentRequest(
                BRAND_ID,
                5_000,
                "UZS",
                "GOODWILL",
                "Order arrived cold",
                "a-different-forged-subject",
                "idem-key-1",
                "");

        controller.adjust(TENANT_ID, CUSTOMER_ID, request);

        ArgumentCaptor<AdjustmentCommand> captured = ArgumentCaptor.forClass(AdjustmentCommand.class);
        verify(adjustments).adjust(captured.capture());
        assertThat(captured.getValue().actor().subject())
                .as("the audit actor must be the authenticated caller, with no path for a request "
                        + "field to name somebody else")
                .isEqualTo(AUTHENTICATED_SUBJECT);
    }
}
