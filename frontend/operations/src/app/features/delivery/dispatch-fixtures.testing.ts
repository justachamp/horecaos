import { DispatchOptions } from './dispatch-rules-api';

/** What the editor's pickers are drawn from, shared by the dispatch rules specs. */
export const DISPATCH_OPTIONS: DispatchOptions = {
  installations: [
    {
      id: 'inst-yandex',
      providerType: 'yandex-delivery',
      displayName: 'Yandex Delivery',
      status: 'ACTIVE',
    },
    { id: 'inst-noor', providerType: 'noor-delivery', displayName: 'Noor', status: 'ACTIVE' },
  ],
  zones: [
    {
      id: 'zone-far',
      brandId: 'b1',
      code: 'FAR',
      nameEn: 'Far zone',
      nameRu: 'Дальняя зона',
      nameUz: 'Uzoq zona',
      status: 'ACTIVE',
    },
  ],
  channels: [
    {
      id: 'ch-web',
      code: 'STOREFRONT',
      systemType: 'WEB',
      displayName: 'Storefront',
      status: 'ACTIVE',
    },
  ],
  locations: [{ id: 'l1', brandId: 'b1', displayName: 'Centre' }],
  recentPlans: [
    {
      planId: 'plan-1',
      locationId: 'l1',
      orderReference: 'D-1001',
      status: 'ASSIGNED',
      sourcingMode: 'FLEET_FIRST',
      ruleId: null,
      createdAt: '2026-09-30T10:00:00Z',
    },
  ],
  groupingAllowed: false,
};
