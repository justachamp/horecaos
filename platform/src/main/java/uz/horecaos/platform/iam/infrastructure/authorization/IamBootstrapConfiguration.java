package uz.horecaos.platform.iam.infrastructure.authorization;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link IamBootstrapProperties} so {@link PlatformAdminBootstrapReconciler} can read it. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IamBootstrapProperties.class)
class IamBootstrapConfiguration {}
