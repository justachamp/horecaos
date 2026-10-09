import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentStoryGroup } from '../../types/content.types';
import { ContentStoriesComponent } from './content-stories.component';

function story(n: number, overrides: Partial<ContentStoryGroup> = {}): ContentStoryGroup {
  return {
    id: `story-${n}`,
    revisionNo: n,
    title: `Story ${n}`,
    textLocale: 'en',
    avatar: {
      kind: 'IMAGE',
      alt: `Avatar ${n}`,
      renditions: [{ widthPx: 200, url: `/m/a${n}?variant=w200` }],
    },
    slides: [
      {
        media: {
          kind: 'IMAGE',
          alt: `Slide ${n}`,
          renditions: [{ widthPx: 800, url: `/m/s${n}?variant=w800` }],
        },
        durationSeconds: 5,
        link: { type: 'NONE' },
      },
    ],
    ...overrides,
  };
}

class FakeEvents {
  impression = vi.fn();
  click = vi.fn();
  completion = vi.fn();
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

async function render(stories: ContentStoryGroup[]) {
  const events = new FakeEvents();
  TestBed.configureTestingModule({
    imports: [ContentStoriesComponent],
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: ContentEventsService, useValue: events },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: { reduced: signal(false) } },
    ],
  });
  // The viewer is behind @defer: its dependency chunk has to be resolvable before the rail is made.
  await TestBed.compileComponents();
  const fixture = TestBed.createComponent(ContentStoriesComponent);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('stories', stories);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  return {
    fixture,
    host,
    events,
    settle,
    buttons: () => Array.from(host.querySelectorAll<HTMLButtonElement>('section button')),
    dialog: () => host.querySelector<HTMLElement>('[role="dialog"]'),
  };
}

describe('ContentStoriesComponent', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('is a labelled region with a round avatar button per story', async () => {
    const { host, buttons } = await render([story(1), story(2)]);

    expect(host.querySelector('section')?.getAttribute('aria-label')).toBe('content.stories');
    expect(buttons()).toHaveLength(2);
    expect(buttons()[0].getAttribute('aria-label')).toBe('content.storyOpen|Story 1');
    expect(buttons()[0].textContent).toContain('Story 1');
  });

  it('draws the avatar from its renditions, and falls back to the first slide when there is none', async () => {
    const { host } = await render([story(1), story(2, { avatar: undefined })]);

    const images = Array.from(host.querySelectorAll('section img'));
    expect(images[0].getAttribute('srcset')).toBe('/m/a1?variant=w200 200w');
    expect(images[1].getAttribute('srcset')).toBe('/m/s2?variant=w800 800w');
  });

  it('a video avatar is its poster only, never a playing video', async () => {
    const { host } = await render([
      story(1, { avatar: { kind: 'VIDEO', alt: 'v', url: '/m/v.mp4', posterUrl: '/m/p.jpg' } }),
    ]);

    expect(host.querySelector('section video')).toBeNull();
    expect(host.querySelector('section img')?.getAttribute('src')).toBe('/m/p.jpg');
  });

  it('an avatar that fails to load is replaced by the first letter of the title; the story stays', async () => {
    const { fixture, host, buttons } = await render([story(1)]);

    host.querySelector('section img')?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(host.querySelector('section img')).toBeNull();
    expect(buttons()).toHaveLength(1);
    expect(host.querySelector('[data-testid="story-initial"]')?.textContent?.trim()).toBe('S');
  });

  it('renders nothing when there are no stories', async () => {
    const { host } = await render([]);

    expect(host.querySelector('section')).toBeNull();
  });

  it('counts an impression of each story and revision as it is shown', async () => {
    const { events } = await render([story(1), story(2)]);

    expect(events.impression).toHaveBeenCalledWith('story-1', 1);
    expect(events.impression).toHaveBeenCalledWith('story-2', 2);
  });

  it('opens a full-screen viewer for the tapped story and counts a click', async () => {
    const { buttons, dialog, events, settle } = await render([story(1), story(2)]);
    expect(dialog()).toBeNull();

    buttons()[1].click();
    await settle();

    expect(dialog()?.getAttribute('aria-label')).toBe('Story 2');
    expect(events.click).toHaveBeenCalledWith('story-2', 2);
  });

  it('closes the viewer and gives focus back to the story that was tapped', async () => {
    const { host, buttons, dialog, settle } = await render([story(1)]);
    buttons()[0].focus();
    buttons()[0].click();
    await settle();

    host.querySelector<HTMLButtonElement>('button[aria-label="content.storyClose"]')?.click();
    await settle();

    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(buttons()[0]);
  });

  it('can be opened again after it was closed', async () => {
    const { host, buttons, dialog, settle } = await render([story(1)]);

    buttons()[0].click();
    await settle();
    host.querySelector<HTMLButtonElement>('button[aria-label="content.storyClose"]')?.click();
    await settle();
    buttons()[0].click();
    await settle();

    expect(dialog()).not.toBeNull();
  });

  it('a story watched to the end is reported as completed', async () => {
    const { host, buttons, events, dialog, settle } = await render([story(1)]);
    buttons()[0].click();
    await settle();

    host.querySelector<HTMLButtonElement>('button[aria-label="content.storyNext"]')?.click();
    await settle();

    expect(events.completion).toHaveBeenCalledWith('story-1', 1);
    expect(dialog()).toBeNull();
  });
});
