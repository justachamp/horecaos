import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentPopup } from '../../types/content.types';
import { popupStorageKey } from '../../utils/popup-schedule';
import { ContentPopupComponent } from './content-popup.component';

/** 2026-10-09 is a Friday. */
const FRIDAY_NOON = new Date(2026, 9, 9, 12, 0, 0);

function popup(overrides: Partial<ContentPopup> = {}): ContentPopup {
  return {
    id: 'pop-1',
    revisionNo: 1,
    title: 'Free delivery',
    body: 'On orders over 100 000',
    ctaLabel: 'Order now',
    textLocale: 'en',
    link: { type: 'CART' },
    showAfterSeconds: 5,
    cap: { mode: 'ONCE_PER_DAY', n: 1 },
    ...overrides,
  };
}

class FakeEvents {
  impression = vi.fn();
  click = vi.fn();
  dismissal = vi.fn();
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

function mount(content: ContentPopup | null) {
  const events = new FakeEvents();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: ContentEventsService, useValue: events },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: { reduced: () => false } },
    ],
  });
  const fixture = TestBed.createComponent(ContentPopupComponent);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('popup', content);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const advance = (seconds: number) => {
    vi.advanceTimersByTime(seconds * 1000);
    fixture.detectChanges();
  };
  return {
    fixture,
    host,
    events,
    advance,
    dialog: () => host.querySelector<HTMLElement>('[role="dialog"]'),
  };
}

describe('ContentPopupComponent', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
    vi.setSystemTime(FRIDAY_NOON);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  describe('when it shows', () => {
    it('shows nothing until showAfterSeconds have passed, then the dialog', () => {
      const { dialog, advance } = mount(popup({ showAfterSeconds: 5 }));

      expect(dialog()).toBeNull();
      advance(4);
      expect(dialog()).toBeNull();
      advance(1);
      expect(dialog()).not.toBeNull();
    });

    it('shows nothing for no popup', () => {
      const { dialog, advance } = mount(null);

      advance(120);

      expect(dialog()).toBeNull();
    });

    it('never shows once the page is gone, however long the delay was', () => {
      const { fixture, dialog, events } = mount(popup({ showAfterSeconds: 5 }));

      fixture.destroy();
      vi.advanceTimersByTime(60_000);

      expect(dialog()).toBeNull();
      expect(events.impression).not.toHaveBeenCalled();
    });

    it('counts an impression when it is shown, not before', () => {
      const { events, advance } = mount(popup());

      expect(events.impression).not.toHaveBeenCalled();
      advance(5);

      expect(events.impression).toHaveBeenCalledTimes(1);
      expect(events.impression).toHaveBeenCalledWith('pop-1', 1);
    });

    it('is a modal dialog named by its title', () => {
      const { dialog, advance, host } = mount(popup());
      advance(5);

      const el = dialog();
      expect(el?.getAttribute('aria-modal')).toBe('true');
      const labelledBy = el?.getAttribute('aria-labelledby') ?? '';
      expect(labelledBy).not.toBe('');
      expect(host.querySelector(`#${labelledBy}`)?.textContent).toContain('Free delivery');
    });

    it('writes its text as text', () => {
      const { host, advance } = mount(
        popup({ title: '<b>hi</b>', body: '<img src=x onerror=alert(1)>' }),
      );
      advance(5);

      expect(host.querySelector('[role="dialog"] b')).toBeNull();
      expect(host.querySelector('[role="dialog"] img')).toBeNull();
      expect(host.querySelector('h2')?.textContent?.trim()).toBe('<b>hi</b>');
    });

    it('shows once per visit even when the same popup is handed over again', () => {
      const { fixture, dialog, advance, events } = mount(popup());
      advance(5);
      fixture.componentRef.setInput('popup', { ...popup() });
      fixture.detectChanges();
      advance(10);

      expect(events.impression).toHaveBeenCalledTimes(1);
      expect(dialog()).not.toBeNull();
    });
  });

  describe('the frequency cap, kept on the device', () => {
    it('ONCE_PER_DAY: shown, remembered, then not again the same local day', () => {
      const first = mount(popup({ cap: { mode: 'ONCE_PER_DAY', n: 1 } }));
      first.advance(5);
      expect(first.dialog()).not.toBeNull();
      first.fixture.destroy();
      TestBed.resetTestingModule();

      vi.setSystemTime(new Date(2026, 9, 9, 22, 0));
      const second = mount(popup({ cap: { mode: 'ONCE_PER_DAY', n: 1 } }));
      second.advance(30);

      expect(second.dialog()).toBeNull();
    });

    it('ONCE_PER_DAY: shown again the next local day', () => {
      mount(popup()).advance(5);
      TestBed.resetTestingModule();

      vi.setSystemTime(new Date(2026, 9, 10, 0, 5));
      const next = mount(popup());
      next.advance(5);

      expect(next.dialog()).not.toBeNull();
    });

    it('ONCE_EVER: never again, a month later', () => {
      const cap = { mode: 'ONCE_EVER' as const, n: 1 };
      mount(popup({ cap })).advance(5);
      TestBed.resetTestingModule();

      vi.setSystemTime(new Date(2026, 10, 20, 12));
      const later = mount(popup({ cap }));
      later.advance(60);

      expect(later.dialog()).toBeNull();
    });

    it('EVERY_N_DAYS: not before n local days have passed, then yes', () => {
      const cap = { mode: 'EVERY_N_DAYS' as const, n: 3 };
      mount(popup({ cap })).advance(5);
      TestBed.resetTestingModule();

      vi.setSystemTime(new Date(2026, 9, 11, 12));
      const early = mount(popup({ cap }));
      early.advance(5);
      expect(early.dialog()).toBeNull();
      TestBed.resetTestingModule();

      vi.setSystemTime(new Date(2026, 9, 12, 12));
      const due = mount(popup({ cap }));
      due.advance(5);
      expect(due.dialog()).not.toBeNull();
    });

    it('keeps the record under a key made from the item AND its revision', () => {
      mount(popup({ id: 'pop-1', revisionNo: 7 })).advance(5);

      expect(localStorage.getItem(popupStorageKey('pop-1', 7))).not.toBeNull();
      expect(localStorage.getItem(popupStorageKey('pop-1', 8))).toBeNull();
    });

    it('editing the item (a new revision) resets the cap', () => {
      mount(popup({ revisionNo: 1 })).advance(5);
      TestBed.resetTestingModule();

      const edited = mount(popup({ revisionNo: 2 }));
      edited.advance(5);

      expect(edited.dialog()).not.toBeNull();
    });

    it('when storage throws on read and write the page is still correct: shown by its other rules', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new DOMException('denied', 'SecurityError');
      });
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('denied', 'SecurityError');
      });

      const { dialog, advance, events } = mount(popup());
      advance(5);

      expect(dialog()).not.toBeNull();
      expect(events.impression).toHaveBeenCalledTimes(1);
    });
  });

  describe('the daily window, on the device clock', () => {
    it('does not show outside the window, however long it waits', () => {
      const { dialog, advance } = mount(
        popup({ daily: { from: '18:00', until: '22:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] } }),
      );

      advance(60);

      expect(dialog()).toBeNull();
    });

    it('shows inside the window', () => {
      const { dialog, advance } = mount(
        popup({ daily: { from: '11:00', until: '13:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] } }),
      );

      advance(5);

      expect(dialog()).not.toBeNull();
    });

    it('does not show on a day that is not listed (it is a Friday)', () => {
      const { dialog, advance } = mount(
        popup({ daily: { from: '11:00', until: '13:00', daysOfWeek: [6, 7] } }),
      );

      advance(5);

      expect(dialog()).toBeNull();
    });

    it('does not show if the window closes while it waits', () => {
      vi.setSystemTime(new Date(2026, 9, 9, 12, 59, 50));
      const { dialog, advance } = mount(
        popup({
          showAfterSeconds: 30,
          daily: { from: '11:00', until: '13:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] },
        }),
      );

      advance(30); // 13:00:20, the window has closed

      expect(dialog()).toBeNull();
    });

    it('a window that runs past midnight counts the small hours', () => {
      vi.setSystemTime(new Date(2026, 9, 10, 1, 0, 0)); // Saturday 01:00
      const { dialog, advance } = mount(
        popup({ daily: { from: '22:00', until: '02:00', daysOfWeek: [5] } }),
      );

      advance(5);

      expect(dialog()).not.toBeNull();
    });

    it('a popup that was kept out by its window does not use up its cap', () => {
      const out = mount(
        popup({ daily: { from: '18:00', until: '22:00', daysOfWeek: [1, 2, 3, 4, 5, 6, 7] } }),
      );
      out.advance(60);

      expect(localStorage.getItem(popupStorageKey('pop-1', 1))).toBeNull();
    });
  });

  describe('dismissal', () => {
    it('the close button hides it and counts a dismissal', () => {
      const { dialog, host, advance, fixture, events } = mount(popup());
      advance(5);

      host.querySelector<HTMLButtonElement>('button[aria-label="content.popupClose"]')?.click();
      fixture.detectChanges();

      expect(dialog()).toBeNull();
      expect(events.dismissal).toHaveBeenCalledTimes(1);
      expect(events.dismissal).toHaveBeenCalledWith('pop-1', 1);
    });

    it('a click on the backdrop dismisses it', () => {
      const { dialog, host, advance, fixture, events } = mount(popup());
      advance(5);

      host.querySelector<HTMLElement>('[data-testid="popup-backdrop"]')?.click();
      fixture.detectChanges();

      expect(dialog()).toBeNull();
      expect(events.dismissal).toHaveBeenCalledTimes(1);
    });

    it('a click inside the dialog does not dismiss it', () => {
      const { dialog, advance, fixture, events } = mount(popup());
      advance(5);

      dialog()?.click();
      fixture.detectChanges();

      expect(dialog()).not.toBeNull();
      expect(events.dismissal).not.toHaveBeenCalled();
    });

    it('Escape dismisses it', () => {
      const { dialog, advance, fixture, events } = mount(popup());
      advance(5);

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      fixture.detectChanges();

      expect(dialog()).toBeNull();
      expect(events.dismissal).toHaveBeenCalledTimes(1);
    });

    it('Escape with no popup open does nothing', () => {
      const { events } = mount(popup());

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));

      expect(events.dismissal).not.toHaveBeenCalled();
    });

    it('moves focus to the close button, and gives it back when it closes', () => {
      const opener = document.createElement('button');
      document.body.appendChild(opener);
      opener.focus();
      const { host, advance, fixture } = mount(popup());
      advance(5);
      vi.runAllTimers();
      fixture.detectChanges();

      const close = host.querySelector<HTMLButtonElement>(
        'button[aria-label="content.popupClose"]',
      );
      expect(document.activeElement).toBe(close);

      close?.click();
      fixture.detectChanges();
      expect(document.activeElement).toBe(opener);
    });
  });

  describe('its call to action', () => {
    it('is a link with the brand label when the link goes somewhere', () => {
      const { host, advance } = mount(popup({ link: { type: 'CART' }, ctaLabel: 'Order now' }));
      advance(5);

      const cta = host.querySelector('[role="dialog"] a');
      expect(cta?.getAttribute('href')).toBe('/cart');
      expect(cta?.textContent).toContain('Order now');
    });

    it('falls back to a generic label when the brand typed none', () => {
      const { host, advance } = mount(popup({ ctaLabel: undefined }));
      advance(5);

      expect(host.querySelector('[role="dialog"] a')?.textContent).toContain('content.open');
    });

    it('is taken: counts a click and closes the popup without counting a dismissal', () => {
      const { host, advance, fixture, dialog, events } = mount(popup());
      advance(5);

      host.querySelector<HTMLAnchorElement>('[role="dialog"] a')?.click();
      fixture.detectChanges();

      expect(events.click).toHaveBeenCalledWith('pop-1', 1);
      expect(dialog()).toBeNull();
      expect(events.dismissal).not.toHaveBeenCalled();
    });

    it('an https external link opens in a new tab with noopener noreferrer', () => {
      const { host, advance } = mount(
        popup({ link: { type: 'EXTERNAL', url: 'https://example.com/promo' } }),
      );
      advance(5);

      const cta = host.querySelector('[role="dialog"] a');
      expect(cta?.getAttribute('href')).toBe('https://example.com/promo');
      expect(cta?.getAttribute('target')).toBe('_blank');
      expect(cta?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it.each([
      { type: 'NONE' as const },
      { type: 'PROMOTION' as const, targetId: '00000000-0000-0000-0000-000000000001' },
      { type: 'EXTERNAL' as const, url: 'http://example.com/' },
      { type: 'EXTERNAL' as const, url: 'javascript:alert(1)' },
    ])('has no call to action for %j', (link) => {
      const { host, advance } = mount(popup({ link }));
      advance(5);

      expect(host.querySelector('[role="dialog"] a')).toBeNull();
      expect(host.textContent).not.toContain('Order now');
    });
  });

  describe('its picture', () => {
    const media = {
      kind: 'IMAGE' as const,
      alt: 'Promo',
      renditions: [{ widthPx: 800, url: '/m/p?variant=w800' }],
    };

    it('draws the rendition and loads it eagerly: the visitor is already looking at the dialog', () => {
      const { host, advance } = mount(popup({ media }));
      advance(5);

      const img = host.querySelector('[role="dialog"] img');
      expect(img?.getAttribute('srcset')).toBe('/m/p?variant=w800 800w');
      expect(img?.getAttribute('loading')).toBe('eager');
    });

    it('drops only the picture when it fails to load; the words stay', () => {
      const { host, advance, fixture } = mount(popup({ media }));
      advance(5);

      host.querySelector('[role="dialog"] img')?.dispatchEvent(new Event('error'));
      fixture.detectChanges();

      expect(host.querySelector('[role="dialog"] img')).toBeNull();
      expect(host.textContent).toContain('Free delivery');
    });
  });
});
