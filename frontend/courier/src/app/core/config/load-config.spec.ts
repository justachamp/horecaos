import { ConfigUnavailableError, loadAppConfig } from './load-config';

const VALID = {
  apiBaseUrl: '/api/v1/',
  issuerUrl: 'https://auth.horecaos.uz/realms/horecaos/',
  clientId: 'horecaos-courier',
  tenantId: '10000000-0000-0000-0000-000000000001',
  displayTimeZone: 'Asia/Tashkent',
  currency: 'UZS',
};

function serving(body: unknown, status = 200): typeof fetch {
  return async () => new Response(JSON.stringify(body), { status });
}

describe('loadAppConfig', () => {
  it('reads a valid file and strips the trailing slashes that would double up in a URL', async () => {
    const config = await loadAppConfig(serving(VALID), 'https:');

    expect(config).toEqual({
      apiBaseUrl: '/api/v1',
      issuerUrl: 'https://auth.horecaos.uz/realms/horecaos',
      clientId: 'horecaos-courier',
      tenantId: '10000000-0000-0000-0000-000000000001',
      displayTimeZone: 'Asia/Tashkent',
      currency: 'UZS',
    });
  });

  it('asks for the file fresh, never from a cache', async () => {
    let init: RequestInit | undefined;
    await loadAppConfig(async (_input, options) => {
      init = options;
      return new Response(JSON.stringify(VALID));
    }, 'https:');

    expect(init?.cache).toBe('no-store');
  });

  for (const key of ['apiBaseUrl', 'issuerUrl', 'clientId', 'tenantId'] as const) {
    it(`refuses to start without "${key}" instead of inventing one`, async () => {
      const incomplete: Record<string, unknown> = { ...VALID };
      delete incomplete[key];

      await expect(loadAppConfig(serving(incomplete), 'https:')).rejects.toThrow(
        new ConfigUnavailableError(`config.json is missing "${key}".`),
      );
    });
  }

  it('refuses a tenant id that is not a UUID -- a placeholder passes a truthiness test and fails every call', async () => {
    await expect(
      loadAppConfig(serving({ ...VALID, tenantId: 'tenant-1' }), 'https:'),
    ).rejects.toThrow('not a UUID');
  });

  it('refuses an http issuer for a page served over https', async () => {
    await expect(
      loadAppConfig(serving({ ...VALID, issuerUrl: 'http://auth.horecaos.uz/realms/h' }), 'https:'),
    ).rejects.toThrow('http "issuerUrl" for a page served over https');
  });

  it('allows an http issuer for a page on a developer machine', async () => {
    const config = await loadAppConfig(
      serving({ ...VALID, issuerUrl: 'http://localhost:8081/realms/horecaos' }),
      'http:',
    );

    expect(config.issuerUrl).toBe('http://localhost:8081/realms/horecaos');
  });

  it('refuses an issuer carrying a query or a fragment', async () => {
    await expect(
      loadAppConfig(serving({ ...VALID, issuerUrl: 'https://auth.horecaos.uz/r?x=1' }), 'https:'),
    ).rejects.toThrow('query or fragment');
  });

  it('refuses an issuer that is not an http(s) URL', async () => {
    await expect(
      loadAppConfig(serving({ ...VALID, issuerUrl: 'javascript:alert(1)' }), 'https:'),
    ).rejects.toThrow('not http(s)');
  });

  it('falls back to the default zone when the named one is unknown, and does not refuse to start for it', async () => {
    const config = await loadAppConfig(
      serving({ ...VALID, displayTimeZone: 'Mars/Olympus' }),
      'https:',
    );

    expect(config.displayTimeZone).toBe('Asia/Tashkent');
  });

  it('defaults the settlement currency when the file names none, and reads it in capitals', async () => {
    const withoutCurrency: Record<string, unknown> = { ...VALID };
    delete withoutCurrency['currency'];

    expect((await loadAppConfig(serving(withoutCurrency), 'https:')).currency).toBe('UZS');
    expect((await loadAppConfig(serving({ ...VALID, currency: ' usd ' }), 'https:')).currency).toBe(
      'USD',
    );
  });

  it('refuses a currency that is not a three-letter code -- it is written into the shift and its cash handover', async () => {
    for (const currency of ['so', 'UZSS', '$$$', 12]) {
      await expect(loadAppConfig(serving({ ...VALID, currency }), 'https:')).rejects.toThrow(
        'not a three-letter currency code',
      );
    }
  });

  it('reports a file that cannot be fetched, is not 200, or is not JSON as the start failing', async () => {
    await expect(
      loadAppConfig(async () => Promise.reject(new TypeError('offline')), 'https:'),
    ).rejects.toThrow('could not be fetched');
    await expect(loadAppConfig(serving({}, 404), 'https:')).rejects.toThrow('answered 404');
    await expect(
      loadAppConfig(async () => new Response('<html>', { status: 200 }), 'https:'),
    ).rejects.toThrow('not JSON');
    await expect(loadAppConfig(serving([1, 2]), 'https:')).rejects.toThrow('is missing');
  });
});
