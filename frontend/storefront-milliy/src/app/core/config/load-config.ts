import type { AppConfig, BrandConfig, BrandTheme } from './app-config';

/**
 * Where the runtime configuration is served from.
 *
 * Relative, so it resolves against the deployment's own base href rather than
 * the origin root: a storefront served under a path prefix still finds its own
 * file instead of somebody else's at `/config.json`.
 */
export const CONFIG_URL = 'config.json';

/**
 * Reads `/config.json` before the application starts.
 *
 * **This throws rather than falling back to defaults, and that is the point.**
 * Every value here is in the path of a platform call. A missing or malformed
 * file that quietly became a placeholder tenant id would produce an application
 * that renders, and then answers 404 to the menu, to serviceability, and to
 * every cart — which is exactly the failure the rebuild branch spent a session
 * diagnosing when `environment.ts` held all-zero placeholders. A start that
 * fails loudly is a bug somebody fixes in minutes.
 *
 * `cache: 'no-store'` because the file is how a deployment is re-pointed: a
 * cached copy would keep sending a brand's customers to the tenant it was
 * moved away from.
 */
export async function loadAppConfig(fetchImpl: typeof fetch = fetch): Promise<AppConfig> {
  let response: Response;
  try {
    response = await fetchImpl(CONFIG_URL, { cache: 'no-store' });
  } catch (cause) {
    throw new ConfigUnavailableError(`${CONFIG_URL} could not be fetched.`, cause);
  }
  if (!response.ok) {
    throw new ConfigUnavailableError(`${CONFIG_URL} answered ${response.status}.`);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch (cause) {
    throw new ConfigUnavailableError(`${CONFIG_URL} is not JSON.`, cause);
  }
  return validate(body);
}

/**
 * The identity a deployment gets when `/config.json` names no brand at all,
 * or names one only partly.
 *
 * Deliberately anonymous rather than a second hardcoded product: the whole
 * point of this file is that a missing value never resolves to whatever
 * brand this application last shipped as. `accent`/`accentDeep` are a plain
 * neutral grey -- recognisably "no brand configured" rather than a second
 * opinion about what a brand should look like.
 */
export const NEUTRAL_BRAND: BrandConfig = {
  displayName: 'Storefront',
  logoUrl: undefined,
  theme: {
    accent: '#52525b',
    accentDeep: '#3f3f46',
  },
};

/** The start failed before the application existed. Nothing here is retryable. */
export class ConfigUnavailableError extends Error {
  constructor(
    detail: string,
    override readonly cause?: unknown,
  ) {
    super(detail);
    this.name = 'ConfigUnavailableError';
  }
}

/**
 * Checks the fields the application cannot run without.
 *
 * The three identifiers are checked for UUID *shape* and not merely for
 * presence: the realistic mistake is a placeholder or a truncated paste, and
 * both are strings that pass a truthiness test and then fail every request.
 */
function validate(body: unknown): AppConfig {
  if (typeof body !== 'object' || body === null) {
    throw new ConfigUnavailableError(`${CONFIG_URL} is not an object.`);
  }
  const raw = body as Record<string, unknown>;

  return {
    apiBaseUrl: requireText(raw, 'apiBaseUrl'),
    tenantId: requireUuid(raw, 'tenantId'),
    brandId: requireUuid(raw, 'brandId'),
    defaultLocationId: optionalUuid(raw, 'defaultLocationId'),
    channel: requireText(raw, 'channel'),
    yandexMapsApiKey: typeof raw['yandexMapsApiKey'] === 'string' ? raw['yandexMapsApiKey'] : '',
    brand: brandFrom(raw),
  };
}

/**
 * Reads the optional `"brand"` object, soft-defaulting missing or malformed
 * fields to {@link NEUTRAL_BRAND} one at a time -- a brand present but
 * missing a logo still gets its own name and colours, rather than the
 * whole object being discarded for one absent field.
 */
function brandFrom(raw: Record<string, unknown>): BrandConfig {
  const value = raw['brand'];
  const rawBrand = typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};

  return {
    displayName: optionalText(rawBrand, 'displayName') ?? NEUTRAL_BRAND.displayName,
    logoUrl: optionalText(rawBrand, 'logoUrl'),
    theme: themeFrom(rawBrand),
  };
}

function themeFrom(rawBrand: Record<string, unknown>): BrandTheme {
  const value = rawBrand['theme'];
  const rawTheme = typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : {};

  return {
    accent: optionalText(rawTheme, 'accent') ?? NEUTRAL_BRAND.theme.accent,
    accentDeep: optionalText(rawTheme, 'accentDeep') ?? NEUTRAL_BRAND.theme.accentDeep,
  };
}

/** Unlike {@link requireText}, a present-but-wrong-shaped value is treated as absent rather than rejected -- brand fields never fail a deployment's start. */
function optionalText(raw: Record<string, unknown>, key: string): string | undefined {
  const value = raw[key];
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function requireText(raw: Record<string, unknown>, key: string): string {
  const value = raw[key];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new ConfigUnavailableError(`${CONFIG_URL} is missing "${key}".`);
  }
  return value;
}

function requireUuid(raw: Record<string, unknown>, key: string): string {
  const value = requireText(raw, key);
  if (!UUID_PATTERN.test(value)) {
    throw new ConfigUnavailableError(`${CONFIG_URL} has a "${key}" that is not a UUID.`);
  }
  return value;
}

function optionalUuid(raw: Record<string, unknown>, key: string): string | undefined {
  const value = raw[key];
  if (value === undefined || value === null || value === '') {
    return undefined;
  }
  if (typeof value !== 'string' || !UUID_PATTERN.test(value)) {
    throw new ConfigUnavailableError(`${CONFIG_URL} has a "${key}" that is not a UUID.`);
  }
  return value;
}
