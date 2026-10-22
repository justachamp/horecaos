import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { PUSH_DISMISSED_KEY, PushSubscribeComponent } from './push-subscribe';
import { PushSubscriptionService, type PushState } from '../../services/push-subscription.service';
import { Session } from '../../core/auth/session';
import { NotificationService } from '../../services/notification.service';
import { TranslateService } from '../../services/translate.service';

class FakePushService {
  readonly state = signal<PushState>('unknown');
  readonly busy = signal(false);
  refresh = vi.fn<() => Promise<PushState>>();
  enable = vi.fn<() => Promise<PushState>>();
  disable = vi.fn<() => Promise<PushState>>();
}

class FakeTranslate {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
}

function setUp(
  options: { authenticated?: boolean; state?: PushState; dismissible?: boolean } = {},
) {
  const push = new FakePushService();
  const toast = { show: vi.fn() };
  push.state.set(options.state ?? 'off');
  push.refresh.mockImplementation(async () => push.state());
  TestBed.configureTestingModule({
    providers: [
      { provide: PushSubscriptionService, useValue: push },
      { provide: Session, useValue: { isAuthenticated: signal(options.authenticated ?? true) } },
      { provide: NotificationService, useValue: toast },
      { provide: TranslateService, useValue: new FakeTranslate() },
    ],
  });
  const fixture = TestBed.createComponent(PushSubscribeComponent);
  fixture.componentRef.setInput('dismissible', options.dismissible ?? false);
  fixture.detectChanges();
  return { fixture, push, toast, host: fixture.nativeElement as HTMLElement };
}

const part = (host: HTMLElement, testId: string): HTMLElement | null =>
  host.querySelector(`[data-testid="${testId}"]`);

describe('PushSubscribeComponent', () => {
  beforeEach(() => {
    // The storefront builders share one jsdom per worker: the dismissal this component remembers must
    // not leak into the next spec, and nothing a previous spec stored may decide this one.
    sessionStorage.clear();
    localStorage.clear();
  });

  afterEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it('renders nothing for a guest, and does not ask the platform anything', () => {
    const { host, push } = setUp({ authenticated: false });

    expect(part(host, 'push-subscribe')).toBeNull();
    expect(push.refresh).not.toHaveBeenCalled();
  });

  it.each<PushState>(['unknown', 'unsupported', 'unavailable'])(
    'renders nothing when push cannot be offered (%s)',
    (state) => {
      const { host } = setUp({ state });

      expect(part(host, 'push-subscribe')).toBeNull();
    },
  );

  it('offers to turn push on, and asks nothing of the browser until the customer taps', async () => {
    const { host, push } = setUp({ state: 'off' });

    expect(part(host, 'push-turn-on')).not.toBeNull();
    expect(host.textContent).toContain('push.consentNote');
    await vi.waitFor(() => expect(push.refresh).toHaveBeenCalled());
    expect(push.enable).not.toHaveBeenCalled();
  });

  it('turns push on from the tap and says so', async () => {
    const { host, push, toast, fixture } = setUp({ state: 'off' });
    push.enable.mockImplementation(async () => {
      push.state.set('on');
      return 'on';
    });

    (part(host, 'push-turn-on') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(toast.show).toHaveBeenCalledWith('push.enabled'));
    fixture.detectChanges();

    expect(push.enable).toHaveBeenCalledTimes(1);
    expect(part(host, 'push-turn-off')).not.toBeNull();
    expect(part(host, 'push-turn-on')).toBeNull();
  });

  it('says nothing was turned on when the customer refused the browser’s own question', async () => {
    const { host, push, toast, fixture } = setUp({ state: 'off' });
    push.enable.mockImplementation(async () => {
      push.state.set('blocked');
      return 'blocked';
    });

    (part(host, 'push-turn-on') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(push.enable).toHaveBeenCalled());
    fixture.detectChanges();

    expect(toast.show).not.toHaveBeenCalled();
    expect(part(host, 'push-blocked')).not.toBeNull();
    expect(part(host, 'push-turn-on')).toBeNull();
  });

  it('shows why when the platform refuses the subscription', async () => {
    const { host, push, fixture } = setUp({ state: 'off' });
    push.enable.mockRejectedValue(new Error('refused'));

    (part(host, 'push-turn-on') as HTMLButtonElement).click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(part(host, 'push-error')).not.toBeNull();
    });
    expect(part(host, 'push-error')?.textContent).toContain('errors.generic');
  });

  it('turns push off with one tap', async () => {
    const { host, push, toast, fixture } = setUp({ state: 'on' });
    push.disable.mockImplementation(async () => {
      push.state.set('off');
      return 'off';
    });

    (part(host, 'push-turn-off') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(toast.show).toHaveBeenCalledWith('push.disabled'));
    fixture.detectChanges();

    expect(part(host, 'push-turn-on')).not.toBeNull();
  });

  it('cannot be dismissed where it is the only place to change the answer', () => {
    const { host } = setUp({ state: 'off', dismissible: false });

    expect(part(host, 'push-not-now')).toBeNull();
  });

  it('remembers «not now» for thirty days where it is only an invitation', () => {
    const first = setUp({ state: 'off', dismissible: true });
    (part(first.host, 'push-not-now') as HTMLButtonElement).click();
    first.fixture.detectChanges();
    expect(part(first.host, 'push-subscribe')).toBeNull();
    expect(Number(localStorage.getItem(PUSH_DISMISSED_KEY))).toBeGreaterThan(0);
    first.fixture.destroy();
    TestBed.resetTestingModule();

    const again = setUp({ state: 'off', dismissible: true });
    expect(part(again.host, 'push-subscribe')).toBeNull();
    again.fixture.destroy();
    TestBed.resetTestingModule();

    localStorage.setItem(PUSH_DISMISSED_KEY, String(Date.now() - 31 * 86_400_000));
    const later = setUp({ state: 'off', dismissible: true });
    expect(part(later.host, 'push-subscribe')).not.toBeNull();
  });

  it('keeps showing a customer who turned push on, even if they once said «not now»', () => {
    localStorage.setItem(PUSH_DISMISSED_KEY, String(Date.now()));

    const { host } = setUp({ state: 'on', dismissible: true });

    expect(part(host, 'push-turn-off')).not.toBeNull();
  });
});
