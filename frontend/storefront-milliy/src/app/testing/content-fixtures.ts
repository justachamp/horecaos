import type {
  ContentCarouselItem,
  ContentGroup,
  ContentMedia,
  ContentPopup,
  ContentStoryGroup,
  ContentStorySlide,
} from '../types/content.types';

/**
 * Builders for the content specs. Data only -- no test-runner globals -- so the file type-checks in
 * the application build too, where nothing imports it.
 */

export const uuid = (n: number): string => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

export function imageMedia(overrides: Partial<ContentMedia> = {}): ContentMedia {
  return {
    kind: 'IMAGE',
    alt: 'A plate of plov',
    widthPx: 1600,
    heightPx: 533,
    renditions: [
      { widthPx: 400, url: 'https://api.example.uz/m/a?variant=w400' },
      { widthPx: 800, url: 'https://api.example.uz/m/a?variant=w800' },
      { widthPx: 1600, url: 'https://api.example.uz/m/a?variant=w1600' },
    ],
    ...overrides,
  };
}

export function videoMedia(overrides: Partial<ContentMedia> = {}): ContentMedia {
  return {
    kind: 'VIDEO',
    alt: 'Plov being made',
    renditions: [],
    url: 'https://api.example.uz/m/v.mp4',
    posterUrl: 'https://api.example.uz/m/p.jpg',
    durationMs: 8000,
    ...overrides,
  };
}

export function carouselItem(overrides: Partial<ContentCarouselItem> = {}): ContentCarouselItem {
  return {
    id: uuid(1),
    kind: 'BANNER',
    revisionNo: 3,
    title: 'Plov week',
    textLocale: 'uz-Latn',
    media: imageMedia(),
    link: { type: 'NONE' },
    ...overrides,
  };
}

export function contentGroup(overrides: Partial<ContentGroup> = {}): ContentGroup {
  return {
    id: uuid(50),
    revisionNo: 2,
    title: 'Popular',
    textLocale: 'uz-Latn',
    productIds: [],
    ...overrides,
  };
}

export function slide(overrides: Partial<ContentStorySlide> = {}): ContentStorySlide {
  return {
    media: imageMedia(),
    durationSeconds: 5,
    link: { type: 'NONE' },
    ...overrides,
  };
}

export function storyGroup(overrides: Partial<ContentStoryGroup> = {}): ContentStoryGroup {
  return {
    id: uuid(70),
    revisionNo: 1,
    title: 'Today',
    textLocale: 'uz-Latn',
    slides: [slide(), slide({ caption: 'Second' })],
    ...overrides,
  };
}

export function contentPopup(overrides: Partial<ContentPopup> = {}): ContentPopup {
  return {
    id: uuid(90),
    revisionNo: 1,
    title: 'Welcome',
    body: 'Free delivery today',
    ctaLabel: 'Order now',
    textLocale: 'uz-Latn',
    link: { type: 'CART' },
    showAfterSeconds: 5,
    cap: { mode: 'ONCE_PER_DAY', n: 1 },
    ...overrides,
  };
}
