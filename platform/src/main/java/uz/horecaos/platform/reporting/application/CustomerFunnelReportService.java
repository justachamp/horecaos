package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 *   <li><strong>Draw a visits stage as anything but "not measured", or "page loads, not people".</strong>
 *       Nothing is collected from a browser (Decision 1), so the stage is returned with {@code measured =
 *       false} and a reason, and no count and no ratio. A zero here would be believed. The one exception
 *       is ADR 0183 D18: once the platform owner has switched the page-load count on, the stage carries a
 *       count whose {@code basis} is {@code PAGE_LOADS}, a count of tab sessions and not of people, with
 *       the days before the counter started reported as unrecorded. It is still never a denominator.
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
    private final PageLoadCountService pageLoads;

    public CustomerFunnelReportService(
            JdbcFunnelStore store,
            BusinessDayService businessDays,
            ReportQueryService reports,
            AttributionClickSeries clicks,
            PageLoadCountService pageLoads) {
        this.store = store;
        this.businessDays = businessDays;
        this.reports = reports;
        this.clicks = clicks;
        this.pageLoads = pageLoads;
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
        stages.add(visits(tenantId, from, to, brandId, blankToNull(channelCode)));
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

    /**
     * The visits stage: «not measured» while the page-load count is dark, and a count of page loads, labelled as such, once
     * it is on (ADR 0183 D18). Business dates in the range before the counter wrote its first row are reported as unrecorded
     * and are not summed as zero, so a range that reaches back past the day it was switched on says its total is partial.
     */
    private Stage visits(
            UUID tenantId, LocalDate from, LocalDate to, @Nullable UUID brandId, @Nullable String channel) {
        if (!pageLoads.enabled()) {
            return Stage.notMeasured("VISITS", "NOT_COLLECTED");
        }
        long loads = store.readPageLoads(tenantId, from, to, brandId, channel);
        long rangeDays = ChronoUnit.DAYS.between(from, to) + 1;
        long unrecorded = store.firstPageLoadDate(tenantId, brandId)
                .map(first -> Math.max(0, Math.min(ChronoUnit.DAYS.between(from, first), rangeDays)))
                .orElse(rangeDays);
        return Stage.pageLoads(loads, (int) unrecorded);
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
     * @param basis         what the count is a count <em>of</em>, when that is not the stage's own name: {@code PAGE_LOADS}
     *                      for the visits stage once the page-load count is on, meaning tab sessions and not people (ADR 0183
     *                      D18). A client must not draw a ratio against it
     */
    public record Stage(
            String stage,
            boolean measured,
            @Nullable String reason,
            @Nullable Long count,
            int unrecordedDays,
            @Nullable String basis) {

        static Stage notMeasured(String stage, String reason) {
            return new Stage(stage, false, reason, null, 0, null);
        }

        static Stage of(String stage, long count, int unrecordedDays) {
            return new Stage(stage, true, null, count, unrecordedDays, null);
        }

        static Stage pageLoads(long count, int unrecordedDays) {
            return new Stage("VISITS", true, null, count, unrecordedDays, "PAGE_LOADS");
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
