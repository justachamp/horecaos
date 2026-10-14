import { Injectable } from '@angular/core';

/** What the cardholder typed, and where the provider takes it. The number goes to `url` and nowhere else. */
export interface TokeniseRequest {
  /** The provider's tokenise address, from the approved environment the server named. */
  readonly url: string;
  /** The provider's public identifier for HorecaOS's merchant account. */
  readonly serviceId: string;
  /** Digits only. Sent to the provider and never kept. */
  readonly cardNumber: string;
  /** `MMYY`. */
  readonly expiry: string;
  /** `'0'` for a token a recurring charge may use, `'1'` for a one-time one. */
  readonly temporary: string;
}

export interface TokeniseResult {
  /** What the provider hands back in place of the number: a reference into its vault. */
  readonly token: string;
  /** The phone the bank's code was texted to, masked by the provider. */
  readonly maskedPhone: string | null;
}

/** Why a tokenisation did not produce a token. Never carries the number, the token or the provider's free text. */
export type TokeniseFailure = 'UNREACHABLE' | 'REFUSED' | 'UNREADABLE';

export class TokeniseError extends Error {
  constructor(readonly failure: TokeniseFailure) {
    super(failure);
    this.name = 'TokeniseError';
  }
}

/**
 * Whether the browser may send a card number to this address: `https:` always, and plain `http:` only to a
 * loopback host, which is how a developer walks the flow against a local provider. Anything else, including a
 * script or data URL, is refused before a byte is sent: the address comes from the server, and this is the
 * last place that can stop a card number going somewhere it should not.
 */
export function isSafeTokeniseUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    if (parsed.protocol === 'https:') {
      return true;
    }
    return (
      parsed.protocol === 'http:' &&
      (parsed.hostname === 'localhost' ||
        parsed.hostname === '127.0.0.1' ||
        parsed.hostname === '[::1]')
    );
  } catch {
    return false;
  }
}

/**
 * Hands a card number to the payment provider and nothing else (ADR 0095, ADR 0028).
 *
 * **The card number never reaches HorecaOS.** This is the one place in the console that holds one, for the
 * length of one request: it goes from the browser to the address the approved environment names, with no
 * HorecaOS credential, no cookie and no referrer (a plain `fetch`, not the API client that attaches the
 * console's token), and what comes back is a token. The caller clears the number the moment this settles,
 * whether it succeeded or not.
 *
 * It is an injectable so a spec can answer for the provider; the real provider cannot be reached from a test.
 */
@Injectable({ providedIn: 'root' })
export class CardTokeniser {
  async tokenise(request: TokeniseRequest): Promise<TokeniseResult> {
    if (!isSafeTokeniseUrl(request.url)) {
      throw new TokeniseError('REFUSED');
    }
    const serviceId = Number(request.serviceId);
    if (!Number.isSafeInteger(serviceId)) {
      throw new TokeniseError('REFUSED');
    }

    let response: Response;
    try {
      response = await fetch(request.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
          service_id: serviceId,
          card_number: request.cardNumber,
          expire_date: request.expiry,
          temporary: Number(request.temporary),
        }),
        credentials: 'omit',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
        mode: 'cors',
      });
    } catch {
      throw new TokeniseError('UNREACHABLE');
    }

    let answer: unknown;
    try {
      answer = await response.json();
    } catch {
      throw new TokeniseError(response.ok ? 'UNREADABLE' : 'REFUSED');
    }
    if (!response.ok || typeof answer !== 'object' || answer === null) {
      throw new TokeniseError('REFUSED');
    }
    const body = answer as Record<string, unknown>;
    if (Number(body['error_code']) !== 0) {
      throw new TokeniseError('REFUSED');
    }
    const token = body['card_token'];
    if (typeof token !== 'string' || token.trim().length === 0) {
      throw new TokeniseError('UNREADABLE');
    }
    const phone = body['phone_number'];
    return { token, maskedPhone: typeof phone === 'string' && phone.length > 0 ? phone : null };
  }
}
