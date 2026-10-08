package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.marketing.api.AttributionClickSeries;
import uz.horecaos.platform.marketing.api.AttributionClickSeries.LinkClicks;
import uz.horecaos.platform.reporting.application.ReportQueryService.Provenance;
import uz.horecaos.platform.reporting.domain.MetricDefinition;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore.FunnelDay;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore.RegistrationDay;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The customer funnel and the acquisition-source chart (ADR 0167, row 7.6c): reads over the
 * counts {@link FunnelCaptureService} wrote and over the anonymous click series.
 *
 * <p>Three things this class will not do, each by design.
 *
 * <ul>
 *   <li><strong>Draw a visits stage as anything but "not measured".</strong> Nothing is collected
 *       from a browser (Decision 1), so the stage is returned with {@code measured = false} and a
 *       reason, and no count and no ratio. A zero here would be believed.
 *   <li><strong>Turn a gap into a zero.</strong> A day whose basket or checkout count is "not
 *       recorded" is summed as nothing <em>and</em> reported in {@code unrecordedDays}, so the
 *       stage says its total is partial rather than quietly low.
 *   <li><strong>Attribute a customer to a link.</strong> Registrations by origin, clicks by link
 *       and orders by channel are returned beside one another and {@code attributesCustomerToLink}
 *       is {@code false} on the wire (tier 2 waits for counsel, Decision 4).
 * </ul>
 */
@Service
public class CustomerFunnelReportService {

    private final JdbcFunnelStore store;
    private final BusinessDayService businessDays;
    private final ReportQueryService reports;
    private final AttributionClickSeries clicks;

    public CustomerFunnelReportService(
            JdbcFunnelStore store,
            BusinessDayService businessDays,
            ReportQueryService reports,
            AttributionClickSeries clicks) {
        this.store = store;
        this.businessDays = businessDays;
        this.reports = reports;
        this.clicks = clicks;
    }

    @Transactional(readOnly = true)
    public FunnelResult funnel(
            UUID tenantId, LocalDate from, LocalDate to, @Nullable UUID brandId, @Nullable String channelCode) {
        ReportQueryService.validateRange(from, to);
        requireBrand(tenantId, brandId);
        reports.refuseMixedBoundaryRegime(tenantId, from, to);

        List<FunnelDay> days = store.readFunnelDays(tenantId, from, to, brandId, blankToNull(channelCode));
        // A registration has no channel (an account has none), so a channel filter leaves the registration
        // stage out rather than pretending to cut it; the response says so.
        boolean registrationsCutByChannel = channelCode != null && !channelCode.isBlank();
        List<RegistrationDay> registrations =
                registrationsCutByChannel ? List.of() : store.readRegistrationDays(tenantId, from, to, brandId);

        List<Stage> stages = new ArrayList<>();
        stages.add(Stage.notMeasured("VISITS", "NOT_COLLECTED"));
        stages.add(
                registrationsCutByChannel
                        ? Stage.notMeasured("REGISTERED", "NOT_BY_CHANNEL")
                        : Stage.of(
                                "REGISTERED",
                                registrations.stream()
                                        .mapToLong(RegistrationDay::registered)
                                        .sum(),
                                0));
        stages.add(stage("BASKET_STARTED", days, FunnelDay::basketStarted));
        stages.add(stage("CHECKOUT_STARTED", days, FunnelDay::checkoutStarted));
        stages.add(stage("ORDERED", days, FunnelDay::ordered));

        return new FunnelResult(from, to, stages, days, registrations, provenance(tenantId, "funnel.stage.v1"));
    }

    @Transactional(readOnly = true)
    public AcquisitionResult acquisition(UUID tenantId, LocalDate from, LocalDate to, @Nullable UUID brandId) {
        ReportQueryService.validateRange(from, to);
        requireBrand(tenantId, brandId);
        reports.refuseMixedBoundaryRegime(tenantId, from, to);

        Map<String, Long> byOrigin = new LinkedHashMap<>();
        for (RegistrationDay day : store.readRegistrationDays(tenantId, from, to, brandId)) {
            byOrigin.merge(day.origin(), (long) day.registered(), Long::sum);
        }
        Map<String, Long> byChannel = new LinkedHashMap<>();
        for (FunnelDay day : store.readFunnelDays(tenantId, from, to, brandId, null)) {
            if (day.ordered() != null) {
                byChannel.merge(day.channelCode(), (long) day.ordered(), Long::sum);
            }
        }
        List<LinkClicks> linkClicks = clicks.clicksBetween(tenantId, brandId, from, to);

        return new AcquisitionResult(
                from,
                to,
                byOrigin.entrySet().stream()
                        .map(entry -> new OriginCount(entry.getKey(), entry.getValue()))
                        .toList(),
                linkClicks,
                byChannel.entrySet().stream()
                        .map(entry -> new ChannelCount(entry.getKey(), entry.getValue()))
                        .toList(),
                false,
                provenance(tenantId, "acquisition.source.v1"));
    }

    private void requireBrand(UUID tenantId, @Nullable UUID brandId) {
        // Another tenant's brand and an unknown one answer alike, so the response cannot be used to
        // find out which brand ids exist elsewhere.
        if (brandId != null && !store.brandBelongsToTenant(tenantId, brandId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No brand " + brandId + " belongs to this tenant");
        }
    }

    private Provenance provenance(UUID tenantId, String metricCode) {
        MetricDefinition metric = MetricRegistry.require(metricCode);
        return reports.provenance(tenantId, List.of(metric), businessDays.boundaryFor(tenantId));
    }

    private static Stage stage(
            String code, List<FunnelDay> days, java.util.function.Function<FunnelDay, Integer> count) {
        long total = 0;
        Set<LocalDate> unrecorded = new LinkedHashSet<>();
        for (FunnelDay day : days) {
            Integer value = count.apply(day);
            if (value == null) {
                unrecorded.add(day.businessDate());
            } else {
                total += value;
            }
        }
        return Stage.of(code, total, unrecorded.size());
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * One stage of the funnel over the requested range.
     *
     * @param count         null exactly when {@code measured} is false
     * @param unrecordedDays business dates inside the range whose count for this stage is "not recorded";
     *                      the total is a sum of what was recorded and is partial when this is above zero
     * @param reason        why a stage is not measured, null otherwise
     */
    public record Stage(
            String stage,
            boolean measured,
            @Nullable String reason,
            @Nullable Long count,
            int unrecordedDays) {

        static Stage notMeasured(String stage, String reason) {
            return new Stage(stage, false, reason, null, 0);
        }

        static Stage of(String stage, long count, int unrecordedDays) {
            return new Stage(stage, true, null, count, unrecordedDays);
        }
    }

    public record FunnelResult(
            LocalDate from,
            LocalDate to,
            List<Stage> stages,
            List<FunnelDay> days,
            List<RegistrationDay> registrations,
            Provenance provenance) {}

    public record OriginCount(String origin, long registered) {}

    public record ChannelCount(String channelCode, long ordered) {}

    /**
     * @param attributesCustomerToLink always false in this build: the three counts sit beside one another
     *                                 and no customer or order is credited to a link (ADR 0167, tier 2)
     */
    public record AcquisitionResult(
            LocalDate from,
            LocalDate to,
            List<OriginCount> registrationsByOrigin,
            List<LinkClicks> clicksByLink,
            List<ChannelCount> ordersByChannel,
            boolean attributesCustomerToLink,
            Provenance provenance) {}
}
