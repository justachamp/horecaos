package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.ordering.application.OrderMarkService;
import uz.horecaos.platform.ordering.application.OrderMarkService.CaptureCommand;
import uz.horecaos.platform.ordering.application.OrderMarkService.MarkNotFoundException;
import uz.horecaos.platform.ordering.application.OrderMarkService.MarkRefusedException;
import uz.horecaos.platform.ordering.domain.MarkEntryMethod;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Marked goods at the pass (ADR 0161, gap map row X.35): scan one unit, take a scan back, read the progress.
 *
 * <p>On the weighing endpoint's path prefix and under its capabilities, because they are the same hands at
 * the same moment: {@code order.advance} to write, {@code order.read} to look. No capability is added. Every
 * mutation carries an {@code Idempotency-Key} (ADR 0031) and no {@code If-Match}: a scan does not bump the
 * order's version, because every scan would otherwise make the version the operator's screen holds stale and
 * the advance button would answer {@code STALE} after each unit of a five-unit line.
 *
 * <p><strong>A code only ever travels in a request body.</strong> It is never in a path, a query string, a
 * response, an error or a log line; this class names no field that could carry one back, and its refusals
 * are fixed text with a stable {@code reason}. The read returns the product identifier (GTIN), the sequence
 * and the time, and never the code: there is no human reveal in v1, so there is no reveal capability.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders")
@Tag(name = "Operations orders", description = "The branch's order queue, approvals, and timeline")
public class OperationsOrderMarksController {

    private final OrderMarkService marks;
    private final CurrentActor currentActor;

    public OperationsOrderMarksController(OrderMarkService marks, CurrentActor currentActor) {
        this.marks = marks;
        this.currentActor = currentActor;
    }

    @PostMapping("/{orderId}/lines/{lineId}/marks")
    @RequiresCapability(value = Capability.ORDER_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Capture one scanned unit of a marked order line",
            description = "ADR 0161. Records one marking code (a DataMatrix, scanned at the pass) against a "
                    + "line of an order that is CONFIRMED, PREPARING or READY. The server derives the stage "
                    + "(PICK or HANDOVER) from the status, normalises the code the way the console does, "
                    + "checks the structure per fiscal.marking.structure_check, checks the GTIN against the "
                    + "product's barcode when it has one, and refuses a unit that is already scanned "
                    + "anywhere in the tenant. The response carries the product identifier and the counts, "
                    + "never the code. Takes the order row's lock first, so a scan, a removal and a hand-over "
                    + "serialise. Replaying the same Idempotency-Key returns the first answer. Answers "
                    + "FEATURE_DISABLED (404-shaped) until feature.marked_goods is on for the tenant, and 429 "
                    + "with Retry-After beyond 240 scans a minute per principal.")
    public ResponseEntity<CaptureMarkResponse> capture(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @Valid @RequestBody CaptureMarkRequest body,
            HttpServletResponse response) {
        MarkEntryMethod method = MarkEntryMethod.parse(body.entryMethod())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "entryMethod must be one of SCANNER, CAMERA, KEYED"));
        try {
            OrderMarkService.CaptureResult result = marks.capture(new CaptureCommand(
                    tenantId, brandId, locationId, orderId, lineId, body.code(), method, actorSubject()));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new CaptureMarkResponse(
                            result.markId(),
                            result.sequence(),
                            result.gtin(),
                            result.captured(),
                            result.required(),
                            result.orderMarksComplete(),
                            result.gtinChecked()));
        } catch (MarkRefusedException refused) {
            throw refusal(refused, response);
        } catch (MarkNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/{orderId}/lines/{lineId}/marks/{markId}/removals")
    @RequiresCapability(value = Capability.ORDER_ADVANCE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Take one scanned unit back",
            description = "ADR 0161. Releases -- never deletes -- one scanned unit, with a coded reason "
                    + "(MISSCAN, UNIT_REPLACED, UNIT_DAMAGED) and no free text, while the order is still "
                    + "CONFIRMED, PREPARING or READY; refused as ORDER_NOT_MARKABLE once it has left the pass. "
                    + "Takes the same order-row lock as a scan and a hand-over and reads the status after it. "
                    + "A damaged symbol is answered by scanning another unit, or the order does not leave the "
                    + "pass: this is the way to take a wrong scan back, not a bypass.")
    public ResponseEntity<RemoveMarkResponse> remove(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @PathVariable UUID lineId,
            @PathVariable UUID markId,
            @Valid @RequestBody RemoveMarkRequest body,
            HttpServletResponse response) {
        try {
            OrderMarkService.RemovalResult result =
                    marks.remove(tenantId, locationId, orderId, lineId, markId, body.reasonCode(), actorSubject());
            return ResponseEntity.ok(new RemoveMarkResponse(result.captured(), result.required()));
        } catch (MarkRefusedException refused) {
            throw refusal(refused, response);
        } catch (MarkNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    @GetMapping("/{orderId}/marks")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The marked lines of an order and the units scanned against them",
            description = "ADR 0161. Per marked line: the units required, the units captured, and each captured "
                    + "unit as its sequence, product identifier (GTIN), stage, time and the staff subject that "
                    + "scanned it. Never a code.")
    public ResponseEntity<OrderMarksResponse> read(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            HttpServletResponse response) {
        try {
            List<MarkedLineResponse> lines = marks.read(tenantId, locationId, orderId).stream()
                    .map(MarkedLineResponse::of)
                    .toList();
            return ResponseEntity.ok(new OrderMarksResponse(orderId, lines));
        } catch (MarkRefusedException refused) {
            throw refusal(refused, response);
        } catch (MarkNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    private String actorSubject() {
        return currentActor.get().subject();
    }

    /**
     * A refusal as problem+json: a stable {@code reason}, fixed text, and the few properties a screen needs.
     * {@code FEATURE_DISABLED} is answered as a 404 so a tenant without the capability learns nothing
     * about it, and a limiter refusal carries {@code Retry-After}.
     */
    private static ApiException refusal(MarkRefusedException refused, HttpServletResponse response) {
        Map<String, Object> properties = new HashMap<>(refused.properties());
        properties.put("reason", refused.refusal().name());
        return switch (refused.refusal()) {
            case FEATURE_DISABLED ->
                new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No such resource", Map.of("reason", "FEATURE_DISABLED"));
            case MARKING_CODE_MALFORMED ->
                new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage(), properties);
            case RATE_LIMITED -> {
                Object retryAfter = refused.properties().get("retryAfterSeconds");
                if (retryAfter != null) {
                    response.setHeader("Retry-After", String.valueOf(retryAfter));
                }
                yield new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, refused.getMessage(), properties);
            }
            case MARKING_CODE_WRONG_PRODUCT,
                    MARKING_CODE_ALREADY_CAPTURED,
                    MARKING_KEYED_ENTRY_REFUSED,
                    LINE_NOT_MARKED,
                    LINE_MARKS_COMPLETE,
                    ORDER_NOT_MARKABLE ->
                new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage(), properties);
        };
    }

    /**
     * @param code the string the scanner read, un-normalised; the console clears its own copy in the same tick
     * @param entryMethod {@code SCANNER}, {@code CAMERA} or {@code KEYED}, client-reported and advisory
     */
    public record CaptureMarkRequest(
            @Classified(value = DataClass.FINANCIAL, reason = "a marking code identifies one physical unit")
            @NotBlank
            @Size(max = 600)
            String code,

            @NotBlank String entryMethod) {

        /** Never renders the code: a request logged by accident must not carry it. */
        @Override
        public String toString() {
            return "CaptureMarkRequest[entryMethod=" + entryMethod + "]";
        }
    }

    public record RemoveMarkRequest(@NotBlank String reasonCode) {}

    /**
     * @param gtin the product the code claims, a product identifier and not a secret
     * @param gtinChecked false when the classification states no barcode to compare with
     */
    public record CaptureMarkResponse(
            UUID markId,
            int sequence,
            @Classified(DataClass.INTERNAL) String gtin,
            int captured,
            int required,
            boolean orderMarksComplete,
            boolean gtinChecked) {}

    public record RemoveMarkResponse(int captured, int required) {}

    public record OrderMarksResponse(UUID orderId, List<MarkedLineResponse> lines) {}

    public record MarkedLineResponse(
            UUID orderLineId,
            int lineNumber,
            String productName,
            int required,
            int captured,
            List<MarkResponse> marks) {

        static MarkedLineResponse of(OrderMarkService.LineProgress progress) {
            return new MarkedLineResponse(
                    progress.lineId(),
                    progress.lineNumber(),
                    progress.productName(),
                    progress.required(),
                    progress.captured(),
                    progress.marks().stream().map(MarkResponse::of).toList());
        }
    }

    public record MarkResponse(
            UUID markId,
            int sequence,
            @Classified(DataClass.INTERNAL) String gtin,
            String stage,
            Instant capturedAt,
            String capturedBy) {

        static MarkResponse of(
                uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderMarkStore.LiveMark mark) {
            return new MarkResponse(
                    mark.markId(), mark.sequence(), mark.gtin(), mark.stage(), mark.capturedAt(), mark.capturedBy());
        }
    }
}
