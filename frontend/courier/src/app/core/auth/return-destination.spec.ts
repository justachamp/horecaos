import { sanitizeReturnTo } from './return-destination';

describe('sanitizeReturnTo', () => {
  it('keeps a path inside the app, query and all', () => {
    expect(sanitizeReturnTo('/profile')).toBe('/profile');
    expect(sanitizeReturnTo('/deliveries/abc?tab=map')).toBe('/deliveries/abc?tab=map');
  });

  for (const hostile of [
    '//evil.example',
    '/\\evil.example',
    'https://evil.example',
    'javascript:alert(1)',
    'evil.example/path',
    '/ok\\..\\evil',
    '/ok\nSet-Cookie: x=1',
  ]) {
    it(`sends ${JSON.stringify(hostile)} to the start screen instead of following it`, () => {
      expect(sanitizeReturnTo(hostile)).toBe('/');
    });
  }

  it('never returns into the sign-in machinery itself', () => {
    expect(sanitizeReturnTo('/auth/callback?code=x')).toBe('/');
    expect(sanitizeReturnTo('/auth')).toBe('/');
    expect(sanitizeReturnTo('/authors')).toBe('/authors');
  });

  it('treats nothing as the start screen', () => {
    expect(sanitizeReturnTo(undefined)).toBe('/');
    expect(sanitizeReturnTo(null)).toBe('/');
    expect(sanitizeReturnTo('')).toBe('/');
  });
});
