import en from './en.json';
import ru from './ru.json';
import uz from './uz.json';

import { reasonMessageKey } from '../app/core/api/problem-details';

/**
 * The real shipped locale files, not a copy pasted into this spec -- a
 * future edit that adds a key to one locale and forgets the other two fails
 * here instead of shipping a customer a raw dot-notation key or a blank
 * line. `TranslateService.get` falls back to the key itself when a lookup
 * misses, which is silent in the running app: nothing throws, the screen
 * just shows `cart.tax` instead of "Soliq". This is the only thing that
 * actually notices.
 */
const LOCALES: Readonly<Record<string, Record<string, unknown>>> = { uz, ru, en };

/** Every dot-notation leaf key in a nested translation object. */
function leafKeys(node: unknown, prefix = ''): string[] {
  if (node == null || typeof node !== 'object' || Array.isArray(node)) {
    return [prefix];
  }
  return Object.entries(node as Record<string, unknown>).flatMap(([key, value]) =>
    leafKeys(value, prefix ? `${prefix}.${key}` : key),
  );
}

function getNested(dict: Record<string, unknown>, dottedKey: string): unknown {
  return dottedKey.split('.').reduce<unknown>((node, part) => {
    if (node == null || typeof node !== 'object') return undefined;
    return (node as Record<string, unknown>)[part];
  }, dict);
}

describe('i18n key parity: uz, ru and en carry exactly the same keys', () => {
  const keysByLocale = Object.fromEntries(
    Object.entries(LOCALES).map(([id, dict]) => [id, new Set(leafKeys(dict))]),
  ) as Record<string, Set<string>>;

  const union = new Set(Object.values(keysByLocale).flatMap((set) => [...set]));

  it.each([...union].sort())('every locale has %s', (key) => {
    for (const [id, keys] of Object.entries(keysByLocale)) {
      expect(keys.has(key), `${id}.json is missing "${key}"`).toBe(true);
    }
  });

  it('no locale carries a key none of the others have (a typo or a leftover)', () => {
    for (const [id, keys] of Object.entries(keysByLocale)) {
      const extra = [...keys].filter((key) => !union.has(key) || !Object.entries(keysByLocale)
        .every(([, other]) => other.has(key)));
      expect(extra, `${id}.json has keys the others lack: ${extra.join(', ')}`).toEqual([]);
    }
  });
});

describe('i18n content: every leaf is real translated text', () => {
  for (const [id, dict] of Object.entries(LOCALES)) {
    it(`${id}.json has no empty or placeholder-only string`, () => {
      const empties = leafKeys(dict).filter((key) => {
        const value = getNested(dict, key);
        return typeof value !== 'string' || value.trim() === '';
      });
      expect(empties).toEqual([]);
    });
  }
});

describe('i18n content: every REASON_MESSAGE_KEYS target resolves in every locale', () => {
  // The vocabulary problem-details.spec.ts already proves `reasonMessageKey`
  // maps -- this proves the *targets* of that map are not dangling. Kept in
  // sync with problem-details.spec.ts's own list rather than importing a
  // private map, so a new reason code needs a deliberate edit in both places.
  const REASON_CODES = [
    'DELIVERY_FEE_UNRESOLVED',
    'DELIVERY_MINIMUM_BASKET_NOT_MET',
    'BELOW_MINIMUM_BASKET',
    'DELIVERY_DESTINATION_REQUIRED',
    'OUT_OF_ZONE',
    'OUTSIDE_CATCHMENT',
    'BEYOND_MAX_DISTANCE',
    'NO_TARIFF',
    'LOCATION_NOT_LOCATED',
    'NOT_SERVICEABLE',
    'CHANNEL_NOT_SELLABLE',
    'GUEST_ORDERS_NOT_ALLOWED',
    'CUSTOMER_BLACKLISTED',
    'CART_EXPIRED',
    'CART_NOT_EDITABLE',
    'ADDRESS_NOT_FOUND',
    'CODE_NOT_FOUND',
    'CODE_NOT_ACTIVE',
    'CODE_NOT_YET_ACTIVE',
    'CODE_EXPIRED',
    'REDEMPTION_LIMIT_REACHED',
    'PER_CUSTOMER_LIMIT_REACHED',
    'CHANNEL_NOT_ENABLED',
    'FULFILMENT_MODE_UNAVAILABLE',
    'MANUALLY_CLOSED',
    'CLOSED_BY_EXCEPTION',
    'OUTSIDE_SERVICE_HOURS',
    'NO_LIVE_MENU',
    'AT_CAPACITY',
  ] as const;

  it.each(REASON_CODES)('%s maps to a key every locale actually has', (reason) => {
    const key = reasonMessageKey(reason);
    expect(key, `${reason} is not mapped by reasonMessageKey`).not.toBeNull();
    for (const [id, dict] of Object.entries(LOCALES)) {
      const value = getNested(dict, key as string);
      expect(typeof value, `${id}.json has no string at "${key}"`).toBe('string');
    }
  });
});
