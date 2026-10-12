import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentStoryGroup } from '../../types/content.types';
import { imageMedia, slide, storyGroup, uuid, videoMedia } from '../../testing/content-fixtures';
import { ContentStoriesComponent } from './content-stories.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string, params?: Record<string, string | number>) =>
    params ? `${key}|${Object.values(params).join('|')}` : key;
  current = () => ({});
}

function render(stories: readonly ContentStoryGroup[], reduced = false) {
  const events = { report: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ContentEventsService, useValue: events },
      { provide: ReducedMotionService, useValue: { reduced: signal(reduced) } },
    ],
  });
  const fixture = TestBed.createComponent(ContentStoriesComponent);
  fixture.componentRef.setInput('stories', stories);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const buttons = () => [...host.querySelectorAll<HTMLButtonElement>('[data-testid="story-open"]')];
  const viewer = () => host.querySelector('[data-testid="story-viewer"]');
  return { fixture, host, events, buttons, viewer };
}

afterEach(() => {
  document.body.replaceChildren();
});

describe('ContentStoriesComponent: the rail of round avatars', () => {
  it('draws nothing when there are no stories', () => {
    const { host } = render([]);

    expect(host.querySelector('[data-testid="content-stories"]')).toBeNull();
  });

  it('is a labelled region with one named button per story', () => {
    const { host, buttons } = render([
      storyGroup({ id: uuid(70), title: 'Today' }),
      storyGroup({ id: uuid(71), title: 'Chef' }),
    ]);

    expect(host.querySelector('[data-testid="content-stories"]')?.getAttribute('aria-label')).toBe(
      'content.storiesLabel',
    );
    expect(buttons().map((button) => button.getAttribute('aria-label'))).toEqual([
      'content.storyOpen|Today',
      'content.storyOpen|Chef',
    ]);
    expect(buttons().map((button) => button.textContent?.trim())).toEqual([
      expect.stringContaining('Today'),
      expect.stringContaining('Chef'),
    ]);
    expect(buttons().every((button) => button.type === 'button')).toBe(true);
  });

  it('draws the avatar, and falls back to the first slide when there is none', () => {
    const { buttons } = render([
      storyGroup({
        id: uuid(70),
        avatar: imageMedia({
          renditions: [{ widthPx: 96, url: 'https://api.example.uz/m/av?variant=w96' }],
        }),
      }),
      storyGroup({ id: uuid(71), avatar: undefined, slides: [slide()] }),
    ]);

    expect(buttons()[0].querySelector('img')?.getAttribute('src')).toBe(
      'https://api.example.uz/m/av?variant=w96',
    );
    expect(buttons()[1].querySelector('img')?.getAttribute('src')).toContain('variant=w1600');
  });

  it('uses the poster of a video as its avatar and never plays a video in the rail', () => {
    const { buttons } = render([
      storyGroup({ avatar: undefined, slides: [slide({ media: videoMedia() })] }),
    ]);

    expect(buttons()[0].querySelector('video')).toBeNull();
    expect(buttons()[0].querySelector('img')?.getAttribute('src')).toBe(
      'https://api.example.uz/m/p.jpg',
    );
  });

  it('shows the first letter of the title when no picture can be drawn', () => {
    const { fixture, buttons } = render([storyGroup({ title: 'Chef', avatar: undefined })]);

    buttons()[0].querySelector('img')?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(buttons()[0].querySelector('img')).toBeNull();
    expect(buttons()[0].querySelector('[data-testid="story-initial"]')?.textContent?.trim()).toBe(
      'C',
    );
  });

  it('draws a title as text, never as markup', () => {
    const { host } = render([storyGroup({ title: '<b>Hot</b>' })]);

    expect(host.querySelector('[data-testid="content-stories"] b')).toBeNull();
    expect(host.textContent).toContain('<b>Hot</b>');
  });

  it('reports an impression of each story group', () => {
    const { events } = render([
      storyGroup({ id: uuid(70), revisionNo: 2 }),
      storyGroup({ id: uuid(71), revisionNo: 5 }),
    ]);

    expect(events.report).toHaveBeenCalledWith(uuid(70), 2, 'IMPRESSION');
    expect(events.report).toHaveBeenCalledWith(uuid(71), 5, 'IMPRESSION');
  });

  it('opens the viewer on the story that was tapped', () => {
    const { fixture, buttons, viewer } = render([
      storyGroup({ id: uuid(70), title: 'Today', slides: [slide({ caption: 'Today slide' })] }),
      storyGroup({ id: uuid(71), title: 'Chef', slides: [slide({ caption: 'Chef slide' })] }),
    ]);
    expect(viewer()).toBeNull();

    buttons()[1].click();
    fixture.detectChanges();

    expect(viewer()).not.toBeNull();
    expect(viewer()?.textContent).toContain('Chef slide');
    expect(viewer()?.getAttribute('aria-label')).toBe('Chef');
  });

  it('closes the viewer, and puts focus back on the story that was tapped', () => {
    const { fixture, buttons, viewer } = render([storyGroup({ id: uuid(70) })]);
    buttons()[0].focus();
    buttons()[0].click();
    fixture.detectChanges();

    viewer()?.querySelector<HTMLElement>('[data-testid="story-close"]')?.click();
    fixture.detectChanges();

    expect(viewer()).toBeNull();
    expect(document.activeElement).toBe(buttons()[0]);
  });

  it('closes the viewer when the story it shows is no longer published', () => {
    const { fixture, buttons, viewer } = render([storyGroup({ id: uuid(70) })]);
    buttons()[0].click();
    fixture.detectChanges();

    fixture.componentRef.setInput('stories', []);
    fixture.detectChanges();

    expect(viewer()).toBeNull();
  });
});
