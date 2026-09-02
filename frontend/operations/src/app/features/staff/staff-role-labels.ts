import { MessageKey } from '../../core/i18n/messages.en';
import { ScopeType } from './staff-api';

/**
 * Display names for the eight tenant-visible `PlatformRole` codes
 * (staff-and-access.md §5's table). A `Record<string, MessageKey>` rather
 * than dynamic key concatenation in a template — `orders/order-status.ts`
 * sets the precedent this follows — because `'staff.role.' + code | t` would
 * either fail Angular's strict template check against the `MessageKey`
 * union or quietly bypass it, and bypassing it is the one thing this
 * catalogue's whole build-time-completeness mechanism exists to prevent.
 */
export const ROLE_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  'tenant-owner': 'staff.role.tenant-owner',
  'tenant-admin': 'staff.role.tenant-admin',
  'tenant-finance': 'staff.role.tenant-finance',
  'support-agent': 'staff.role.support-agent',
  'brand-manager': 'staff.role.brand-manager',
  'courier-dispatcher': 'staff.role.courier-dispatcher',
  'location-manager': 'staff.role.location-manager',
  'location-staff': 'staff.role.location-staff',
};

/** The one-line description under a job's name (§4's job select, §5's list). */
export const ROLE_DESCRIPTION_KEYS: Readonly<Record<string, MessageKey>> = {
  'tenant-owner': 'staff.role.tenant-owner.description',
  'tenant-admin': 'staff.role.tenant-admin.description',
  'tenant-finance': 'staff.role.tenant-finance.description',
  'support-agent': 'staff.role.support-agent.description',
  'brand-manager': 'staff.role.brand-manager.description',
  'courier-dispatcher': 'staff.role.courier-dispatcher.description',
  'location-manager': 'staff.role.location-manager.description',
  'location-staff': 'staff.role.location-staff.description',
};

export const SCOPE_LEVEL_LABEL_KEYS: Readonly<Record<ScopeType, MessageKey>> = {
  PLATFORM: 'staff.scope.platform',
  TENANT: 'staff.scope.company',
  BRAND: 'staff.scope.brand',
  LOCATION: 'staff.scope.location',
};

/** A role's label, or the raw code for one this catalogue does not yet name — never a blank cell. */
export function roleLabel(code: string, translate: (key: MessageKey) => string): string {
  const key = ROLE_LABEL_KEYS[code];
  return key ? translate(key) : code;
}

export function roleDescription(
  code: string,
  translate: (key: MessageKey) => string,
): string | null {
  const key = ROLE_DESCRIPTION_KEYS[code];
  return key ? translate(key) : null;
}

export function scopeLevelLabel(
  scopeType: ScopeType,
  translate: (key: MessageKey) => string,
): string {
  return translate(SCOPE_LEVEL_LABEL_KEYS[scopeType]);
}
