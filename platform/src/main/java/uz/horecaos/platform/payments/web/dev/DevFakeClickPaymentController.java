package uz.horecaos.platform.payments.web.dev;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.payments.infrastructure.click.fake.FakeCustomerPaymentService;
import uz.horecaos.platform.payments.infrastructure.click.fake.FakeCustomerPaymentService.FakePaymentResult;
import uz.horecaos.platform.payments.infrastructure.click.fake.FakeCustomerPaymentService.NoPayableClickAttemptException;

/**
 * The dev-loop's "customer pays" button — {@code local} profile only, and
 * unmistakably not a real capability (ADR 0007, ADR 0013).
 *
 * <p><strong>Impossible to ship, by three independent things.</strong> {@code
 * @Profile("local")} means this bean, and the URL it answers, do not exist unless
 * the {@code local} profile is active — the same guard {@code
 * FakeClickProviderConfiguration} uses for the fake provider this pays through.
 * {@code @Hidden} keeps it out of the published OpenAPI document even if that ever
 * changes, the same reason {@code ClickShopApiController} carries it. And the name
 * — package, class, and path all say {@code dev}/{@code fake} — so nobody mistakes
 * this for a capability-gated production endpoint the way an unlabeled shortcut
 * could be.
 *
 * <p>Declares no capability (ADR 0025) because it is not a tenant operation; a
 * dedicated {@code local}-only security chain permits it instead of asking for a
 * bearer token no developer running this against their own laptop has any reason
 * to mint. See {@code DevFakeClickPaymentSecurity}.
 */
@RestController
@Profile("local")
@Hidden
@RequestMapping("/dev/fake-providers/click")
public class DevFakeClickPaymentController {

    private final FakeCustomerPaymentService fakeCustomer;

    public DevFakeClickPaymentController(FakeCustomerPaymentService fakeCustomer) {
        this.fakeCustomer = fakeCustomer;
    }

    /**
     * Pays whichever CLICK payment session is currently open on this order, the
     * way {@code make seed-payments}' printed dev loop and {@code
     * tools/pay-fake-click} both drive it.
     */
    @PostMapping("/tenants/{tenantId}/orders/{orderId}/pay")
    public FakePaymentResult pay(@PathVariable UUID tenantId, @PathVariable UUID orderId) {
        return fakeCustomer.payOpenClickAttempt(tenantId, orderId);
    }

    @ExceptionHandler(NoPayableClickAttemptException.class)
    public ResponseEntity<ProblemDetail> noPayableAttempt(NoPayableClickAttemptException failure) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.CONFLICT, failure.getMessage());
        problem.setTitle("NO_PAYABLE_CLICK_ATTEMPT");
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                .body(problem);
    }
}
