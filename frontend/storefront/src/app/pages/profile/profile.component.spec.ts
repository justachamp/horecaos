import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ProfileComponent } from './profile.component';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { CustomerOtp } from '../../core/session/customer-otp';
import { FavouritesService } from '../../services/favourites.service';
import { AvatarService } from '../../services/avatar.service';
import { TelegramLinkService } from '../../services/telegram-link.service';
import { TranslateService } from '../../services/translate.service';
import { ThemeService } from '../../services/theme.service';
import { Session } from '../../core/auth/session';
import type { CustomerProfile } from '../../core/api/customer-api';

class FakeCustomerProfileService {
  readonly load = vi.fn(async (): Promise<CustomerProfile | null> => null);
}

class FakeCustomerOtp {
  readonly signOut = vi.fn(async () => {});
}

class FakeFavouritesService {
  readonly forget = vi.fn();
}

class FakeAvatarService {
  readonly forget = vi.fn();
}

class FakeTelegramLinkService {
  readonly forget = vi.fn();
}

/**
 * `ThemeService`'s real constructor reads `window.matchMedia`, which this
 * suite's environment does not implement -- a gap orthogonal to what this
 * spec is about (ProfileMenuComponent only reads `theme.mode()` to highlight
 * the current choice). Faked out rather than polyfilling matchMedia here.
 */
class FakeThemeService {
  readonly mode = () => 'light' as const;
  readonly setMode = vi.fn();
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function setUp() {
  const profileService = new FakeCustomerProfileService();

  TestBed.configureTestingModule({
    imports: [ProfileComponent],
    providers: [
      provideRouter([]),
      { provide: CustomerProfileService, useValue: profileService },
      { provide: CustomerOtp, useClass: FakeCustomerOtp },
      { provide: FavouritesService, useClass: FakeFavouritesService },
      { provide: AvatarService, useClass: FakeAvatarService },
      { provide: TelegramLinkService, useClass: FakeTelegramLinkService },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ThemeService, useClass: FakeThemeService },
    ],
  });

  const session = TestBed.inject(Session);
  const fixture = TestBed.createComponent(ProfileComponent);

  return { fixture, comp: fixture.componentInstance, profileService, session };
}

function signIn(session: Session): void {
  session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
}

async function mount(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('ProfileComponent: anonymous visitor', () => {
  beforeEach(() => localStorage.clear());

  it('renders a signed-out state instead of assuming a session', async () => {
    const { fixture, comp, profileService } = setUp();

    await mount(fixture);

    expect(comp.isAuthorized()).toBe(false);
    expect(profileService.load).not.toHaveBeenCalled();
  });

  it('shows the "Sign in" affordance in place of account data', async () => {
    const { fixture } = setUp();

    await mount(fixture);

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('/auth/login');
    expect(html).toContain('auth.login');
  });
});

describe('ProfileComponent: signed in', () => {
  beforeEach(() => localStorage.clear());

  it('reads the account and renders as authorised, unchanged from before', async () => {
    const { fixture, comp, profileService, session } = setUp();
    signIn(session);

    await mount(fixture);

    expect(comp.isAuthorized()).toBe(true);
    expect(profileService.load).toHaveBeenCalledTimes(1);
  });
});
