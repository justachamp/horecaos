import {
  apiOrigin,
  contactUrlOrNull,
  httpsUrlOrNull,
  isSiteRelativePath,
  resolveApiPath,
} from './safe-url';

describe('httpsUrlOrNull: the client re-checks every external link before it becomes an href', () => {
  it('accepts a plain https URL and returns it normalised', () => {
    expect(httpsUrlOrNull('https://example.com/menu?x=1')).toBe('https://example.com/menu?x=1');
  });

  it.each([
    'http://example.com/',
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'tel:+998901234567',
    'mailto:a@b.uz',
    '//example.com/x',
    '/relative/path',
    'example.com',
    '',
    '   ',
  ])('refuses %j', (value) => {
    expect(httpsUrlOrNull(value)).toBeNull();
  });

  it('refuses a scheme smuggled past a parser that strips tabs and newlines', () => {
    expect(httpsUrlOrNull('java\tscript:alert(1)')).toBeNull();
    expect(httpsUrlOrNull('https://exa\nmple.com')).toBeNull();
  });

  it('refuses a URL with credentials in it: it reads as one host and goes to another', () => {
    expect(httpsUrlOrNull('https://shop.example@evil.example/')).toBeNull();
  });

  it('refuses a value that is not a string', () => {
    expect(httpsUrlOrNull(undefined)).toBeNull();
    expect(httpsUrlOrNull(null)).toBeNull();
    expect(httpsUrlOrNull(42)).toBeNull();
    expect(httpsUrlOrNull({ href: 'https://example.com' })).toBeNull();
  });
});

describe('contactUrlOrNull: https, tel and mailto are the editorial link schemes', () => {
  it.each(['https://example.com/', 'tel:+998901234567', 'mailto:jobs@example.uz'])(
    'accepts %s',
    (value) => {
      expect(contactUrlOrNull(value)).not.toBeNull();
    },
  );

  it.each(['http://example.com/', 'javascript:alert(1)', 'data:text/plain,x', 'sms:+998901234567'])(
    'refuses %s',
    (value) => {
      expect(contactUrlOrNull(value)).toBeNull();
    },
  );
});

describe('isSiteRelativePath: a path the server sent for one of its own files', () => {
  it('accepts a single-slash path', () => {
    expect(isSiteRelativePath('/api/v1/storefront/tenants/t/media/a?variant=w800')).toBe(true);
  });

  it.each([
    '//evil.example/x.jpg',
    '/\\evil.example/x.jpg',
    'https://example.com/x.jpg',
    'x.jpg',
    '',
  ])('refuses %j, which a browser could read as another host', (value) => {
    expect(isSiteRelativePath(value)).toBe(false);
  });

  it('refuses a path with whitespace or a control character in it', () => {
    expect(isSiteRelativePath('/a b')).toBe(false);
    expect(isSiteRelativePath('/a\nb')).toBe(false);
  });

  it('refuses a value that is not a string', () => {
    expect(isSiteRelativePath(undefined)).toBe(false);
    expect(isSiteRelativePath(7)).toBe(false);
  });
});

describe('apiOrigin and resolveApiPath: a rendition path goes back on the API origin', () => {
  it('a cross-origin API base gives its origin', () => {
    expect(apiOrigin('https://api.example.uz/api/v1')).toBe('https://api.example.uz');
    expect(apiOrigin('http://localhost:8080/api/v1')).toBe('http://localhost:8080');
  });

  it('a same-origin API base gives no origin at all', () => {
    expect(apiOrigin('/api/v1')).toBe('');
  });

  it('resolves a site-relative path against the API origin', () => {
    expect(resolveApiPath('https://api.example.uz/api/v1', '/api/v1/storefront/m/a')).toBe(
      'https://api.example.uz/api/v1/storefront/m/a',
    );
  });

  it('leaves a site-relative path alone when the API is same-origin', () => {
    expect(resolveApiPath('/api/v1', '/api/v1/storefront/m/a')).toBe('/api/v1/storefront/m/a');
  });
});
