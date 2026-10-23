import { describe, expect, it } from 'vitest';

import {
  ConfigurationKeyView,
  ConfigurationPlacementView,
  ConfigurationPlacementsView,
  PolicyPlacementView,
} from '../../core/api/configuration';
import {
  FindScope,
  findSettings,
  formatSettingValue,
  keyDetail,
  nearestPlacement,
  policyDetail,
  scopeQuery,
} from './settings-find';

const HERE: FindScope = { brandId: 'b1', locationId: 'l1' };

function placement(overrides: Partial<ConfigurationPlacementView>): ConfigurationPlacementView {
  return {
    scopeType: 'TENANT',
    brandId: null,
    brandName: null,
    locationId: null,
    locationName: null,
    explicitNull: false,
    displayValue: '15',
    displayTruncated: false,
    version: 1,
    changedByName: null,
    changedAt: null,
    ...overrides,
  };
}

const TENANT = placement({ scopeType: 'TENANT', displayValue: '20' });
const MY_BRAND = placement({
  scopeType: 'BRAND',
  brandId: 'b1',
  brandName: 'Rayhon',
  displayValue: '15',
});
const OTHER_BRAND = placement({
  scopeType: 'BRAND',
  brandId: 'b2',
  brandName: 'Osh Markazi',
  displayValue: '30',
});
const MY_BRANCH = placement({
  scopeType: 'LOCATION',
  brandId: 'b1',
  brandName: 'Rayhon',
  locationId: 'l1',
  locationName: 'Chilonzor',
  displayValue: '10',
});
const SISTER_BRANCH = placement({
  scopeType: 'LOCATION',
  brandId: 'b1',
  brandName: 'Rayhon',
  locationId: 'l2',
  locationName: 'Yunusobod',
  displayValue: '12',
});
const FOREIGN_BRANCH = placement({
  scopeType: 'LOCATION',
  brandId: 'b2',
  brandName: 'Osh Markazi',
  locationId: 'l9',
  locationName: 'Sergeli',
  displayValue: '40',
});

function key(overrides: Partial<ConfigurationKeyView> = {}): ConfigurationKeyView {
  return {
    code: 'ordering.late_order_threshold_minutes',
    valueType: 'Integer',
    defaultValue: 10,
    settableScopes: ['PLATFORM', 'TENANT', 'BRAND', 'LOCATION'],
    owningModule: 'ordering',
    explicitNullTerminates: false,
    description: 'Minutes past the promise at which an order shows as late.',
    ...overrides,
  };
}

describe('nearestPlacement', () => {
  it('is null when nothing is stored', () => {
    expect(nearestPlacement([], HERE)).toBeNull();
  });

  it('prefers a value at the operator’s branch, then their brand, then the company: the order resolution tries them', () => {
    expect(nearestPlacement([TENANT, MY_BRAND, MY_BRANCH], HERE)?.chosen).toBe(MY_BRANCH);
    expect(nearestPlacement([TENANT, MY_BRAND], HERE)?.chosen).toBe(MY_BRAND);
    expect(nearestPlacement([MY_BRAND, TENANT], HERE)?.chosen).toBe(MY_BRAND);
    expect(nearestPlacement([TENANT], HERE)?.chosen).toBe(TENANT);
  });

  it('ranks the values that do not apply to them after the company’s: a sister branch, another brand, its branch', () => {
    expect(nearestPlacement([FOREIGN_BRANCH, OTHER_BRAND, SISTER_BRANCH], HERE)?.chosen).toBe(
      SISTER_BRANCH,
    );
    expect(nearestPlacement([FOREIGN_BRANCH, OTHER_BRAND], HERE)?.chosen).toBe(OTHER_BRAND);
    expect(nearestPlacement([FOREIGN_BRANCH], HERE)?.chosen).toBe(FOREIGN_BRANCH);
    expect(nearestPlacement([SISTER_BRANCH, TENANT], HERE)?.chosen).toBe(TENANT);
  });

  it('counts the others so the line can say there are more', () => {
    expect(nearestPlacement([TENANT, MY_BRAND, OTHER_BRAND, SISTER_BRANCH], HERE)).toEqual({
      chosen: MY_BRAND,
      others: 3,
    });
  });

  it('with no scope to be near, takes the company’s value first and keeps the stored order after that', () => {
    const nowhere: FindScope = { brandId: null, locationId: null };

    expect(nearestPlacement([OTHER_BRAND, TENANT], nowhere)?.chosen).toBe(TENANT);
    expect(nearestPlacement([OTHER_BRAND, MY_BRAND], nowhere)?.chosen).toBe(OTHER_BRAND);
  });
});

describe('scopeQuery', () => {
  it('opens the bar at the company level for a company value', () => {
    expect(scopeQuery(TENANT)).toEqual({ level: 'tenant' });
  });

  it('names the brand for a brand value, and the brand and branch for a branch value', () => {
    expect(scopeQuery(MY_BRAND)).toEqual({ brand: 'b1' });
    expect(scopeQuery(MY_BRANCH)).toEqual({ brand: 'b1', location: 'l1' });
  });
});

describe('formatSettingValue', () => {
  it('writes a duration with its unit in the operator’s language', () => {
    expect(formatSettingValue('15', 'minutes', 'ru')).toBe('15 мин');
    expect(formatSettingValue('15', 'minutes', 'uz-Latn')).toBe('15 daq.');
    expect(formatSettingValue('15', 'minutes', 'en')).toBe('15 min');
    expect(formatSettingValue('30', 'days', 'ru')).toBe('30 дн.');
    expect(formatSettingValue('6', 'months', 'en')).toBe('6 mo');
  });

  it('writes a percentage, and hundredths of one for basis points', () => {
    expect(formatSettingValue('12', 'percent', 'en')).toBe('12%');
    expect(formatSettingValue('1500', 'basisPoints', 'en')).toBe('15%');
    expect(formatSettingValue('250', 'basisPoints', 'en')).toBe('2.5%');
  });

  it('writes an hour of the day as a clock time', () => {
    expect(formatSettingValue('4', 'hour', 'en')).toBe('04:00');
    expect(formatSettingValue('23', 'hour', 'en')).toBe('23:00');
  });

  it('writes minor units as the major amount, grouped for the language', () => {
    const grouped = formatSettingValue('25000000', 'money', 'en');
    expect(grouped).toBe('250,000');
    expect(formatSettingValue('25000000', 'money', 'ru').replace(/\s/g, ' ')).toBe('250 000');
    expect(formatSettingValue('1050', 'money', 'en')).toBe('10.5');
  });

  it('writes a switch as on or off, in words', () => {
    expect(formatSettingValue('true', 'toggle', 'ru')).toBe('включено');
    expect(formatSettingValue('false', 'toggle', 'uz-Latn')).toBe('oʻchirilgan');
    expect(formatSettingValue('true', 'toggle', 'en')).toBe('on');
  });

  it('shows a text as it is, with an ellipsis when the server cut it', () => {
    expect(formatSettingValue('#ff0000', 'text', 'en')).toBe('#ff0000');
    expect(formatSettingValue('Responses are generated', 'text', 'en', true)).toBe(
      'Responses are generated…',
    );
  });

  it('shows what is not the number its unit says it is, rather than guessing', () => {
    expect(formatSettingValue('soon', 'minutes', 'en')).toBe('soon');
    expect(formatSettingValue('', 'percent', 'en')).toBe('');
  });
});

describe('keyDetail', () => {
  it('says where the nearest value is set, and what it is', () => {
    expect(keyDetail(key(), 'minutes', [MY_BRAND], HERE, 'ru')).toBe(
      'задано: бренд «Rayhon» (15 мин)',
    );
    expect(keyDetail(key(), 'minutes', [TENANT], HERE, 'en')).toBe('set: company (20 min)');
    expect(keyDetail(key(), 'minutes', [MY_BRANCH], HERE, 'uz-Latn')).toBe(
      'belgilangan: filial «Chilonzor» (10 daq.)',
    );
  });

  it('adds how many other places hold it', () => {
    expect(keyDetail(key(), 'minutes', [TENANT, MY_BRAND, OTHER_BRAND], HERE, 'en')).toBe(
      'set: brand “Rayhon” (15 min) · 2 more',
    );
  });

  it('says a value was deliberately removed where it was, not that it is empty', () => {
    const removed = placement({
      scopeType: 'BRAND',
      brandId: 'b1',
      brandName: 'Rayhon',
      explicitNull: true,
      displayValue: null,
    });

    expect(keyDetail(key(), 'minutes', [removed], HERE, 'en')).toBe('unset: brand “Rayhon”');
  });

  it('says it is not set and what it falls back to, when it is set nowhere', () => {
    expect(keyDetail(key(), 'minutes', [], HERE, 'en')).toBe('not set · default 10 min');
    expect(keyDetail(key({ defaultValue: null }), 'minutes', [], HERE, 'en')).toBe('not set');
    expect(keyDetail(key({ defaultValue: '' }), 'text', [], HERE, 'en')).toBe('not set');
  });

  it('says which levels may set it when that is fewer than all of them', () => {
    expect(
      keyDetail(
        key({ settableScopes: ['PLATFORM', 'TENANT', 'BRAND'] }),
        'minutes',
        [],
        HERE,
        'en',
      ),
    ).toBe('not set · default 10 min · brand and above only');
    expect(
      keyDetail(key({ settableScopes: ['PLATFORM', 'TENANT'] }), 'minutes', [], HERE, 'ru'),
    ).toBe('не задано · по умолчанию 10 мин · только компания');
  });

  it('says the plan locks it, ahead of anything else', () => {
    const locked = key({
      lockedByPlan: { entitlementKey: 'assistant.answering.enabled', description: 'the assistant' },
    });

    expect(keyDetail(locked, 'toggle', [MY_BRAND], HERE, 'en')).toBe(
      'locked by plan: the assistant',
    );
  });
});

describe('policyDetail', () => {
  function version(overrides: Partial<PolicyPlacementView>): PolicyPlacementView {
    return {
      scopeType: 'TENANT',
      brandId: null,
      brandName: null,
      locationId: null,
      locationName: null,
      version: 2,
      changedByName: 'Aziza Karimova',
      effectiveFrom: '2026-10-01T08:00:00Z',
      ...overrides,
    };
  }

  it('names the newest version nearest the operator, and who published it', () => {
    expect(policyDetail([version({})], HERE, 'en')).toBe('version 2: company, Aziza Karimova');
    expect(
      policyDetail(
        [
          version({}),
          version({ scopeType: 'BRAND', brandId: 'b1', brandName: 'Rayhon', version: 3 }),
        ],
        HERE,
        'en',
      ),
    ).toBe('version 3: brand “Rayhon”, Aziza Karimova · 1 more');
  });

  it('leaves out the person when this tenant keeps no name for them', () => {
    expect(policyDetail([version({ changedByName: null })], HERE, 'ru')).toBe('версия 2: компания');
  });

  it('says nothing is published when nothing is', () => {
    expect(policyDetail([], HERE, 'en')).toBe('not published');
  });
});

describe('findSettings', () => {
  const breadcrumbOf = (route: string): string => `crumb(${route})`;
  const placements: ConfigurationPlacementsView = {
    keys: [{ keyCode: 'ordering.late_order_threshold_minutes', placements: [MY_BRAND, TENANT] }],
    policies: [],
  };

  function find(query: string, overrides: Partial<Parameters<typeof findSettings>[0]> = {}) {
    return findSettings({
      locale: 'en',
      query,
      keys: [key(), key({ code: 'commercial.enforcement_ceiling', description: 'Hidden.' })],
      placements,
      scope: HERE,
      breadcrumbOf,
      ...overrides,
    });
  }

  it('finds nothing for an empty or blank query', () => {
    expect(find('')).toEqual([]);
    expect(find('   ')).toEqual([]);
  });

  it('finds a key by the words the operator uses, by its code and by its description', () => {
    expect(find('late-order').map((found) => found.id)).toEqual([
      'configurationKey:ordering.late_order_threshold_minutes',
    ]);
    expect(find('late_order_threshold').map((found) => found.id)).toHaveLength(1);
    expect(find('past the promise').map((found) => found.id)).toHaveLength(1);
  });

  it('finds a key by the screen it lives on', () => {
    expect(find('crumb(order-policy)').map((found) => found.id)).toContain(
      'configurationKey:ordering.late_order_threshold_minutes',
    );
  });

  it('names the key, the place and the fact, and opens the screen with the scope bar at the nearest value', () => {
    const [found] = find('late-order');

    expect(found).toEqual({
      id: 'configurationKey:ordering.late_order_threshold_minutes',
      label: 'Late-order threshold',
      breadcrumb: 'crumb(order-policy)',
      detail: 'set: brand “Rayhon” (15 min) · 1 more',
      route: 'order-policy',
      queryParams: { brand: 'b1' },
    });
  });

  it('never offers a key no screen sets: there would be nowhere to go', () => {
    expect(find('enforcement')).toEqual([]);
    expect(find('ceiling')).toEqual([]);
  });

  it('speaks the operator’s language', () => {
    expect(find('порог', { locale: 'ru' })[0].label).toBe('Порог опоздания заказа');
    expect(find('kechikish', { locale: 'uz-Latn' })[0].label).toBe('Buyurtma kechikish chegarasi');
  });

  it('still finds it, with what it can say, when the placements could not be read', () => {
    const [found] = find('late-order', { placements: null });

    expect(found.detail).toBe('not set · default 10 min');
    expect(found.queryParams).toEqual({});
  });

  it('opens a key only the company can set at the company level, since no other bar can edit it', () => {
    const [found] = find('late-order', {
      placements: null,
      keys: [key({ settableScopes: ['PLATFORM', 'TENANT'] })],
    });

    expect(found.queryParams).toEqual({ level: 'tenant' });
  });

  it('lists the versioned policies beside the settings, with who published them and where', () => {
    const [found] = find('lateness', {
      placements: {
        keys: [],
        policies: [
          {
            policyCode: 'ordering.lateness',
            placements: [
              {
                scopeType: 'BRAND',
                brandId: 'b1',
                brandName: 'Rayhon',
                locationId: null,
                locationName: null,
                version: 4,
                changedByName: 'Aziza Karimova',
                effectiveFrom: '2026-10-01T08:00:00Z',
              },
            ],
          },
        ],
      },
    });

    expect(found).toEqual({
      id: 'policy:ordering.lateness',
      label: 'Order lateness',
      breadcrumb: 'crumb(order-policy)',
      detail: 'version 4: brand “Rayhon”, Aziza Karimova',
      route: 'order-policy',
      queryParams: { brand: 'b1' },
    });
  });

  it('lists a policy nobody has published, as not published, and opens its screen without a bar', () => {
    const [found] = find('dispatch rules', { placements: null });

    expect(found.id).toBe('policy:fulfillment.dispatch_rules');
    expect(found.detail).toBe('not published');
    expect(found.queryParams).toEqual({});
  });
});
