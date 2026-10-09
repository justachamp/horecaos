package uz.horecaos.platform.integration.provider.routing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code horecaos.routing.osrm.*} (ADR 0147). The adapter and the platform
 * installation service are components; this only makes their one properties record a
 * bean, and is a class of its own so the binding does not hide inside either of them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OsrmProperties.class)
class OsrmRoutingConfiguration {}
