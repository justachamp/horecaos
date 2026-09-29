import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { APP_CONFIG, type AppConfig } from './core/config/app-config';

const CONFIG: AppConfig = {
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
    providers: [provideRouter(routes), { provide: APP_CONFIG, useValue: CONFIG }],
  });
  return TestBed.inject(Router);
}

/** The route config the router actually settled on for the last navigation. */
function matchedPath(router: Router): string | undefined {
  return router.routerState.snapshot.root.firstChild?.routeConfig?.path;
}

describe('app.routes: the table-QR flow (ADR 0047)', () => {
  beforeEach(() => localStorage.clear());

  it('a printed table code lands on the scan route, with no sign-in in the way', async () => {
    const router = setUp();

    const ok = await router.navigateByUrl('/dine-in/printed-table-token');

    expect(ok).toBe(true);
    expect(matchedPath(router)).toBe('dine-in/:tableToken');
    expect(router.routerState.snapshot.root.firstChild?.paramMap.get('tableToken')).toBe(
      'printed-table-token',
    );
  });

  it('the token-free table screen is its own route, never mistaken for a table token called "table"', async () => {
    const router = setUp();

    const ok = await router.navigateByUrl('/dine-in/table');

    expect(ok).toBe(true);
    expect(matchedPath(router)).toBe('dine-in/table');
  });

  it('neither dine-in route is behind the sign-in guard: a guest at a table holds no account yet', () => {
    const scan = routes.find((route) => route.path === 'dine-in/:tableToken');
    const table = routes.find((route) => route.path === 'dine-in/table');

    expect(scan).toBeDefined();
    expect(table).toBeDefined();
    expect(scan?.canActivate).toBeUndefined();
    expect(table?.canActivate).toBeUndefined();
  });

  it('a delivery-side screen that needs an account still asks for one', () => {
    const cart = routes.find((route) => route.path === 'cart');

    expect(cart?.canActivate?.length).toBeGreaterThan(0);
  });
});

/** The deepest route the router settled on, as its configured path. */
function deepestPath(router: Router): string | undefined {
  let route = router.routerState.snapshot.root;
  while (route.firstChild) {
    route = route.firstChild;
  }
  return route.routeConfig?.path;
}

describe('app.routes: sign-in (ADR 0051)', () => {
  beforeEach(() => localStorage.clear());

  it('/auth/login is a real screen -- not swallowed by the catch-all that sends unknown paths home', async () => {
    const router = setUp();

    const ok = await router.navigateByUrl('/auth/login');

    expect(ok).toBe(true);
    expect(router.url).toBe('/auth/login');
    expect(deepestPath(router)).toBe('login');
  });

  it('/auth/code is a real screen too', async () => {
    const router = setUp();

    await router.navigateByUrl('/auth/code');

    expect(router.url).toBe('/auth/code');
    expect(deepestPath(router)).toBe('code');
  });

  it('a bare /auth opens the login screen', async () => {
    const router = setUp();

    await router.navigateByUrl('/auth');

    expect(router.url).toBe('/auth/login');
  });

  it('is not behind the sign-in guard: nobody signing in has a session yet', () => {
    const auth = routes.find((route) => route.path === 'auth');

    expect(auth).toBeDefined();
    expect(auth?.canActivate).toBeUndefined();
    for (const child of auth?.children ?? []) {
      expect(child.canActivate).toBeUndefined();
    }
  });

  it('a screen that needs an account now really does send an anonymous visitor to the sign-in screen', async () => {
    const router = setUp();

    await router.navigateByUrl('/cart');

    // Before /auth/login existed this ended on /home: the guard's redirect hit
    // the catch-all, and the visitor was never asked to sign in at all.
    expect(router.url).toBe('/auth/login');
    expect(deepestPath(router)).toBe('login');
  });
});
