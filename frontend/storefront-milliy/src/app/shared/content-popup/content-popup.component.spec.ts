import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentPopup } from '../../types/content.types';
import { contentPopup, imageMedia, uuid, videoMedia } from '../../testing/content-fixtures';
import { popupStorageKey, resetPopupMemoryForTests } from '../../utils/popup-schedule';
import { ContentPopupComponent } from './content-popup.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string) => key;
  current = () => ({});
}

// 2026-10-09 is a Friday. Every Date is built from local components: the daily window is read on
// the device clock, and these specs must not depend on the zone they run in.
const NOON = () => new Date(2026, 9, 9, 12, 0, 0);

function render(popup: ContentPopup | null, options: { reduced?: boolean } = {}) {
  const events = { report: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ContentEventsService, useValue: events },
      { provide: ReducedMotionService, useValue: { reduced: signal(options.reduced ?? false) } },
    ],
  });
  const opener = document.createElement('button');
  document.body.appendChild(opener);
  opener.focus();
  const fixture = TestBed.createComponent(ContentPopupComponent);
  fixture.componentRef.setInput('popup', popup);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const dialog = () => host.querySelector<HTMLElement>('[data-testid="content-popup"]');
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const advance = (ms: number) => {
    vi.advanceTimersByTime(ms);
    fixture.detectChanges();
  };
  const reports = (event: string) =>
    events.report.mock.calls.filter(([, , name]) => name === event);
  return { fixture, host, events, dialog, q, advance, reports, opener };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOON());
  localStorage.clear();
  resetPopupMemoryForTests();
});

afterEach(() => {
  document.body.replaceChildren();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('ContentPopupComponent: when it is shown', () => {
  it('shows nothing for no pop-up', () => {
    const { dialog, advance } = render(null);

    advance(120_000);

    expect(dialog()).toBeNull();
  });

  it('waits for its delay, then shows', () => {
    const { dialog, advance } = render(contentPopup({ showAfterSeconds: 5 }));

    expect(dialog()).toBeNull();
    advance(4_999);
    expect(dialog()).toBeNull();
    advance(1);

    expect(dialog()).not.toBeNull();
  });

  it('shows only once in a page session, and reports that it was seen once', () => {
    const { dialog, advance, q, reports } = render(contentPopup({ id: uuid(90), revisionNo: 3 }));
    advance(5_000);
    expect(reports('IMPRESSION')).toEqual([[uuid(90), 3, 'IMPRESSION']]);

    q('content-popup-close')?.click();
    advance(600_000);

    expect(dialog()).toBeNull();
    expect(reports('IMPRESSION')).toHaveLength(1);
  });

  it('shows nothing and sets no timer when the cap says it has had its turn', () => {
    const popup = contentPopup({ cap: { mode: 'ONCE_EVER', n: 1 } });
    localStorage.setItem(popupStorageKey(popup), JSON.stringify({ count: 1, lastShownAt: 1 }));
    const { dialog, advance } = render(popup);

    advance(120_000);

    expect(dialog()).toBeNull();
  });

  it('remembers on the device that it was shown, under a key of the item and its revision', () => {
    const popup = contentPopup({ id: uuid(90), revisionNo: 3 });
    const { advance } = render(popup);

    advance(5_000);

    expect(JSON.parse(localStorage.getItem(popupStorageKey(popup)) ?? 'null')).toEqual({
      count: 1,
      lastShownAt: NOON().getTime() + 5_000,
    });
    expect(popupStorageKey(popup)).toContain(uuid(90));
    expect(popupStorageKey(popup)).toContain('3');
  });

  it('shows again for an edited item: a new revision is a new cap', () => {
    const seen = contentPopup({ id: uuid(90), revisionNo: 1, cap: { mode: 'ONCE_EVER', n: 1 } });
    localStorage.setItem(popupStorageKey(seen), JSON.stringify({ count: 1, lastShownAt: 1 }));

    const { dialog, advance } = render({ ...seen, revisionNo: 2 });
    advance(5_000);

    expect(dialog()).not.toBeNull();
  });

  it('ONCE_PER_DAY: shows the day after, not the same day', () => {
    const popup = contentPopup({ cap: { mode: 'ONCE_PER_DAY', n: 1 } });
    const yesterday = new Date(2026, 9, 8, 23, 0).getTime();
    localStorage.setItem(
      popupStorageKey(popup),
      JSON.stringify({ count: 1, lastShownAt: yesterday }),
    );
    const first = render(popup);
    first.advance(5_000);
    expect(first.dialog()).not.toBeNull();

    TestBed.resetTestingModule();
    document.body.replaceChildren();
    resetPopupMemoryForTests();
    const second = render(popup);
    second.advance(5_000);
    expect(second.dialog()).toBeNull();
  });

  it('EVERY_N_DAYS: waits until n local days have passed', () => {
    const popup = contentPopup({ cap: { mode: 'EVERY_N_DAYS', n: 3 } });
    const twoDaysAgo = new Date(2026, 9, 7, 20, 0).getTime();
    localStorage.setItem(
      popupStorageKey(popup),
      JSON.stringify({ count: 1, lastShownAt: twoDaysAgo }),
    );
    const early = render(popup);
    early.advance(5_000);
    expect(early.dialog()).toBeNull();

    TestBed.resetTestingModule();
    document.body.replaceChildren();
    localStorage.setItem(
      popupStorageKey(popup),
      JSON.stringify({ count: 1, lastShownAt: new Date(2026, 9, 6, 20, 0).getTime() }),
    );
    const due = render(popup);
    due.advance(5_000);
    expect(due.dialog()).not.toBeNull();
  });

  it('stays hidden outside its daily window, on the device clock', () => {
    const popup = contentPopup({
      daily: { from: '18:00', until: '22:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] },
    });
    const { dialog, advance } = render(popup);

    advance(60_000);

    expect(dialog()).toBeNull();
  });

  it('shows inside its daily window', () => {
    const popup = contentPopup({ daily: { from: '11:00', until: '13:00', daysOfWeek: [5] } });
    const { dialog, advance } = render(popup);

    advance(5_000);

    expect(dialog()).not.toBeNull();
  });

  it('stays hidden on a day the window does not run', () => {
    const popup = contentPopup({ daily: { from: '11:00', until: '13:00', daysOfWeek: [1, 2] } });
    const { dialog, advance } = render(popup);

    advance(60_000);

    expect(dialog()).toBeNull();
  });

  it('does not show if the window closed while it was waiting', () => {
    const popup = contentPopup({
      showAfterSeconds: 60,
      daily: { from: '11:00', until: '12:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] },
    });
    const { dialog, advance } = render(popup);

    vi.setSystemTime(new Date(2026, 9, 9, 12, 0, 30));
    advance(60_000);

    expect(dialog()).toBeNull();
  });

  it('is correct when storage throws on every read and write: shown by its other rules, once', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded');
    });
    const { dialog, advance, q } = render(contentPopup());

    advance(5_000);
    expect(dialog()).not.toBeNull();

    q('content-popup-close')?.click();
    advance(600_000);
    expect(dialog()).toBeNull();
  });

  it('is correct when merely touching localStorage throws', () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    const { dialog, advance } = render(contentPopup());

    advance(5_000);

    expect(dialog()).not.toBeNull();
  });

  it('forgets a pending pop-up when it is taken away before its time', () => {
    const { fixture, dialog, advance } = render(contentPopup({ showAfterSeconds: 10 }));

    advance(5_000);
    fixture.componentRef.setInput('popup', null);
    fixture.detectChanges();
    advance(30_000);

    expect(dialog()).toBeNull();
  });
});

describe('ContentPopupComponent: what is shown', () => {
  it('is a modal dialog named by its own title', () => {
    const { dialog, advance, q } = render(
      contentPopup({ title: 'Welcome', body: 'Free delivery' }),
    );
    advance(5_000);

    expect(dialog()?.getAttribute('role')).toBe('dialog');
    expect(dialog()?.getAttribute('aria-modal')).toBe('true');
    expect(dialog()?.getAttribute('aria-labelledby')).toBe(q('content-popup-title')?.id);
    expect(q('content-popup-title')?.id).toBeTruthy();
    expect(q('content-popup-title')?.textContent?.trim()).toBe('Welcome');
    expect(dialog()?.textContent).toContain('Free delivery');
  });

  it('draws title and body as text, never as markup', () => {
    const { host, advance } = render(
      contentPopup({ title: '<b>Hot</b>', body: '<img src=x onerror=alert(1)>' }),
    );
    advance(5_000);

    expect(host.querySelector('[data-testid="content-popup"] b')).toBeNull();
    expect(host.querySelector('[data-testid="content-popup"] img')).toBeNull();
    expect(host.textContent).toContain('<b>Hot</b>');
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('puts the language of the text on the text', () => {
    const { q, advance } = render(contentPopup({ textLocale: 'ru' }));
    advance(5_000);

    expect(q('content-popup-text')?.getAttribute('lang')).toBe('ru');
  });

  it('draws its picture from the renditions', () => {
    const { host, advance } = render(contentPopup({ media: imageMedia() }));
    advance(5_000);

    expect(
      host.querySelector('[data-testid="content-popup"] img')?.getAttribute('srcset'),
    ).toContain('800w');
  });

  it('shows only the poster of a video under reduced motion', () => {
    const { host, advance } = render(contentPopup({ media: videoMedia() }), { reduced: true });
    advance(5_000);

    expect(host.querySelector('video')).toBeNull();
    expect(host.querySelector('[data-testid="content-popup"] img')).not.toBeNull();
  });

  it('puts focus on the close button, keeps Tab inside, and gives focus back when it is closed', () => {
    const { dialog, advance, q, opener, fixture } = render(contentPopup());
    advance(5_000);

    expect(document.activeElement).toBe(q('content-popup-close'));

    const focusable = [...(dialog()?.querySelectorAll<HTMLElement>('button, a[href]') ?? [])];
    focusable[focusable.length - 1].focus();
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    dialog()?.dispatchEvent(tab);
    expect(tab.defaultPrevented).toBe(true);
    expect(dialog()?.contains(document.activeElement)).toBe(true);

    q('content-popup-close')?.click();
    fixture.detectChanges();
    expect(document.activeElement).toBe(opener);
  });
});

describe('ContentPopupComponent: closing it', () => {
  it('has a close button with a name, and reports a dismissal when it is used', () => {
    const { dialog, advance, q, reports } = render(contentPopup({ id: uuid(90), revisionNo: 3 }));
    advance(5_000);
    expect(q('content-popup-close')?.getAttribute('aria-label')).toBe('content.popupClose');

    q('content-popup-close')?.click();
    advance(0);

    expect(dialog()).toBeNull();
    expect(reports('DISMISSAL')).toEqual([[uuid(90), 3, 'DISMISSAL']]);
  });

  it('closes on a tap on the backdrop, and not on a tap inside the dialog', () => {
    const { dialog, advance, q, reports } = render(contentPopup());
    advance(5_000);

    q('content-popup-text')?.click();
    advance(0);
    expect(dialog()).not.toBeNull();

    q('content-popup-backdrop')?.click();
    advance(0);
    expect(dialog()).toBeNull();
    expect(reports('DISMISSAL')).toHaveLength(1);
  });

  it('closes on Escape', () => {
    const { dialog, advance, reports } = render(contentPopup());
    advance(5_000);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    advance(0);

    expect(dialog()).toBeNull();
    expect(reports('DISMISSAL')).toHaveLength(1);
  });

  it('does not answer Escape when nothing is shown', () => {
    const { advance, reports } = render(contentPopup({ showAfterSeconds: 30 }));

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    advance(0);

    expect(reports('DISMISSAL')).toEqual([]);
  });

  it('reports a dismissal once, however it is closed', () => {
    const { advance, q, reports } = render(contentPopup());
    advance(5_000);

    q('content-popup-close')?.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    advance(0);

    expect(reports('DISMISSAL')).toHaveLength(1);
  });
});

describe('ContentPopupComponent: its call to action', () => {
  it('links where the link goes, reports a click, and closes without calling it a dismissal', () => {
    const { dialog, advance, q, reports } = render(
      contentPopup({ id: uuid(90), revisionNo: 3, link: { type: 'CART' }, ctaLabel: 'Order now' }),
    );
    advance(5_000);

    const cta = q('content-popup-cta');
    const anchor = cta?.querySelector('a');
    expect(anchor?.getAttribute('href')).toBe('/cart');
    expect(cta?.textContent?.trim()).toBe('Order now');
    anchor?.click();
    advance(0);

    expect(reports('CLICK')).toEqual([[uuid(90), 3, 'CLICK']]);
    expect(reports('DISMISSAL')).toEqual([]);
    expect(dialog()).toBeNull();
  });

  it('opens an https address in a new tab that cannot reach back', () => {
    const { advance, q } = render(
      contentPopup({ link: { type: 'EXTERNAL', url: 'https://example.uz/offer' } }),
    );
    advance(5_000);

    const anchor = q('content-popup-cta')?.querySelector('a');
    expect(anchor?.getAttribute('target')).toBe('_blank');
    expect(anchor?.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it.each([
    ['NONE', { type: 'NONE' }],
    ['PROMOTION', { type: 'PROMOTION', targetId: uuid(9) }],
    ['an EXTERNAL address that is not https', { type: 'EXTERNAL', url: 'javascript:alert(1)' }],
  ] as const)('has no call to action when the link is %s', (_label, link) => {
    const { host, advance, q } = render(contentPopup({ link }));
    advance(5_000);

    expect(q('content-popup-cta')).toBeNull();
    expect(host.querySelector('[data-testid="content-popup"] [href]')).toBeNull();
  });

  it('falls back to a generic label when the link works but the brand wrote none', () => {
    const { advance, q } = render(contentPopup({ ctaLabel: undefined, link: { type: 'CART' } }));
    advance(5_000);

    expect(q('content-popup-cta')?.textContent?.trim()).toBe('content.openLink');
  });
});
