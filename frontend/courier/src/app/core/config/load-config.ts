import type { AppConfig } from './app-config';

/**
 * Where the runtime configuration is served from.
 *
 * Relative, so it resolves against the deployment's own base href rather than the origin root.
 */
export const CONFIG_URL = 'config.json';

const DEFAULT_TIME_ZONE = 'Asia/Tashkent';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The start failed before the application existed. Nothing here is retryable by the app itself. */
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
 * Reads `/config.json` before the application starts.
 *
 * **This throws rather than falling back to defaults, and that is the point.** Every value here
 * is in the path of a sign-in or a platform call. A missing or malformed file that quietly became
 * a placeholder tenant would produce an app that renders and then answers 404 to everything; a
 * start that fails loudly is a bug somebody fixes in minutes.
 *
 * `cache: 'no-store'` because the file is how a deployment is re-pointed, and the service worker
 * never holds it (it is in no `ngsw-config.json` asset group).
 *
 * @param pageProtocol the protocol of the page the app is served from; an https page refuses an
 *     http issuer, because a token endpoint reached over plain http is a downgrade nobody chose
 */
export async function loadAppConfig(
  fetchImpl: typeof fetch = fetch,
  pageProtocol: string = globalThis.location?.protocol ?? 'https:',
): Promise<AppConfig> {
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
  return validate(body, pageProtocol);
}

function validate(body: unknown, pageProtocol: string): AppConfig {
  if (typeof body !== 'object' || body === null) {
    throw new ConfigUnavailableError(`${CONFIG_URL} is not an object.`);
  }
  const raw = body as Record<string, unknown>;

  const issuerUrl = requireText(raw, 'issuerUrl').replace(/\/+$/, '');
  assertIssuer(issuerUrl, pageProtocol);

  return {
    apiBaseUrl: requireText(raw, 'apiBaseUrl').replace(/\/+$/, ''),
    issuerUrl,
    clientId: requireText(raw, 'clientId'),
    tenantId: requireUuid(raw, 'tenantId'),
    displayTimeZone: optionalZone(raw, 'displayTimeZone') ?? DEFAULT_TIME_ZONE,
  };
}

function assertIssuer(issuerUrl: string, pageProtocol: string): void {
  let parsed: URL;
  try {
    parsed = new URL(issuerUrl);
  } catch {
    throw new ConfigUnavailableError(`${CONFIG_URL} has an "issuerUrl" that is not a URL.`);
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new ConfigUnavailableError(`${CONFIG_URL} has an "issuerUrl" that is not http(s).`);
  }
  if (pageProtocol === 'https:' && parsed.protocol !== 'https:') {
    throw new ConfigUnavailableError(
      `${CONFIG_URL} names an http "issuerUrl" for a page served over https.`,
    );
  }
  if (parsed.search !== '' || parsed.hash !== '') {
    throw new ConfigUnavailableError(`${CONFIG_URL} has an "issuerUrl" with a query or fragment.`);
  }
}

function requireText(raw: Record<string, unknown>, key: string): string {
  const value = raw[key];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new ConfigUnavailableError(`${CONFIG_URL} is missing "${key}".`);
  }
  return value.trim();
}

function requireUuid(raw: Record<string, unknown>, key: string): string {
  const value = requireText(raw, key);
  if (!UUID_PATTERN.test(value)) {
    throw new ConfigUnavailableError(`${CONFIG_URL} has a "${key}" that is not a UUID.`);
  }
  return value.toLowerCase();
}

/** A zone name the runtime does not know is dropped for the default, not a reason to refuse to start. */
function optionalZone(raw: Record<string, unknown>, key: string): string | undefined {
  const value = raw[key];
  if (typeof value !== 'string' || value.trim() === '') {
    return undefined;
  }
  try {
    new Intl.DateTimeFormat('en', { timeZone: value.trim() });
    return value.trim();
  } catch {
    return undefined;
  }
}
