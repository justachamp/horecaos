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
