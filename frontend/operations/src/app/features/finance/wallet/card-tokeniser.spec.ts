import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CardTokeniser, TokeniseError, isSafeTokeniseUrl } from './card-tokeniser';

const URL_OK = 'https://api.click.uz/v2/merchant/card_token/request';

const REQUEST = {
  url: URL_OK,
  serviceId: '100100',
  cardNumber: '8600123412344242',
  expiry: '1230',
  temporary: '0',
};

function answer(body: unknown, status = 200): Response {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('CardTokeniser', () => {
  let tokeniser: CardTokeniser;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    tokeniser = TestBed.inject(CardTokeniser);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('sends the number to the provider alone: no credential, no cookie, no referrer', async () => {
    fetchMock.mockResolvedValue(
      answer({ error_code: 0, error_note: '', card_token: 'tok-1', phone_number: '+998*******97' }),
    );

    const result = await tokeniser.tokenise(REQUEST);

    expect(result).toEqual({ token: 'tok-1', maskedPhone: '+998*******97' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(URL_OK);
    expect(init.credentials).toBe('omit');
    expect(init.referrerPolicy).toBe('no-referrer');
    expect(init.cache).toBe('no-store');
    expect(Object.keys(init.headers as Record<string, string>).map((h) => h.toLowerCase())).toEqual(
      ['content-type', 'accept'],
    );
    expect(JSON.parse(init.body as string)).toEqual({
      service_id: 100100,
      card_number: '8600123412344242',
      expire_date: '1230',
      temporary: 0,
    });
  });

  it('reads a provider’s refusal as a refusal and never repeats the number', async () => {
    fetchMock.mockResolvedValue(answer({ error_code: -100, error_note: '8600123412344242 bad' }));

    const failure = await tokeniser.tokenise(REQUEST).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(TokeniseError);
    expect((failure as TokeniseError).failure).toBe('REFUSED');
    expect(String((failure as TokeniseError).message)).not.toContain('8600');
  });

  it('says the provider could not be reached when the network or the origin policy stops the call', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const failure = await tokeniser.tokenise(REQUEST).catch((error: unknown) => error);

    expect((failure as TokeniseError).failure).toBe('UNREACHABLE');
  });

  it('says the answer could not be read when the provider answers with something that is not a token', async () => {
    fetchMock.mockResolvedValueOnce(answer('<html>gateway</html>'));
    fetchMock.mockResolvedValueOnce(answer({ error_code: 0 }));

    const first = await tokeniser.tokenise(REQUEST).catch((error: unknown) => error);
    const second = await tokeniser.tokenise(REQUEST).catch((error: unknown) => error);

    expect((first as TokeniseError).failure).toBe('UNREADABLE');
    expect((second as TokeniseError).failure).toBe('UNREADABLE');
  });

  it('treats an HTTP error as a refusal', async () => {
    fetchMock.mockResolvedValue(answer({ error_note: 'nope' }, 400));

    const failure = await tokeniser.tokenise(REQUEST).catch((error: unknown) => error);

    expect((failure as TokeniseError).failure).toBe('REFUSED');
  });

  it('never sends a number to an address that is not https, or not loopback', async () => {
    for (const url of [
      'http://api.click.uz/v2/merchant/card_token/request',
      'http://evil.example/collect',
      'javascript:alert(1)',
      'data:text/plain,x',
      'not a url',
      '',
    ]) {
      const failure = await tokeniser
        .tokenise({ ...REQUEST, url })
        .catch((error: unknown) => error);
      expect((failure as TokeniseError).failure, url).toBe('REFUSED');
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a service id that is not a number', async () => {
    const failure = await tokeniser
      .tokenise({ ...REQUEST, serviceId: '100100; drop' })
      .catch((error: unknown) => error);

    expect((failure as TokeniseError).failure).toBe('REFUSED');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('allows plain http to a loopback provider, which is how a developer walks the flow', () => {
    expect(isSafeTokeniseUrl('http://localhost:18090/v2/merchant/card_token/request')).toBe(true);
    expect(isSafeTokeniseUrl('http://127.0.0.1:18090/x')).toBe(true);
    expect(isSafeTokeniseUrl('https://anything.example/x')).toBe(true);
    expect(isSafeTokeniseUrl('http://192.168.0.5/x')).toBe(false);
  });
});
