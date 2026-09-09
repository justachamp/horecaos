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
 * {@code kitchen.web.KitchenDeviceController}, behind {@code
 * kitchen.station.manage}, because approving a device is a decision about one
 * branch's kitchen, and {@code iam} does not know what a kitchen is.
 */
@RestController
@RequestMapping("/api/v1/control-plane/device-enrolments")
@Tag(name = "Device enrolment", description = "ADR 0079: how an unenrolled device gets a Keycloak identity")
public class DeviceEnrolmentController {

    private final DeviceEnrolmentPort enrolment;

    public DeviceEnrolmentController(DeviceEnrolmentPort enrolment) {
        this.enrolment = enrolment;
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
        return ResponseEntity.ok(new BeginResponse(
                result.deviceCode(), result.userCode(), result.expiresAt(), result.pollIntervalSeconds()));
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

    record BeginResponse(String deviceCode, String userCode, Instant expiresAt, int pollIntervalSeconds) {}

    record CredentialResponse(String tokenEndpoint, String clientId, String clientSecret) {}

    record PollResponse(String status, @Nullable CredentialResponse credential) {}
}
