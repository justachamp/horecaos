package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;

/**
 * How an unenrolled device gets a Keycloak identity (ADR 0079).
 *
 * <p>Both endpoints are unauthenticated, and unavoidably so: a device calling
 * either one holds no credential yet — asking for one is what happens
 * <em>before</em> there is a principal, which is the identical reasoning ADR
 * 0015's pre-account customer identity endpoints already carry. {@code
 * SecurityConfiguration} permits exactly these two paths, and {@code
 * EndpointCapabilityDeclarationTests} exempts them by the same reasoning as
 * that controller's own exemption.
 *
 * <p>What authorizes the flow is never this controller: the
 * human-in-the-loop half of enrolment — approving a pending code — lives in
 * the module that owns the class: {@code kitchen.web.KitchenDeviceController}, behind {@code
 * kitchen.station.manage}, for the two kitchen displays, and the tenancy module's kiosk
 * controller, behind {@code kiosk.device.manage}, for a kiosk (ADR 0162). Approving a device is a
 * decision about what that device will be allowed to do at one branch.
 */
@RestController
@RequestMapping("/api/v1/control-plane/device-enrolments")
@Tag(name = "Device enrolment", description = "ADR 0079: how an unenrolled device gets a Keycloak identity")
public class DeviceEnrolmentController {

    /**
     * The route of the console's approval page for a kiosk (ADR 0162), under the operations origin. The
     * one place a pairing link is built, so the link is the configured origin plus this path plus the
     * pending user code and nothing a device or a proxy header supplied.
     */
    static final String KIOSK_APPROVAL_PATH = "/settings/channel-setup/kiosk-pairing";

    private final DeviceEnrolmentPort enrolment;
    private final String operationsOrigin;

    public DeviceEnrolmentController(
            DeviceEnrolmentPort enrolment,
            @Value("${horecaos.frontends.operations-origin:http://localhost:4200}") String operationsOrigin) {
        this.enrolment = enrolment;
        this.operationsOrigin = operationsOrigin.endsWith("/")
                ? operationsOrigin.substring(0, operationsOrigin.length() - 1)
                : operationsOrigin;
    }

    @PostMapping
    @Operation(
            summary = "Begin enrolling a device",
            description = "Called by the device itself, before it holds any credential. Returns a "
                    + "deviceCode for the device to poll with and a short userCode for a person to "
                    + "read off the screen and approve from the operations console.")
    public ResponseEntity<BeginResponse> begin(@Valid @RequestBody BeginRequest body, HttpServletRequest request) {

        var result = enrolment.beginEnrolment(
                new DeviceEnrolmentPort.BeginEnrolment(body.deviceClass(), body.label()), callerKey(request));
        // ADR 0162: for a kiosk only, a link the manager's own phone camera can open. The kitchen display's
        // code stays typed (ADR 0079/0119) until a record says otherwise, and the approval page refuses
        // any class but KIOSK, so no link is offered for the others. The device code is never in it.
        String approvalUrl = body.deviceClass() == DevicePrincipalClass.KIOSK
                ? operationsOrigin + KIOSK_APPROVAL_PATH + "?code=" + result.userCode()
                : null;
        return ResponseEntity.ok(new BeginResponse(
                result.deviceCode(), result.userCode(), result.expiresAt(), result.pollIntervalSeconds(), approvalUrl));
    }

    @PostMapping("/{deviceCode}/poll")
    @Operation(
            summary = "Poll for approval",
            description = "The first poll to observe approved receives the device's Keycloak client "
                    + "credential; every later poll of the same code, including a retry of the winning "
                    + "one, receives none. The device stores the credential and authenticates directly "
                    + "against tokenEndpoint from then on.")
    public ResponseEntity<PollResponse> poll(@PathVariable String deviceCode, HttpServletRequest request) {
        var result = enrolment.poll(deviceCode, callerKey(request));
        return ResponseEntity.ok(new PollResponse(
                result.status().name(),
                result.credential() == null
                        ? null
                        : new CredentialResponse(
                                result.credential().tokenEndpoint(),
                                result.credential().clientId(),
                                result.credential().clientSecret())));
    }

    /**
     * An opaque, stable handle for the caller, for ADR 0033 rate limiting and
     * nothing else. The identical construction and the identical reasoning as
     * {@code StorefrontCustomerIdentityController.callerKey}: hashed here so
     * no address reaches a domain module, and {@code getRemoteAddr} is the
     * real client because {@code server.forward-headers-strategy: framework}
     * applies {@code X-Forwarded-For} before a handler sees the request.
     */
    private static String callerKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            return "unattributed";
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record BeginRequest(
            @NotNull DevicePrincipalClass deviceClass,
            @Nullable @Size(max = 120) String label) {}

    /**
     * @param approvalUrl the console page a manager's phone opens from the QR (ADR 0162); present for a kiosk
     *                    request only, built from the configured operations origin, and never containing the
     *                    device code
     */
    record BeginResponse(
            String deviceCode,
            String userCode,
            Instant expiresAt,
            int pollIntervalSeconds,
            @Nullable String approvalUrl) {}

    record CredentialResponse(String tokenEndpoint, String clientId, String clientSecret) {}

    record PollResponse(String status, @Nullable CredentialResponse credential) {}
}
