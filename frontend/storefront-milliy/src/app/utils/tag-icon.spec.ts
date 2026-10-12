import { tagIconSrc } from './tag-icon';

const SAME_ORIGIN = { apiBaseUrl: '/api/v1', tenantId: 't-1' };
const CROSS_ORIGIN = { apiBaseUrl: 'https://api.example.uz/api/v1', tenantId: 't-1' };

describe('tagIconSrc', () => {
  it('draws the path the platform sent, on the origin the API is served from', () => {
    const path = '/api/v1/storefront/tenants/t-1/media/a-1';

    expect(tagIconSrc({ iconUrl: path }, SAME_ORIGIN)).toBe(path);
    expect(tagIconSrc({ iconUrl: path }, CROSS_ORIGIN)).toBe(`https://api.example.uz${path}`);
  });

  it('builds the same address from the bare asset id the customer’s own review carries', () => {
    expect(tagIconSrc({ iconAssetId: 'a-1' }, CROSS_ORIGIN)).toBe(
      'https://api.example.uz/api/v1/storefront/tenants/t-1/media/a-1',
    );
  });

  it('has no icon for a tag without one', () => {
    expect(tagIconSrc({}, SAME_ORIGIN)).toBeNull();
    expect(tagIconSrc({ iconUrl: null, iconAssetId: null }, SAME_ORIGIN)).toBeNull();
  });

  it('refuses a path that is not the platform’s own, so a tag cannot point the page at another host', () => {
    for (const hostile of [
      'https://evil.example/x.svg',
      '//evil.example/x.svg',
      '/\\evil.example/x.svg',
      'javascript:alert(1)',
      'data:image/svg+xml,<svg/>',
      'media/a-1',
    ]) {
      expect(tagIconSrc({ iconUrl: hostile }, SAME_ORIGIN), hostile).toBeNull();
    }
  });

  it('does not let an asset id climb out of the media route', () => {
    expect(tagIconSrc({ iconAssetId: '../../x' }, SAME_ORIGIN)).toBe(
      '/api/v1/storefront/tenants/t-1/media/..%2F..%2Fx',
    );
  });
});
