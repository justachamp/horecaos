import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentSlide, ContentStoryGroup } from '../../types/content.types';
import { StoryViewerComponent } from './story-viewer.component';

function imageSlide(n: number, overrides: Partial<ContentSlide> = {}): ContentSlide {
  return {
    media: {
      kind: 'IMAGE',
      alt: `Slide ${n}`,
      renditions: [{ widthPx: 800, url: `/m/s${n}?variant=w800` }],
    },
    durationSeconds: 5,
    link: { type: 'NONE' },
    ...overrides,
  };
}

function videoSlide(n: number, overrides: Partial<ContentSlide> = {}): ContentSlide {
  return {
    media: { kind: 'VIDEO', alt: `Clip ${n}`, url: `/m/v${n}.mp4`, posterUrl: `/m/p${n}.jpg` },
    durationSeconds: 6,
    link: { type: 'NONE' },
    ...overrides,
  };
}

function story(slides: ContentSlide[]): ContentStoryGroup {
  return { id: 'story-1', revisionNo: 3, title: 'Weekend specials', textLocale: 'ru', slides };
}

class FakeEvents {
  click = vi.fn();
  completion = vi.fn();
  impression = vi.fn();
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

function mount(content: ContentStoryGroup, reduced = false) {
  const events = new FakeEvents();
  const motion = { reduced: signal(reduced) };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: ContentEventsService, useValue: events },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: motion },
    ],
  });
  const fixture = TestBed.createComponent(StoryViewerComponent);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('story', content);
  const closed = vi.fn();
  fixture.componentInstance.closed.subscribe(closed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const advance = (seconds: number) => {
    vi.advanceTimersByTime(seconds * 1000);
    fixture.detectChanges();
  };
  const click = (selector: string) => {
    host.querySelector<HTMLElement>(selector)?.click();
    fixture.detectChanges();
  };
  return {
    fixture,
    host,
    events,
    motion,
    closed,
    advance,
    click,
    shownAlt: () => host.querySelector('img')?.getAttribute('alt') ?? null,
  };
}

const NEXT = 'button[aria-label="content.storyNext"]';
const PREV = 'button[aria-label="content.storyPrevious"]';
const CLOSE = 'button[aria-label="content.storyClose"]';

describe('StoryViewerComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  describe('the dialog', () => {
    it('is a modal dialog named by the story', () => {
      const { host } = mount(story([imageSlide(1)]));

      const dialog = host.querySelector('[role="dialog"]');
      expect(dialog?.getAttribute('aria-modal')).toBe('true');
      expect(dialog?.getAttribute('aria-label')).toBe('Weekend specials');
      expect(dialog?.getAttribute('lang')).toBe('ru');
    });

    it('starts on the first slide, with one progress bar per slide', () => {
      const { host, shownAlt } = mount(story([imageSlide(1), imageSlide(2), imageSlide(3)]));

      expect(shownAlt()).toBe('Slide 1');
      expect(host.querySelectorAll('[data-testid="story-progress"] li')).toHaveLength(3);
    });

    it('announces where the visitor is', () => {
      const { host } = mount(story([imageSlide(1), imageSlide(2)]));

      expect(host.querySelector('[aria-live="polite"]')?.textContent).toContain(
        'content.storySlide|1|2',
      );
    });

    it('draws the caption as text', () => {
      const { host } = mount(story([imageSlide(1, { caption: '<b>Fresh</b> today' })]));

      expect(host.querySelector('[data-testid="story-caption"] b')).toBeNull();
      expect(host.querySelector('[data-testid="story-caption"]')?.textContent?.trim()).toBe(
        '<b>Fresh</b> today',
      );
    });

    it('moves focus to the close button', () => {
      const { host, fixture } = mount(story([imageSlide(1)]));
      vi.runOnlyPendingTimers();
      fixture.detectChanges();

      expect(document.activeElement).toBe(host.querySelector(CLOSE));
    });
  });

  describe('moving through the slides', () => {
    it('advances by itself after the slide duration', () => {
      const { shownAlt, advance } = mount(
        story([imageSlide(1), imageSlide(2, { durationSeconds: 8 })]),
      );

      advance(4);
      expect(shownAlt()).toBe('Slide 1');
      advance(1);
      expect(shownAlt()).toBe('Slide 2');
    });

    it('each slide keeps its own duration', () => {
      const { shownAlt, advance } = mount(
        story([
          imageSlide(1, { durationSeconds: 3 }),
          imageSlide(2, { durationSeconds: 10 }),
          imageSlide(3),
        ]),
      );

      advance(3);
      expect(shownAlt()).toBe('Slide 2');
      advance(9);
      expect(shownAlt()).toBe('Slide 2');
      advance(1);
      expect(shownAlt()).toBe('Slide 3');
    });

    it('tapping next and previous moves one slide', () => {
      const { shownAlt, click } = mount(story([imageSlide(1), imageSlide(2), imageSlide(3)]));

      click(NEXT);
      expect(shownAlt()).toBe('Slide 2');
      click(NEXT);
      expect(shownAlt()).toBe('Slide 3');
      click(PREV);
      expect(shownAlt()).toBe('Slide 2');
    });

    it('previous on the first slide stays on it', () => {
      const { shownAlt, click, closed } = mount(story([imageSlide(1), imageSlide(2)]));

      click(PREV);

      expect(shownAlt()).toBe('Slide 1');
      expect(closed).not.toHaveBeenCalled();
    });

    it('the arrow keys move too', () => {
      const { shownAlt, fixture } = mount(story([imageSlide(1), imageSlide(2)]));

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
      fixture.detectChanges();
      expect(shownAlt()).toBe('Slide 2');
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
      fixture.detectChanges();
      expect(shownAlt()).toBe('Slide 1');
    });

    it('restarts the timer for the slide it lands on', () => {
      const { shownAlt, click, advance } = mount(
        story([imageSlide(1), imageSlide(2), imageSlide(3)]),
      );

      advance(4);
      click(NEXT);
      advance(4);
      expect(shownAlt()).toBe('Slide 2');
      advance(1);
      expect(shownAlt()).toBe('Slide 3');
    });
  });

  describe('finishing', () => {
    it('when the last slide runs out it reports a completion, once, and closes', () => {
      const { events, closed, advance } = mount(story([imageSlide(1), imageSlide(2)]));

      advance(5);
      expect(events.completion).not.toHaveBeenCalled();
      advance(5);
      advance(60);

      expect(events.completion).toHaveBeenCalledTimes(1);
      expect(events.completion).toHaveBeenCalledWith('story-1', 3);
      expect(closed).toHaveBeenCalledTimes(1);
    });

    it('tapping next on the last slide is finishing it', () => {
      const { events, closed, click } = mount(story([imageSlide(1)]));

      click(NEXT);

      expect(events.completion).toHaveBeenCalledWith('story-1', 3);
      expect(closed).toHaveBeenCalledTimes(1);
    });

    it('closing early closes without a completion', () => {
      const { events, closed, click, advance } = mount(story([imageSlide(1), imageSlide(2)]));

      advance(5);
      click(CLOSE);

      expect(closed).toHaveBeenCalledTimes(1);
      expect(events.completion).not.toHaveBeenCalled();
    });

    it('Escape closes without a completion', () => {
      const { events, closed, fixture } = mount(story([imageSlide(1), imageSlide(2)]));

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();

      expect(closed).toHaveBeenCalledTimes(1);
      expect(events.completion).not.toHaveBeenCalled();
    });

    it('stops its timer when it is taken away', () => {
      const { fixture, events, closed } = mount(story([imageSlide(1)]));

      fixture.destroy();
      vi.advanceTimersByTime(60_000);

      expect(events.completion).not.toHaveBeenCalled();
      expect(closed).not.toHaveBeenCalled();
    });
  });

  describe('a slide that cannot be shown', () => {
    it('is skipped', () => {
      const { host, shownAlt, fixture } = mount(story([imageSlide(1), imageSlide(2)]));

      host.querySelector('img')?.dispatchEvent(new Event('error'));
      fixture.detectChanges();

      expect(shownAlt()).toBe('Slide 2');
    });

    it('on the last slide closes the story without counting it as seen', () => {
      const { host, events, closed, fixture } = mount(story([imageSlide(1)]));

      host.querySelector('img')?.dispatchEvent(new Event('error'));
      fixture.detectChanges();

      expect(closed).toHaveBeenCalledTimes(1);
      expect(events.completion).not.toHaveBeenCalled();
    });
  });

  describe('video slides', () => {
    it('play muted and inline, and advance after the slide duration', () => {
      const { host, shownAlt, advance } = mount(story([videoSlide(1), imageSlide(2)]));

      const video = host.querySelector('video');
      expect(video?.hasAttribute('muted')).toBe(true);
      expect(video?.hasAttribute('playsinline')).toBe(true);
      expect(video?.hasAttribute('loop')).toBe(false);
      advance(6);
      expect(shownAlt()).toBe('Slide 2');
    });

    it('under reduced motion show the poster only and do not advance by themselves', () => {
      const { host, shownAlt, advance, click } = mount(story([videoSlide(1), imageSlide(2)]), true);

      expect(host.querySelector('video')).toBeNull();
      expect(host.querySelector('img')?.getAttribute('src')).toBe('/m/p1.jpg');
      advance(120);
      expect(host.querySelector('img')?.getAttribute('src')).toBe('/m/p1.jpg');

      click(NEXT);
      expect(shownAlt()).toBe('Slide 2');
    });

    it('under reduced motion an image slide still advances', () => {
      const { shownAlt, advance } = mount(story([imageSlide(1), imageSlide(2)]), true);

      advance(5);

      expect(shownAlt()).toBe('Slide 2');
    });
  });

  describe('the slide link', () => {
    const cta = (host: HTMLElement) => host.querySelector('[data-testid="story-cta"] a');

    it('is a call to action when the link goes somewhere', () => {
      const { host } = mount(story([imageSlide(1, { link: { type: 'CART' } })]));

      expect(cta(host)?.getAttribute('href')).toBe('/cart');
      expect(cta(host)?.textContent).toContain('content.learnMore');
    });

    it('is taken: counts a click on the story', () => {
      const { events, click } = mount(story([imageSlide(1, { link: { type: 'CART' } })]));

      click('[data-testid="story-cta"] a');

      expect(events.click).toHaveBeenCalledWith('story-1', 3);
    });

    it('an https external link opens in a new tab with noopener noreferrer', () => {
      const { host } = mount(
        story([imageSlide(1, { link: { type: 'EXTERNAL', url: 'https://example.com/x' } })]),
      );

      expect(cta(host)?.getAttribute('target')).toBe('_blank');
      expect(cta(host)?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it.each([
      { type: 'NONE' as const },
      { type: 'PROMOTION' as const, targetId: '00000000-0000-0000-0000-000000000001' },
      { type: 'EXTERNAL' as const, url: 'http://example.com/' },
    ])('has no call to action for %j', (link) => {
      const { host } = mount(story([imageSlide(1, { link })]));

      expect(cta(host)).toBeNull();
    });
  });
});
