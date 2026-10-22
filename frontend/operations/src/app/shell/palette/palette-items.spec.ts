import { describe, expect, it } from 'vitest';

import { FoundSetting } from '../../features/settings/settings-find';
import { Destination, MAX_PALETTE_ITEMS, paletteItems } from './palette-items';

const DESTINATIONS: readonly Destination[] = [
  { id: 'go:/orders', label: 'Orders', hint: 'Section', target: { commands: ['/orders'] } },
  { id: 'go:/delivery', label: 'Delivery', hint: 'Section', target: { commands: ['/delivery'] } },
  {
    id: 'go:settings:order-policy',
    label: 'Order policy',
    hint: 'Settings → Selling',
    target: { commands: ['/settings', 'order-policy'] },
  },
  { id: 'go:/reports', label: 'Cart reports', hint: 'Section', target: { commands: ['/reports'] } },
];

const SETTING: FoundSetting = {
  id: 'configurationKey:ordering.vat_rate_percent',
  label: 'VAT rate',
  breadcrumb: 'Selling → Order policy',
  detail: 'set: company (12%)',
  route: 'order-policy',
  queryParams: { level: 'tenant' },
};

const OUTSIDE: FoundSetting = {
  id: 'configurationKey:delivery.out_of_zone_policy',
  label: 'Out-of-zone addresses',
  breadcrumb: 'Selling → Delivery policy',
  detail: 'not set',
  route: '/delivery/courier-policy',
  queryParams: {},
};

function items(query: string, settings: readonly FoundSetting[] = []) {
  return paletteItems(query, DESTINATIONS, settings, (q) => `Find order “${q}”`, 'Opens the board');
}

describe('paletteItems', () => {
  it('offers nothing for an empty or blank query: a palette that lists everything is a menu', () => {
    expect(items('')).toEqual([]);
    expect(items('   ')).toEqual([]);
  });

  it('finds the places whose name holds what was typed, those that start with it first', () => {
    // «or» starts «Orders» and «Order policy» and is only inside «Cart reports», which therefore comes last.
    expect(items('or').map((item) => item.label)).toEqual([
      'Orders',
      'Order policy',
      'Cart reports',
    ]);
    expect(items('art').map((item) => item.label)).toEqual(['Cart reports']);
    expect(items('d').map((item) => item.label)).toEqual(['Delivery', 'Orders', 'Order policy']);
  });

  it('keeps the order the destinations were given in among equal matches', () => {
    expect(items('order').map((item) => item.id)).toEqual([
      'go:/orders',
      'go:settings:order-policy',
    ]);
  });

  it('opens a Settings screen under /settings, and a section by its own path', () => {
    const [orders, policy] = items('order');

    expect(orders.target).toEqual({ commands: ['/orders'] });
    expect(policy.target).toEqual({ commands: ['/settings', 'order-policy'] });
  });

  it('lists the settings «Find a setting» found after the places, with where each is and what is set', () => {
    const result = items('vat', [SETTING]);

    expect(result).toEqual([
      {
        id: 'configurationKey:ordering.vat_rate_percent',
        group: 'setting',
        label: 'VAT rate',
        hint: 'Selling → Order policy · set: company (12%)',
        target: { commands: ['/settings', 'order-policy'], queryParams: { level: 'tenant' } },
      },
    ]);
  });

  it('opens a setting on a screen outside Settings by that screen’s path, and presets nothing it has no bar for', () => {
    const [found] = items('zone', [OUTSIDE]);

    expect(found.target).toEqual({ commands: ['/delivery/courier-policy'] });
    expect('queryParams' in found.target).toBe(false);
  });

  it('offers an order lookup for what reads as an order number or a partner reference, last', () => {
    const result = items('4819');

    expect(result.map((item) => item.group)).toEqual(['order']);
    expect(result[0]).toMatchObject({
      label: 'Find order “4819”',
      target: { commands: ['/orders'], queryParams: { tab: 'all', q: '4819' } },
    });
    expect(items('WOLT-4819').at(-1)?.group).toBe('order');
  });

  it('puts the lookup behind a section of the same name, so Enter on it goes to the section', () => {
    const numbered: readonly Destination[] = [
      { id: 'go:/2', label: '2nd floor', hint: 'Section', target: { commands: ['/2'] } },
    ];

    const result = paletteItems('2', numbered, [], (q) => `Find order “${q}”`, 'hint');

    expect(result.map((item) => item.group)).toEqual(['go', 'order']);
  });

  it('does not offer a lookup for words: the board’s search is for numbers and references, never names or phones', () => {
    expect(items('Aziza').map((item) => item.group)).not.toContain('order');
    expect(items('Aziza Karimova').map((item) => item.group)).not.toContain('order');
    expect(items('+998 90 123').map((item) => item.group)).not.toContain('order');
    expect(items('a'.repeat(40) + '1').map((item) => item.group)).not.toContain('order');
  });

  it('stops at a screenful', () => {
    const many: readonly Destination[] = Array.from({ length: 30 }, (_, index) => ({
      id: `go:${index}`,
      label: `Section ${index}`,
      hint: 'Section',
      target: { commands: [`/s${index}`] },
    }));

    expect(paletteItems('section', many, [], (q) => q, '')).toHaveLength(MAX_PALETTE_ITEMS);
  });
});
