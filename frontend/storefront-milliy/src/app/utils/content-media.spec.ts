import type { ContentMedia } from '../types/content.types';
import { buildSrcset, mediaAspectRatio, mediaPlan, resolveMediaUrl } from './content-media';

const IMAGE: ContentMedia = {
  kind: 'IMAGE',
  alt: 'Plov',
  widthPx: 1600,
  heightPx: 533,
  renditions: [
    { widthPx: 400, url: 'https://api.example.uz/a?variant=w400' },
    { widthPx: 800, url: 'https://api.example.uz/a?variant=w800' },
    { widthPx: 1600, url: 'https://api.example.uz/a?variant=w1600' },
  ],
};

const VIDEO: ContentMedia = {
  kind: 'VIDEO',
  renditions: [],
  url: 'https://api.example.uz/v.mp4',
  posterUrl: 'https://api.example.uz/p.jpg',
  durationMs: 8000,
};

describe('resolveMediaUrl: media is read from the platform, not from anywhere a document says', () => {
  it('keeps a site-relative path as it is when the app is served from the API origin', () => {
    expect(resolveMediaUrl('/api/v1/storefront/t/media/x?variant=w800', '/api/v1')).toBe(
      '/api/v1/storefront/t/media/x?variant=w800',
    );
  });

  it('puts a site-relative path on the origin the API client already talks to', () => {
    expect(
      resolveMediaUrl('/api/v1/storefront/t/media/x?variant=w800', 'https://api.example.uz/api/v1'),
    ).toBe('https://api.example.uz/api/v1/storefront/t/media/x?variant=w800');
  });

  it('accepts an absolute https URL', () => {
    expect(resolveMediaUrl('https://cdn.example.uz/x.jpg', '/api/v1')).toBe(
      'https://cdn.example.uz/x.jpg',
    );
  });

  it.each([
    'http://cdn.example.uz/x.jpg',
    '//evil.example/x.jpg',
    'javascript:alert(1)',
    'data:image/svg+xml;base64,AAAA',
    'relative/path.jpg',
    '',
    '   ',
  ])('refuses %j', (url) => {
    expect(resolveMediaUrl(url, '/api/v1')).toBeNull();
  });

  it('refuses a value that is not text', () => {
    expect(resolveMediaUrl(42, '/api/v1')).toBeNull();
    expect(resolveMediaUrl(null, '/api/v1')).toBeNull();
    expect(resolveMediaUrl(undefined, '/api/v1')).toBeNull();
  });
});

describe('buildSrcset', () => {
  it('lists every rendition by its width, narrowest first, whatever order they arrive in', () => {
    expect(
      buildSrcset([
        { widthPx: 800, url: '/b' },
        { widthPx: 400, url: '/a' },
        { widthPx: 1600, url: '/c' },
      ]),
    ).toBe('/a 400w, /b 800w, /c 1600w');
  });

  it('is empty when there is nothing to list', () => {
    expect(buildSrcset([])).toBe('');
  });
});

describe('mediaPlan: what is drawn for a piece of media', () => {
  it('draws an image from its renditions and falls back to the largest one, never an original', () => {
    expect(mediaPlan(IMAGE, false)).toEqual({
      kind: 'image',
      src: 'https://api.example.uz/a?variant=w1600',
      srcset:
        'https://api.example.uz/a?variant=w400 400w, https://api.example.uz/a?variant=w800 800w, https://api.example.uz/a?variant=w1600 1600w',
    });
  });

  it('falls back to the largest rendition that exists when the widest was never produced', () => {
    const plan = mediaPlan({ ...IMAGE, renditions: [{ widthPx: 800, url: '/only' }] }, false);

    expect(plan).toEqual({ kind: 'image', src: '/only', srcset: '/only 800w' });
  });

  it('draws nothing for an image with no rendition', () => {
    expect(mediaPlan({ ...IMAGE, renditions: [] }, false)).toBeNull();
  });

  it('plays a video muted inline, with its poster', () => {
    expect(mediaPlan(VIDEO, false)).toEqual({
      kind: 'video',
      src: 'https://api.example.uz/v.mp4',
      poster: 'https://api.example.uz/p.jpg',
    });
  });

  it('shows only the poster of a video when the visitor asked for reduced motion', () => {
    expect(mediaPlan(VIDEO, true)).toEqual({
      kind: 'poster',
      src: 'https://api.example.uz/p.jpg',
    });
  });

  it('draws nothing for a video under reduced motion that has no poster', () => {
    expect(mediaPlan({ ...VIDEO, posterUrl: undefined }, true)).toBeNull();
  });

  it('plays a video that has no poster when motion is fine', () => {
    expect(mediaPlan({ ...VIDEO, posterUrl: undefined }, false)).toEqual({
      kind: 'video',
      src: 'https://api.example.uz/v.mp4',
      poster: null,
    });
  });

  it('shows the poster of a video whose file is missing', () => {
    expect(mediaPlan({ ...VIDEO, url: undefined }, false)).toEqual({
      kind: 'poster',
      src: 'https://api.example.uz/p.jpg',
    });
  });

  it('draws nothing for a video with neither file nor poster', () => {
    expect(mediaPlan({ ...VIDEO, url: undefined, posterUrl: undefined }, false)).toBeNull();
  });
});

describe('mediaAspectRatio', () => {
  it('reserves the shape the brand uploaded so the page does not jump when the image arrives', () => {
    expect(mediaAspectRatio(IMAGE)).toBe('1600 / 533');
  });

  it('says nothing when the dimensions are not known', () => {
    expect(mediaAspectRatio({ ...IMAGE, widthPx: undefined })).toBeNull();
    expect(mediaAspectRatio({ ...IMAGE, heightPx: 0 })).toBeNull();
  });
});
