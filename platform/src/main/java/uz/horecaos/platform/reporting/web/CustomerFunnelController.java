package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.api.AttributionClickSeries.LinkClicks;
import uz.horecaos.platform.reporting.application.CustomerFunnelReportService;
import uz.horecaos.platform.reporting.application.CustomerFunnelReportService.AcquisitionResult;
import uz.horecaos.platform.reporting.application.CustomerFunnelReportService.FunnelResult;
import uz.horecaos.platform.reporting.application.CustomerFunnelReportService.Stage;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore.FunnelDay;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore.RegistrationDay;
import uz.horecaos.platform.reporting.web.ReportingController.ProvenanceResponse;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Row 7.6c: the customer funnel and the acquisition-source chart (ADR 0167).
 *
 * <p>Both are reads behind {@code reporting.read}, and neither response contains a person. The
 * funnel's first stage, visits, is returned as {@code measured: false} with a reason and no count,
 * because nothing is collected from a browser and a zero would be believed. The acquisition read
 * carries {@code attributesCustomerToLink: false} so the sentence the screen shows ("this does not
 * say which customer a link brought") is a fact of the contract and not only a caption.
 *
 * <p>Both take an optional {@code brandId}; a brand that is not this tenant's answers 404, the same
 * answer as one that does not exist.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/reporting")
@Tag(
        name = "Customer funnel",
        description = "Registrations, baskets, checkouts and orders; acquisition by source (ADR 0167)")
public class CustomerFunnelController {

    private final CustomerFunnelReportService funnel;

    public CustomerFunnelController(CustomerFunnelReportService funnel) {
        this.funnel = funnel;
    }

    @GetMapping("/customer-funnel")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "7.6c: the daily customer funnel, visits to orders, from server-side facts",
            description = "Stages are counts of distinct events on a business day, per brand and sales "
                    + "channel, and not a cohort: a ratio between two of them is a rate of activity and "
                    + "not the probability that one person converts. The visits stage is always "
                    + "measured=false (reason NOT_COLLECTED) with no count: nothing is collected from "
                    + "a browser, an app or a bot for this purpose. A basket or checkout count that is "
                    + "null on a day means 'not recorded' (its carts were past retention when the "
                    + "capture ran) and is never zero; the stage reports how many days are unrecorded.")
    public ResponseEntity<FunnelResponse> customerFunnel(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable String channel) {

        return ResponseEntity.ok(FunnelResponse.of(funnel.funnel(tenantId, from, to, brandId, channel)));
    }

    @GetMapping("/acquisition-sources")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "7.6c: registrations by origin, clicks by attribution link, orders by channel",
            description = "Three counts side by side. They are not joined: nothing here says which "
                    + "customer or order a link brought (attributesCustomerToLink is false), because "
                    + "recording that on a person waits for a legal answer (ADR 0167 Decision 4).")
    public ResponseEntity<AcquisitionResponse> acquisitionSources(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Nullable UUID brandId) {

        return ResponseEntity.ok(AcquisitionResponse.of(funnel.acquisition(tenantId, from, to, brandId)));
    }

    public record StageResponse(
            String stage,
            boolean measured,
            @Nullable String reason,
            @Nullable Long count,
            int unrecordedDays) {

        static StageResponse of(Stage stage) {
            return new StageResponse(
                    stage.stage(), stage.measured(), stage.reason(), stage.count(), stage.unrecordedDays());
        }
    }

    /** A null count is "not recorded", never zero. */
    public record FunnelDayResponse(
            LocalDate businessDate,
            UUID brandId,
            String channelCode,
            @Nullable Integer basketStarted,
            @Nullable Integer checkoutStarted,
            @Nullable Integer ordered) {

        static FunnelDayResponse of(FunnelDay day) {
            return new FunnelDayResponse(
                    day.businessDate(),
                    day.brandId(),
                    day.channelCode(),
                    day.basketStarted(),
                    day.checkoutStarted(),
                    day.ordered());
        }
    }

    public record RegistrationDayResponse(LocalDate businessDate, UUID brandId, String origin, int registered) {

        static RegistrationDayResponse of(RegistrationDay day) {
            return new RegistrationDayResponse(day.businessDate(), day.brandId(), day.origin(), day.registered());
        }
    }

    public record FunnelResponse(
            LocalDate from,
            LocalDate to,
            List<StageResponse> stages,
            List<FunnelDayResponse> days,
            List<RegistrationDayResponse> registrations,
            ProvenanceResponse provenance) {

        static FunnelResponse of(FunnelResult result) {
            return new FunnelResponse(
                    result.from(),
                    result.to(),
                    result.stages().stream().map(StageResponse::of).toList(),
                    result.days().stream().map(FunnelDayResponse::of).toList(),
                    result.registrations().stream()
                            .map(RegistrationDayResponse::of)
                            .toList(),
                    ProvenanceResponse.of(result.provenance()));
        }
    }

    public record OriginCountResponse(String origin, long registered) {}

    public record LinkClicksResponse(
            UUID linkId, UUID brandId, String label, String channel, String status, long clicks) {

        static LinkClicksResponse of(LinkClicks row) {
            return new LinkClicksResponse(
                    row.linkId(), row.brandId(), row.label(), row.channel(), row.status(), row.clicks());
        }
    }

    public record ChannelCountResponse(String channelCode, long ordered) {}

    public record AcquisitionResponse(
            LocalDate from,
            LocalDate to,
            List<OriginCountResponse> registrationsByOrigin,
            List<LinkClicksResponse> clicksByLink,
            List<ChannelCountResponse> ordersByChannel,
            boolean attributesCustomerToLink,
            ProvenanceResponse provenance) {

        static AcquisitionResponse of(AcquisitionResult result) {
            return new AcquisitionResponse(
                    result.from(),
                    result.to(),
                    result.registrationsByOrigin().stream()
                            .map(row -> new OriginCountResponse(row.origin(), row.registered()))
                            .toList(),
                    result.clicksByLink().stream().map(LinkClicksResponse::of).toList(),
                    result.ordersByChannel().stream()
                            .map(row -> new ChannelCountResponse(row.channelCode(), row.ordered()))
                            .toList(),
                    result.attributesCustomerToLink(),
                    ProvenanceResponse.of(result.provenance()));
        }
    }
}
