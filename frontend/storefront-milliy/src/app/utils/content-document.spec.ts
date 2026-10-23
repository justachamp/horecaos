import { NO_CONTENT } from '../types/content.types';
import { parseContentHome } from './content-document';

const API = 'https://api.example.uz';
const resolveUrl = (url: unknown): string | null =>
  typeof url === 'string' && url.startsWith('/') && !url.startsWith('//') ? `${API}${url}` : null;

const ID1 = '10000000-0000-0000-0000-000000000001';
const ID2 = '10000000-0000-0000-0000-000000000002';
const ID3 = '10000000-0000-0000-0000-000000000003';

function image(extra: Record<string, unknown> = {}) {
  return {
    kind: 'IMAGE',
    alt: 'A plate of plov',
    widthPx: 1600,
    heightPx: 533,
    renditions: [
      { widthPx: 800, url: '/media/a?variant=w800' },
      { widthPx: 400, url: '/media/a?variant=w400' },
    ],
    ...extra,
  };
}

function banner(overrides: Record<string, unknown> = {}) {
  return {
    id: ID1,
    kind: 'BANNER',
    revisionNo: 3,
    title: 'Plov week',
    subtitle: 'Every day',
    ctaLabel: 'Order',
    textLocale: 'uz-Latn',
    media: image(),
    link: { type: 'PRODUCT', targetId: ID2 },
    ...overrides,
  };
}

const parse = (raw: unknown) => parseContentHome(raw, resolveUrl);

describe('parseContentHome: the document, read tolerantly', () => {
  it('reads a banner whole, with its renditions resolved and narrowest first', () => {
    const home = parse({ locale: 'uz-Latn', carousel: [banner()], groups: [] });

    expect(home.locale).toBe('uz-Latn');
    expect(home.carousel).toEqual([
      {
        id: ID1,
        kind: 'BANNER',
        revisionNo: 3,
        title: 'Plov week',
        subtitle: 'Every day',
        ctaLabel: 'Order',
        textLocale: 'uz-Latn',
        media: {
          kind: 'IMAGE',
          alt: 'A plate of plov',
          widthPx: 1600,
          heightPx: 533,
          renditions: [
            { widthPx: 400, url: `${API}/media/a?variant=w400` },
            { widthPx: 800, url: `${API}/media/a?variant=w800` },
          ],
        },
        link: { type: 'PRODUCT', targetId: ID2 },
      },
    ]);
  });

  it('reads a document with nothing in it as no content', () => {
    expect(parse({})).toEqual({ ...NO_CONTENT });
    expect(parse({ locale: 'ru', carousel: [], groups: [] })).toEqual({
      ...NO_CONTENT,
      locale: 'ru',
    });
  });

  it.each([null, undefined, 'text', 42, [], true])(
    'refuses a body that is not an object (%j)',
    (raw) => {
      expect(() => parse(raw)).toThrow();
    },
  );

  it('ignores a field it does not know, at the top and inside an item', () => {
    const home = parse({
      carousel: [banner({ futureField: { a: 1 } })],
      somethingNew: [1, 2, 3],
    });

    expect(home.carousel).toHaveLength(1);
    expect(home.carousel[0]).not.toHaveProperty('futureField');
  });

  it('skips a carousel item of a kind it does not know and keeps the others in order', () => {
    const home = parse({
      carousel: [
        banner({ id: ID1 }),
        banner({ id: ID2, kind: 'HOLOGRAM' }),
        banner({ id: ID3, kind: 'PROMO_CARD' }),
      ],
    });

    expect(home.carousel.map((item) => item.id)).toEqual([ID1, ID3]);
  });

  it.each([
    ['no id', { id: undefined }],
    ['no title', { title: undefined }],
    ['a title that is not text', { title: 7 }],
    ['no revision', { revisionNo: undefined }],
    ['a revision of zero', { revisionNo: 0 }],
    ['no media', { media: undefined }],
    ['media of a kind it does not know', { media: { kind: 'HOLOGRAM' } }],
    ['an image with no rendition', { media: image({ renditions: [] }) }],
    [
      'an image whose renditions are all unusable',
      { media: image({ renditions: [{ widthPx: 800, url: 'javascript:alert(1)' }] }) },
    ],
  ])('skips a banner with %s', (_label, overrides) => {
    const home = parse({ carousel: [banner(overrides), banner({ id: ID3 })] });

    expect(home.carousel.map((item) => item.id)).toEqual([ID3]);
  });

  it('drops a rendition it cannot use and keeps the rest of the image', () => {
    const home = parse({
      carousel: [
        banner({
          media: image({
            renditions: [
              { widthPx: 800, url: '/media/a?variant=w800' },
              { widthPx: 0, url: '/media/zero' },
              { widthPx: 1600, url: 'http://insecure.example/x' },
              { widthPx: 'wide', url: '/media/wide' },
            ],
          }),
        }),
      ],
    });

    expect(home.carousel[0].media.renditions).toEqual([
      { widthPx: 800, url: `${API}/media/a?variant=w800` },
    ]);
  });

  it('drops a rendition whose address would break a srcset list (a space or a comma)', () => {
    const home = parse({
      carousel: [
        banner({
          media: image({
            renditions: [
              { widthPx: 400, url: '/media/a?variant=w400' },
              { widthPx: 800, url: '/media/with space' },
              { widthPx: 1600, url: '/media/a,b' },
            ],
          }),
        }),
      ],
    });

    expect(home.carousel[0].media.renditions.map((rendition) => rendition.widthPx)).toEqual([400]);
  });

  it('turns a link type it does not know into no link, and keeps the item', () => {
    const home = parse({ carousel: [banner({ link: { type: 'TELEPORT', targetId: ID2 } })] });

    expect(home.carousel).toHaveLength(1);
    expect(home.carousel[0].link).toEqual({ type: 'NONE' });
  });

  it('keeps a missing link as no link', () => {
    const home = parse({ carousel: [banner({ link: undefined })] });

    expect(home.carousel[0].link).toEqual({ type: 'NONE' });
  });

  it('keeps optional texts only when they are text', () => {
    const home = parse({ carousel: [banner({ subtitle: 9, ctaLabel: null })] });

    expect(home.carousel[0].subtitle).toBeUndefined();
    expect(home.carousel[0].ctaLabel).toBeUndefined();
  });

  it('keeps markup in a title as text for the template to interpolate, never as anything else', () => {
    const home = parse({ carousel: [banner({ title: '<img src=x onerror=alert(1)>' })] });

    expect(home.carousel[0].title).toBe('<img src=x onerror=alert(1)>');
  });

  it('reads a video with its file and poster resolved', () => {
    const home = parse({
      carousel: [
        banner({
          media: {
            kind: 'VIDEO',
            url: '/media/v.mp4',
            posterUrl: '/media/p.jpg',
            durationMs: 8000,
          },
        }),
      ],
    });

    expect(home.carousel[0].media).toMatchObject({
      kind: 'VIDEO',
      url: `${API}/media/v.mp4`,
      posterUrl: `${API}/media/p.jpg`,
      durationMs: 8000,
      renditions: [],
    });
  });

  it('keeps a video that has only a poster, and skips one with neither file nor poster', () => {
    const home = parse({
      carousel: [
        banner({ id: ID1, media: { kind: 'VIDEO', posterUrl: '/media/p.jpg' } }),
        banner({ id: ID2, media: { kind: 'VIDEO', url: 'http://insecure/v.mp4' } }),
      ],
    });

    expect(home.carousel.map((item) => item.id)).toEqual([ID1]);
  });

  it('reads the promo grid like the carousel', () => {
    const home = parse({ promoGrid: [banner({ kind: 'PROMO_CARD' }), banner({ kind: 'X' })] });

    expect(home.promoGrid).toHaveLength(1);
    expect(home.promoGrid[0].kind).toBe('PROMO_CARD');
  });
});

describe('parseContentHome: groups', () => {
  const group = (overrides: Record<string, unknown> = {}) => ({
    id: ID1,
    revisionNo: 2,
    title: 'Popular',
    textLocale: 'ru',
    productIds: [ID2, ID3],
    ...overrides,
  });

  it('reads a manual group', () => {
    expect(parse({ groups: [group()] }).groups).toEqual([
      { id: ID1, revisionNo: 2, title: 'Popular', textLocale: 'ru', productIds: [ID2, ID3] },
    ]);
  });

  it('reads a by-category group with its order and limit', () => {
    expect(
      parse({
        groups: [group({ productIds: [], categoryId: ID2, order: 'NEWEST', limit: 6 })],
      }).groups[0],
    ).toMatchObject({ categoryId: ID2, order: 'NEWEST', limit: 6, productIds: [] });
  });

  it('forgets an order it does not know and a limit that is no use', () => {
    const read = parse({ groups: [group({ categoryId: ID2, order: 'RANDOM', limit: 0 })] })
      .groups[0];

    expect(read.order).toBeUndefined();
    expect(read.limit).toBeUndefined();
  });

  it('keeps only the ids that are text, and reads a missing list as empty', () => {
    expect(
      parse({ groups: [group({ productIds: [ID2, 7, null, ID3] })] }).groups[0].productIds,
    ).toEqual([ID2, ID3]);
    expect(parse({ groups: [group({ productIds: undefined })] }).groups[0].productIds).toEqual([]);
  });

  it('skips a group with no title or no revision', () => {
    expect(
      parse({ groups: [group({ title: undefined }), group({ revisionNo: undefined })] }).groups,
    ).toEqual([]);
  });
});

describe('parseContentHome: stories', () => {
  const slide = (overrides: Record<string, unknown> = {}) => ({
    media: image(),
    durationSeconds: 5,
    caption: 'Fresh',
    link: { type: 'CART' },
    ...overrides,
  });
  const story = (overrides: Record<string, unknown> = {}) => ({
    id: ID1,
    revisionNo: 1,
    title: 'Today',
    textLocale: 'uz-Latn',
    avatar: image({ renditions: [{ widthPx: 96, url: '/media/av?variant=w96' }] }),
    slides: [slide()],
    ...overrides,
  });

  it('reads a story group with its slides', () => {
    const read = parse({ stories: [story()] }).stories[0];

    expect(read).toMatchObject({ id: ID1, revisionNo: 1, title: 'Today' });
    expect(read.avatar?.renditions).toEqual([{ widthPx: 96, url: `${API}/media/av?variant=w96` }]);
    expect(read.slides).toHaveLength(1);
    expect(read.slides[0]).toMatchObject({
      durationSeconds: 5,
      caption: 'Fresh',
      link: { type: 'CART' },
    });
  });

  it('keeps a group whose avatar is unusable, without the avatar', () => {
    const read = parse({ stories: [story({ avatar: { kind: 'IMAGE', renditions: [] } })] }).stories;

    expect(read).toHaveLength(1);
    expect(read[0].avatar).toBeUndefined();
  });

  it('skips a slide whose media is unusable and a group left with no slide', () => {
    const read = parse({
      stories: [
        story({ id: ID1, slides: [slide({ media: { kind: 'HOLOGRAM' } })] }),
        story({ id: ID2, slides: [slide({ media: { kind: 'HOLOGRAM' } }), slide()] }),
      ],
    }).stories;

    expect(read.map((group) => group.id)).toEqual([ID2]);
    expect(read[0].slides).toHaveLength(1);
  });

  it.each([
    [1, 3],
    [20, 15],
    [7.6, 7],
    [undefined, 5],
    ['long', 5],
  ])('reads a slide duration of %j as %i seconds, inside 3..15', (given, expected) => {
    const read = parse({ stories: [story({ slides: [slide({ durationSeconds: given })] })] });

    expect(read.stories[0].slides[0].durationSeconds).toBe(expected);
  });
});

describe('parseContentHome: the pop-up', () => {
  const popup = (overrides: Record<string, unknown> = {}) => ({
    id: ID1,
    revisionNo: 2,
    title: 'Welcome',
    body: 'Free delivery today',
    ctaLabel: 'Go',
    textLocale: 'uz-Latn',
    media: image(),
    link: { type: 'CART' },
    showAfterSeconds: 5,
    daily: { from: '09:00', until: '21:00', daysOfWeek: [1, 2, 3] },
    cap: { mode: 'EVERY_N_DAYS', n: 3 },
    dismiss: 'DISMISSIBLE',
    ...overrides,
  });

  it('reads a pop-up whole', () => {
    expect(parse({ popup: popup() }).popup).toMatchObject({
      id: ID1,
      revisionNo: 2,
      title: 'Welcome',
      body: 'Free delivery today',
      ctaLabel: 'Go',
      showAfterSeconds: 5,
      daily: { from: '09:00', until: '21:00', daysOfWeek: [1, 2, 3] },
      cap: { mode: 'EVERY_N_DAYS', n: 3 },
      link: { type: 'CART' },
    });
  });

  it('has no pop-up when the document names none', () => {
    expect(parse({}).popup).toBeNull();
  });

  it('reads a pop-up with no daily window, no media and no body', () => {
    const read = parse({
      popup: popup({ daily: undefined, media: undefined, body: undefined, ctaLabel: undefined }),
    }).popup;

    expect(read).not.toBeNull();
    expect(read?.daily).toBeUndefined();
    expect(read?.media).toBeUndefined();
  });

  it.each([
    [1, 3],
    [90, 60],
    [12.4, 12],
    [undefined, 3],
  ])('clamps a delay of %j to %i seconds, inside 3..60', (given, expected) => {
    expect(parse({ popup: popup({ showAfterSeconds: given }) }).popup?.showAfterSeconds).toBe(
      expected,
    );
  });

  it('reads a cap days count below one as one', () => {
    expect(parse({ popup: popup({ cap: { mode: 'EVERY_N_DAYS', n: 0 } }) }).popup?.cap).toEqual({
      mode: 'EVERY_N_DAYS',
      n: 1,
    });
  });

  it.each([
    ['a cap mode it does not know', { cap: { mode: 'WHENEVER', n: 1 } }],
    ['no cap', { cap: undefined }],
    ['a daily window it cannot read', { daily: { from: 'dawn', until: '21:00', daysOfWeek: [1] } }],
    [
      'a daily window with no day',
      { daily: { from: '09:00', until: '21:00', daysOfWeek: [0, 8] } },
    ],
    ['no title', { title: undefined }],
    ['no revision', { revisionNo: undefined }],
  ])(
    'drops a pop-up with %s: it cannot be shown under rules the client cannot keep',
    (_label, overrides) => {
      expect(parse({ popup: popup(overrides) }).popup).toBeNull();
    },
  );

  it('keeps only the days of the week that exist', () => {
    const read = parse({
      popup: popup({ daily: { from: '09:00', until: '21:00', daysOfWeek: [0, 1, 7, 8, 'x'] } }),
    }).popup;

    expect(read?.daily?.daysOfWeek).toEqual([1, 7]);
  });

  it('keeps a pop-up whose media is unusable, without the media', () => {
    const read = parse({ popup: popup({ media: { kind: 'IMAGE', renditions: [] } }) }).popup;

    expect(read).not.toBeNull();
    expect(read?.media).toBeUndefined();
  });
});

describe('parseContentHome: the brand switcher and the layout (ADR 0183 D17)', () => {
  const entry = (n: number, overrides: Record<string, unknown> = {}) => ({
    targetBrandId: `brand-${n}`,
    name: `Brand ${n}`,
    url: `https://brand${n}.example.uz/`,
    ...overrides,
  });

  it('a document that has neither is read as no switcher and the standard layout', () => {
    const home = parse({});

    expect(home.brandSwitcher).toEqual([]);
    expect(home.layout).toBe('STANDARD');
    expect(NO_CONTENT.brandSwitcher).toEqual([]);
    expect(NO_CONTENT.layout).toBe('STANDARD');
  });

  it('reads the entries in order, with the logo resolved against the API origin', () => {
    const home = parse({ brandSwitcher: [entry(1, { logo: image({ alt: 'Logo' }) }), entry(2)] });

    expect(home.brandSwitcher.map((e) => e.name)).toEqual(['Brand 1', 'Brand 2']);
    expect(home.brandSwitcher[0].url).toBe('https://brand1.example.uz/');
    expect(home.brandSwitcher[0].logo?.renditions[0].url).toBe(`${API}/media/a?variant=w400`);
    expect(home.brandSwitcher[1].logo).toBeUndefined();
  });

  it('drops an entry whose address is not an https address of its own: it is where a tap is sent', () => {
    const home = parse({
      brandSwitcher: [
        entry(1, { url: 'http://brand1.example.uz/' }),
        entry(2, { url: 'javascript:alert(1)' }),
        entry(3, { url: 'https://user:pass@brand3.example.uz/' }),
        entry(4, { url: undefined }),
        entry(5),
      ],
    });

    expect(home.brandSwitcher.map((e) => e.name)).toEqual(['Brand 5']);
  });

  it('drops an entry with no name or no target, and one that is not an object', () => {
    const home = parse({
      brandSwitcher: [
        entry(1, { name: '  ' }),
        entry(2, { targetBrandId: '' }),
        'x',
        null,
        entry(3),
      ],
    });

    expect(home.brandSwitcher.map((e) => e.name)).toEqual(['Brand 3']);
  });

  it('keeps the entry, without a logo, when the logo cannot be shown or is not an image', () => {
    const home = parse({
      brandSwitcher: [
        entry(1, { logo: image({ renditions: [] }) }),
        entry(2, { logo: { kind: 'VIDEO', url: '/media/v.mp4' } }),
      ],
    });

    expect(home.brandSwitcher.map((e) => e.logo)).toEqual([undefined, undefined]);
  });

  it('a section that is not an array is no switcher', () => {
    expect(parse({ brandSwitcher: 'nope' }).brandSwitcher).toEqual([]);
  });

  it('turns the marketplace layout on for the exact word only, and any other value is the standard one', () => {
    expect(parse({ layout: 'MARKETPLACE' }).layout).toBe('MARKETPLACE');
    for (const value of ['marketplace', 'GRID', '', null, 3, undefined]) {
      expect(parse({ layout: value }).layout).toBe('STANDARD');
    }
  });
});
