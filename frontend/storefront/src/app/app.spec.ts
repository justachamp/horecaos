import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import { APP_CONFIG, type AppConfig } from './core/config/app-config';

/**
 * A fixture-shaped config, not a real deployment's.
 *
 * `main.ts` normally supplies `APP_CONFIG` from `/config.json` before the
 * first injector is built (see `load-config.ts`); a spec never runs that
 * bootstrap, so nothing provides the token unless a test does. Without it,
 * constructing `App` throws `NG0201` the moment anything on the injected
 * chain reaches `ApiClient` -- `BottomNavComponent` does, by way of
 * `UiCartService` and `CartService`, well before any HTTP call is made.
 */
const TEST_APP_CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
};

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // The app's own routes, not `[]`: `BottomNavComponent`'s `routerLink`s
      // need a real `ActivatedRoute` to resolve against, which an empty route
      // table still supplies -- an unmatched empty table would not.
      providers: [provideRouter(routes), { provide: APP_CONFIG, useValue: TEST_APP_CONFIG }],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render layout shell', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('router-outlet')).toBeTruthy();
  });
});
