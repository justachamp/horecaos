import { ConfigUnavailableError, loadAppConfig } from './load-config';

const VALID_BODY = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: 'a-key',
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
});
