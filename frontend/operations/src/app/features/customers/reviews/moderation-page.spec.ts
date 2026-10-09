import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { LocationsApi } from '../../settings/locations/locations-api';
import { ModerationApi, QueuePage, QueueEntry, QueueState } from './moderation-api';
import { ModerationPage } from './moderation-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

class FakeCurrentBrand {
  readonly scope = signal<BrandScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function entry(overrides: Partial<QueueEntry> = {}): QueueEntry {
  return {
    reviewId: 'review-1',
    locationId: 'location-1',
    rating: 5,
    comment: 'The plov was the best I have had',
    submittedAt: '2026-10-08T06:00:00Z',
    state: 'PENDING',
    reasonCode: null,
    version: 1,
    updatedAt: '2026-10-08T06:00:00Z',
    tags: [
      {
        id: 'tag-1',
        code: 'food_tasty',
        category: 'FOOD',
        sentiment: 'POSITIVE',
        labels: { en: 'Tasty food' },
      },
    ],
    ...overrides,
  };
}

function queue(
  items: QueueEntry[],
  counts: QueuePage['counts'] = { PENDING: items.length },
): QueuePage {
  return { items, nextCursor: null, counts };
}

describe('ModerationPage', () => {
  let fixture: ComponentFixture<ModerationPage>;
  let api: {
    queue: ReturnType<typeof vi.fn>;
    publish: ReturnType<typeof vi.fn>;
    hide: ReturnType<typeof vi.fn>;
  };

  async function render(
    first: QueuePage | (() => Promise<QueuePage>) = queue([entry()]),
    brand: FakeCurrentBrand = new FakeCurrentBrand(),
  ): Promise<void> {
    api = {
      queue: vi.fn(typeof first === 'function' ? first : async () => first),
      publish: vi.fn().mockResolvedValue({}),
      hide: vi.fn().mockResolvedValue({}),
    };
    await TestBed.configureTestingModule({
      imports: [ModerationPage],
      providers: [
        { provide: ModerationApi, useValue: api },
        { provide: LocationsApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
        { provide: CurrentBrand, useValue: brand },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ModerationPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function el<T extends HTMLElement>(testId: string): T {
    const found = host().querySelector(`[data-testid="${testId}"]`);
    expect(found, testId).not.toBeNull();
    return found as T;
  }

  async function click(testId: string): Promise<void> {
    el(testId).click();
    await flush();
    fixture.detectChanges();
  }

  it('lists the comments waiting for a decision, with the stars, the tags and the three counts', async () => {
    await render(queue([entry()], { PENDING: 4, PUBLISHED: 12, HIDDEN: 2 }));

    expect(api.queue).toHaveBeenCalledWith(SCOPE, 'PENDING', { cursor: null, limit: 25 });
    expect(el('entry-comment').textContent).toContain('The plov was the best');
    expect(host().querySelector('[aria-label="5 / 5"]')).not.toBeNull();
    expect(host().textContent).toContain('Tasty food');
    expect(el('moderation-tab-PENDING').textContent).toContain('4');
    expect(el('moderation-tab-PUBLISHED').textContent).toContain('12');
    expect(el('moderation-tab-HIDDEN').textContent).toContain('2');
  });

  it('publishes a waiting comment with the version the queue returned, then reads the queue again', async () => {
    await render(queue([entry({ version: 3 })]));
    api.queue.mockClear();

    await click('entry-publish');

    expect(api.publish).toHaveBeenCalledWith(SCOPE, 'review-1', 3);
    expect(api.queue).toHaveBeenCalledTimes(1);
  });

  it('will not hide without a reason, and offers exactly the five', async () => {
    await render();

    await click('entry-hide');

    const reason = el<HTMLSelectElement>('hide-reason');
    const offered = Array.from(reason.options)
      .map((o) => o.value)
      .filter((v) => v !== '');
    expect(offered).toEqual([
      'ABUSIVE_LANGUAGE',
      'PERSONAL_DATA_OF_ANOTHER',
      'NAMES_AN_INDIVIDUAL',
      'SPAM_OR_UNRELATED',
      'LEGAL_REQUEST',
    ]);
    expect(el<HTMLButtonElement>('hide-confirm').disabled).toBe(true);
    expect(host().querySelector('input[type="text"], textarea')).toBeNull();
  });

  it('hides with the reason chosen and the entry’s version', async () => {
    await render(queue([entry({ version: 2 })]));
    await click('entry-hide');

    const reason = el<HTMLSelectElement>('hide-reason');
    reason.value = 'NAMES_AN_INDIVIDUAL';
    reason.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await click('hide-confirm');

    expect(api.hide).toHaveBeenCalledWith(SCOPE, 'review-1', 'NAMES_AN_INDIVIDUAL', 2);
    expect(host().querySelector('[data-testid="hide-form"]')).toBeNull();
  });

  it('switches to the published comments and offers only Hide there', async () => {
    await render();
    api.queue.mockResolvedValue(queue([entry({ state: 'PUBLISHED' })], { PUBLISHED: 1 }));

    await click('moderation-tab-PUBLISHED');

    expect(api.queue).toHaveBeenLastCalledWith(SCOPE, 'PUBLISHED' satisfies QueueState, {
      cursor: null,
      limit: 25,
    });
    expect(host().querySelector('[data-testid="entry-publish"]')).toBeNull();
    expect(host().querySelector('[data-testid="entry-hide"]')).not.toBeNull();
  });

  it('offers to restore what a moderator hid, with the reason it was hidden for', async () => {
    await render();
    api.queue.mockResolvedValue(
      queue([entry({ state: 'HIDDEN', reasonCode: 'SPAM_OR_UNRELATED', version: 4 })], {
        HIDDEN: 1,
      }),
    );
    await click('moderation-tab-HIDDEN');

    expect(el('entry-reason').textContent).toContain('Spam or unrelated');
    await click('entry-restore');

    expect(api.publish).toHaveBeenCalledWith(SCOPE, 'review-1', 4);
  });

  it('offers no restore for a comment the customer took back, and says it cannot be restored', async () => {
    await render();
    api.queue.mockResolvedValue(
      queue([entry({ state: 'HIDDEN', reasonCode: 'CUSTOMER_WITHDREW' })], { HIDDEN: 1 }),
    );
    await click('moderation-tab-HIDDEN');

    expect(host().querySelector('[data-testid="entry-restore"]')).toBeNull();
    expect(el('entry-no-restore').textContent).toContain('cannot be restored');
    expect(el('entry-reason').textContent).toContain('Withdrawn by the customer');
  });

  it('offers no restore for a comment whose author was erased either', async () => {
    await render();
    api.queue.mockResolvedValue(
      queue([entry({ state: 'HIDDEN', reasonCode: 'CUSTOMER_ERASED', comment: null })], {
        HIDDEN: 1,
      }),
    );
    await click('moderation-tab-HIDDEN');

    expect(host().querySelector('[data-testid="entry-restore"]')).toBeNull();
  });

  it('reads the queue again and says so when a comment changed under the moderator', async () => {
    await render(queue([entry({ version: 1 })]));
    api.publish.mockRejectedValueOnce(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'corr'));
    api.queue.mockClear();

    await click('entry-publish');

    expect(el('moderation-error').textContent).toContain('changed since you opened the list');
    expect(api.queue).toHaveBeenCalled();
  });

  it('shows the honest empty state per tab', async () => {
    await render(queue([], { PENDING: 0 }));

    expect(host().textContent).toContain('No comments are waiting for a decision.');
  });

  it('shows the denied state and no queue when the server refuses', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });

    expect(host().textContent).toContain('You do not have access to moderation.');
    expect(host().querySelector('[data-testid="moderation-entry"]')).toBeNull();
  });
});

beforeEach(() => TestBed.resetTestingModule());
