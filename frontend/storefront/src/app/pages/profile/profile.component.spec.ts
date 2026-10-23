import { signal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ProfileComponent } from './profile.component';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { CustomerOtp } from '../../core/session/customer-otp';
import { FavouritesService } from '../../services/favourites.service';
import { AvatarService } from '../../services/avatar.service';
import { type PushState, PushSubscriptionService } from '../../services/push-subscription.service';
import { TelegramLinkService } from '../../services/telegram-link.service';
import { TranslateService } from '../../services/translate.service';
import { type PointsBalance, PointsService } from '../../services/points.service';
import { ThemeService } from '../../services/theme.service';
import { Session } from '../../core/auth/session';
import type { CustomerProfile } from '../../core/api/customer-api';

class FakeCustomerProfileService {
  readonly load = vi.fn(async (): Promise<CustomerProfile | null> => null);
  readonly forget = vi.fn();
}

class FakePointsService {
  readonly balance = vi.fn(async (): Promise<PointsBalance> => ({
    accountId: null,
    brandId: 'brand-1',
    balance: null,
    spendable: null,
    held: null,
    nextExpiryAt: null,
    nextExpiryAmount: null,
  }));
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

/** The opt-in card reads these four members and signing out calls `forget` (ADR 0182, D14). */
class FakePushSubscriptionService {
  readonly state = signal<PushState>('off');
  readonly busy = signal(false);
  readonly refresh = vi.fn(async (): Promise<PushState> => 'off');
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
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}|${Object.values(params).join('|')}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function setUp(points = new FakePointsService()) {
  const profileService = new FakeCustomerProfileService();
  const push = new FakePushSubscriptionService();

  TestBed.configureTestingModule({
    imports: [ProfileComponent],
    providers: [
      provideRouter([]),
      { provide: CustomerProfileService, useValue: profileService },
      { provide: CustomerOtp, useClass: FakeCustomerOtp },
      { provide: FavouritesService, useClass: FakeFavouritesService },
      { provide: AvatarService, useClass: FakeAvatarService },
      { provide: TelegramLinkService, useClass: FakeTelegramLinkService },
      { provide: PushSubscriptionService, useValue: push },
      { provide: PointsService, useValue: points },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ThemeService, useClass: FakeThemeService },
    ],
  });

  const session = TestBed.inject(Session);
  const fixture = TestBed.createComponent(ProfileComponent);

  return { fixture, comp: fixture.componentInstance, profileService, session, points, push };
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

  it('offers no push opt-in to a visitor who is not signed in', async () => {
    const { fixture, push } = setUp();

    await mount(fixture);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('app-push-subscribe'),
    ).not.toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="push-subscribe"]'),
      'the card renders nothing for a guest, however the page mounts it',
    ).toBeNull();
    expect(push.refresh).not.toHaveBeenCalled();
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

  it('mounts the Web Push opt-in for a signed-in customer (ADR 0182, D14)', async () => {
    const { fixture, session, push } = setUp();
    signIn(session);

    await mount(fixture);

    expect(push.refresh).toHaveBeenCalledTimes(1);
    const card = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="push-subscribe"]',
    );
    expect(card).not.toBeNull();
    expect(card?.getAttribute('data-state')).toBe('off');
  });

  it('forgets the push state on sign-out, beside the Telegram link, so the next customer is asked afresh', async () => {
    const { fixture, comp, session, push } = setUp();
    signIn(session);
    await mount(fixture);

    comp.onLogout();

    expect(push.forget).toHaveBeenCalledTimes(1);
  });
});

describe('ProfileComponent: the customer’s own points (ADR 0046, ADR 0185)', () => {
  beforeEach(() => localStorage.clear());

  const balanceOf = (balance: number, spendable: number): PointsBalance => ({
    accountId: 'pa-1',
    brandId: 'brand-1',
    balance: { amountMinor: balance, currency: 'UZS' },
    spendable: { amountMinor: spendable, currency: 'UZS' },
    held: { amountMinor: balance - spendable, currency: 'UZS' },
    nextExpiryAt: null,
    nextExpiryAmount: null,
  });

  const text = (fixture: ComponentFixture<unknown>, id: string) =>
    (
      (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`)?.textContent ??
      ''
    )
      .replace(/\s+/g, ' ')
      .trim();

  it('shows what the customer can spend of their own points', async () => {
    const points = new FakePointsService();
    points.balance.mockResolvedValue(balanceOf(12_000, 12_000));
    const { fixture, session } = setUp(points);
    signIn(session);

    await mount(fixture);
    fixture.detectChanges();

    expect(points.balance).toHaveBeenCalledTimes(1);
    expect(text(fixture, 'profile-points-spendable')).toContain('12 000');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="profile-points-total"]'),
      'nothing is held back, so there is only one figure',
    ).toBeNull();
  });

  it('shows the total as well when some points are not spendable yet', async () => {
    const points = new FakePointsService();
    points.balance.mockResolvedValue(balanceOf(15_000, 12_000));
    const { fixture, session } = setUp(points);
    signIn(session);

    await mount(fixture);
    fixture.detectChanges();

    expect(text(fixture, 'profile-points-spendable')).toContain('12 000');
    expect(text(fixture, 'profile-points-total')).toContain('15 000');
  });

  it('shows nothing for a customer who has earned no points here', async () => {
    const { fixture, session } = setUp();
    signIn(session);

    await mount(fixture);
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="profile-points"]'),
    ).toBeNull();
  });

  it('shows nothing, and does not disturb the screen, when the balance cannot be read', async () => {
    const points = new FakePointsService();
    points.balance.mockRejectedValue(new Error('offline'));
    const { fixture, comp, session } = setUp(points);
    signIn(session);

    await mount(fixture);
    fixture.detectChanges();

    expect(comp.isAuthorized()).toBe(true);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="profile-points"]'),
    ).toBeNull();
  });

  it('asks nothing of a visitor who is not signed in', async () => {
    const { fixture, points } = setUp();

    await mount(fixture);

    expect(points.balance).not.toHaveBeenCalled();
  });
});
