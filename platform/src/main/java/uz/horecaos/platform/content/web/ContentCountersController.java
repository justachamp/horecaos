package uz.horecaos.platform.content.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.content.application.ContentStatsService;
import uz.horecaos.platform.content.application.ContentStatsService.Accepted;
import uz.horecaos.platform.content.application.ContentStatsService.CounterEvent;
import uz.horecaos.platform.content.application.ContentStatsService.Reported;

/**
 * Anonymous view counts from the storefront (ADR 0157, Decision 10; Release 2).
 *
 * <p>A mutating endpoint with <strong>no principal</strong>: opened by exact path in
 * {@code SecurityConfiguration} and a named exemption in {@code EndpointCapabilityDeclarationTests}.
 * It takes no {@code Idempotency-Key} -- the interceptor scopes a key by the calling subject and an
 * anonymous caller has none -- because the counters are client-reported, labelled so and inflatable
 * by anyone, so a retried batch counting twice is inside what is already accepted. What bounds it is
 * the batch cap of twenty and the per-IP budget on the {@code @storefront_content_events} path at
 * the edge. It stores no identifier: not a device, a customer, an address or a network.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/content")
@Tag(name = "Storefront content", description = "The home content a brand has published")
public class ContentCountersController {

    private final ContentStatsService stats;

    public ContentCountersController(ContentStatsService stats) {
        this.stats = stats;
    }

    @PostMapping("/events")
    @Operation(
            summary = "Report anonymous view counts",
            description = "A batch of at most 20 {itemId, revisionNo, event}, event one of IMPRESSION, CLICK, "
                    + "DISMISSAL (a pop-up) and COMPLETION (a story). Counted per item, revision, tenant day and "
                    + "channel; no identifier of any kind is stored. Client-reported and labelled so: anyone can "
                    + "inflate them, and they are not a metric the reporting layer consumes. An event naming an "
                    + "item or revision the brand does not have, or one that makes no sense for the kind, is "
                    + "rejected and counted and the rest of the batch is kept.")
    public ResponseEntity<AcceptedResponse> events(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody EventsRequest body) {
        Accepted accepted = stats.record(
                tenantId,
                brandId,
                body.channel(),
                body.events().stream()
                        .map(e -> new Reported(e.itemId(), e.revisionNo(), CounterEvent.valueOf(e.event())))
                        .toList());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new AcceptedResponse(accepted.accepted(), accepted.rejected()));
    }

    public record EventsRequest(
            @NotBlank @Size(max = 32) String channel,

            @NotNull @Size(min = 1, max = ContentStatsService.MAX_BATCH)
            List<@Valid EventEntry> events) {}

    public record EventEntry(
            @NotNull UUID itemId,
            @NotNull @Min(1) Integer revisionNo,

            @NotNull @Pattern(regexp = "IMPRESSION|CLICK|DISMISSAL|COMPLETION")
            String event) {}

    public record AcceptedResponse(int accepted, int rejected) {}
}
