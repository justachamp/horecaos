import { ConfigUnavailableError, loadAppConfig, NEUTRAL_BRAND } from './load-config';

const VALID_BRAND = {
  displayName: "Tandir House",
  logoUrl: 'https://cdn.example.com/tandir-house/logo.svg',
  theme: {
    accent: '#c0392b',
    accentDeep: '#7b241c',
  },
};

const VALID_BODY = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: 'a-key',
  brand: VALID_BRAND,
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status });
}

describe('loadAppConfig', () => {
  it('populates the full AppConfig shape on success', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(VALID_BODY));

    const config = await loadAppConfig(fetchImpl);

    expect(config).toEqual({
      apiBaseUrl: '/api/v1',
      tenantId: '10000000-0000-0000-0000-000000000001',
      brandId: '10000000-0000-0000-0000-000000000002',
      defaultLocationId: '10000000-0000-0000-0000-000000000003',
      channel: 'STOREFRONT',
      yandexMapsApiKey: 'a-key',
      brand: VALID_BRAND,
    });
  });

  it('fetches config.json with cache: no-store', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(VALID_BODY));

    await loadAppConfig(fetchImpl);

    expect(fetchImpl).toHaveBeenCalledWith('config.json', { cache: 'no-store' });
  });

  it('defaults yandexMapsApiKey to empty string when absent', async () => {
    const { yandexMapsApiKey: _drop, ...rest } = VALID_BODY;
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(rest));

    const config = await loadAppConfig(fetchImpl);

    expect(config.yandexMapsApiKey).toBe('');
  });

  it('defaults yandexMapsApiKey to empty string when not a string', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse({ ...VALID_BODY, yandexMapsApiKey: 42 }));

    const config = await loadAppConfig(fetchImpl);

    expect(config.yandexMapsApiKey).toBe('');
  });

  it('leaves defaultLocationId undefined when absent', async () => {
    const { defaultLocationId: _drop, ...rest } = VALID_BODY;
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(rest));

    const config = await loadAppConfig(fetchImpl);

    expect(config.defaultLocationId).toBeUndefined();
  });

  it('leaves defaultLocationId undefined when an empty string', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse({ ...VALID_BODY, defaultLocationId: '' }));

    const config = await loadAppConfig(fetchImpl);

    expect(config.defaultLocationId).toBeUndefined();
  });

  it('rejects a defaultLocationId that is present but not a UUID', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse({ ...VALID_BODY, defaultLocationId: 'not-a-uuid' }));

    await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
  });

  it('rejects a body that is not an object', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse('just a string'));

    await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
  });

  it('rejects a null body', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(null));

    await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
  });

  it.each(['apiBaseUrl', 'tenantId', 'brandId', 'channel'] as const)(
    'rejects a body missing required field "%s"',
    async (field) => {
      const body = { ...VALID_BODY } as Record<string, unknown>;
      delete body[field];
      const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(body));

      await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
    },
  );

  it.each(['apiBaseUrl', 'tenantId', 'brandId', 'channel'] as const)(
    'rejects a body with an empty string for required field "%s"',
    async (field) => {
      const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ ...VALID_BODY, [field]: '   ' }));

      await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
    },
  );

  it.each(['tenantId', 'brandId'] as const)(
    'rejects a "%s" that is not shaped like a UUID',
    async (field) => {
      const fetchImpl = vi
        .fn()
        .mockResolvedValue(jsonResponse({ ...VALID_BODY, [field]: 'placeholder-not-a-uuid' }));

      await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
    },
  );

  it('rejects a response body that is malformed JSON', async () => {
    const malformed = new Response('{not valid json', { status: 200 });
    const fetchImpl = vi.fn().mockResolvedValue(malformed);

    await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
  });

  it('rejects a non-ok response without attempting to parse it', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(VALID_BODY, 404));

    await expect(loadAppConfig(fetchImpl)).rejects.toThrow(ConfigUnavailableError);
  });

  it('rejects when the fetch itself fails (offline, DNS, CORS)', async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));

    const failure = await loadAppConfig(fetchImpl).catch((e: unknown) => e);

    expect(failure).toBeInstanceOf(ConfigUnavailableError);
    expect((failure as ConfigUnavailableError).cause).toBeInstanceOf(TypeError);
  });

  describe('brand', () => {
    it('degrades to the neutral brand when "brand" is entirely absent', async () => {
      const { brand: _drop, ...rest } = VALID_BODY;
      const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(rest));

      const config = await loadAppConfig(fetchImpl);

      expect(config.brand).toEqual(NEUTRAL_BRAND);
    });

    it('degrades to the neutral brand when "brand" is not an object', async () => {
      const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ ...VALID_BODY, brand: 'JizBiz' }));

      const config = await loadAppConfig(fetchImpl);

      expect(config.brand).toEqual(NEUTRAL_BRAND);
    });

    it('never resolves the neutral default to the legacy brand this app was cloned from', () => {
      const serialized = JSON.stringify(NEUTRAL_BRAND).toLowerCase();

      expect(serialized).not.toContain('jizbiz');
    });

    it('fills a missing displayName with the neutral name, independent of the rest of the brand', async () => {
      const { displayName: _drop, ...restBrand } = VALID_BRAND;
      const fetchImpl = vi
        .fn()
        .mockResolvedValue(jsonResponse({ ...VALID_BODY, brand: restBrand }));

      const config = await loadAppConfig(fetchImpl);

      expect(config.brand.displayName).toBe(NEUTRAL_BRAND.displayName);
      expect(config.brand.theme).toEqual(VALID_BRAND.theme);
    });

    it('leaves logoUrl undefined when absent, rather than defaulting it to an asset', async () => {
      const { logoUrl: _drop, ...restBrand } = VALID_BRAND;
      const fetchImpl = vi
        .fn()
        .mockResolvedValue(jsonResponse({ ...VALID_BODY, brand: restBrand }));

      const config = await loadAppConfig(fetchImpl);

      expect(config.brand.logoUrl).toBeUndefined();
    });

    it('fills a missing theme with the neutral, brand-less colours', async () => {
      const { theme: _drop, ...restBrand } = VALID_BRAND;
      const fetchImpl = vi
        .fn()
        .mockResolvedValue(jsonResponse({ ...VALID_BODY, brand: restBrand }));

      const config = await loadAppConfig(fetchImpl);

      expect(config.brand.theme).toEqual(NEUTRAL_BRAND.theme);
    });

    it('fills a partial theme field-by-field rather than discarding the whole theme', async () => {
      const fetchImpl = vi.fn().mockResolvedValue(
        jsonResponse({
          ...VALID_BODY,
          brand: { ...VALID_BRAND, theme: { accent: '#123456' } },
        }),
      );

      const config = await loadAppConfig(fetchImpl);

      expect(config.brand.theme).toEqual({
        accent: '#123456',
        accentDeep: NEUTRAL_BRAND.theme.accentDeep,
      });
    });

    it('does not reject the whole config when brand fields are the wrong type', async () => {
      const fetchImpl = vi.fn().mockResolvedValue(
        jsonResponse({
          ...VALID_BODY,
          brand: { displayName: 42, logoUrl: null, theme: { accent: 7, accentDeep: [] } },
        }),
      );

      const config = await loadAppConfig(fetchImpl);

      expect(config.brand).toEqual(NEUTRAL_BRAND);
    });
  });
});
