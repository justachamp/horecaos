import { TestBed } from '@angular/core/testing';
import { Router, Routes, provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { APP_CONFIG, type AppConfig } from './core/config/app-config';
import { Session } from './core/auth/session';

const TEST_APP_CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

function setUp() {
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), { provide: APP_CONFIG, useValue: TEST_APP_CONFIG }],
  });
  return { router: TestBed.inject(Router), session: TestBed.inject(Session) };
}

describe('app.routes: the anonymous browse surface', () => {
  beforeEach(() => localStorage.clear());

  it('an anonymous visitor reaches /home directly', async () => {
    const { router } = setUp();

    const ok = await router.navigateByUrl('/home');

    expect(ok).toBe(true);
    expect(router.url).toBe('/home');
  });

  it('an anonymous visitor reaches /search directly', async () => {
    const { router } = setUp();

    const ok = await router.navigateByUrl('/search');

    expect(ok).toBe(true);
    expect(router.url).toBe('/search');
  });

  it('an anonymous visitor reaches /category directly', async () => {
    const { router } = setUp();

    const ok = await router.navigateByUrl('/category');

    expect(ok).toBe(true);
    expect(router.url).toBe('/category');
  });

  it('an anonymous visitor reaches a product page directly', async () => {
    const { router } = setUp();

    const ok = await router.navigateByUrl('/product/11111111-1111-1111-1111-111111111111');

    expect(ok).toBe(true);
    expect(router.url).toBe('/product/11111111-1111-1111-1111-111111111111');
  });

  it('the root path lands on the anonymous home', async () => {
    const { router } = setUp();

    await router.navigateByUrl('/');

    expect(router.url).toBe('/home');
  });
});

describe('app.routes: checkout, orders and the address book stay behind sign-in', () => {
  beforeEach(() => localStorage.clear());

  it('redirects /cart to /auth/login for an anonymous visitor', async () => {
    const { router } = setUp();

    await router.navigateByUrl('/cart');

    expect(router.url).toBe('/auth/login');
  });

  it('redirects /orders to /auth/login for an anonymous visitor', async () => {
    const { router } = setUp();

    await router.navigateByUrl('/orders');

    expect(router.url).toBe('/auth/login');
  });

  it('redirects /locations to /auth/login for an anonymous visitor', async () => {
    const { router } = setUp();

    await router.navigateByUrl('/locations');

    expect(router.url).toBe('/auth/login');
  });

  it('lets a signed-in customer reach /cart', async () => {
    const { router, session } = setUp();
    session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    const ok = await router.navigateByUrl('/cart');

    expect(ok).toBe(true);
    expect(router.url).toBe('/cart/items');
  });
});

describe('app.routes: the profile shell is public, its account screens are not', () => {
  beforeEach(() => localStorage.clear());

  it('an anonymous visitor reaches /profile itself', async () => {
    const { router } = setUp();

    const ok = await router.navigateByUrl('/profile');

    expect(ok).toBe(true);
    expect(router.url).toBe('/profile');
  });

  it('redirects /profile/details to /auth/login for an anonymous visitor', async () => {
    const { router } = setUp();

    await router.navigateByUrl('/profile/details');

    expect(router.url).toBe('/auth/login');
  });

  it('redirects /profile/favorites to /auth/login for an anonymous visitor', async () => {
    const { router } = setUp();

    await router.navigateByUrl('/profile/favorites');

    expect(router.url).toBe('/auth/login');
  });

  it('lets a signed-in customer reach /profile/details', async () => {
    const { router, session } = setUp();
    session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 60_000).toISOString() });

    const ok = await router.navigateByUrl('/profile/details');

    expect(ok).toBe(true);
    expect(router.url).toBe('/profile/details');
  });
});

describe('app.routes: dine-in QR (row 10.5, ADR 0047) needs no account either', () => {
  beforeEach(() => localStorage.clear());

  it('an anonymous scan reaches the scan-landing route with the printed token as a param', async () => {
    const { router } = setUp();

    const ok = await router.navigateByUrl('/dine-in/a-printed-table-token');

    expect(ok).toBe(true);
    expect(router.url).toBe('/dine-in/a-printed-table-token');
  });

  it('the literal /dine-in/table route -- not the :tableToken route -- resumes an existing visit', async () => {
    const { router } = setUp();

    const ok = await router.navigateByUrl('/dine-in/table');

    expect(ok).toBe(true);
    expect(router.url).toBe('/dine-in/table');
  });
});

describe('app.routes: the initial bundle carries the home page and nothing else', () => {
  /**
   * Every routed screen except `home` (the landing route, painted on first
   * load) is fetched on demand. A screen imported into this file statically
   * rides in the initial bundle -- the reason storefront sat at 590 kB against a
   * 500 kB warning -- and `PreloadAllModules` (app.config.ts) already fetches
   * the lazy chunks in the background once the shell is up, so the visitor
   * does not wait on a chunk when they navigate.
   */
  function eagerComponentPaths(list: Routes, prefix = ''): string[] {
    return list.flatMap((route) => {
      const path = `${prefix}/${route.path ?? ''}`;
      const own = route.component ? [path] : [];
      return [...own, ...eagerComponentPaths(route.children ?? [], path)];
    });
  }

  it('routes no component eagerly except home', () => {
    expect(eagerComponentPaths(routes)).toEqual(['/home']);
  });
});
