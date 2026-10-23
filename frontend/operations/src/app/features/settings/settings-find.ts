import {
  ConfigurationKeyView,
  ConfigurationPlacementView,
  ConfigurationPlacementsView,
  ConfigurationScopeType,
  PolicyPlacementView,
} from '../../core/api/configuration';
import { Locale } from '../../core/i18n/i18n';
import {
  POLICY_ROUTES,
  PolicyCode,
  SETTING_ENTRIES,
  SettingKeyCode,
  SettingUnit,
} from './settings-search-index';
import { settingsSearchText } from './settings-search-text';

/** Where the operator is working: the nearest stored value to this is the one a result names. */
export interface FindScope {
  readonly brandId: string | null;
  readonly locationId: string | null;
}

/** One result of «Find a setting» for a configuration key or a policy (settings.md §10.0). */
export interface FoundSetting {
  /** `configurationKey:<code>` or `policy:<code>`: what the combobox hands back on Enter. */
  readonly id: string;
  readonly label: string;
  /** «Заказы → Правила заказа». */
  readonly breadcrumb: string;
  /** «задано: бренд «Rayhon» (15 мин)». */
  readonly detail: string;
  /** The screen, as the index writes it. */
  readonly route: string;
  /** The scope bar the screen opens at: the level the value was found at. */
  readonly queryParams: Readonly<Record<string, string>>;
}

type StoredAt = Pick<ConfigurationPlacementView, 'scopeType' | 'brandId' | 'locationId'>;

/**
 * How near a stored value is to where the operator works, lowest first: a value at their branch, at their
 * brand, at the company -- the three that apply to them, in the order resolution tries them -- and then the
 * ones that do not (another branch of their brand, another brand, a branch of another brand).
 */
function distance(at: StoredAt, scope: FindScope): number {
  if (at.scopeType === 'LOCATION') {
    if (scope.locationId !== null && at.locationId === scope.locationId) {
      return 0;
    }
    return scope.brandId !== null && at.brandId === scope.brandId ? 3 : 5;
  }
  if (at.scopeType === 'BRAND') {
    return scope.brandId !== null && at.brandId === scope.brandId ? 1 : 4;
  }
  return 2;
}

/** The stored value nearest to `scope`, and how many others there are to mention. Null when nothing is stored. */
export function nearestPlacement<T extends StoredAt>(
  placements: readonly T[],
  scope: FindScope,
): { readonly chosen: T; readonly others: number } | null {
  if (placements.length === 0) {
    return null;
  }
  let chosen = placements[0];
  for (const candidate of placements) {
    if (distance(candidate, scope) < distance(chosen, scope)) {
      chosen = candidate;
    }
  }
  return { chosen, others: placements.length - 1 };
}

/** The scope bar that edits exactly where a value is stored (`?brand=&location=&level=tenant`, settings.md §1.1). */
export function scopeQuery(at: StoredAt): Readonly<Record<string, string>> {
  if (at.scopeType === 'TENANT') {
    return { level: 'tenant' };
  }
  if (at.scopeType === 'LOCATION' && at.brandId !== null && at.locationId !== null) {
    return { brand: at.brandId, location: at.locationId };
  }
  return at.brandId === null ? {} : { brand: at.brandId };
}

function numberTag(locale: Locale): string {
  return locale;
}

function formatNumber(value: number, locale: Locale): string {
  try {
    return new Intl.NumberFormat(numberTag(locale), { maximumFractionDigits: 2 }).format(value);
  } catch {
    return new Intl.NumberFormat('en', { maximumFractionDigits: 2 }).format(value);
  }
}

/**
 * A stored or default value as the operator reads it: «15 мин», «12%», «включено». A value that is not the
 * number its unit says it is is shown as it is rather than guessed at. `truncated` marks a long text the
 * server cut, so the line ends in an ellipsis instead of pretending to be whole.
 */
export function formatSettingValue(
  raw: string,
  unit: SettingUnit,
  locale: Locale,
  truncated = false,
): string {
  const text = (key: Parameters<typeof settingsSearchText.text>[1], n?: string): string =>
    settingsSearchText.text(locale, key, n === undefined ? undefined : { n });
  const value = raw.trim();
  const amount = Number(value);
  const numeric = value !== '' && Number.isFinite(amount);
  switch (unit) {
    case 'toggle':
      return value === 'true' ? text('value.on') : value === 'false' ? text('value.off') : value;
    case 'minutes':
      return numeric ? text('unit.minutes', formatNumber(amount, locale)) : value;
    case 'days':
      return numeric ? text('unit.days', formatNumber(amount, locale)) : value;
    case 'months':
      return numeric ? text('unit.months', formatNumber(amount, locale)) : value;
    case 'hour':
      return numeric ? `${String(amount).padStart(2, '0')}:00` : value;
    case 'percent':
      return numeric ? `${formatNumber(amount, locale)}%` : value;
    case 'basisPoints':
      return numeric ? `${formatNumber(amount / 100, locale)}%` : value;
    case 'money':
      return numeric ? formatNumber(amount / 100, locale) : value;
    case 'count':
      return numeric ? formatNumber(amount, locale) : value;
    default:
      return truncated ? `${value}…` : value;
  }
}

function where(
  at: Pick<ConfigurationPlacementView, 'scopeType' | 'brandName' | 'locationName'>,
  locale: Locale,
): string {
  if (at.scopeType === 'TENANT') {
    return settingsSearchText.text(locale, 'level.tenant');
  }
  if (at.scopeType === 'BRAND') {
    return settingsSearchText.text(locale, 'level.brand', { name: at.brandName ?? '' });
  }
  return settingsSearchText.text(locale, 'level.location', { name: at.locationName ?? '' });
}

/** «только бренд и выше»: the levels a key can be set at, when that is fewer than all three. */
function restriction(settable: readonly ConfigurationScopeType[], locale: Locale): string | null {
  if (settable.includes('LOCATION')) {
    return null;
  }
  return settingsSearchText.text(
    locale,
    settable.includes('BRAND') ? 'detail.onlyBrandUp' : 'detail.onlyTenant',
  );
}

function more(others: number, locale: Locale): string {
  return others === 0
    ? ''
    : ` · ${settingsSearchText.text(locale, 'detail.more', { count: others })}`;
}

/**
 * The third column of a configuration key's result.
 *
 * `placements` is where the key is stored, or **null when that could not be read** (the read failed, or has not
 * come back yet). Null is not «nowhere»: it says where it is set is unknown, because «not set · default 10 min»
 * about a threshold a brand has set to 15 is a false statement an operator would act on.
 */
export function keyDetail(
  key: ConfigurationKeyView,
  unit: SettingUnit,
  placements: readonly ConfigurationPlacementView[] | null,
  scope: FindScope,
  locale: Locale,
): string {
  const text = (k: Parameters<typeof settingsSearchText.text>[1], v?: Record<string, string>) =>
    settingsSearchText.text(locale, k, v);
  if (key.lockedByPlan) {
    return text('detail.locked', { feature: key.lockedByPlan.description });
  }
  const nearest = placements === null ? null : nearestPlacement(placements, scope);
  if (nearest !== null) {
    const { chosen, others } = nearest;
    const line = chosen.explicitNull
      ? text('detail.unsetHere', { where: where(chosen, locale) })
      : text('detail.set', {
          where: where(chosen, locale),
          value: formatSettingValue(
            chosen.displayValue ?? '',
            unit,
            locale,
            chosen.displayTruncated,
          ),
        });
    return `${line}${more(others, locale)}`;
  }
  const fallback = key.defaultValue;
  const hasDefault = fallback !== null && fallback !== undefined && fallback !== '';
  const known = placements !== null;
  const value = hasDefault ? formatSettingValue(String(fallback), unit, locale) : '';
  const base = hasDefault
    ? text(known ? 'detail.notSetDefault' : 'detail.unknownDefault', { value })
    : text(known ? 'detail.notSet' : 'detail.unknown');
  const limit = restriction(key.settableScopes, locale);
  return limit === null ? base : `${base} · ${limit}`;
}

/**
 * The third column of a policy's result: its newest version nearest the operator, who published it. A null
 * `placements` (could not be read) says it is unknown, not that nothing is published.
 */
export function policyDetail(
  placements: readonly PolicyPlacementView[] | null,
  scope: FindScope,
  locale: Locale,
): string {
  if (placements === null) {
    return settingsSearchText.text(locale, 'detail.unknown');
  }
  const nearest = nearestPlacement(placements, scope);
  if (nearest === null) {
    return settingsSearchText.text(locale, 'detail.policyNone');
  }
  const { chosen, others } = nearest;
  const by = chosen.changedByName === null ? '' : `, ${chosen.changedByName}`;
  return `${settingsSearchText.text(locale, 'detail.policy', {
    version: chosen.version,
    where: where(chosen, locale),
    by,
  })}${more(others, locale)}`;
}

export interface FindInput {
  readonly locale: Locale;
  readonly query: string;
  readonly keys: readonly ConfigurationKeyView[];
  readonly placements: ConfigurationPlacementsView | null;
  readonly scope: FindScope;
  /** «Group → Screen» for a route, in the operator's language. */
  readonly breadcrumbOf: (route: string) => string;
}

function matches(needle: string, ...haystack: readonly string[]): boolean {
  return haystack.some((text) => text.toLowerCase().includes(needle));
}

/**
 * The configuration keys and policies that match what was typed, in the order the index lists them, each with
 * its name, place and the fact about where it is set. A key with no screen in the index is not a result: there
 * would be nowhere to go (it is on `NO_CONSOLE_SCREEN`, with the row that owes it one).
 */
export function findSettings(input: FindInput): readonly FoundSetting[] {
  const needle = input.query.trim().toLowerCase();
  if (needle === '') {
    return [];
  }
  const { locale, scope, placements } = input;
  const found: FoundSetting[] = [];

  const byKey = new Map(placements?.keys.map((entry) => [entry.keyCode, entry.placements]));
  for (const key of input.keys) {
    const entry = (
      SETTING_ENTRIES as Readonly<Record<string, (typeof SETTING_ENTRIES)[SettingKeyCode]>>
    )[key.code];
    if (entry === undefined) {
      continue;
    }
    const label = settingsSearchText.text(locale, `key.${key.code as SettingKeyCode}`);
    const breadcrumb = input.breadcrumbOf(entry.route);
    if (!matches(needle, label, key.code, key.description, breadcrumb)) {
      continue;
    }
    const stored = placements === null ? null : (byKey.get(key.code) ?? []);
    const nearest = stored === null ? null : nearestPlacement(stored, scope);
    found.push({
      id: `configurationKey:${key.code}`,
      label,
      breadcrumb,
      detail: keyDetail(key, entry.unit, stored, scope, locale),
      route: entry.route,
      queryParams:
        nearest !== null
          ? scopeQuery(nearest.chosen)
          : key.settableScopes.includes('BRAND')
            ? {}
            : { level: 'tenant' },
    });
  }

  const byPolicy = new Map(
    placements?.policies.map((entry) => [entry.policyCode, entry.placements]),
  );
  for (const [code, entry] of Object.entries(POLICY_ROUTES)) {
    const label = settingsSearchText.text(locale, `policy.${code as PolicyCode}`);
    const breadcrumb = input.breadcrumbOf(entry.route);
    if (!matches(needle, label, code, breadcrumb)) {
      continue;
    }
    const stored = placements === null ? null : (byPolicy.get(code) ?? []);
    const nearest = stored === null ? null : nearestPlacement(stored, scope);
    found.push({
      id: `policy:${code}`,
      label,
      breadcrumb,
      detail: policyDetail(stored, scope, locale),
      route: entry.route,
      queryParams: nearest === null ? {} : scopeQuery(nearest.chosen),
    });
  }
  return found;
}
