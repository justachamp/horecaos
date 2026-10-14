import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, TitleStrategy, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { TEST_CONFIG, courierProfile } from '../testing/fixtures';
import { callbackFor, configureAuth, type AuthHarness } from '../testing/auth-harness';
import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { APP_CONFIG } from './core/config/app-config';
import { CourierApi } from './core/courier/courier-api';
import { I18n } from './core/i18n/i18n';
import { HomePage } from './features/home/home';
import { AppTitleStrategy } from './core/i18n/title-strategy';

describe('the route table', () => {
  let harness: AuthHarness;

  beforeEach(async () => {
    sessionStorage.clear();
    harness = configureAuth();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes, withComponentInputBinding()),
        { provide: TitleStrategy, useClass: AppTitleStrategy },
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: { me: async () => courierProfile() } },
      ],
    });
    await TestBed.inject(I18n).use('en');
  });

  afterEach(() => sessionStorage.clear());

  it('sends an unauthenticated visit to the identity provider instead of drawing the screen', async () => {
    const routed = await (await RouterTestingHarness.create()).navigateByUrl('/profile');

    expect(routed).toBeNull();
    const destination = new URL(harness.navigations.at(-1)!);
    expect(destination.pathname).toContain('/protocol/openid-connect/auth');
    expect(destination.searchParams.get('code_challenge_method')).toBe('S256');
    // …and remembers where it was going, so the courier lands there rather than on the start screen.
    const auth = TestBed.inject(AuthService);
    expect(await auth.completeSignIn(callbackFor(harness))).toBe('/profile');
  });

  it('draws the screens once signed in, each with its own title', async () => {
    const auth = TestBed.inject(AuthService);
    await auth.beginSignIn('/');
    await auth.completeSignIn(callbackFor(harness));
    const router = TestBed.inject(Router);

    const home = await (await RouterTestingHarness.create()).navigateByUrl('/');
    expect(home).toBeInstanceOf(HomePage);
    await vi.waitFor(() => expect(document.title).toBe('Today · HorecaOS Courier'));

    await router.navigateByUrl('/profile');
    await vi.waitFor(() => expect(document.title).toBe('Profile · HorecaOS Courier'));
  });

  it('keeps the sign-in screens public -- nobody signing in has a session to show', async () => {
    const harnessRouter = await RouterTestingHarness.create();

    await harnessRouter.navigateByUrl('/auth/error?code=access_denied');
    expect(harnessRouter.routeNativeElement?.textContent).toContain('Sign-in was cancelled.');

    await harnessRouter.navigateByUrl('/auth/signed-out');
    expect(harnessRouter.routeNativeElement?.textContent).toContain('You are signed out');
    expect(harness.navigations).toHaveLength(0);
  });

  it('turns a sign-in that cannot even start into the error screen, not a loop', async () => {
    harness.provider.discoveryIssuer = 'https://auth.evil.test/realms/horecaos';

    const harnessRouter = await RouterTestingHarness.create();
    await harnessRouter.navigateByUrl('/');

    await vi.waitFor(() =>
      expect(TestBed.inject(Router).url).toContain('/auth/error?code=issuer_mismatch'),
    );
    expect(harness.navigations).toHaveLength(0);
  });

  it('sends a path nothing owns to the start screen, which is itself behind the guard', async () => {
    const harnessRouter = await RouterTestingHarness.create();

    await harnessRouter.navigateByUrl('/no/such/screen');

    expect(harness.navigations.at(-1)).toContain('/protocol/openid-connect/auth');
  });

  it('is a lazy chunk per screen: nothing of a screen is in the route table itself', () => {
    const lazy = routes
      .flatMap((route) => [route, ...(route.children ?? [])])
      .filter((r) => r.path !== '**');
    for (const route of lazy.filter((r) => r.children === undefined)) {
      expect(route.loadComponent, route.path).toBeTypeOf('function');
      expect(route.component, route.path).toBeUndefined();
    }
  });
});
