package uz.horecaos.platform.integration.api.provider;

/**
 * Platform-wide provider installation health, for a control-plane digest (ADR
 * 0026, ADR 0058).
 *
 * @param activeInstallations installations currently {@code ACTIVE}, across every tenant
 * @param failingConnections  installations whose last connection check {@code FAILED}
 */
public record ProviderHealth(long activeInstallations, long failingConnections) {}
