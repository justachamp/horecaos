package uz.qoida.platform.observability;

import java.io.File;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.system.DiskSpaceMetrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Free space on the volume the database, the Kafka segments, the audit
 * partitions, and the trace store all share (ADR 0023, ADR 0034).
 *
 * <p>Micrometer's own binder rather than a hand-rolled gauge: it publishes
 * {@code disk.free} and {@code disk.total} tagged by path, which is exactly the
 * pair the 85% morning alert is computed from, and it costs one line.
 *
 * <p>The path is configurable and defaults to the working directory because a
 * container sees the host's data volume only where it is mounted. On the ADR
 * 0034 machine the application container's filesystem is read-only and tells the
 * operator nothing useful about the host, so the figure that drives the alert is
 * read from the host by the probe in {@code infra/observability}. This gauge is
 * the in-process view: it is what a dashboard graphs, and it is what makes the
 * disk visible from inside a rehearsal or a staging VM where no probe runs.
 *
 * <p>85% and not 95%: the recovery order — expire Kafka segments, prune backups
 * past retention, drop leftover rehearsal databases, extend the volume — needs a
 * working day, and only the last step needs the facility. The alert exists to
 * buy that day, which is why it is a morning item rather than a page.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "qoida.observability.metrics.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DataVolumeMetrics {

    @Bean
    DiskSpaceMetrics dataVolumeDiskSpaceMetrics(
            MeterRegistry meters,
            @Value("${qoida.observability.data-volume-path:.}") String dataVolumePath) {
        DiskSpaceMetrics metrics = new DiskSpaceMetrics(new File(dataVolumePath));
        metrics.bindTo(meters);
        return metrics;
    }
}
