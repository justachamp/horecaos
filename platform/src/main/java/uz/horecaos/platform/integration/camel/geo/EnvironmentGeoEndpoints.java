package uz.horecaos.platform.integration.camel.geo;

import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.provider.JdbcProviderEnvironmentLookup;

/** {@link GeoEndpoints} over the platform-owned catalogue; a read per call, never cached (see its doc). */
@Component
class EnvironmentGeoEndpoints implements GeoEndpoints {

    private final JdbcProviderEnvironmentLookup environments;

    EnvironmentGeoEndpoints(JdbcProviderEnvironmentLookup environments) {
        this.environments = environments;
    }

    @Override
    public Optional<String> baseUrlOf(String environmentCode) {
        return environments.baseUrlOf(environmentCode);
    }
}
