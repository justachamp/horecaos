import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { HorecaOSApiError } from '../../../core/api/problem-details';
import { LangService } from '../../../services/lang.service';
import { type ReviewResponse, ReviewsService } from '../../../services/reviews.service';
import { TranslateService } from '../../../services/translate.service';
import { MyReviewsComponent } from './my-reviews.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

function review(overrides: Partial<ReviewResponse> = {}): ReviewResponse {
  return {
    id: 'r1',
    orderId: 'o1',
    rating: 2,
    comment: 'Cold soup',
    submittedAt: '2026-10-08T10:00:00Z',
    tags: [],
    publicDisplay: { state: 'PUBLISHED', reasonCode: null, withdrawable: true },
    ...overrides,
  };
}

async function setUp(items: readonly ReviewResponse[] = [review()]) {
  const reviews = {
    myReviews: vi.fn(async () => ({ items, nextCursor: null })),
    withdrawPublicDisplay: vi.fn(async () => ({
      state: 'HIDDEN' as const,
      reasonCode: 'CUSTOMER_WITHDREW',
      withdrawable: false,
    })),
  };
  TestBed.configureTestingModule({
    imports: [MyReviewsComponent],
    providers: [
      { provide: ReviewsService, useValue: reviews },
      { provide: LangService, useValue: { langId: signal('en') } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(MyReviewsComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement, reviews };
}

describe('MyReviewsComponent', () => {
  it('reads the customer’s own reviews once on arrival', async () => {
    const { reviews, host } = await setUp();

    expect(reviews.myReviews).toHaveBeenCalledTimes(1);
    expect(host.querySelectorAll('[data-testid="my-review"]')).toHaveLength(1);
  });

  it('reads them again when the page bumps the counter, and not before', async () => {
    const { fixture, reviews } = await setUp();

    fixture.componentRef.setInput('refresh', 1);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(reviews.myReviews).toHaveBeenCalledTimes(2);
  });

  it('renders nothing for a customer with no review: no heading, no empty frame', async () => {
    const { host } = await setUp([]);

    expect(host.querySelector('[data-testid="my-reviews"]')).toBeNull();
    expect(host.textContent?.trim()).toBe('');
  });

  it('takes one comment off the page and disables no other review’s button while it works', async () => {
    const { fixture, host, reviews } = await setUp([
      review({ id: 'a' }),
      review({ id: 'b', orderId: 'o2' }),
    ]);
    const buttons = host.querySelectorAll<HTMLButtonElement>('[data-testid="withdraw"]');

    buttons[0].click();
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(reviews.withdrawPublicDisplay).toHaveBeenCalledTimes(1);
    expect(reviews.withdrawPublicDisplay).toHaveBeenCalledWith('a');
    expect(host.querySelectorAll('[data-testid="withdraw"]')).toHaveLength(1);
    expect(host.querySelectorAll('[data-testid="my-review-state"]')[0].textContent).toContain(
      'profile.hiddenReason.CUSTOMER_WITHDREW',
    );
  });

  it('has no sentence for a reason code it does not know and says only that the comment is off the page', async () => {
    const { host } = await setUp([
      review({
        publicDisplay: { state: 'HIDDEN', reasonCode: 'SOMETHING_NEW', withdrawable: true },
      }),
    ]);
    const text = host.querySelector('[data-testid="my-review-state"]')?.textContent ?? '';

    expect(text).toContain('profile.publicState.HIDDEN');
    expect(text).not.toContain('SOMETHING_NEW');
  });

  it('shows a refusal as a translated message, never the raw code', async () => {
    const { fixture, host, reviews } = await setUp();
    reviews.withdrawPublicDisplay.mockRejectedValue(
      new HorecaOSApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'not shared' }),
    );

    (host.querySelector('[data-testid="withdraw"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(host.textContent).not.toContain('UNPROCESSABLE_STATE');
    expect(host.querySelector('.hint--error')).not.toBeNull();
  });

  it('draws the icon of a tag on the customer’s own review, and nothing for a tag without one', async () => {
    const icon = '/api/v1/storefront/tenants/t-1/media/a-1';
    const { host } = await setUp([
      review({
        tags: [
          {
            id: 't1',
            code: 'tasty',
            category: 'FOOD',
            sentiment: 'POSITIVE',
            labels: { en: 'Tasty' },
            iconUrl: icon,
          },
          {
            id: 't2',
            code: 'fast',
            category: 'SERVICE',
            sentiment: 'POSITIVE',
            labels: { en: 'Fast' },
            iconUrl: null,
          },
        ],
      }),
    ]);

    const icons = host.querySelectorAll('[data-testid="my-review"] [data-testid="tag-icon"]');
    expect(icons).toHaveLength(1);
    expect(icons[0].getAttribute('src')).toBe(icon);
  });
});
