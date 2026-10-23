/**
 * The pieces of Authorization Code + PKCE (RFC 7636) that need a source of randomness or a hash.
 *
 * Kept free of Angular so the whole of it can be pinned to the RFC's own test vector. A public
 * client has no secret: the code verifier is the only thing that stops a stolen authorization
 * code from being redeemed by somebody else, so the two ways this goes wrong -- a verifier that
 * is guessable, and a challenge that is not the S256 of it -- are the ones tested directly.
 */

/** Base64url without padding (RFC 4648 §5), the encoding PKCE, `state` and `nonce` all use. */
export function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** The inverse of {@link base64Url}. */
export function fromBase64Url(text: string): Uint8Array {
  const padded = text
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(text.length / 4) * 4, '=');
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

/** `byteLength` bytes from the platform's CSPRNG, base64url encoded. Never `Math.random`. */
export function randomToken(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  globalThis.crypto.getRandomValues(bytes);
  return base64Url(bytes);
}

/**
 * A fresh code verifier: 64 random bytes, which is 86 characters of the unreserved alphabet.
 * RFC 7636 asks for 43 to 128 characters and at least 256 bits of entropy; this has 512.
 */
export function newCodeVerifier(): string {
  return randomToken(64);
}

/** `BASE64URL(SHA256(verifier))` -- the only challenge method this app sends. `plain` is never used. */
export async function codeChallengeS256(verifier: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(verifier),
  );
  return base64Url(new Uint8Array(digest));
}

/** The payload of a JWT, decoded and not verified. See `OidcClient.exchangeCode` for why that is enough there. */
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split('.');
  if (parts.length !== 3) {
    throw new Error('Not a JWT');
  }
  const json = new TextDecoder().decode(fromBase64Url(parts[1]));
  const payload: unknown = JSON.parse(json);
  if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
    throw new Error('A JWT payload is not an object');
  }
  return payload as Record<string, unknown>;
}
