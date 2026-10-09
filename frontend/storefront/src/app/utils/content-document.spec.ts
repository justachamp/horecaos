import { normalizeHomeDocument } from './content-document';

const API = 'https://api.example.uz/api/v1';
const ID = (n: number) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;

function image(overrides: Record<string, unknown> = {}) {
  return {
    kind: 'IMAGE',
    alt: 'A banner',
    widthPx: 1600,
    heightPx: 533,
    renditions: [
      { widthPx: 400, url: '/api/v1/storefront/tenants/t/media/a?variant=w400' },
      { widthPx: 800, url: '/api/v1/storefront/tenants/t/media/a?variant=w800' },
    ],
    ...overrides,
  };
}

function banner(n: number, overrides: Record<string, unknown> = {}) {
  return {
    id: ID(n),
    kind: 'BANNER',
    revisionNo: 1,
    title: `Banner ${n}`,
    textLocale: 'uz-Latn',
    media: image(),
    link: { type: 'NONE' },
    ...overrides,
  };
}

function normalize(raw: unknown) {
  return normalizeHomeDocument(raw, API);
}

describe('normalizeHomeDocument: a document that is not a document is no content', () => {
  it.each([null, undefined, 'x', 42, [], true])('%j gives null', (raw) => {
    expect(normalize(raw)).toBeNull();
  });

  it('an empty object is a valid, empty document', () => {
    const home = normalize({});
    expect(home).not.toBeNull();
    expect(home?.carousel).toEqual([]);
    expect(home?.promoGrid).toEqual([]);
    expect(home?.groups).toEqual([]);
    expect(home?.stories).toEqual([]);
    expect(home?.popup).toBeNull();
  });

  it('a section that is not an array is treated as absent, not as a failure', () => {
    const home = normalize({ carousel: 'nope', groups: { a: 1 }, stories: 3, promoGrid: null });
    expect(home?.carousel).toEqual([]);
    expect(home?.groups).toEqual([]);
    expect(home?.stories).toEqual([]);
    expect(home?.promoGrid).toEqual([]);
  });
});

describe('normalizeHomeDocument: carousel items', () => {
  it('keeps the banner with its fields and resolves rendition paths against the API origin', () => {
    const home = normalize({
      locale: 'uz-Latn',
      carousel: [banner(1, { subtitle: 'Sub', ctaLabel: 'Go' })],
    });
    const item = home?.carousel[0];
    expect(item).toMatchObject({
      id: ID(1),
      kind: 'BANNER',
      revisionNo: 1,
      title: 'Banner 1',
      subtitle: 'Sub',
      ctaLabel: 'Go',
      textLocale: 'uz-Latn',
    });
    expect(item?.media).toMatchObject({ kind: 'IMAGE', alt: 'A banner' });
    expect(item?.media.kind === 'IMAGE' && item.media.renditions).toEqual([
      {
        widthPx: 400,
        url: 'https://api.example.uz/api/v1/storefront/tenants/t/media/a?variant=w400',
      },
      {
        widthPx: 800,
        url: 'https://api.example.uz/api/v1/storefront/tenants/t/media/a?variant=w800',
      },
    ]);
  });

  it('on a same-origin API the rendition paths stay site-relative', () => {
    const home = normalizeHomeDocument({ carousel: [banner(1)] }, '/api/v1');
    const media = home?.carousel[0].media;
    expect(media?.kind === 'IMAGE' && media.renditions[0].url).toBe(
      '/api/v1/storefront/tenants/t/media/a?variant=w400',
    );
  });

  it('sorts renditions ascending whatever order they arrive in', () => {
    const home = normalize({
      carousel: [
        banner(1, {
          media: image({
            renditions: [
              { widthPx: 800, url: '/m/b' },
              { widthPx: 200, url: '/m/a' },
              { widthPx: 1600, url: '/m/c' },
            ],
          }),
        }),
      ],
    });
    const media = home?.carousel[0].media;
    expect(media?.kind === 'IMAGE' && media.renditions.map((r) => r.widthPx)).toEqual([
      200, 800, 1600,
    ]);
  });

  it('uses the item title as the alt text when the media carries none', () => {
    const home = normalize({ carousel: [banner(1, { media: image({ alt: undefined }) })] });
    expect(home?.carousel[0].media.alt).toBe('Banner 1');
  });

  it('skips an item of a kind it does not know and keeps the rest, in order', () => {
    const home = normalize({
      carousel: [banner(1), banner(2, { kind: 'HOLOGRAM' }), banner(3, { kind: 'PROMO_CARD' })],
    });
    expect(home?.carousel.map((i) => i.id)).toEqual([ID(1), ID(3)]);
  });

  it('ignores a field it does not know', () => {
    const home = normalize({ carousel: [banner(1, { futureField: { a: 1 } })], futureSection: [] });
    expect(home?.carousel).toHaveLength(1);
  });

  it.each([
    ['no id', { id: undefined }],
    ['a blank id', { id: '  ' }],
    ['no revision', { revisionNo: undefined }],
    ['revision 0', { revisionNo: 0 }],
    ['a fractional revision', { revisionNo: 1.5 }],
    ['no title', { title: undefined }],
    ['no media', { media: undefined }],
  ])('skips an item with %s', (_label, overrides) => {
    const home = normalize({ carousel: [banner(1, overrides), banner(2)] });
    expect(home?.carousel.map((i) => i.id)).toEqual([ID(2)]);
  });

  it('skips an image with no usable rendition: there is nothing but the original to show', () => {
    const home = normalize({
      carousel: [
        banner(1, { media: image({ renditions: [] }) }),
        banner(2, {
          media: image({ renditions: [{ widthPx: 400, url: 'https://evil.example/a.jpg' }] }),
        }),
        banner(3, {
          media: image({ renditions: [{ widthPx: 400, url: '//evil.example/a.jpg' }] }),
        }),
        banner(4),
      ],
    });
    expect(home?.carousel.map((i) => i.id)).toEqual([ID(4)]);
  });

  it('drops a rendition whose width is not a positive integer, keeps the others', () => {
    const home = normalize({
      carousel: [
        banner(1, {
          media: image({
            renditions: [
              { widthPx: 0, url: '/m/zero' },
              { widthPx: 'wide', url: '/m/text' },
              { widthPx: 600, url: '/m/ok' },
            ],
          }),
        }),
      ],
    });
    const media = home?.carousel[0].media;
    expect(media?.kind === 'IMAGE' && media.renditions).toEqual([
      { widthPx: 600, url: 'https://api.example.uz/m/ok' },
    ]);
  });

  it('keeps a video with its mp4 and poster resolved, and skips one with no mp4', () => {
    const home = normalize({
      carousel: [
        banner(1, {
          media: {
            kind: 'VIDEO',
            alt: 'v',
            url: '/m/v.mp4',
            posterUrl: '/m/p.jpg',
            durationMs: 5000,
          },
        }),
        banner(2, { media: { kind: 'VIDEO', alt: 'v', posterUrl: '/m/p.jpg' } }),
        banner(3, { media: { kind: 'VIDEO', alt: 'v', url: 'https://evil.example/v.mp4' } }),
      ],
    });
    expect(home?.carousel).toHaveLength(1);
    expect(home?.carousel[0].media).toMatchObject({
      kind: 'VIDEO',
      url: 'https://api.example.uz/m/v.mp4',
      posterUrl: 'https://api.example.uz/m/p.jpg',
      durationMs: 5000,
    });
  });

  it('skips an item whose media kind it does not know', () => {
    const home = normalize({
      carousel: [banner(1, { media: { kind: 'AUDIO', url: '/m/a.mp3' } })],
    });
    expect(home?.carousel).toEqual([]);
  });
});

describe('normalizeHomeDocument: links', () => {
  const linkOf = (link: unknown) =>
    normalize({ carousel: [banner(1, { link })] })?.carousel[0].link;

  it('keeps a NONE link and a missing link as NONE', () => {
    expect(linkOf({ type: 'NONE' })).toEqual({ type: 'NONE' });
    expect(linkOf(undefined)).toEqual({ type: 'NONE' });
  });

  it('keeps PRODUCT and CATEGORY links that name a uuid', () => {
    expect(linkOf({ type: 'PRODUCT', targetId: ID(9) })).toEqual({
      type: 'PRODUCT',
      targetId: ID(9),
    });
    expect(linkOf({ type: 'CATEGORY', targetId: ID(8) })).toEqual({
      type: 'CATEGORY',
      targetId: ID(8),
    });
  });

  it('degrades a PRODUCT or CATEGORY link with no usable target to NONE', () => {
    expect(linkOf({ type: 'PRODUCT' })).toEqual({ type: 'NONE' });
    expect(linkOf({ type: 'CATEGORY', targetId: '../../etc' })).toEqual({ type: 'NONE' });
  });

  it('keeps CART and PROMOTION as they are (the screen decides what it can open)', () => {
    expect(linkOf({ type: 'CART' })).toEqual({ type: 'CART' });
    expect(linkOf({ type: 'PROMOTION', targetId: ID(7) })).toEqual({
      type: 'PROMOTION',
      targetId: ID(7),
    });
  });

  it('keeps an https EXTERNAL link', () => {
    expect(linkOf({ type: 'EXTERNAL', url: 'https://example.com/a' })).toEqual({
      type: 'EXTERNAL',
      url: 'https://example.com/a',
    });
  });

  it.each([
    'http://example.com/',
    'javascript:alert(1)',
    'data:text/html,x',
    'not a url',
    '',
    undefined,
  ])('degrades an EXTERNAL link to %j into NONE', (url) => {
    expect(linkOf({ type: 'EXTERNAL', url })).toEqual({ type: 'NONE' });
  });

  it('degrades a link type it does not know into NONE', () => {
    expect(linkOf({ type: 'TELEPORT', targetId: ID(1) })).toEqual({ type: 'NONE' });
  });
});

describe('normalizeHomeDocument: promoGrid', () => {
  it('holds promo cards only', () => {
    const home = normalize({
      promoGrid: [
        banner(1, { kind: 'PROMO_CARD' }),
        banner(2, { kind: 'BANNER' }),
        banner(3, { kind: 'PROMO_CARD' }),
      ],
    });
    expect(home?.promoGrid.map((i) => i.id)).toEqual([ID(1), ID(3)]);
  });
});

describe('normalizeHomeDocument: groups', () => {
  const group = (n: number, overrides: Record<string, unknown> = {}) => ({
    id: ID(n),
    revisionNo: 2,
    title: `Group ${n}`,
    textLocale: 'ru',
    productIds: [ID(101), ID(102)],
    ...overrides,
  });

  it('reads a manual group', () => {
    const home = normalize({ groups: [group(1)] });
    expect(home?.groups[0]).toMatchObject({
      id: ID(1),
      revisionNo: 2,
      title: 'Group 1',
      textLocale: 'ru',
      source: { kind: 'MANUAL', productIds: [ID(101), ID(102)] },
    });
  });

  it('reads a by-category group, defaulting the order to the menu order', () => {
    const home = normalize({
      groups: [group(1, { productIds: [], categoryId: ID(50), limit: 4 })],
    });
    expect(home?.groups[0].source).toEqual({
      kind: 'BY_CATEGORY',
      categoryId: ID(50),
      order: 'MENU_ORDER',
      limit: 4,
    });
  });

  it('keeps NEWEST and refuses an order it does not know (falls back to the menu order)', () => {
    const newest = normalize({ groups: [group(1, { categoryId: ID(50), order: 'NEWEST' })] });
    const odd = normalize({ groups: [group(2, { categoryId: ID(50), order: 'RANDOM' })] });
    expect(newest?.groups[0].source).toMatchObject({ order: 'NEWEST' });
    expect(odd?.groups[0].source).toMatchObject({ order: 'MENU_ORDER' });
  });

  it('ignores a limit that is not a positive integer', () => {
    const home = normalize({
      groups: [
        group(1, { categoryId: ID(50), limit: 0 }),
        group(2, { categoryId: ID(50), limit: 'x' }),
      ],
    });
    expect(
      home?.groups.map((g) => (g.source.kind === 'BY_CATEGORY' ? g.source.limit : 'n/a')),
    ).toEqual([undefined, undefined]);
  });

  it('drops product ids that are not strings, and a group that names nothing', () => {
    const home = normalize({
      groups: [
        group(1, { productIds: [ID(1), 7, null, ID(2)] }),
        group(2, { productIds: [] }),
        group(3, { productIds: undefined }),
      ],
    });
    expect(home?.groups).toHaveLength(1);
    expect(home?.groups[0].source).toEqual({ kind: 'MANUAL', productIds: [ID(1), ID(2)] });
  });

  it('skips a group with no title or no revision', () => {
    const home = normalize({
      groups: [group(1, { title: '' }), group(2, { revisionNo: 0 }), group(3)],
    });
    expect(home?.groups.map((g) => g.id)).toEqual([ID(3)]);
  });
});

describe('normalizeHomeDocument: stories', () => {
  const slide = (overrides: Record<string, unknown> = {}) => ({
    media: image(),
    durationSeconds: 5,
    link: { type: 'NONE' },
    ...overrides,
  });
  const story = (n: number, overrides: Record<string, unknown> = {}) => ({
    id: ID(n),
    revisionNo: 3,
    title: `Story ${n}`,
    textLocale: 'en',
    avatar: image(),
    slides: [slide()],
    ...overrides,
  });

  it('reads a story group with its slides and caption', () => {
    const home = normalize({ stories: [story(1, { slides: [slide({ caption: 'Hello' })] })] });
    expect(home?.stories[0]).toMatchObject({ id: ID(1), revisionNo: 3, title: 'Story 1' });
    expect(home?.stories[0].slides[0]).toMatchObject({ durationSeconds: 5, caption: 'Hello' });
    expect(home?.stories[0].avatar?.kind).toBe('IMAGE');
  });

  it('clamps a slide duration into 3..15 seconds', () => {
    const home = normalize({
      stories: [
        story(1, {
          slides: [
            slide({ durationSeconds: 1 }),
            slide({ durationSeconds: 99 }),
            slide({ durationSeconds: 'x' }),
          ],
        }),
      ],
    });
    expect(home?.stories[0].slides.map((s) => s.durationSeconds)).toEqual([3, 15, 5]);
  });

  it('skips a slide with unusable media and a story left with no slides', () => {
    const home = normalize({
      stories: [
        story(1, { slides: [slide({ media: { kind: 'AUDIO' } }), slide()] }),
        story(2, { slides: [slide({ media: undefined })] }),
        story(3, { slides: [] }),
      ],
    });
    expect(home?.stories.map((s) => s.id)).toEqual([ID(1)]);
    expect(home?.stories[0].slides).toHaveLength(1);
  });

  it('keeps at most ten slides', () => {
    const home = normalize({
      stories: [story(1, { slides: Array.from({ length: 14 }, () => slide()) })],
    });
    expect(home?.stories[0].slides).toHaveLength(10);
  });

  it('a story with no usable avatar still stands (the first slide stands in)', () => {
    const home = normalize({ stories: [story(1, { avatar: { kind: 'AUDIO' } })] });
    expect(home?.stories[0].avatar).toBeUndefined();
  });
});

describe('normalizeHomeDocument: popup', () => {
  const popup = (overrides: Record<string, unknown> = {}) => ({
    id: ID(1),
    revisionNo: 4,
    title: 'Hello',
    body: 'Body',
    ctaLabel: 'Go',
    textLocale: 'uz-Latn',
    link: { type: 'CART' },
    showAfterSeconds: 5,
    cap: { mode: 'ONCE_PER_DAY', n: 1 },
    dismiss: 'DISMISSIBLE',
    ...overrides,
  });

  it('reads a popup', () => {
    const home = normalize({ popup: popup() });
    expect(home?.popup).toMatchObject({
      id: ID(1),
      revisionNo: 4,
      title: 'Hello',
      body: 'Body',
      ctaLabel: 'Go',
      showAfterSeconds: 5,
      link: { type: 'CART' },
      cap: { mode: 'ONCE_PER_DAY', n: 1 },
    });
    expect(home?.popup?.daily).toBeUndefined();
    expect(home?.popup?.media).toBeUndefined();
  });

  it('reads the daily window and the media', () => {
    const home = normalize({
      popup: popup({
        daily: { from: '22:00', until: '02:30', daysOfWeek: [5, 6] },
        media: image(),
      }),
    });
    expect(home?.popup?.daily).toEqual({ from: '22:00', until: '02:30', daysOfWeek: [5, 6] });
    expect(home?.popup?.media?.kind).toBe('IMAGE');
  });

  it('clamps showAfterSeconds into 3..60', () => {
    expect(normalize({ popup: popup({ showAfterSeconds: 0 }) })?.popup?.showAfterSeconds).toBe(3);
    expect(normalize({ popup: popup({ showAfterSeconds: 600 }) })?.popup?.showAfterSeconds).toBe(
      60,
    );
  });

  it('reads every cap mode', () => {
    for (const mode of ['ONCE_EVER', 'ONCE_PER_DAY', 'EVERY_N_DAYS'] as const) {
      expect(normalize({ popup: popup({ cap: { mode, n: 3 } }) })?.popup?.cap).toEqual({
        mode,
        n: 3,
      });
    }
  });

  it('drops a popup whose cap, dismissal rule or window it cannot honour: it would otherwise show every time', () => {
    expect(normalize({ popup: popup({ cap: { mode: 'WHENEVER', n: 1 } }) })?.popup).toBeNull();
    expect(normalize({ popup: popup({ dismiss: 'STICKY' }) })?.popup).toBeNull();
    expect(
      normalize({ popup: popup({ daily: { from: '25:99', until: '02:00', daysOfWeek: [1] } }) })
        ?.popup,
    ).toBeNull();
    expect(
      normalize({ popup: popup({ daily: { from: '10:00', until: '11:00', daysOfWeek: [9] } }) })
        ?.popup,
    ).toBeNull();
  });

  it('an empty list of days means every day, as the server normalises it', () => {
    const home = normalize({
      popup: popup({ daily: { from: '10:00', until: '11:00', daysOfWeek: [] } }),
    });
    expect(home?.popup?.daily?.daysOfWeek).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it('drops a popup with no id, title or revision', () => {
    expect(normalize({ popup: popup({ id: undefined }) })?.popup).toBeNull();
    expect(normalize({ popup: popup({ title: '' }) })?.popup).toBeNull();
    expect(normalize({ popup: popup({ revisionNo: 0 }) })?.popup).toBeNull();
  });

  it('a popup that is not an object is no popup', () => {
    expect(normalize({ popup: 'x' })?.popup).toBeNull();
    expect(normalize({ popup: [] })?.popup).toBeNull();
  });
});
