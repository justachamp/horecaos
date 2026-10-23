import {
  base64Url,
  codeChallengeS256,
  decodeJwtPayload,
  fromBase64Url,
  newCodeVerifier,
  randomToken,
} from './pkce';

describe('PKCE', () => {
  it('produces the challenge RFC 7636 appendix B gives for its own verifier', async () => {
    // The only thing that makes "S256" mean anything is that it matches the standard's vector.
    expect(await codeChallengeS256('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(
      'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    );
  });

  it('makes a verifier of 86 unreserved characters, a different one each time', () => {
    const first = newCodeVerifier();
    const second = newCodeVerifier();

    expect(first).toMatch(/^[A-Za-z0-9_-]{86}$/);
    expect(first).not.toBe(second);
  });

  it('makes tokens of the requested entropy and never a padded or url-unsafe one', () => {
    const token = randomToken(32);

    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it('round-trips bytes through base64url, including ones that base64 would pad or escape', () => {
    const bytes = Uint8Array.from([251, 255, 254, 0, 1]);

    const text = base64Url(bytes);

    expect(text).not.toMatch(/[+/=]/);
    expect(Array.from(fromBase64Url(text))).toEqual(Array.from(bytes));
  });

  describe('decodeJwtPayload', () => {
    const encode = (value: unknown) => base64Url(new TextEncoder().encode(JSON.stringify(value)));

    it('reads a payload, multi-byte text included', () => {
      const token = `${encode({ alg: 'none' })}.${encode({ sub: 'abc', name: 'Ёлкин Oʻtkir' })}.sig`;

      expect(decodeJwtPayload(token)).toEqual({ sub: 'abc', name: 'Ёлкин Oʻtkir' });
    });

    it('refuses something that is not three segments', () => {
      expect(() => decodeJwtPayload('abc.def')).toThrow();
    });

    it('refuses a payload that is not an object', () => {
      expect(() => decodeJwtPayload(`${encode({})}.${encode([1])}.sig`)).toThrow();
    });
  });
});
