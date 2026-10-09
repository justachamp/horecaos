import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentStoryGroup } from '../../types/content.types';
import { imageMedia, slide, storyGroup, uuid, videoMedia } from '../../testing/content-fixtures';
import { ContentStoryViewerComponent } from './content-story-viewer.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string, params?: Record<string, string | number>) =>
    params ? `${key}|${Object.values(params).join('|')}` : key;
  current = () => ({});
}

function render(story: ContentStoryGroup, options: { reduced?: boolean } = {}) {
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
  const fixture = TestBed.createComponent(ContentStoryViewerComponent);
  fixture.componentRef.setInput('story', story);
  const closed = vi.fn();
  fixture.componentInstance.closed.subscribe(closed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const caption = () => q('story-caption')?.textContent?.trim() ?? null;
  const current = () =>
    [...host.querySelectorAll('[data-testid="story-bar"]')].findIndex((bar) =>
      bar.classList.contains('is-current'),
    );
  const tick = (ms: number) => {
    vi.advanceTimersByTime(ms);
    fixture.detectChanges();
  };
  return { fixture, host, events, closed, q, caption, current, tick, opener };
}

const THREE = (): ContentStoryGroup =>
  storyGroup({
    id: uuid(70),
    revisionNo: 4,
    slides: [
      slide({ caption: 'One', durationSeconds: 5 }),
      slide({ caption: 'Two', durationSeconds: 8 }),
      slide({ caption: 'Three', durationSeconds: 3 }),
    ],
  });

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  document.body.replaceChildren();
  vi.useRealTimers();
});

describe('ContentStoryViewerComponent: a full-screen story', () => {
  it('is a modal dialog named after the story, with a bar for each slide and the first one showing', () => {
    const { q, host, caption, current } = render(THREE());
    const dialog = q('story-viewer');

    expect(dialog?.getAttribute('role')).toBe('dialog');
    expect(dialog?.getAttribute('aria-modal')).toBe('true');
    expect(dialog?.getAttribute('aria-label')).toBe('Today');
    expect(host.querySelectorAll('[data-testid="story-bar"]')).toHaveLength(3);
    expect(current()).toBe(0);
    expect(caption()).toBe('One');
    expect(host.querySelector('img')?.getAttribute('srcset')).toContain('400w');
  });

  it('tells a screen reader which slide it is on', () => {
    const { q, tick } = render(THREE());
    expect(q('story-progress-text')?.textContent).toContain('content.storyProgress|1|3');

    tick(5_000);

    expect(q('story-progress-text')?.textContent).toContain('content.storyProgress|2|3');
  });

  it('moves on by itself after the slide’s own duration', () => {
    const { caption, current, tick } = render(THREE());

    tick(4_999);
    expect(caption()).toBe('One');
    tick(1);
    expect(caption()).toBe('Two');
    expect(current()).toBe(1);
    tick(8_000);
    expect(caption()).toBe('Three');
  });

  it('moves to the next slide on a tap, and back on a tap on the other side', () => {
    const { q, caption, fixture } = render(THREE());

    q('story-next')?.click();
    fixture.detectChanges();
    expect(caption()).toBe('Two');

    q('story-previous')?.click();
    fixture.detectChanges();
    expect(caption()).toBe('One');
  });

  it('stays on the first slide when "back" is tapped there, and starts its time again', () => {
    const { q, caption, fixture, tick } = render(THREE());

    tick(4_000);
    q('story-previous')?.click();
    fixture.detectChanges();
    tick(4_000);

    expect(caption()).toBe('One');
    tick(1_000);
    expect(caption()).toBe('Two');
  });

  it('starts a slide’s time over when it is reached by a tap', () => {
    const { q, caption, fixture, tick } = render(THREE());

    tick(4_000);
    q('story-next')?.click();
    fixture.detectChanges();
    tick(7_999);

    expect(caption()).toBe('Two');
    tick(1);
    expect(caption()).toBe('Three');
  });

  it('answers the arrow keys and Escape', () => {
    const { q, caption, fixture, closed } = render(THREE());
    const dialog = q('story-viewer') as HTMLElement;
    const press = (key: string) => {
      dialog.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
      fixture.detectChanges();
    };

    press('ArrowRight');
    expect(caption()).toBe('Two');
    press('ArrowLeft');
    expect(caption()).toBe('One');
    press('Escape');

    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('reports a completion and closes when the last slide runs out', () => {
    const { events, closed, tick } = render(THREE());

    tick(5_000);
    tick(8_000);
    expect(closed).not.toHaveBeenCalled();
    tick(3_000);

    expect(closed).toHaveBeenCalledTimes(1);
    expect(events.report).toHaveBeenCalledWith(uuid(70), 4, 'COMPLETION');
    expect(events.report.mock.calls.filter(([, , event]) => event === 'COMPLETION')).toHaveLength(
      1,
    );
  });

  it('reports a completion when "next" is tapped on the last slide', () => {
    const { q, events, closed, fixture } = render(
      storyGroup({ id: uuid(70), revisionNo: 4, slides: [slide({ caption: 'Only' })] }),
    );

    q('story-next')?.click();
    fixture.detectChanges();

    expect(closed).toHaveBeenCalledTimes(1);
    expect(events.report).toHaveBeenCalledWith(uuid(70), 4, 'COMPLETION');
  });

  it('closes from the close button without claiming the story was watched to the end', () => {
    const { q, events, closed, tick } = render(THREE());

    tick(5_000);
    q('story-close')?.click();

    expect(closed).toHaveBeenCalledTimes(1);
    expect(events.report.mock.calls.filter(([, , event]) => event === 'COMPLETION')).toEqual([]);
    tick(60_000);
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('names its buttons for a screen reader', () => {
    const { q } = render(THREE());

    expect(q('story-close')?.getAttribute('aria-label')).toBe('content.storyClose');
    expect(q('story-next')?.getAttribute('aria-label')).toBe('content.storyNext');
    expect(q('story-previous')?.getAttribute('aria-label')).toBe('content.storyPrevious');
  });

  it('shows a caption as text and in the language it was written in', () => {
    const { q } = render(
      storyGroup({
        textLocale: 'ru',
        slides: [slide({ caption: '<img src=x onerror=alert(1)>' })],
      }),
    );

    expect(q('story-caption')?.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(q('story-caption')?.querySelector('img')).toBeNull();
    expect(q('story-caption')?.getAttribute('lang')).toBe('ru');
  });

  it('shows no caption on a slide that has none', () => {
    const { q } = render(storyGroup({ slides: [slide()] }));

    expect(q('story-caption')).toBeNull();
  });

  it('offers a link on a slide whose link goes somewhere, and reports a click on it', () => {
    const { q, events, closed } = render(
      storyGroup({
        id: uuid(70),
        revisionNo: 4,
        slides: [slide({ link: { type: 'PRODUCT', targetId: uuid(9) } })],
      }),
    );

    const cta = q('story-cta');
    const anchor = cta?.querySelector('a');
    expect(anchor?.getAttribute('href')).toBe(`/product/${uuid(9)}`);
    expect(cta?.textContent?.trim()).toBe('content.openLink');
    anchor?.click();

    expect(events.report).toHaveBeenCalledWith(uuid(70), 4, 'CLICK');
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['NONE', { type: 'NONE' }],
    ['PROMOTION', { type: 'PROMOTION', targetId: uuid(9) }],
    ['an EXTERNAL address that is not https', { type: 'EXTERNAL', url: 'javascript:alert(1)' }],
  ] as const)('offers no link on a slide whose link is %s', (_label, link) => {
    const { q, host } = render(storyGroup({ slides: [slide({ link })] }));

    expect(q('story-cta')).toBeNull();
    expect(host.querySelector('[href]')).toBeNull();
  });

  it('plays a video slide muted and inline, and moves on after its duration like any other', () => {
    const { host, caption, tick } = render(
      storyGroup({
        slides: [
          slide({ media: videoMedia(), caption: 'Clip', durationSeconds: 6 }),
          slide({ caption: 'After' }),
        ],
      }),
    );

    expect(host.querySelector('video')?.muted).toBe(true);
    tick(6_000);
    expect(caption()).toBe('After');
  });

  it('shows only the poster of a video slide under reduced motion, and does not move on by itself', () => {
    const { host, caption, tick } = render(
      storyGroup({
        slides: [
          slide({ media: videoMedia(), caption: 'Clip', durationSeconds: 6 }),
          slide({ caption: 'After' }),
        ],
      }),
      { reduced: true },
    );

    expect(host.querySelector('video')).toBeNull();
    expect(host.querySelector('img')?.getAttribute('src')).toBe('https://api.example.uz/m/p.jpg');
    tick(600_000);
    expect(caption()).toBe('Clip');
  });

  it('still moves an image slide on by its duration under reduced motion', () => {
    const { caption, tick } = render(THREE(), { reduced: true });

    tick(5_000);

    expect(caption()).toBe('Two');
  });

  it('skips a slide whose picture cannot be loaded', () => {
    const { host, caption, fixture } = render(THREE());

    host.querySelector('img')?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(caption()).toBe('Two');
  });

  it('closes without a completion when the last slide cannot be loaded', () => {
    const { host, events, closed, fixture } = render(storyGroup({ slides: [slide()] }));

    host.querySelector('img')?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(closed).toHaveBeenCalledTimes(1);
    expect(events.report.mock.calls.filter(([, , event]) => event === 'COMPLETION')).toEqual([]);
  });

  it('puts focus on the close button, keeps Tab inside, and gives focus back when it goes', () => {
    const { q, fixture, opener, host } = render(THREE());
    const dialog = q('story-viewer') as HTMLElement;

    expect(document.activeElement).toBe(q('story-close'));

    const buttons = [...dialog.querySelectorAll<HTMLElement>('button, a[href]')];
    buttons[buttons.length - 1].focus();
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    dialog.dispatchEvent(tab);
    expect(tab.defaultPrevented).toBe(true);
    expect(dialog.contains(document.activeElement)).toBe(true);

    fixture.destroy();
    expect(document.activeElement).toBe(opener);
    expect(host.isConnected).toBe(true);
  });

  it('stops its timer when it is closed', () => {
    const { fixture, closed } = render(THREE());

    fixture.destroy();
    vi.advanceTimersByTime(60_000);

    expect(closed).not.toHaveBeenCalled();
  });

  it('draws nothing for a slide whose media cannot be drawn, yet keeps the story going', () => {
    const { host, caption, tick } = render(
      storyGroup({
        slides: [
          slide({ media: imageMedia({ renditions: [] }), caption: 'Blank', durationSeconds: 3 }),
          slide({ caption: 'Next' }),
        ],
      }),
    );

    expect(host.querySelector('img')).toBeNull();
    expect(caption()).toBe('Blank');
    tick(3_000);
    expect(caption()).toBe('Next');
  });
});
