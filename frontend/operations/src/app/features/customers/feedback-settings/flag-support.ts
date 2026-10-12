import { ConfigurationApi, ConfigurationResolutionView } from '../../settings/configuration-api';

/** A boolean ADR 0030 key read at the tenant, with the version a write is checked against. */
export interface FlagState {
  readonly on: boolean;
  readonly version: number | null;
}

export async function readFlag(
  api: ConfigurationApi,
  tenantId: string,
  code: string,
): Promise<FlagState> {
  const resolution = await api.resolution(tenantId, code, 'TENANT');
  return { on: resolution.value === true, version: resolution.currentVersionAtScope };
}

/** Switches a tenant-level flag on or off. The key is `feature.*`, settable at the platform and the tenant. */
export async function writeFlag(
  api: ConfigurationApi,
  tenantId: string,
  code: string,
  value: boolean,
  version: number | null,
  reason: string,
): Promise<void> {
  await api.setValue(tenantId, code, {
    scopeType: 'TENANT',
    explicitNull: false,
    booleanValue: value,
    expectedVersion: version,
    reason,
  });
}

export function stringOf(resolution: ConfigurationResolutionView): string | null {
  return typeof resolution.value === 'string' ? resolution.value : null;
}

export function numberOf(resolution: ConfigurationResolutionView): number | null {
  return typeof resolution.value === 'number' ? resolution.value : null;
}

export function booleanOf(resolution: ConfigurationResolutionView): boolean | null {
  return typeof resolution.value === 'boolean' ? resolution.value : null;
}
