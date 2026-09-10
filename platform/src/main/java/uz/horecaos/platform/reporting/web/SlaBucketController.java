package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reporting.domain.SlaBucketSet;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The elapsed-time buckets every order is reported in (ADR 0043), for the
 * control plane's reference data.
 *
 * <p>Read-only by design: the buckets are fixed per release so a chart drawn
 * last quarter keeps its meaning; a different set is a new version, not a
 * setting.
 */
@RestController
@Tag(name = "Reference data", description = "The platform's own supported countries, locales and public holidays")
public class SlaBucketController {

    @GetMapping("/api/v1/control-plane/reference-data/sla-buckets")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The elapsed-time buckets orders are reported in",
            description = "Half-open intervals in minutes, exhaustive and fixed per version.")
    SlaBuckets buckets() {
        return new SlaBuckets(
                SlaBucketSet.VERSION,
                SlaBucketSet.buckets().stream()
                        .map(bucket -> new Bucket(bucket.code(), bucket.fromMinutes(), bucket.toMinutesExclusive()))
                        .toList());
    }

    public record SlaBuckets(int version, List<Bucket> buckets) {}

    public record Bucket(
            String code, int fromMinutes, @Nullable Integer toMinutesExclusive) {}
}
