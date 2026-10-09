/**
 * The only URLs content may turn into a link.
 *
 * Content is authored by a brand's staff and read by every visitor, so a link in it is the one place
 * a customer's tap can be sent somewhere the brand typed. The server already checks the host against
 * the brand's allowlist and drops what it cannot vouch for; this is the second check, in the client,
 * because the client is the one that puts the string in an `href` (ADR 0157, Decision 6). Anything
 * that does not parse, or whose scheme is not on the list, is not a link: it never reaches the DOM as
 * one, and in particular `javascript:` and `data:` never do.
 */

/** `https:` only: a link that leaves the storefront must not downgrade the customer to plain HTTP. */
export function httpsUrlOrNull(value: unknown): string | null {
  return schemeUrlOrNull(value, ['https:']);
}

/** `https:`, `tel:` and `mailto:`: what an editorial text or a vacancy's contact may point at. */
export function contactUrlOrNull(value: unknown): string | null {
  return schemeUrlOrNull(value, ['https:', 'tel:', 'mailto:']);
}

function schemeUrlOrNull(value: unknown, schemes: readonly string[]): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const text = value.trim();
  // Whitespace or a control character inside the string is how a scheme gets smuggled past a parser
  // that strips them (`java\tscript:`); a URL a brand typed has neither.
  // eslint-disable-next-line no-control-regex
  if (text === '' || /[\u0000- \u007f-\u009f]/.test(text)) {
    return null;
  }
  let parsed: URL;
  try {
    parsed = new URL(text);
  } catch {
    return null;
  }
  if (!schemes.includes(parsed.protocol)) {
    return null;
  }
  if (parsed.protocol === 'https:' && (parsed.username !== '' || parsed.password !== '')) {
    // `https://shop.example@evil.example/` reads as the first host and goes to the second.
    return null;
  }
  return parsed.href;
}

/**
 * A path the server sent for one of its own files: begins with a single `/`, so it can only mean
 * "this site" and never another host (`//host/x` and `/\host` both mean another host to a browser).
 */
export function isSiteRelativePath(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !value.startsWith('/\\') &&
    // eslint-disable-next-line no-control-regex
    !/[\u0000- \u007f-\u009f]/.test(value)
  );
}

/**
 * The origin the API is served from, or the empty string when the storefront reaches it on its own
 * origin (`apiBaseUrl` is then just `/api/v1`). A rendition URL is a site-relative path under
 * `/api/v1/storefront/...`, so against a cross-origin API it has to be put back on that origin.
 */
export function apiOrigin(apiBaseUrl: string): string {
  const match = /^(https?:\/\/[^/?#]+)/i.exec(apiBaseUrl.trim());
  return match ? match[1] : '';
}

/** A site-relative server path made loadable from here. */
export function resolveApiPath(apiBaseUrl: string, path: string): string {
  return `${apiOrigin(apiBaseUrl)}${path}`;
}
