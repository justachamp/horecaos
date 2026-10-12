import { TestBed } from '@angular/core/testing';

import type { MediaPlan } from '../../utils/content-media';
import { ContentMediaComponent } from './content-media.component';

const IMAGE: MediaPlan = {
  kind: 'image',
  src: 'https://api.example.uz/m/a?variant=w1600',
  srcset:
    'https://api.example.uz/m/a?variant=w400 400w, https://api.example.uz/m/a?variant=w1600 1600w',
};
const POSTER: MediaPlan = { kind: 'poster', src: 'https://api.example.uz/m/p.jpg' };
const VIDEO: MediaPlan = {
  kind: 'video',
  src: 'https://api.example.uz/m/v.mp4',
  poster: 'https://api.example.uz/m/p.jpg',
};

function render(plan: MediaPlan, inputs: Record<string, unknown> = {}) {
  const fixture = TestBed.createComponent(ContentMediaComponent);
  fixture.componentRef.setInput('plan', plan);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  const failed = vi.fn();
  fixture.componentInstance.failed.subscribe(failed);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, failed };
}

describe('ContentMediaComponent', () => {
  it('draws an image from its renditions, with the brand’s own description', () => {
    const { host } = render(IMAGE, {
      alt: 'A plate of plov',
      sizes: '(min-width: 768px) 50vw, 85vw',
    });
    const img = host.querySelector('img');

    expect(img?.getAttribute('srcset')).toBe(IMAGE.kind === 'image' ? IMAGE.srcset : '');
    expect(img?.getAttribute('sizes')).toBe('(min-width: 768px) 50vw, 85vw');
    expect(img?.getAttribute('src')).toBe('https://api.example.uz/m/a?variant=w1600');
    expect(img?.getAttribute('alt')).toBe('A plate of plov');
    expect(img?.getAttribute('decoding')).toBe('async');
  });

  it('is decorative, with an empty alt, when the brand described nothing', () => {
    const { host } = render(IMAGE);

    expect(host.querySelector('img')?.getAttribute('alt')).toBe('');
  });

  it('loads an image lazily, except the first one on the page', () => {
    expect(render(IMAGE).host.querySelector('img')?.getAttribute('loading')).toBe('lazy');

    TestBed.resetTestingModule();
    expect(render(IMAGE, { eager: true }).host.querySelector('img')?.getAttribute('loading')).toBe(
      'eager',
    );
  });

  it('reserves the shape of the image so the page does not jump when it arrives', () => {
    const { host } = render(IMAGE, { aspectRatio: '1600 / 533' });

    expect(host.style.getPropertyValue('--media-aspect')).toBe('1600 / 533');
  });

  it('reserves nothing when the shape is not known', () => {
    const { host } = render(IMAGE);

    expect(host.style.getPropertyValue('--media-aspect')).toBe('');
  });

  it('shows a poster as an image and creates no video element', () => {
    const { host } = render(POSTER, { alt: 'Plov being made' });

    expect(host.querySelector('img')?.getAttribute('src')).toBe('https://api.example.uz/m/p.jpg');
    expect(host.querySelector('video')).toBeNull();
  });

  it('plays a video muted and inline, loading only its metadata, with its poster', () => {
    const { host } = render(VIDEO, { alt: 'Plov being made' });
    const video = host.querySelector('video');

    expect(video).not.toBeNull();
    expect(video?.muted).toBe(true);
    expect(video?.hasAttribute('playsinline')).toBe(true);
    expect(video?.getAttribute('preload')).toBe('metadata');
    expect(video?.getAttribute('poster')).toBe('https://api.example.uz/m/p.jpg');
    expect(video?.getAttribute('src')).toBe('https://api.example.uz/m/v.mp4');
    expect(video?.hasAttribute('autoplay')).toBe(true);
    expect(video?.hasAttribute('loop')).toBe(true);
    expect(video?.hasAttribute('controls')).toBe(false);
    expect(video?.getAttribute('aria-label')).toBe('Plov being made');
  });

  it('plays a video that has no poster without a poster attribute', () => {
    const { host } = render({ kind: 'video', src: 'https://api.example.uz/m/v.mp4', poster: null });

    expect(host.querySelector('video')?.hasAttribute('poster')).toBe(false);
  });

  it('says so when an image cannot be loaded', () => {
    const { host, failed } = render(IMAGE);

    host.querySelector('img')?.dispatchEvent(new Event('error'));

    expect(failed).toHaveBeenCalledTimes(1);
  });

  it('says so when a video cannot be loaded', () => {
    const { host, failed } = render(VIDEO);

    host.querySelector('video')?.dispatchEvent(new Event('error'));

    expect(failed).toHaveBeenCalledTimes(1);
  });
});
