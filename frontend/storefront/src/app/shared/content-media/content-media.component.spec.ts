import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ReducedMotionService } from '../../services/reduced-motion.service';
import type { ContentMedia } from '../../types/content.types';
import { ContentMediaComponent } from './content-media.component';

const IMAGE: ContentMedia = {
  kind: 'IMAGE',
  alt: 'A banner',
  widthPx: 1600,
  heightPx: 533,
  renditions: [
    { widthPx: 400, url: '/m/a?variant=w400' },
    { widthPx: 800, url: '/m/a?variant=w800' },
    { widthPx: 1600, url: '/m/a?variant=w1600' },
  ],
};

const VIDEO: ContentMedia = {
  kind: 'VIDEO',
  alt: 'A clip',
  url: '/m/v.mp4',
  posterUrl: '/m/p.jpg',
  durationMs: 8000,
};

function setUp(media: ContentMedia, inputs: Record<string, unknown> = {}, reduced = false) {
  const motion = { reduced: signal(reduced) };
  TestBed.configureTestingModule({
    providers: [{ provide: ReducedMotionService, useValue: motion }],
  });
  const fixture = TestBed.createComponent(ContentMediaComponent);
  fixture.componentRef.setInput('media', media);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  const failed = vi.fn();
  fixture.componentInstance.failed.subscribe(failed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    motion,
    failed,
    img: () => host.querySelector('img'),
    video: () => host.querySelector('video'),
  };
}

describe('ContentMediaComponent: an image is a srcset of renditions, never the original', () => {
  it('builds srcset from every rendition with its width descriptor', () => {
    const { img } = setUp(IMAGE);

    expect(img()?.getAttribute('srcset')).toBe(
      '/m/a?variant=w400 400w, /m/a?variant=w800 800w, /m/a?variant=w1600 1600w',
    );
  });

  it('falls back to the largest rendition as the plain src', () => {
    const { img } = setUp(IMAGE);

    expect(img()?.getAttribute('src')).toBe('/m/a?variant=w1600');
  });

  it('works with a single rendition', () => {
    const { img } = setUp({
      ...IMAGE,
      kind: 'IMAGE',
      renditions: [{ widthPx: 800, url: '/m/only' }],
    });

    expect(img()?.getAttribute('srcset')).toBe('/m/only 800w');
    expect(img()?.getAttribute('src')).toBe('/m/only');
  });

  it('passes the sizes hint through', () => {
    const { img } = setUp(IMAGE, { sizes: '(min-width: 430px) 380px, 88vw' });

    expect(img()?.getAttribute('sizes')).toBe('(min-width: 430px) 380px, 88vw');
  });

  it('takes the alt text from the document', () => {
    const { img } = setUp(IMAGE);

    expect(img()?.getAttribute('alt')).toBe('A banner');
  });

  it('is lazy unless it is the first one on the page', () => {
    expect(setUp(IMAGE).img()?.getAttribute('loading')).toBe('lazy');
    TestBed.resetTestingModule();
    expect(setUp(IMAGE, { eager: true }).img()?.getAttribute('loading')).toBe('eager');
  });

  it('keeps the intrinsic size so the page does not jump as it loads', () => {
    const { img } = setUp(IMAGE);

    expect(img()?.getAttribute('width')).toBe('1600');
    expect(img()?.getAttribute('height')).toBe('533');
  });

  it('says it failed when the image does not load', () => {
    const { img, failed } = setUp(IMAGE);

    img()?.dispatchEvent(new Event('error'));

    expect(failed).toHaveBeenCalledTimes(1);
  });
});

describe('ContentMediaComponent: video is muted, inline and metadata-only', () => {
  it('renders a muted, playsinline video that preloads metadata only, with its poster', () => {
    const { video } = setUp(VIDEO);

    const el = video();
    expect(el).not.toBeNull();
    expect(el?.hasAttribute('muted')).toBe(true);
    expect(el?.muted).toBe(true);
    expect(el?.hasAttribute('playsinline')).toBe(true);
    expect(el?.getAttribute('preload')).toBe('metadata');
    expect(el?.getAttribute('poster')).toBe('/m/p.jpg');
    expect(el?.getAttribute('src')).toBe('/m/v.mp4');
  });

  it('autoplays and loops by default, and does not loop when asked', () => {
    const looping = setUp(VIDEO).video();
    expect(looping?.hasAttribute('loop')).toBe(true);
    expect(looping?.hasAttribute('autoplay')).toBe(true);
    TestBed.resetTestingModule();
    expect(setUp(VIDEO, { loop: false }).video()?.hasAttribute('loop')).toBe(false);
  });

  it('says it failed when the video does not load', () => {
    const { video, failed } = setUp(VIDEO);

    video()?.dispatchEvent(new Event('error'));

    expect(failed).toHaveBeenCalledTimes(1);
  });

  it('under prefers-reduced-motion shows the poster and no <video> at all', () => {
    const { video, img } = setUp(VIDEO, {}, true);

    expect(video()).toBeNull();
    expect(img()?.getAttribute('src')).toBe('/m/p.jpg');
    expect(img()?.getAttribute('alt')).toBe('A clip');
  });

  it('under reduced motion with no poster there is nothing to show, and it says so', () => {
    const { video, img, failed } = setUp(
      { ...VIDEO, kind: 'VIDEO', posterUrl: undefined },
      {},
      true,
    );

    expect(video()).toBeNull();
    expect(img()).toBeNull();
    expect(failed).toHaveBeenCalledTimes(1);
  });

  it('posterOnly never plays, whatever the motion setting is', () => {
    const { video, img } = setUp(VIDEO, { posterOnly: true });

    expect(video()).toBeNull();
    expect(img()?.getAttribute('src')).toBe('/m/p.jpg');
  });

  it('starts playing when reduced motion is switched off', () => {
    const { fixture, motion, video } = setUp(VIDEO, {}, true);
    expect(video()).toBeNull();

    motion.reduced.set(false);
    fixture.detectChanges();

    expect(video()).not.toBeNull();
  });
});
