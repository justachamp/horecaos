import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { HorecaOSApiError } from '../../core/api/problem-details';
import { LangService } from '../../services/lang.service';
import {
  type FeedbackConfig,
  type OfferedTag,
  type PendingReview,
  type ReviewResponse,
  ReviewsService,
} from '../../services/reviews.service';
import { TranslateService } from '../../services/translate.service';
import { RateOrderComponent } from './rate-order.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
}

function tag(id: string, uz: string): OfferedTag {
  return { id, code: id, category: 'FOOD', labels: { 'uz-Latn': uz, en: id }, iconUrl: null };
}

function config(overrides: Partial<FeedbackConfig> = {}): FeedbackConfig {
  return {
    enabled: true,
    maxTagsPerReview: 2,
    publicComments: true,
    consentTextKey: 'reviews.publicDisplay.consent',
    positive: [tag('tasty', 'Mazali'), tag('fast', 'Tez')],
    negative: [tag('cold', 'Sovigan'), tag('late', 'Kech'), tag('rude', 'Qo‘pol')],
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
    orderId: 'o9',
    rating: 1,
    comment: 'Cold soup',
    submittedAt: '2026-10-08T10:00:00Z',
    tags: [],
    publicDisplay: { state: 'NOT_SHARED', reasonCode: null, withdrawable: false },
    ...overrides,
  };
}

interface Options {
  readonly config?: FeedbackConfig | Error;
  readonly pending?: readonly PendingReview[];
  readonly mine?: readonly ReviewResponse[];
}

async function setUp(options: Options = {}) {
  const reviews = {
    feedbackConfig: vi.fn(async () => {
      if (options.config instanceof Error) {
        throw options.config;
      }
      return options.config ?? config();
    }),
    pending: vi.fn(async () => options.pending ?? [PENDING]),
    myReviews: vi.fn(async () => ({ items: options.mine ?? [], nextCursor: null })),
    submit: vi.fn(async () => review()),
    withdrawPublicDisplay: vi.fn(async () => ({
      state: 'HIDDEN' as const,
      reasonCode: 'CUSTOMER_WITHDREW',
      withdrawable: false,
    })),
  };
  TestBed.configureTestingModule({
    imports: [RateOrderComponent],
    providers: [
      { provide: ReviewsService, useValue: reviews },
      { provide: LangService, useValue: { langId: signal('uz') } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(RateOrderComponent);
  fixture.componentRef.setInput('orderId', 'o9');
  fixture.componentRef.setInput('orderNumber', 'A-17');
  fixture.detectChanges();
  await settle(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, reviews };
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

function star(host: HTMLElement, value: number): void {
  (host.querySelectorAll('[data-testid="star"]')[value - 1] as HTMLButtonElement).click();
}

function write(host: HTMLElement, value: string): void {
  const area = host.querySelector('[data-testid="comment"]') as HTMLTextAreaElement;
  area.value = value;
  area.dispatchEvent(new Event('input'));
}

describe('RateOrderComponent -- whether to show anything', () => {
  it('shows the form for an order the platform lists as awaiting a review', async () => {
    const { host } = await setUp();

    expect(host.querySelector('[data-testid="rate-order"]')).not.toBeNull();
    expect(host.textContent).toContain('"number":"A-17"');
  });

  it('shows nothing while the restaurant has not switched its feedback settings on, and asks nothing more', async () => {
    const { host, reviews } = await setUp({ config: config({ enabled: false }) });

    expect(host.textContent?.trim()).toBe('');
    expect(reviews.pending).not.toHaveBeenCalled();
  });

  it('shows nothing, and does not fail the screen, when the config cannot be read', async () => {
    const { host } = await setUp({ config: new Error('offline') });

    expect(host.textContent?.trim()).toBe('');
  });

  it('shows nothing for an order the platform does not list as awaiting a review', async () => {
    const { host } = await setUp({ pending: [{ ...PENDING, orderId: 'someone-elses' }] });

    expect(host.querySelector('[data-testid="rate-order"]')).toBeNull();
  });
});

describe('RateOrderComponent -- the form', () => {
  it('does not submit before a star is picked', async () => {
    const { host, reviews } = await setUp();

    const submit = host.querySelector('[data-testid="submit"]') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    submit.click();

    expect(reviews.submit).not.toHaveBeenCalled();
  });

  it('offers complaints for one and two stars, praise for four and five, and both for three', async () => {
    const { fixture, host } = await setUp();
    expect(chips(host)).toEqual([]);

    star(host, 1);
    fixture.detectChanges();
    expect(chips(host)).toEqual(['Sovigan', 'Kech', 'Qo‘pol']);

    star(host, 5);
    fixture.detectChanges();
    expect(chips(host)).toEqual(['Mazali', 'Tez']);

    star(host, 3);
    fixture.detectChanges();
    expect(chips(host)).toEqual(['Sovigan', 'Kech', 'Qo‘pol', 'Mazali', 'Tez']);
  });

  it('sends the tags tapped, no more than the platform’s limit, and drops one the new rating no longer offers', async () => {
    const { fixture, host, reviews } = await setUp();
    star(host, 1);
    fixture.detectChanges();
    const chip = (index: number) =>
      host.querySelectorAll('[data-testid="tag-chip"]')[index] as HTMLButtonElement;

    chip(0).click();
    chip(1).click();
    chip(2).click();
    fixture.detectChanges();
    expect(host.querySelectorAll('[aria-pressed="true"][data-testid="tag-chip"]')).toHaveLength(2);

    star(host, 5);
    fixture.detectChanges();
    expect(host.querySelectorAll('[aria-pressed="true"][data-testid="tag-chip"]')).toHaveLength(0);

    star(host, 1);
    fixture.detectChanges();
    chip(2).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="submit"]') as HTMLButtonElement).click();
    await settle(fixture);

    expect(reviews.submit).toHaveBeenCalledWith('o9', 1, '', {
      tagIds: ['rude'],
      publicConsent: false,
    });
  });

  it('sends the plain three-argument request when nothing was tapped and nothing ticked', async () => {
    const { fixture, host, reviews } = await setUp();
    star(host, 4);
    fixture.detectChanges();

    (host.querySelector('[data-testid="submit"]') as HTMLButtonElement).click();
    await settle(fixture);

    expect(reviews.submit).toHaveBeenCalledWith('o9', 4, '');
  });

  it('shows the consent box only for a written comment at a restaurant that shows comments, unticked', async () => {
    const { fixture, host } = await setUp();
    star(host, 4);
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="public-consent"]')).toBeNull();

    write(host, 'Very good');
    fixture.detectChanges();
    const box = host.querySelector('[data-testid="public-consent"] input') as HTMLInputElement;
    expect(box.checked).toBe(false);

    write(host, '   ');
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="public-consent"]')).toBeNull();
  });

  it('shows no box at a restaurant that does not show comments publicly', async () => {
    const { fixture, host } = await setUp({ config: config({ publicComments: false }) });
    star(host, 4);
    write(host, 'Very good');
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="public-consent"]')).toBeNull();
  });

  it('sends the consent only for a tick whose comment is still there', async () => {
    const { fixture, host, reviews } = await setUp();
    star(host, 4);
    write(host, 'Very good');
    fixture.detectChanges();
    (host.querySelector('[data-testid="public-consent"] input') as HTMLInputElement).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="submit"]') as HTMLButtonElement).click();
    await settle(fixture);
    expect(reviews.submit).toHaveBeenLastCalledWith('o9', 4, 'Very good', {
      tagIds: [],
      publicConsent: true,
    });
  });

  it('never sends the consent of a tick whose comment was cleared afterwards', async () => {
    const { fixture, host, reviews } = await setUp();
    star(host, 4);
    write(host, 'Very good');
    fixture.detectChanges();
    (host.querySelector('[data-testid="public-consent"] input') as HTMLInputElement).click();
    write(host, '');
    fixture.detectChanges();

    (host.querySelector('[data-testid="submit"]') as HTMLButtonElement).click();
    await settle(fixture);

    expect(reviews.submit).toHaveBeenCalledWith('o9', 4, '');
  });

  it('turns the form into the customer’s own review after a successful submission', async () => {
    const { fixture, host, reviews } = await setUp();
    reviews.submit.mockResolvedValue(
      review({
        rating: 4,
        comment: 'Very good',
        publicDisplay: { state: 'PENDING', reasonCode: null, withdrawable: true },
      }),
    );
    star(host, 4);
    fixture.detectChanges();

    (host.querySelector('[data-testid="submit"]') as HTMLButtonElement).click();
    await settle(fixture);

    expect(host.querySelector('[data-testid="rate-order"]')).toBeNull();
    expect(host.querySelector('[data-testid="your-review"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="review-state"]')?.textContent).toContain(
      'rateOrder.publicState.PENDING',
    );
  });

  it('shows a refusal as a translated message, never the raw code, and keeps the form', async () => {
    const { fixture, host, reviews } = await setUp();
    reviews.submit.mockRejectedValue(
      new HorecaOSApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'already reviewed' }),
    );
    star(host, 1);
    fixture.detectChanges();

    (host.querySelector('[data-testid="submit"]') as HTMLButtonElement).click();
    await settle(fixture);

    expect(host.textContent).not.toContain('RESOURCE_CONFLICT');
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="rate-order"]')).not.toBeNull();
  });
});

describe('RateOrderComponent -- an order that already has a review', () => {
  it('shows the customer’s own review and where its comment stands, never the form', async () => {
    const { host } = await setUp({
      pending: [],
      mine: [
        review({
          publicDisplay: { state: 'HIDDEN', reasonCode: 'NAMES_AN_INDIVIDUAL', withdrawable: true },
        }),
      ],
    });

    expect(host.querySelector('[data-testid="rate-order"]')).toBeNull();
    const state = host.querySelector('[data-testid="review-state"]')?.textContent ?? '';
    expect(state).toContain('rateOrder.publicState.HIDDEN');
    expect(state).toContain('rateOrder.hiddenReason.NAMES_AN_INDIVIDUAL');
  });

  it('does not show another order’s review', async () => {
    const { host } = await setUp({ pending: [], mine: [review({ orderId: 'someone-elses' })] });

    expect(host.textContent?.trim()).toBe('');
  });

  it('offers to take the comment off the page only where that is possible, and does so', async () => {
    const { fixture, host, reviews } = await setUp({
      pending: [],
      mine: [
        review({ publicDisplay: { state: 'PUBLISHED', reasonCode: null, withdrawable: true } }),
      ],
    });

    (host.querySelector('[data-testid="withdraw"]') as HTMLButtonElement).click();
    await settle(fixture);

    expect(reviews.withdrawPublicDisplay).toHaveBeenCalledWith('r1');
    expect(host.querySelector('[data-testid="withdraw"]')).toBeNull();
    expect(host.querySelector('[data-testid="review-state"]')?.textContent).toContain(
      'rateOrder.hiddenReason.CUSTOMER_WITHDREW',
    );
  });

  it('offers no withdrawal for a comment that was never shared, or already taken off', async () => {
    const never = await setUp({ pending: [], mine: [review()] });
    expect(never.host.querySelector('[data-testid="withdraw"]')).toBeNull();
    expect(never.host.querySelector('[data-testid="review-state"]')).toBeNull();
  });

  it('shows a refused withdrawal as a translated message', async () => {
    const { fixture, host, reviews } = await setUp({
      pending: [],
      mine: [
        review({ publicDisplay: { state: 'PUBLISHED', reasonCode: null, withdrawable: true } }),
      ],
    });
    reviews.withdrawPublicDisplay.mockRejectedValue(
      new HorecaOSApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'not shared' }),
    );

    (host.querySelector('[data-testid="withdraw"]') as HTMLButtonElement).click();
    await settle(fixture);

    expect(host.textContent).not.toContain('UNPROCESSABLE_STATE');
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
  });
});
