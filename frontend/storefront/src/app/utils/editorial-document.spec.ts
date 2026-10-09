import {
  normalizeEditorialDetail,
  normalizeEditorialPage,
  normalizeFeatures,
} from './editorial-document';

const API = 'https://api.example.uz/api/v1';

function cover(n = 1) {
  return {
    kind: 'IMAGE',
    alt: `Cover ${n}`,
    renditions: [
      { widthPx: 800, url: `/api/v1/storefront/tenants/t/media/c${n}?variant=w800` },
      { widthPx: 400, url: `/api/v1/storefront/tenants/t/media/c${n}?variant=w400` },
    ],
  };
}

describe('normalizeFeatures: a kind is on only when the server says exactly true', () => {
  it('reads the four flags', () => {
    expect(
      normalizeFeatures({ news: true, gallery: false, recipes: true, vacancies: false }),
    ).toEqual({
      news: true,
      gallery: false,
      recipes: true,
      vacancies: false,
    });
  });

  it.each([null, undefined, 'x', 42, [], {}])('%j is all off', (raw) => {
    expect(normalizeFeatures(raw)).toEqual({
      news: false,
      gallery: false,
      recipes: false,
      vacancies: false,
    });
  });

  it('a flag that is not the boolean true is off', () => {
    expect(normalizeFeatures({ news: 'true', gallery: 1, recipes: {}, vacancies: [] })).toEqual({
      news: false,
      gallery: false,
      recipes: false,
      vacancies: false,
    });
  });

  it('ignores a kind it does not know', () => {
    expect(normalizeFeatures({ news: true, podcasts: true })).toEqual({
      news: true,
      gallery: false,
      recipes: false,
      vacancies: false,
    });
  });
});

describe('normalizeEditorialPage', () => {
  const item = (overrides: Record<string, unknown> = {}) => ({
    slug: 'summer-menu',
    title: 'Summer menu',
    summary: 'What is new',
    textLocale: 'uz-Latn',
    cover: cover(),
    publishedAt: '2026-10-01T09:00:00Z',
    ...overrides,
  });

  it('a body that is not an object is no page', () => {
    expect(normalizeEditorialPage(null, API)).toBeNull();
    expect(normalizeEditorialPage('x', API)).toBeNull();
    expect(normalizeEditorialPage([], API)).toBeNull();
  });

  it('reads the items in order, with the cursor', () => {
    const page = normalizeEditorialPage(
      { items: [item(), item({ slug: 'second', title: 'Second' })], nextCursor: 'abc' },
      API,
    );
    expect(page?.items.map((i) => i.slug)).toEqual(['summer-menu', 'second']);
    expect(page?.nextCursor).toBe('abc');
    expect(page?.items[0]).toMatchObject({
      title: 'Summer menu',
      summary: 'What is new',
      textLocale: 'uz-Latn',
      publishedAt: '2026-10-01T09:00:00Z',
    });
  });

  it('puts the cover renditions on the API origin, smallest first', () => {
    const page = normalizeEditorialPage({ items: [item()] }, API);
    const media = page?.items[0].cover;
    expect(media?.kind === 'IMAGE' && media.renditions.map((r) => r.url)).toEqual([
      'https://api.example.uz/api/v1/storefront/tenants/t/media/c1?variant=w400',
      'https://api.example.uz/api/v1/storefront/tenants/t/media/c1?variant=w800',
    ]);
  });

  it('an item with no cover, or a cover it cannot show, is still an item', () => {
    const page = normalizeEditorialPage(
      { items: [item({ cover: undefined }), item({ slug: 'b', cover: { kind: 'AUDIO' } })] },
      API,
    );
    expect(page?.items).toHaveLength(2);
    expect(page?.items[0].cover).toBeUndefined();
    expect(page?.items[1].cover).toBeUndefined();
  });

  it.each([
    ['no slug', { slug: undefined }],
    ['a blank slug', { slug: ' ' }],
    ['a slug with a slash', { slug: 'a/b' }],
    ['a slug with a query', { slug: 'a?b=1' }],
    ['no title', { title: undefined }],
    ['no date', { publishedAt: undefined }],
    ['a date that is not one', { publishedAt: 'yesterday-ish' }],
  ])('skips an item with %s', (_label, overrides) => {
    const page = normalizeEditorialPage({ items: [item(overrides), item({ slug: 'ok' })] }, API);
    expect(page?.items.map((i) => i.slug)).toEqual(['ok']);
  });

  it('accepts a slug in Cyrillic', () => {
    const page = normalizeEditorialPage({ items: [item({ slug: 'новое-меню' })] }, API);
    expect(page?.items[0].slug).toBe('новое-меню');
  });

  it('reads an employment type and drops one it does not know', () => {
    const page = normalizeEditorialPage(
      {
        items: [
          item({ slug: 'a', employmentType: 'SHIFT' }),
          item({ slug: 'b', employmentType: 'GIG' }),
        ],
      },
      API,
    );
    expect(page?.items[0].employmentType).toBe('SHIFT');
    expect(page?.items[1].employmentType).toBeUndefined();
  });

  it('no items, or items that are not a list, is an empty page, not a failure', () => {
    expect(normalizeEditorialPage({}, API)?.items).toEqual([]);
    expect(normalizeEditorialPage({ items: 'x' }, API)?.items).toEqual([]);
  });

  it('an empty cursor is no cursor', () => {
    expect(normalizeEditorialPage({ items: [], nextCursor: '' }, API)?.nextCursor).toBeUndefined();
    expect(normalizeEditorialPage({ items: [], nextCursor: 7 }, API)?.nextCursor).toBeUndefined();
  });
});

describe('normalizeEditorialDetail', () => {
  const detail = (overrides: Record<string, unknown> = {}) => ({
    slug: 'summer-menu',
    kind: 'NEWS',
    title: 'Summer menu',
    summary: 'What is new',
    textLocale: 'uz-Latn',
    publishedAt: '2026-10-01T09:00:00Z',
    blocks: [],
    images: [],
    ...overrides,
  });
  const inline = (text: string, extra: Record<string, unknown> = {}) => ({ text, ...extra });

  it('a body that is not an object, or has no slug or title, is no document', () => {
    expect(normalizeEditorialDetail(null, API)).toBeNull();
    expect(normalizeEditorialDetail('x', API)).toBeNull();
    expect(normalizeEditorialDetail(detail({ slug: undefined }), API)).toBeNull();
    expect(normalizeEditorialDetail(detail({ title: undefined }), API)).toBeNull();
    expect(normalizeEditorialDetail(detail({ publishedAt: 'nope' }), API)).toBeNull();
  });

  it('reads the head of the document', () => {
    const doc = normalizeEditorialDetail(detail({ cover: cover(), bodyLocale: 'ru' }), API);
    expect(doc).toMatchObject({
      slug: 'summer-menu',
      title: 'Summer menu',
      summary: 'What is new',
      textLocale: 'uz-Latn',
      bodyLocale: 'ru',
      publishedAt: '2026-10-01T09:00:00Z',
    });
    expect(doc?.cover?.kind).toBe('IMAGE');
    expect(doc?.blocks).toEqual([]);
    expect(doc?.images).toEqual([]);
    expect(doc?.vacancy).toBeUndefined();
  });

  describe('blocks', () => {
    const blocksOf = (blocks: unknown[]) =>
      normalizeEditorialDetail(detail({ blocks }), API)?.blocks;

    it('reads a paragraph, a heading, a quote and a divider', () => {
      expect(
        blocksOf([
          { type: 'PARAGRAPH', inlines: [inline('Hello '), inline('world', { bold: true })] },
          { type: 'HEADING', level: 2, inlines: [inline('Title')] },
          { type: 'QUOTE', inlines: [inline('Quoted', { italic: true })] },
          { type: 'DIVIDER' },
        ]),
      ).toEqual([
        { type: 'PARAGRAPH', inlines: [{ text: 'Hello ' }, { text: 'world', bold: true }] },
        { type: 'HEADING', level: 2, inlines: [{ text: 'Title' }] },
        { type: 'QUOTE', inlines: [{ text: 'Quoted', italic: true }] },
        { type: 'DIVIDER' },
      ]);
    });

    it('keeps an inline href as the server gave it (the screen re-checks it where it draws it)', () => {
      const blocks = blocksOf([
        { type: 'PARAGRAPH', inlines: [inline('site', { href: 'https://example.com' })] },
      ]);
      expect(blocks?.[0]).toEqual({
        type: 'PARAGRAPH',
        inlines: [{ text: 'site', href: 'https://example.com' }],
      });
    });

    it('reads an ordered and an unordered list', () => {
      expect(
        blocksOf([
          { type: 'LIST', ordered: true, items: [[inline('one')], [inline('two'), inline('!')]] },
          { type: 'LIST', ordered: false, items: [[inline('dot')]] },
        ]),
      ).toEqual([
        {
          type: 'LIST',
          ordered: true,
          items: [[{ text: 'one' }], [{ text: 'two' }, { text: '!' }]],
        },
        { type: 'LIST', ordered: false, items: [[{ text: 'dot' }]] },
      ]);
    });

    it('reads an image block with its renditions on the API origin, and a caption', () => {
      const blocks = blocksOf([{ type: 'IMAGE', media: cover(), caption: [inline('A dish')] }]);
      const block = blocks?.[0];
      expect(block?.type).toBe('IMAGE');
      if (block?.type === 'IMAGE') {
        expect(block.media.renditions[0].url).toBe(
          'https://api.example.uz/api/v1/storefront/tenants/t/media/c1?variant=w400',
        );
        expect(block.caption).toEqual([{ text: 'A dish' }]);
      }
    });

    it('skips a block whose type it does not know, and keeps the rest in order', () => {
      expect(
        blocksOf([
          { type: 'PARAGRAPH', inlines: [inline('a')] },
          { type: 'TABLE', rows: [] },
          { type: 'EMBED', html: '<script>alert(1)</script>' },
          { notAType: true },
          'x',
          { type: 'DIVIDER' },
        ])?.map((b) => b.type),
      ).toEqual(['PARAGRAPH', 'DIVIDER']);
    });

    it('skips a block whose body is malformed', () => {
      expect(
        blocksOf([
          { type: 'PARAGRAPH', inlines: 'text' },
          { type: 'PARAGRAPH' },
          { type: 'LIST', ordered: true, items: 'x' },
          { type: 'LIST', ordered: true, items: [] },
          { type: 'IMAGE', media: { kind: 'IMAGE', renditions: [] } },
          { type: 'IMAGE', media: { kind: 'VIDEO', url: '/m/v.mp4' } },
          { type: 'DIVIDER' },
        ])?.map((b) => b.type),
      ).toEqual(['DIVIDER']);
    });

    it('drops an inline that is not text and keeps its neighbours', () => {
      expect(
        blocksOf([
          { type: 'PARAGRAPH', inlines: [inline('a'), { text: 7 }, 'x', null, inline('b')] },
        ])?.[0],
      ).toEqual({ type: 'PARAGRAPH', inlines: [{ text: 'a' }, { text: 'b' }] });
    });

    it('a heading level other than 2 is a 3', () => {
      expect(
        blocksOf([
          { type: 'HEADING', level: 3, inlines: [inline('a')] },
          { type: 'HEADING', level: 1, inlines: [inline('b')] },
          { type: 'HEADING', inlines: [inline('c')] },
        ])?.map((b) => (b.type === 'HEADING' ? b.level : null)),
      ).toEqual([3, 3, 3]);
    });

    it('blocks that are not a list are no blocks', () => {
      expect(normalizeEditorialDetail(detail({ blocks: 'x' }), API)?.blocks).toEqual([]);
    });
  });

  describe('images', () => {
    it('reads the album with captions as text, from a string or from inlines', () => {
      const doc = normalizeEditorialDetail(
        detail({
          images: [
            { media: cover(1), caption: 'Plain caption' },
            { media: cover(2), caption: [{ text: 'Rich ' }, { text: 'caption', bold: true }] },
            { media: cover(3) },
          ],
        }),
        API,
      );
      expect(doc?.images.map((i) => i.caption)).toEqual([
        'Plain caption',
        'Rich caption',
        undefined,
      ]);
      expect(doc?.images).toHaveLength(3);
    });

    it('skips an image it cannot show', () => {
      const doc = normalizeEditorialDetail(
        detail({ images: [{ media: { kind: 'IMAGE', renditions: [] } }, { media: cover() }] }),
        API,
      );
      expect(doc?.images).toHaveLength(1);
    });
  });

  describe('vacancy', () => {
    const vacancyOf = (vacancy: unknown) =>
      normalizeEditorialDetail(detail({ vacancy }), API)?.vacancy;

    it('reads the employment type, the one contact and the branch', () => {
      expect(
        vacancyOf({
          employmentType: 'FULL_TIME',
          apply: { type: 'PHONE', value: '+998 90 123 45 67' },
          branch: {
            locationId: 'loc-1',
            name: 'Chilanzar',
            addressLine: 'Bunyodkor 1',
            city: 'Tashkent',
          },
        }),
      ).toEqual({
        employmentType: 'FULL_TIME',
        apply: { type: 'PHONE', value: '+998 90 123 45 67' },
        branch: {
          locationId: 'loc-1',
          name: 'Chilanzar',
          addressLine: 'Bunyodkor 1',
          city: 'Tashkent',
        },
      });
    });

    it.each(['PHONE', 'TELEGRAM', 'URL', 'EMAIL'])('knows the %s contact', (type) => {
      expect(vacancyOf({ employmentType: 'SHIFT', apply: { type, value: 'x' } })?.apply?.type).toBe(
        type,
      );
    });

    it('drops a contact of a type it does not know, or with no value; the vacancy stays', () => {
      expect(vacancyOf({ employmentType: 'SHIFT', apply: { type: 'FAX', value: '1' } })).toEqual({
        employmentType: 'SHIFT',
      });
      expect(vacancyOf({ employmentType: 'SHIFT', apply: { type: 'PHONE', value: ' ' } })).toEqual({
        employmentType: 'SHIFT',
      });
    });

    it('an employment type it does not know is left out; the vacancy and its contact stay', () => {
      expect(
        vacancyOf({ employmentType: 'GIG', apply: { type: 'EMAIL', value: 'jobs@example.uz' } }),
      ).toEqual({ apply: { type: 'EMAIL', value: 'jobs@example.uz' } });
    });

    it('a branch with no name is no branch', () => {
      expect(
        vacancyOf({ employmentType: 'SHIFT', branch: { locationId: 'l' } })?.branch,
      ).toBeUndefined();
    });

    it('a vacancy that is not an object is none', () => {
      expect(vacancyOf('x')).toBeUndefined();
      expect(vacancyOf(null)).toBeUndefined();
    });
  });
});
