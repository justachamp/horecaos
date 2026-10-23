import { TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ProfileComponent } from './profile.component';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { CustomerOtp } from '../../core/session/customer-otp';
import { LangService } from '../../services/lang.service';
import { OrdersService, type ApiOrder } from '../../services/orders.service';
import {
  ReviewsService,
  type FeedbackConfig,
  type OfferedTag,
  type PendingReview,
  type PublicDisplay,
  type ReviewResponse,
} from '../../services/reviews.service';
import { type PushState, PushSubscriptionService } from '../../services/push-subscription.service';
import { TranslateService } from '../../services/translate.service';
import { type PointsBalance, PointsService } from '../../services/points.service';
import { HorecaOSApiError } from '../../core/api/problem-details';
import { Session } from '../../core/auth/session';
import type { CustomerProfile } from '../../core/api/customer-api';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
  setLang = vi.fn();
}

@Component({ selector: 'app-test-target', template: '' })
class TestTargetComponent {}

function profile(displayName: string | null): CustomerProfile {
  return {
    accountId: 'acc-1',
    brandId: 'brand-1',
    status: 'ACTIVE',
    identityMode: 'BRAND',
    profileScope: 'BRAND',
    identityPolicyVersion: 1,
    displayName,
    preferredLocale: 'uz',
    preferredTimezone: 'Asia/Tashkent',
    contactPoints: [],
    version: 1,
    createdAt: new Date().toISOString(),
  };
}

function completedOrder(id: string): ApiOrder {
  return {
    id: id as unknown as number,
    status: { id: 'COMPLETED', name: 'COMPLETED' },
    total: 25_000,
    total_price: 25_000,
    order_number: 'PN-1' as unknown as number,
    number: 1,
    created_date: new Date().toISOString(),
    created_time: new Date().toISOString(),
    actions: [],
  };
}

class FakeCustomerProfileService {
  private readonly current = signal<CustomerProfile | null>(profile('Jasur Rahimov'));
  profile = () => this.current();
  firstName = () => this.current()?.displayName?.split(' ')[0] ?? '';
  lastName = () => this.current()?.displayName?.split(' ').slice(1).join(' ') ?? '';
  load = vi.fn(async () => this.current());
  update = vi.fn(async () => this.current()!);

  setProfile(value: CustomerProfile | null): void {
    this.current.set(value);
  }
}

class FakePointsService {
  balance = vi.fn(async (): Promise<PointsBalance> => ({
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
  signOut = vi.fn(async () => {});
}

/** The opt-in card reads these four members and signing out calls `forget` (ADR 0182, D14). */
class FakePushSubscriptionService {
  state = signal<PushState>('off');
  busy = signal(false);
  refresh = vi.fn(async (): Promise<PushState> => 'off');
  forget = vi.fn();
}

class FakeOrdersService {
  getOrders = vi.fn(() => of<ApiOrder[]>([]));
}

const DISABLED: FeedbackConfig = {
  enabled: false,
  maxTagsPerReview: 5,
  publicComments: false,
  consentTextKey: null,
  positive: [],
  negative: [],
};

class FakeReviewsService {
  feedbackConfig = vi.fn(async (): Promise<FeedbackConfig> => DISABLED);
  pending = vi.fn(async (): Promise<readonly PendingReview[]> => []);
  withdrawPublicDisplay = vi.fn(async (): Promise<PublicDisplay> => ({
    state: 'HIDDEN',
    reasonCode: 'CUSTOMER_WITHDREW',
    withdrawable: false,
  }));
  myReviews = vi.fn(async () => ({ items: [] as ReviewResponse[], nextCursor: null }));
  submit = vi.fn(async (): Promise<ReviewResponse> => ({
    id: 'r1',
    orderId: 'o1',
    rating: 5,
    comment: null,
    submittedAt: new Date().toISOString(),
  }));
}

async function setUp(
  configure: (
    profileService: FakeCustomerProfileService,
    orders: FakeOrdersService,
    reviews: FakeReviewsService,
  ) => void = () => {},
  configurePoints: (points: FakePointsService) => void = () => {},
  signedIn = false,
) {
  const profileService = new FakeCustomerProfileService();
  const orders = new FakeOrdersService();
  const reviews = new FakeReviewsService();
  const customerOtp = new FakeCustomerOtp();
  const points = new FakePointsService();
  const push = new FakePushSubscriptionService();
  configure(profileService, orders, reviews);
  configurePoints(points);

  TestBed.configureTestingModule({
    imports: [ProfileComponent],
    providers: [
      provideRouter([
        { path: 'home', component: TestTargetComponent },
        { path: 'referral', component: TestTargetComponent },
      ]),
      { provide: CustomerProfileService, useValue: profileService },
      { provide: CustomerOtp, useValue: customerOtp },
      {
        provide: LangService,
        useValue: {
          langId: signal('uz'),
          // What the language service offers a brand that serves Russian and Uzbek (ADR 0149).
          offered: signal([
            { id: 'ru', tag: 'ru', label: 'Русский' },
            { id: 'uz', tag: 'uz-Latn', label: 'Oʻzbekcha' },
          ]),
        },
      },
      { provide: OrdersService, useValue: orders },
      { provide: ReviewsService, useValue: reviews },
      { provide: PointsService, useValue: points },
      { provide: PushSubscriptionService, useValue: push },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  if (signedIn) {
    TestBed.inject(Session).adopt({
      accessToken: 'tok',
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    });
  }

  const fixture = TestBed.createComponent(ProfileComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return {
    fixture,
    profileService,
    orders,
    reviews,
    customerOtp,
    points,
    push,
    translate: TestBed.inject(TranslateService) as unknown as FakeTranslateService,
    router: TestBed.inject(Router),
  };
}

describe('ProfileComponent -- denied/empty states', () => {
  it('shows a guest fallback name when there is no display name', async () => {
    const { fixture } = await setUp((profileService) => profileService.setProfile(profile(null)));

    expect(fixture.nativeElement.textContent).toContain('profile.guestName');
  });

  it('never shows a rating card when there is nothing eligible to rate', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('.rate-card')).toBeNull();
  });

  it('never shows a rating card for a completed order that already has a review', async () => {
    const { fixture } = await setUp((_p, orders, reviews) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
      reviews.myReviews.mockResolvedValue({
        items: [
          {
            id: 'r1',
            orderId: 'o1',
            rating: 4,
            comment: null,
            submittedAt: new Date().toISOString(),
          },
        ],
        nextCursor: null,
      });
    });

    expect(fixture.nativeElement.querySelector('.rate-card')).toBeNull();
  });

  it('surfaces a profile load failure as a translated message', async () => {
    const { fixture } = await setUp((profileService) => {
      profileService.load.mockRejectedValue(new Error('offline'));
    });

    expect(fixture.nativeElement.textContent).toContain('profile.loadError');
  });
});

describe('ProfileComponent -- the Web Push opt-in (ADR 0182, D14)', () => {
  // A signed-in case persists its session in the browser's storage; the guest case must start without one.
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('mounts the opt-in card for a signed-in customer and asks the platform where they stand', async () => {
    const { fixture, push } = await setUp(undefined, undefined, true);

    expect(push.refresh).toHaveBeenCalledTimes(1);
    const card = fixture.nativeElement.querySelector('[data-testid="push-subscribe"]');
    expect(card).not.toBeNull();
    expect(card.getAttribute('data-state')).toBe('off');
  });

  it('offers no opt-in to a visitor who is not signed in', async () => {
    const { fixture, push } = await setUp();

    expect(fixture.nativeElement.querySelector('[data-testid="push-subscribe"]')).toBeNull();
    expect(push.refresh).not.toHaveBeenCalled();
  });

  it('forgets the push state on sign-out, so the next customer on the handset is asked afresh', async () => {
    const { fixture, push } = await setUp(undefined, undefined, true);

    (fixture.nativeElement.querySelector('.signout') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(push.forget).toHaveBeenCalledTimes(1);
  });
});

describe('ProfileComponent -- sign-out and language', () => {
  it('signs out through the platform, then leaves for /home', async () => {
    const { fixture, customerOtp, router } = await setUp();
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.signout') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(customerOtp.signOut).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/home']);
  });

  it('switching language calls through with exactly the code that was tapped', async () => {
    const { fixture, translate } = await setUp();

    const chips = fixture.nativeElement.querySelectorAll('.lang-chip');
    const ru = Array.from(chips as NodeListOf<HTMLButtonElement>).find((el) =>
      el.textContent?.includes('RU'),
    )!;
    ru.click();

    expect(translate.setLang).toHaveBeenCalledWith('ru');
  });

  it('offers exactly the languages the language service offers, and none it does not', async () => {
    const { fixture } = await setUp();

    const chips = Array.from(
      fixture.nativeElement.querySelectorAll('.lang-chip') as NodeListOf<HTMLButtonElement>,
    );
    expect(chips.map((chip) => chip.textContent?.trim())).toEqual(['RU', 'UZ']);
    expect(chips.map((chip) => chip.title)).toEqual(['Русский', 'Oʻzbekcha']);
  });
});

describe('ProfileComponent -- referral (ADR 0067)', () => {
  it('the referral row leads to a real screen, not a promise with nothing behind it', async () => {
    const { fixture, router } = await setUp();
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.nav-row') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(navigateSpy).toHaveBeenCalledWith(['/referral']);
  });
});

describe('ProfileComponent -- order rating (ADR 0071)', () => {
  it('does not submit while no star has been picked', async () => {
    const { fixture, reviews } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });

    const submit = fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    submit.click();

    expect(reviews.submit).not.toHaveBeenCalled();
  });

  it('sends exactly the picked rating and the typed comment for the eligible order', async () => {
    const { fixture, reviews } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });

    const stars = fixture.nativeElement.querySelectorAll('.star-btn');
    (stars[3] as HTMLButtonElement).click(); // the 4th star: a rating of 4
    fixture.detectChanges();
    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = 'Juda mazali!';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(reviews.submit).toHaveBeenCalledWith('o1', 4, 'Juda mazali!');
  });

  it('shows the thank-you state and never a raw code after a successful submission', async () => {
    const { fixture } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });

    (fixture.nativeElement.querySelectorAll('.star-btn')[4] as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('profile.rateThanks');
    expect(fixture.nativeElement.querySelector('.rate-card')).toBeNull();
  });

  it('a refusal (already reviewed, or not yet completed) surfaces a translated message, never the raw ADR 0031 code', async () => {
    const { fixture, reviews } = await setUp((_p, orders) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
    });
    reviews.submit.mockRejectedValue(
      new HorecaOSApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'already reviewed' }),
    );

    (fixture.nativeElement.querySelectorAll('.star-btn')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('errors.generic');
    expect(fixture.nativeElement.textContent).not.toContain('RESOURCE_CONFLICT');
  });
});

function tag(
  id: string,
  uz: string,
  sentiment: 'POSITIVE' | 'NEGATIVE',
): OfferedTag & {
  readonly sentiment: string;
} {
  return {
    id,
    code: id,
    category: 'FOOD',
    labels: { 'uz-Latn': uz, en: id },
    iconUrl: null,
    sentiment,
  };
}

function enabled(overrides: Partial<FeedbackConfig> = {}): FeedbackConfig {
  return {
    enabled: true,
    maxTagsPerReview: 2,
    publicComments: true,
    consentTextKey: 'reviews.publicDisplay.consent',
    positive: [tag('tasty', 'Mazali', 'POSITIVE'), tag('fast', 'Tez', 'POSITIVE')],
    negative: [
      tag('cold', 'Sovigan', 'NEGATIVE'),
      tag('late', 'Kech', 'NEGATIVE'),
      tag('rude', 'Qo‘pol', 'NEGATIVE'),
    ],
    ...overrides,
  };
}

const PENDING: PendingReview = {
  orderId: 'o9',
  publicOrderNumber: 'A-17',
  locationId: 'l1',
  placedAt: '2026-10-08T10:00:00Z',
};

function review(overrides: Partial<ReviewResponse> = {}): ReviewResponse {
  return {
    id: 'r1',
    orderId: 'o1',
    rating: 1,
    comment: 'Cold soup',
    submittedAt: '2026-10-08T10:00:00Z',
    tags: [],
    publicDisplay: { state: 'NOT_SHARED', reasonCode: null, withdrawable: false },
    ...overrides,
  };
}

async function settle(fixture: { detectChanges: () => void; whenStable: () => Promise<unknown> }) {
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function chips(host: HTMLElement): string[] {
  return Array.from(host.querySelectorAll('[data-testid="tag-chip"]')).map(
    (chip) => chip.textContent?.trim() ?? '',
  );
}

function pickStar(host: HTMLElement, value: number): void {
  (host.querySelectorAll('.star-btn')[value - 1] as HTMLButtonElement).click();
}

function type(host: HTMLElement, text: string): void {
  const textarea = host.querySelector('textarea') as HTMLTextAreaElement;
  textarea.value = text;
  textarea.dispatchEvent(new Event('input'));
}

describe('ProfileComponent -- the prompt, tags and the public-display box (ADR 0159)', () => {
  it('lets the platform name the order to rate when feedback settings are on, and reads no order list', async () => {
    const { fixture, orders, reviews } = await setUp((_p, _o, r) => {
      r.feedbackConfig.mockResolvedValue(enabled());
      r.pending.mockResolvedValue([PENDING]);
    });

    expect(reviews.pending).toHaveBeenCalled();
    expect(orders.getOrders).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.rate-card')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('"order":"A-17"');
  });

  it('shows no prompt when the platform names nothing pending, even if the order list has a completed order', async () => {
    const { fixture } = await setUp((_p, orders, r) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
      r.feedbackConfig.mockResolvedValue(enabled());
      r.pending.mockResolvedValue([]);
    });

    expect(fixture.nativeElement.querySelector('.rate-card')).toBeNull();
  });

  it('falls back to the order list when the config cannot be read, so a missing config never hides the prompt', async () => {
    const { fixture, reviews } = await setUp((_p, orders, r) => {
      orders.getOrders.mockReturnValue(of([completedOrder('o1')]));
      r.feedbackConfig.mockRejectedValue(new Error('offline'));
    });

    expect(reviews.pending).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.rate-card')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="tag-chip"]')).toBeNull();
  });

  describe('tags follow the rating', () => {
    async function withTags() {
      return setUp((_p, _o, r) => {
        r.feedbackConfig.mockResolvedValue(enabled());
        r.pending.mockResolvedValue([PENDING]);
      });
    }

    it('offers none before a star is picked', async () => {
      const { fixture } = await withTags();

      expect(chips(fixture.nativeElement)).toEqual([]);
    });

    it('offers complaints for one and two stars, praise for four and five, and both for three', async () => {
      const { fixture } = await withTags();
      const host = fixture.nativeElement as HTMLElement;

      pickStar(host, 1);
      fixture.detectChanges();
      expect(chips(host)).toEqual(['Sovigan', 'Kech', 'Qo‘pol']);

      pickStar(host, 5);
      fixture.detectChanges();
      expect(chips(host)).toEqual(['Mazali', 'Tez']);

      pickStar(host, 3);
      fixture.detectChanges();
      expect(chips(host)).toEqual(['Sovigan', 'Kech', 'Qo‘pol', 'Mazali', 'Tez']);
    });

    it('sends the tags tapped, at most the platform’s limit, and drops a tag the new rating no longer offers', async () => {
      const { fixture, reviews } = await withTags();
      const host = fixture.nativeElement as HTMLElement;
      pickStar(host, 1);
      fixture.detectChanges();
      const buttons = () =>
        host.querySelectorAll('[data-testid="tag-chip"]') as NodeListOf<HTMLButtonElement>;

      buttons()[0].click();
      buttons()[1].click();
      buttons()[2].click(); // a third: over the limit of two
      fixture.detectChanges();
      expect(host.querySelectorAll('.tag-chip.is-selected')).toHaveLength(2);

      pickStar(host, 5); // praise now: both complaints no longer fit
      fixture.detectChanges();
      expect(host.querySelectorAll('.tag-chip.is-selected')).toHaveLength(0);

      pickStar(host, 1);
      fixture.detectChanges();
      buttons()[2].click();
      fixture.detectChanges();
      (host.querySelector('.btn--block') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(reviews.submit).toHaveBeenCalledWith('o9', 1, '', {
        tagIds: ['rude'],
        publicConsent: false,
      });
    });

    it('sends the old three-argument request when nothing was tapped and nothing ticked', async () => {
      const { fixture, reviews } = await withTags();
      const host = fixture.nativeElement as HTMLElement;
      pickStar(host, 4);
      fixture.detectChanges();

      (host.querySelector('.btn--block') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(reviews.submit).toHaveBeenCalledWith('o9', 4, '');
    });
  });

  describe('the box that lets the words appear on the restaurant’s page', () => {
    async function withConsent(config: FeedbackConfig = enabled()) {
      return setUp((_p, _o, r) => {
        r.feedbackConfig.mockResolvedValue(config);
        r.pending.mockResolvedValue([PENDING]);
      });
    }

    it('is not shown until there are words to show', async () => {
      const { fixture } = await withConsent();
      const host = fixture.nativeElement as HTMLElement;
      pickStar(host, 1);
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="public-consent"]')).toBeNull();

      type(host, 'Cold soup');
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="public-consent"]')).not.toBeNull();

      type(host, '   ');
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="public-consent"]')).toBeNull();
    });

    it('is never shown when the restaurant does not show comments publicly', async () => {
      const { fixture } = await withConsent(enabled({ publicComments: false }));
      const host = fixture.nativeElement as HTMLElement;
      pickStar(host, 1);
      type(host, 'Cold soup');
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="public-consent"]')).toBeNull();
    });

    it('starts unticked, and the comment is sent with the consent only when the customer ticks it', async () => {
      const { fixture, reviews } = await withConsent();
      const host = fixture.nativeElement as HTMLElement;
      pickStar(host, 4);
      type(host, 'Very good');
      fixture.detectChanges();
      const box = host.querySelector('[data-testid="public-consent"] input') as HTMLInputElement;
      expect(box.checked).toBe(false);

      box.click();
      fixture.detectChanges();
      (host.querySelector('.btn--block') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(reviews.submit).toHaveBeenCalledWith('o9', 4, 'Very good', {
        tagIds: [],
        publicConsent: true,
      });
    });

    it('never sends the consent of a tick whose comment was cleared afterwards', async () => {
      const { fixture, reviews } = await withConsent();
      const host = fixture.nativeElement as HTMLElement;
      pickStar(host, 4);
      type(host, 'Very good');
      fixture.detectChanges();
      (host.querySelector('[data-testid="public-consent"] input') as HTMLInputElement).click();
      type(host, '');
      fixture.detectChanges();

      (host.querySelector('.btn--block') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(reviews.submit).toHaveBeenCalledWith('o9', 4, '');
    });

    it('says what happens next: a comment that waits for approval, or one that is already shown', async () => {
      for (const [state, expected] of [
        ['PENDING', 'profile.rateThanksPending'],
        ['PUBLISHED', 'profile.rateThanksPublished'],
        ['NOT_SHARED', 'profile.rateThanks'],
      ] as const) {
        TestBed.resetTestingModule();
        const { fixture, reviews } = await setUp((_p, _o, r) => {
          r.feedbackConfig.mockResolvedValue(enabled());
          r.pending.mockResolvedValue([PENDING]);
        });
        reviews.submit.mockResolvedValue(
          review({
            publicDisplay: { state, reasonCode: null, withdrawable: state !== 'NOT_SHARED' },
          }),
        );
        pickStar(fixture.nativeElement, 5);
        fixture.detectChanges();
        (fixture.nativeElement.querySelector('.btn--block') as HTMLButtonElement).click();
        await settle(fixture);

        expect(fixture.nativeElement.querySelector('.thanks')?.textContent).toContain(expected);
      }
    });
  });

  describe('the customer’s own reviews', () => {
    it('shows where a comment stands and why it is off the page, in the customer’s words', async () => {
      const { fixture } = await setUp((_p, _o, r) => {
        r.myReviews.mockResolvedValue({
          items: [
            review({
              id: 'a',
              publicDisplay: { state: 'PENDING', reasonCode: null, withdrawable: true },
            }),
            review({
              id: 'b',
              publicDisplay: {
                state: 'HIDDEN',
                reasonCode: 'NAMES_AN_INDIVIDUAL',
                withdrawable: true,
              },
            }),
            review({
              id: 'c',
              publicDisplay: {
                state: 'HIDDEN',
                reasonCode: 'CUSTOMER_WITHDREW',
                withdrawable: false,
              },
            }),
            review({ id: 'd' }),
          ],
          nextCursor: null,
        });
      });
      const states = Array.from(
        fixture.nativeElement.querySelectorAll('[data-testid="my-review-state"]'),
      ).map((node) => (node as HTMLElement).textContent?.replace(/\s+/g, ' ').trim());

      expect(states).toEqual([
        'profile.publicState.PENDING',
        'profile.publicState.HIDDEN · profile.hiddenReason.NAMES_AN_INDIVIDUAL',
        'profile.publicState.HIDDEN · profile.hiddenReason.CUSTOMER_WITHDREW',
      ]);
      expect(fixture.nativeElement.querySelectorAll('[data-testid="my-review"]')).toHaveLength(4);
    });

    it('shows the tags a review carries in the customer’s language', async () => {
      const { fixture } = await setUp((_p, _o, r) => {
        r.myReviews.mockResolvedValue({
          items: [
            review({
              tags: [
                {
                  id: 't',
                  code: 'food_cold',
                  category: 'FOOD',
                  sentiment: 'NEGATIVE',
                  labels: { 'uz-Latn': 'Taom sovigan', ru: 'Остыла', en: 'Cold' },
                },
              ],
            }),
          ],
          nextCursor: null,
        });
      });

      expect(fixture.nativeElement.querySelector('.tag-chip--static')?.textContent).toContain(
        'Taom sovigan',
      );
    });

    it('offers to take a comment off the page only where that is possible', async () => {
      const { fixture } = await setUp((_p, _o, r) => {
        r.myReviews.mockResolvedValue({
          items: [
            review({
              id: 'a',
              publicDisplay: { state: 'PUBLISHED', reasonCode: null, withdrawable: true },
            }),
            review({ id: 'b' }),
            review({
              id: 'c',
              publicDisplay: {
                state: 'HIDDEN',
                reasonCode: 'CUSTOMER_WITHDREW',
                withdrawable: false,
              },
            }),
          ],
          nextCursor: null,
        });
      });

      expect(fixture.nativeElement.querySelectorAll('[data-testid="withdraw"]')).toHaveLength(1);
    });

    it('withdraws the one review tapped and shows it as off the page afterwards, without a reload', async () => {
      const { fixture, reviews } = await setUp((_p, _o, r) => {
        r.myReviews.mockResolvedValue({
          items: [
            review({
              id: 'a',
              publicDisplay: { state: 'PUBLISHED', reasonCode: null, withdrawable: true },
            }),
          ],
          nextCursor: null,
        });
      });

      (
        fixture.nativeElement.querySelector('[data-testid="withdraw"]') as HTMLButtonElement
      ).click();
      await settle(fixture);

      expect(reviews.withdrawPublicDisplay).toHaveBeenCalledWith('a');
      expect(fixture.nativeElement.querySelector('[data-testid="withdraw"]')).toBeNull();
      expect(
        fixture.nativeElement.querySelector('[data-testid="my-review-state"]').textContent,
      ).toContain('profile.hiddenReason.CUSTOMER_WITHDREW');
    });

    it('shows a refused withdrawal as a translated message, never the raw code', async () => {
      const { fixture, reviews } = await setUp((_p, _o, r) => {
        r.myReviews.mockResolvedValue({
          items: [
            review({
              id: 'a',
              publicDisplay: { state: 'PUBLISHED', reasonCode: null, withdrawable: true },
            }),
          ],
          nextCursor: null,
        });
      });
      reviews.withdrawPublicDisplay.mockRejectedValue(
        new HorecaOSApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'not shared' }),
      );

      (
        fixture.nativeElement.querySelector('[data-testid="withdraw"]') as HTMLButtonElement
      ).click();
      await settle(fixture);

      expect(fixture.nativeElement.textContent).not.toContain('UNPROCESSABLE_STATE');
      expect(fixture.nativeElement.querySelector('.hint--error')).not.toBeNull();
    });

    it('shows no section at all when the customer has no review', async () => {
      const { fixture } = await setUp();

      expect(fixture.nativeElement.querySelector('[data-testid="my-reviews"]')).toBeNull();
    });
  });
});

describe('ProfileComponent -- the customer’s own points (ADR 0046, ADR 0185)', () => {
  const balanceOf = (balance: number, spendable: number): PointsBalance => ({
    accountId: 'pa-1',
    brandId: 'brand-1',
    balance: { amountMinor: balance, currency: 'UZS' },
    spendable: { amountMinor: spendable, currency: 'UZS' },
    held: { amountMinor: balance - spendable, currency: 'UZS' },
    nextExpiryAt: null,
    nextExpiryAmount: null,
  });

  const textOf = (fixture: { nativeElement: unknown }, id: string) =>
    (
      (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`)?.textContent ??
      ''
    ).replace(/\s+/g, ' ');

  it('shows what the customer can spend of their own points', async () => {
    const { fixture, points } = await setUp(undefined, (p) =>
      p.balance.mockResolvedValue(balanceOf(12_000, 12_000)),
    );

    expect(points.balance).toHaveBeenCalledTimes(1);
    expect(textOf(fixture, 'profile-points-spendable')).toContain('12 000');
    expect(
      fixture.nativeElement.querySelector('[data-testid="profile-points-total"]'),
      'nothing is held back, so there is only one figure',
    ).toBeNull();
  });

  it('shows the total as well when some points are not spendable yet', async () => {
    const { fixture } = await setUp(undefined, (p) =>
      p.balance.mockResolvedValue(balanceOf(15_000, 12_000)),
    );

    expect(textOf(fixture, 'profile-points-spendable')).toContain('12 000');
    expect(textOf(fixture, 'profile-points-total')).toContain('15 000');
  });

  it('shows nothing for a customer who has earned no points here', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('[data-testid="profile-points"]')).toBeNull();
  });

  it('shows nothing, and does not disturb the screen, when the balance cannot be read', async () => {
    const { fixture } = await setUp(undefined, (p) =>
      p.balance.mockRejectedValue(new Error('offline')),
    );

    expect(fixture.nativeElement.querySelector('[data-testid="profile-points"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Jasur Rahimov');
  });
});
