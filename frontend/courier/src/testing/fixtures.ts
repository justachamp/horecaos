import { signal, type Signal } from '@angular/core';

import type { AppConfig } from '../app/core/config/app-config';
import type { CourierProfile } from '../app/core/courier/courier-profile';

export const TENANT_ID = '10000000-0000-0000-0000-000000000001';

export const TEST_CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  issuerUrl: 'https://auth.example.test/realms/horecaos',
  clientId: 'horecaos-courier',
  tenantId: TENANT_ID,
  displayTimeZone: 'Asia/Tashkent',
};

/** A courier with a main branch, an open shift and a valid registration. Override what a case is about. */
export function courierProfile(overrides: Partial<CourierProfile> = {}): CourierProfile {
  return {
    courierId: '018fe100-4000-7000-8000-0000000000e1',
    displayReference: 'REF-00E1',
    status: 'ACTIVE',
    engagement: {
      status: 'ACTIVE',
      warningState: 'VALID',
      registrationValidUntil: '2027-01-31',
      dispatchable: true,
    },
    branches: [
      {
        brandId: '018fe100-4000-7000-8000-0000000000b1',
        locationId: '018fe100-4000-7000-8000-0000000000c1',
        displayName: 'Chilonzor',
        primary: true,
      },
      {
        brandId: '018fe100-4000-7000-8000-0000000000b1',
        locationId: '018fe100-4000-7000-8000-0000000000c2',
        displayName: 'Yunusobod',
        primary: false,
      },
    ],
    openShift: {
      shiftId: '018fe100-4000-7000-8000-0000000000f1',
      brandId: '018fe100-4000-7000-8000-0000000000b1',
      locationId: '018fe100-4000-7000-8000-0000000000c1',
      locationName: 'Chilonzor',
      status: 'OPEN',
      dutyState: 'AVAILABLE',
      enforcementMode: 'ADVISORY',
      openedAt: '2026-10-14T04:30:00Z',
      version: 1,
    },
    telemetry: { batchCadenceSeconds: 10, maximumBatchSize: 60 },
    ...overrides,
  };
}

/** A signal a spec can set, standing in for a store's read-only one. */
export function writable<T>(initial: T): { source: Signal<T>; set: (value: T) => void } {
  const source = signal(initial);
  return { source: source.asReadonly(), set: (value) => source.set(value) };
}
