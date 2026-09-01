import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, Subject, firstValueFrom, from } from 'rxjs';

import { ProfileTelegramComponent } from './profile-telegram';
import { TelegramLinkService } from '../../../services/telegram-link.service';
import { OrdersService } from '../../../services/orders.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslateService } from '../../../services/translate.service';
import { Session } from '../../../core/auth/session';
import { HorecaOSApiError } from '../../../core/api/problem-details';
import type { TelegramLinkCode } from '../../../core/api/telegram-link-api';

function mintedCode(suffix = 'abc123'): TelegramLinkCode {
  return { code: suffix, deepLink: `https://t.me/jizbiz_bot?start=${suffix}` };
}

/**
 * Mirrors `TelegramLinkService`'s real behaviour closely enough for the
 * component to be exercised honestly: `refresh()` reports whatever `_linked`
 * currently holds (a test sets it to model "the platform now reports X", the
 * same fact a real `GET /link` would answer), and `mintCode`/`unlink` update
 * state the way the real service's own methods do.
 */
class FakeTelegramLinkService {
  private _linked: boolean | null = null;
  private _pendingCode: TelegramLinkCode | null = null;

  readonly refresh = vi.fn(async () => this._linked ?? false);
  readonly mintCode = vi.fn(async () => {
    const code = mintedCode();
    this._pendingCode = code;
    return code;
  });
  readonly unlink = vi.fn(async () => {
    this._linked = false;
    this._pendingCode = null;
  });
  readonly discardPendingCode = vi.fn(() => {
    this._pendingCode = null;
  });

  linked(): boolean | null {
    return this._linked;
  }

  pendingCode(): TelegramLinkCode | null {
    return this._pendingCode;
  }

  setLinked(value: boolean | null): void {
    this._linked = value;
  }

  setPendingCode(value: TelegramLinkCode | null): void {
    this._pendingCode = value;
  }
}

/** Captures the `source` a component passes to `poll()` so a test can drive ticks by hand. */
class FakeOrdersService {
  private readonly subject = new Subject<boolean>();
  private source: (() => Observable<boolean>) | null = null;

  readonly poll = vi.fn((_intervalMs: number, source: () => Observable<boolean>) => {
    this.source = source;
    return this.subject.asObservable();
  });

  get observed(): boolean {
    return this.subject.observed;
  }

  /** Runs the captured source once and pushes what it resolves to, like one real poll tick. */
  async tick(): Promise<void> {
    if (!this.source) {
      throw new Error('poll() was never called -- nothing to tick');
    }
    const value = await firstValueFrom(this.source());
    this.subject.next(value);
  }
}

class FakeNotificationService {
  show = vi.fn();
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
  const telegramLink = new FakeTelegramLinkService();
  const orders = new FakeOrdersService();
  const notification = new FakeNotificationService();

  TestBed.configureTestingModule({
    imports: [ProfileTelegramComponent],
    providers: [
      provideRouter([]),
      { provide: TelegramLinkService, useValue: telegramLink },
      { provide: OrdersService, useValue: orders },
      { provide: NotificationService, useValue: notification },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const session = TestBed.inject(Session);
  const fixture = TestBed.createComponent(ProfileTelegramComponent);

  return { fixture, comp: fixture.componentInstance, telegramLink, orders, notification, session };
}

function signIn(session: Session): void {
  session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
}

/**
 * `ngOnInit`'s `refresh().catch().finally()` chain is three microtask hops
 * past the mocked promise settling -- the same depth `CartConfirmationComponent`'s
 * own `loadPaymentMethods()` chain is, per that spec's own note. `whenStable()`
 * does not reliably drain a chain that deep; a macrotask flush (which always
 * runs after every pending microtask) is what actually guarantees `loading`
 * has been set to `false` before a test reads it.
 */
async function mount(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('ProfileTelegramComponent: unauthenticated', () => {
  beforeEach(() => localStorage.clear());

  it('shows the sign-in prompt and never calls the platform when there is no live session', async () => {
    const { fixture, comp, telegramLink } = setUp();

    await mount(fixture);

    expect(comp.needsSignIn()).toBe(true);
    expect(comp.loading()).toBe(false);
    expect(telegramLink.refresh).not.toHaveBeenCalled();
  });
});

describe('ProfileTelegramComponent: unlinked rendering', () => {
  beforeEach(() => localStorage.clear());

  it('loads to an unlinked, no-pending-code state for a signed-in customer with no link yet', async () => {
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    telegramLink.setLinked(false);

    await mount(fixture);

    expect(comp.loading()).toBe(false);
    expect(comp.needsSignIn()).toBe(false);
    expect(comp.error()).toBeNull();
    expect(comp.linked()).toBe(false);
    expect(comp.pendingCode()).toBeNull();
  });

  it('treats a guest -- no account at this brand yet -- the same as unlinked, with no error banner', async () => {
    // FavouritesService/CustomerProfileService both fold not-found into an
    // empty/false state rather than an error; TelegramLinkService.refresh does
    // the same, so the component here should show a plain unlinked screen.
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    telegramLink.setLinked(false); // what refresh() would report for a 404

    await mount(fixture);

    expect(comp.error()).toBeNull();
    expect(comp.linked()).toBe(false);
  });
});

describe('ProfileTelegramComponent: mint flow', () => {
  beforeEach(() => localStorage.clear());

  it('mints a code, holds it as pendingCode, and starts polling', async () => {
    const { fixture, comp, session, telegramLink, orders } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);

    await comp.connect();

    expect(telegramLink.mintCode).toHaveBeenCalledTimes(1);
    expect(comp.pendingCode()).toEqual(mintedCode());
    expect(orders.poll).toHaveBeenCalledTimes(1);
    expect(orders.poll.mock.calls[0][0]).toBe(5_000);
  });

  it('a second connect() while a code is already outstanding replaces it and restarts polling', async () => {
    const { fixture, comp, session, telegramLink, orders } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);
    await comp.connect();
    const firstPoll = orders.poll.mock.calls.length;

    // `mockResolvedValueOnce` would bypass the fake's own body -- and with it
    // the side effect (`setPendingCode`) that makes `pendingCode()` observe
    // the new value, the same way the real service's `mintCode` sets `pending`
    // as a side effect of resolving. `mockImplementationOnce` keeps that intact.
    telegramLink.mintCode.mockImplementationOnce(async () => {
      const next = mintedCode('zzz999');
      telegramLink.setPendingCode(next);
      return next;
    });
    await comp.connect();

    expect(comp.pendingCode()).toEqual(mintedCode('zzz999'));
    expect(orders.poll.mock.calls.length).toBeGreaterThan(firstPoll);
  });

  it('does not mint again while a mint is already in flight', async () => {
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);

    let resolveMint!: (code: TelegramLinkCode) => void;
    telegramLink.mintCode.mockReturnValueOnce(
      new Promise<TelegramLinkCode>((resolve) => (resolveMint = resolve)),
    );

    const first = comp.connect();
    const second = comp.connect(); // should be a no-op: minting() is already true
    resolveMint(mintedCode());
    await Promise.all([first, second]);

    expect(telegramLink.mintCode).toHaveBeenCalledTimes(1);
  });
});

describe('ProfileTelegramComponent: poll flips the screen to linked', () => {
  beforeEach(() => localStorage.clear());

  it('a poll tick that reports linked flips the UI, with no manual refresh', async () => {
    const { fixture, comp, session, telegramLink, orders } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);
    await comp.connect();
    expect(comp.linked()).toBe(false);

    telegramLink.setLinked(true); // the customer completed /start in Telegram
    await orders.tick();

    expect(comp.linked()).toBe(true);
  });

  it('stops polling once linked -- the subject the component was listening on has no more subscribers', async () => {
    const { fixture, comp, session, telegramLink, orders } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);
    await comp.connect();
    expect(orders.observed).toBe(true);

    telegramLink.setLinked(true);
    await orders.tick();

    expect(orders.observed).toBe(false);
  });

  it('a still-unlinked tick keeps polling and leaves the pending code alone', async () => {
    const { fixture, comp, session, telegramLink, orders } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);
    await comp.connect();

    await orders.tick();

    expect(comp.linked()).toBe(false);
    expect(comp.pendingCode()).toEqual(mintedCode());
    expect(orders.observed).toBe(true);
  });
});

describe('ProfileTelegramComponent: unlink with confirm', () => {
  beforeEach(() => localStorage.clear());

  async function setUpLinked() {
    const result = setUp();
    signIn(result.session);
    result.telegramLink.setLinked(true);
    await mount(result.fixture);
    return result;
  }

  it('requestUnlink shows the confirm step without calling the platform yet', async () => {
    const { comp, telegramLink } = await setUpLinked();

    comp.requestUnlink();

    expect(comp.confirmingUnlink()).toBe(true);
    expect(telegramLink.unlink).not.toHaveBeenCalled();
  });

  it('cancelUnlink backs out without calling the platform', async () => {
    const { comp, telegramLink } = await setUpLinked();
    comp.requestUnlink();

    comp.cancelUnlink();

    expect(comp.confirmingUnlink()).toBe(false);
    expect(telegramLink.unlink).not.toHaveBeenCalled();
    expect(comp.linked()).toBe(true);
  });

  it('confirmUnlink calls DELETE, closes the confirm step, flips to unlinked, and notifies', async () => {
    const { comp, telegramLink, notification } = await setUpLinked();
    comp.requestUnlink();

    await comp.confirmUnlink();

    expect(telegramLink.unlink).toHaveBeenCalledTimes(1);
    expect(comp.confirmingUnlink()).toBe(false);
    expect(comp.linked()).toBe(false);
    expect(notification.show).toHaveBeenCalledWith('profile.telegramUnlinked');
  });

  it('a failed unlink shows an inline error and leaves the confirm step open, still linked', async () => {
    const { comp, telegramLink } = await setUpLinked();
    comp.requestUnlink();
    const failure = new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'offline' });
    telegramLink.unlink.mockRejectedValueOnce(failure);

    await comp.confirmUnlink();

    expect(comp.unlinkError()).toBe('errors.offline');
    expect(comp.confirmingUnlink()).toBe(true);
    expect(comp.linked()).toBe(true);
  });
});

describe('ProfileTelegramComponent: error paths', () => {
  beforeEach(() => localStorage.clear());

  it('a failed initial load shows a translated, code-mapped inline error', async () => {
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    const failure = new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'offline' });
    telegramLink.refresh.mockRejectedValueOnce(failure);

    await mount(fixture);

    expect(comp.error()).toBe('errors.offline');
    expect(comp.needsSignIn()).toBe(false);
  });

  it('a session that died between mount and load is shown as a sign-in prompt, not a generic error', async () => {
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    const failure = new HorecaOSApiError({ status: 401, code: 'SESSION_EXPIRED', detail: 'gone' });
    telegramLink.refresh.mockRejectedValueOnce(failure);

    await mount(fixture);

    expect(comp.needsSignIn()).toBe(true);
    expect(comp.error()).toBeNull();
  });

  it('a failed mint (network failure) reports inline without ever setting pendingCode', async () => {
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);
    const failure = new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'boom' });
    telegramLink.mintCode.mockRejectedValueOnce(failure);

    await comp.connect();

    expect(comp.mintError()).toBe('errors.generic');
    expect(comp.pendingCode()).toBeNull();
    expect(comp.minting()).toBe(false);
  });

  it('an unauthenticated mint failure flips to the sign-in prompt', async () => {
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);
    const failure = new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'gone' });
    telegramLink.mintCode.mockRejectedValueOnce(failure);

    await comp.connect();

    expect(comp.needsSignIn()).toBe(true);
  });
});

describe('ProfileTelegramComponent.ngOnDestroy', () => {
  beforeEach(() => localStorage.clear());

  it('unsubscribes the poll and discards an unfinished pending code', async () => {
    const { fixture, comp, session, telegramLink, orders } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);
    await comp.connect();
    expect(orders.observed).toBe(true);

    fixture.destroy();

    expect(orders.observed).toBe(false);
    expect(telegramLink.discardPendingCode).toHaveBeenCalledTimes(1);
  });

  it('does not discard the code once linked -- there is nothing left to discard', async () => {
    const { fixture, session, telegramLink } = setUp();
    signIn(session);
    telegramLink.setLinked(true);
    await mount(fixture);

    fixture.destroy();

    expect(telegramLink.discardPendingCode).not.toHaveBeenCalled();
  });
});

describe('ProfileTelegramComponent: idempotency', () => {
  beforeEach(() => localStorage.clear());

  it('mintCode is invoked through the service, which is responsible for the idempotency key -- the component never mints its own', async () => {
    const { fixture, comp, session, telegramLink } = setUp();
    signIn(session);
    telegramLink.setLinked(false);
    await mount(fixture);

    await comp.connect();

    expect(telegramLink.mintCode).toHaveBeenCalledWith();
  });
});

// A minimal `from`/`Observable` sanity check that the fake's own tick() plumbing
// behaves the way the real `OrdersService.poll` -> `subscribe` chain does.
describe('FakeOrdersService.tick (self-check)', () => {
  it('runs the captured source and forwards its value to the subscriber', async () => {
    const orders = new FakeOrdersService();
    const emissions: boolean[] = [];
    orders.poll(1000, () => from(Promise.resolve(true))).subscribe((v) => emissions.push(v));

    await orders.tick();

    expect(emissions).toEqual([true]);
  });
});
