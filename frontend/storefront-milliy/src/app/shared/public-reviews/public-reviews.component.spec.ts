import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { LangService } from '../../services/lang.service';
import {
  type PublicCommentPage,
  type PublicSummary,
  ReviewsService,
} from '../../services/reviews.service';
import { TranslateService } from '../../services/translate.service';
import { PublicReviewsComponent } from './public-reviews.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
}

const NOT_READY: PublicSummary = {
  visibility: 'NONE',
  ready: false,
  count: null,
  average: null,
  distribution: null,
};

function ready(overrides: Partial<PublicSummary> = {}): PublicSummary {
  return {
    visibility: 'RATING_ONLY',
    ready: true,
    count: 4,
    average: 3,
    distribution: [1, 1, 0, 1, 1],
    ...overrides,
  };
}

function comments(
  items: PublicCommentPage['items'],
  nextCursor: string | null = null,
): PublicCommentPage {
  return { visibility: 'RATING_AND_COMMENTS', items, nextCursor };
}

const COMMENT = {
  rating: 5,
  comment: 'The plov was the best I have had',
  tags: [
    {
      code: 'food_tasty',
      category: 'FOOD',
      sentiment: 'POSITIVE' as const,
      labels: { 'uz-Latn': 'Mazali', ru: 'Вкусно', en: 'Tasty' },
      iconUrl: null,
    },
  ],
  date: '2026-10-08',
  branchName: 'Chilonzor',
};

async function setUp(
  summary: PublicSummary | Error,
  firstPage: PublicCommentPage = comments([]),
  langId = 'uz',
  commentsFail = false,
) {
  const reviews = {
    publicSummary: vi.fn(async () => {
      if (summary instanceof Error) {
        throw summary;
      }
      return summary;
    }),
    publicComments: vi.fn(async () => {
      if (commentsFail) {
        throw new Error('offline');
      }
      return firstPage;
    }),
  };
  TestBed.configureTestingModule({
    imports: [PublicReviewsComponent],
    providers: [
      { provide: ReviewsService, useValue: reviews },
      { provide: LangService, useValue: { langId: signal(langId) } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(PublicReviewsComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement, reviews };
}

describe('PublicReviewsComponent', () => {
  it('renders nothing at all while the platform reports no summary: no heading over an empty frame', async () => {
    const { host } = await setUp(NOT_READY);

    expect(host.querySelector('[data-testid="public-reviews"]')).toBeNull();
    expect(host.textContent?.trim()).toBe('');
  });

  it('renders nothing, and does not break the page, when the summary cannot be read', async () => {
    const { host } = await setUp(new Error('offline'));

    expect(host.querySelector('[data-testid="public-reviews"]')).toBeNull();
  });

  it('shows the average to one decimal, the count and the five bars, five stars first', async () => {
    const { host } = await setUp(ready({ count: 4, average: 3, distribution: [1, 1, 0, 1, 1] }));

    expect(host.querySelector('[data-testid="public-average"]')?.textContent?.trim()).toBe('3.0');
    expect(host.querySelector('[data-testid="public-count"]')?.textContent).toContain('"count":4');
    const bars = Array.from(host.querySelectorAll('[data-testid="public-bar"]'));
    expect(bars.map((bar) => bar.querySelector('.bar__label')?.textContent?.trim())).toEqual([
      '5',
      '4',
      '3',
      '2',
      '1',
    ]);
    expect(bars.map((bar) => bar.querySelector('.bar__count')?.textContent?.trim())).toEqual([
      '1',
      '1',
      '0',
      '1',
      '1',
    ]);
    expect(bars.map((bar) => (bar.querySelector('.bar__fill') as HTMLElement).style.width)).toEqual(
      ['25%', '25%', '0%', '25%', '25%'],
    );
  });

  it('asks for no comments when the restaurant shows only the rating', async () => {
    const { host, reviews } = await setUp(ready({ visibility: 'RATING_ONLY' }));

    expect(reviews.publicComments).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="public-comment"]')).toBeNull();
  });

  it('lists the published comments with their stars, words, tags in the visitor’s language, day and branch', async () => {
    const { host } = await setUp(
      ready({ visibility: 'RATING_AND_COMMENTS' }),
      comments([COMMENT]),
      'ru',
    );

    const card = host.querySelector('[data-testid="public-comment"]') as HTMLElement;
    expect(card.textContent).toContain('The plov was the best I have had');
    expect(card.textContent).toContain('Вкусно');
    expect(card.textContent).toContain('Chilonzor');
    expect(card.querySelector('[aria-label="5 / 5"]')).not.toBeNull();
  });

  it('falls back to Uzbek, then to any wording at all, for a tag with no wording in the visitor’s language', async () => {
    const tagged = {
      ...COMMENT,
      tags: [{ ...COMMENT.tags[0], labels: { 'uz-Latn': 'Mazali' } }],
    };
    const { host } = await setUp(
      ready({ visibility: 'RATING_AND_COMMENTS' }),
      comments([tagged]),
      'en',
    );

    expect(host.querySelector('.comment__tag')?.textContent).toContain('Mazali');
  });

  it('shows no name, no order number and no time of day, because none is ever sent', async () => {
    const { host } = await setUp(ready({ visibility: 'RATING_AND_COMMENTS' }), comments([COMMENT]));
    const text = host.textContent ?? '';

    expect(text).not.toMatch(/\d{1,2}:\d{2}/);
    expect(Object.keys(COMMENT).sort()).toEqual([
      'branchName',
      'comment',
      'date',
      'rating',
      'tags',
    ]);
  });

  it('offers more only while the platform gives a cursor, and appends the next page', async () => {
    const { fixture, host, reviews } = await setUp(
      ready({ visibility: 'RATING_AND_COMMENTS' }),
      comments([COMMENT], '1'),
    );
    expect(host.querySelector('[data-testid="public-more"]')).not.toBeNull();

    reviews.publicComments.mockResolvedValueOnce(
      comments([{ ...COMMENT, comment: 'Second page' }], null),
    );
    (host.querySelector('[data-testid="public-more"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(reviews.publicComments).toHaveBeenLastCalledWith('1', 10);
    expect(host.querySelectorAll('[data-testid="public-comment"]')).toHaveLength(2);
    expect(host.querySelector('[data-testid="public-more"]')).toBeNull();
  });

  it('keeps the rating on screen when the comments cannot be read', async () => {
    const { host } = await setUp(
      ready({ visibility: 'RATING_AND_COMMENTS' }),
      comments([]),
      'uz',
      true,
    );

    expect(host.querySelector('[data-testid="public-average"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="public-comment"]')).toBeNull();
  });
});

describe('PublicReviewsComponent -- tag icons', () => {
  it('draws the icon a tenant uploaded for a tag beside its wording, and nothing for a tag without one', async () => {
    const icon = '/api/v1/storefront/tenants/t-1/media/a-1';
    const { host } = await setUp(
      ready({ visibility: 'RATING_AND_COMMENTS' }),
      comments([
        {
          ...COMMENT,
          tags: [
            { ...COMMENT.tags[0], iconUrl: icon },
            { ...COMMENT.tags[0], code: 'food_hot', iconUrl: null },
          ],
        },
      ]),
    );

    const icons = host.querySelectorAll('[data-testid="public-comment"] [data-testid="tag-icon"]');
    expect(icons).toHaveLength(1);
    expect(icons[0].getAttribute('src')).toBe(icon);
    expect(icons[0].getAttribute('alt')).toBe('');
  });
});
