import { describe, expect, it } from 'vitest';

import { resolveAppConfig } from './app-config';

describe('resolveAppConfig', () => {
  it('falls back to localhost when no config.js has been deployed', () => {
    // Not to production. A missing configuration file should fail against a
    // machine that is not there rather than silently pointing at the real API.
    const config = resolveAppConfig({});
    expect(config.apiBaseUrl).toContain('localhost');
  });

  it('takes what config.js supplies', () => {
    const config = resolveAppConfig({
      horecaosControlPlaneConfig: { apiBaseUrl: 'https://api.horecaos.uz' },
    });
    expect(config.apiBaseUrl).toBe('https://api.horecaos.uz');
  });

  it('keeps defaults for fields config.js does not mention', () => {
    const config = resolveAppConfig({
      horecaosControlPlaneConfig: { apiBaseUrl: 'https://api.horecaos.uz' },
    });
    expect(config.displayTimeZone).toBe('Asia/Tashkent');
  });

  it('strips a trailing slash so paths do not become //api/v1', () => {
    const config = resolveAppConfig({
      horecaosControlPlaneConfig: { apiBaseUrl: 'https://api.horecaos.uz/' },
    });
    expect(config.apiBaseUrl).toBe('https://api.horecaos.uz');
  });
});
