import { NO_EDITORIAL } from '../types/editorial.types';
import { parseEditorialDetail, parseEditorialPage, parseFeatures } from './editorial-document';

const API = 'https://api.example.uz';
const resolveUrl = (url: unknown): string | null =>
  typeof url === 'string' && url.startsWith('/') && !url.startsWith('//') ? `${API}${url}` : null;

const image = (extra: Record<string, unknown> = {}) => ({
  kind: 'IMAGE',
  alt: 'A dish',
  widthPx: 1600,
  heightPx: 900,
  renditions: [
    { widthPx: 800, url: '/media/a?variant=w800' },
    { widthPx: 400, url: '/media/a?variant=w400' },
  ],
  ...extra,
});

describe('parseFeatures', () => {
  it('reads which kinds are on', () => {
    expect(parseFeatures({ news: true, gallery: false, recipes: true, vacancies: false })).toEqual({
      news: true,
      gallery: false,
      recipes: true,
      vacancies: false,
    });
  });

  it('reads anything that is not exactly true as off, and an unknown key as nothing', () => {
    expect(parseFeatures({ news: 'yes', gallery: 1, recipes: null, blog: true })).toEqual({
      ...NO_EDITORIAL,
    });
  });

  it.each([null, undefined, 'text', 7, []])(
    'reads a body that is not an object (%j) as all off',
    (raw) => {
      expect(parseFeatures(raw)).toEqual({ ...NO_EDITORIAL });
    },
  );
});

describe('parseEditorialPage', () => {
  const entry = (overrides: Record<string, unknown> = {}) => ({
    slug: 'plov-week',
    title: 'Plov week',
    summary: 'Seven days of plov',
    textLocale: 'uz-Latn',
    cover: image(),
    publishedAt: '2026-10-01T08:30:00Z',
    ...overrides,
  });

  it('reads a page of entries, with the cover resolved and the cursor kept', () => {
    const page = parseEditorialPage({ items: [entry()], nextCursor: 'abc' }, resolveUrl);

    expect(page.nextCursor).toBe('abc');
    expect(page.items).toHaveLength(1);
    expect(page.items[0]).toMatchObject({
      slug: 'plov-week',
      title: 'Plov week',
      summary: 'Seven days of plov',
      textLocale: 'uz-Latn',
      publishedAt: '2026-10-01T08:30:00Z',
    });
    expect(page.items[0].cover?.renditions.map((r) => r.url)).toEqual([
      `${API}/media/a?variant=w400`,
      `${API}/media/a?variant=w800`,
    ]);
  });

  it('reads a last page, and an empty one', () => {
    expect(parseEditorialPage({ items: [entry()] }, resolveUrl).nextCursor).toBeUndefined();
    expect(parseEditorialPage({ items: [] }, resolveUrl).items).toEqual([]);
    expect(parseEditorialPage({}, resolveUrl).items).toEqual([]);
  });

  it('refuses a body that is not an object', () => {
    expect(() => parseEditorialPage('<html>', resolveUrl)).toThrow();
    expect(() => parseEditorialPage(null, resolveUrl)).toThrow();
  });

  it('skips an entry it cannot draw and keeps the others in order', () => {
    const page = parseEditorialPage(
      {
        items: [
          entry({ slug: 'one' }),
          entry({ slug: '' }),
          entry({ slug: 'no-title', title: undefined }),
          entry({ slug: 'no-date', publishedAt: undefined }),
          'nonsense',
          entry({ slug: 'two' }),
        ],
      },
      resolveUrl,
    );

    expect(page.items.map((item) => item.slug)).toEqual(['one', 'two']);
  });

  it('keeps an entry whose cover cannot be drawn, without the cover', () => {
    const page = parseEditorialPage(
      { items: [entry({ cover: { kind: 'IMAGE', renditions: [] } })] },
      resolveUrl,
    );

    expect(page.items).toHaveLength(1);
    expect(page.items[0].cover).toBeUndefined();
  });

  it('reads the employment type of a vacancy, and forgets one it does not know', () => {
    const page = parseEditorialPage(
      {
        items: [
          entry({ slug: 'a', employmentType: 'SHIFT' }),
          entry({ slug: 'b', employmentType: 'WHENEVER' }),
        ],
      },
      resolveUrl,
    );

    expect(page.items[0].employmentType).toBe('SHIFT');
    expect(page.items[1].employmentType).toBeUndefined();
  });

  it('keeps markup in a title as text', () => {
    const page = parseEditorialPage(
      { items: [entry({ title: '<script>alert(1)</script>' })] },
      resolveUrl,
    );

    expect(page.items[0].title).toBe('<script>alert(1)</script>');
  });
});

describe('parseEditorialDetail', () => {
  const detail = (overrides: Record<string, unknown> = {}) => ({
    slug: 'plov-week',
    kind: 'NEWS',
    title: 'Plov week',
    summary: 'Seven days',
    textLocale: 'uz-Latn',
    publishedAt: '2026-10-01T08:30:00Z',
    bodyLocale: 'ru',
    blocks: [],
    images: [],
    ...overrides,
  });

  it('reads an entry whole', () => {
    const read = parseEditorialDetail(detail({ cover: image() }), resolveUrl);

    expect(read).toMatchObject({
      slug: 'plov-week',
      kind: 'NEWS',
      title: 'Plov week',
      summary: 'Seven days',
      textLocale: 'uz-Latn',
      bodyLocale: 'ru',
      publishedAt: '2026-10-01T08:30:00Z',
      blocks: [],
      images: [],
    });
    expect(read.cover?.kind).toBe('IMAGE');
    expect(read.vacancy).toBeUndefined();
  });

  it('refuses a body that is not an entry', () => {
    expect(() => parseEditorialDetail(null, resolveUrl)).toThrow();
    expect(() => parseEditorialDetail('x', resolveUrl)).toThrow();
    expect(() => parseEditorialDetail(detail({ slug: undefined }), resolveUrl)).toThrow();
    expect(() => parseEditorialDetail(detail({ title: undefined }), resolveUrl)).toThrow();
  });

  describe('blocks', () => {
    const read = (blocks: unknown[]) => parseEditorialDetail(detail({ blocks }), resolveUrl).blocks;

    it('reads a paragraph with bold, italic and linked runs', () => {
      expect(
        read([
          {
            type: 'PARAGRAPH',
            inlines: [
              { text: 'Plain ' },
              { text: 'bold', bold: true },
              { text: 'italic', italic: true },
              { text: 'link', href: 'https://example.uz/x' },
            ],
          },
        ]),
      ).toEqual([
        {
          type: 'PARAGRAPH',
          inlines: [
            { text: 'Plain ' },
            { text: 'bold', bold: true },
            { text: 'italic', italic: true },
            { text: 'link', href: 'https://example.uz/x' },
          ],
        },
      ]);
    });

    it('reads a heading at level 2 or 3, and any other level as 2', () => {
      expect(
        read([
          { type: 'HEADING', level: 3, inlines: [{ text: 'a' }] },
          { type: 'HEADING', level: 2, inlines: [{ text: 'b' }] },
          { type: 'HEADING', level: 6, inlines: [{ text: 'c' }] },
          { type: 'HEADING', inlines: [{ text: 'd' }] },
        ]).map((block) => (block.type === 'HEADING' ? block.level : null)),
      ).toEqual([3, 2, 2, 2]);
    });

    it('reads a quote, a divider and a list', () => {
      expect(
        read([
          { type: 'QUOTE', inlines: [{ text: 'q' }] },
          { type: 'DIVIDER' },
          { type: 'LIST', ordered: true, items: [[{ text: 'one' }], [{ text: 'two' }]] },
          { type: 'LIST', items: [[{ text: 'dot' }]] },
        ]),
      ).toEqual([
        { type: 'QUOTE', inlines: [{ text: 'q' }] },
        { type: 'DIVIDER' },
        { type: 'LIST', ordered: true, items: [[{ text: 'one' }], [{ text: 'two' }]] },
        { type: 'LIST', ordered: false, items: [[{ text: 'dot' }]] },
      ]);
    });

    it('reads an image block with its caption, the media resolved', () => {
      const [block] = read([
        { type: 'IMAGE', media: image(), caption: [{ text: 'Fresh', italic: true }] },
      ]);

      expect(block.type).toBe('IMAGE');
      if (block.type === 'IMAGE') {
        expect(block.media.renditions[0].url).toBe(`${API}/media/a?variant=w400`);
        expect(block.caption).toEqual([{ text: 'Fresh', italic: true }]);
      }
    });

    it('skips a block of a type it does not know, and keeps the rest in order', () => {
      const blocks = read([
        { type: 'PARAGRAPH', inlines: [{ text: 'first' }] },
        { type: 'EMBED', src: 'https://example.uz' },
        { type: 'SCRIPT', code: 'alert(1)' },
        'nonsense',
        null,
        { inlines: [{ text: 'no type' }] },
        { type: 'PARAGRAPH', inlines: [{ text: 'last' }] },
      ]);

      expect(blocks.map((block) => block.type)).toEqual(['PARAGRAPH', 'PARAGRAPH']);
    });

    it('skips an image block whose picture cannot be drawn, or is a video', () => {
      const blocks = read([
        { type: 'IMAGE', media: { kind: 'IMAGE', renditions: [] } },
        { type: 'IMAGE', media: { kind: 'VIDEO', url: '/media/v.mp4', posterUrl: '/media/p.jpg' } },
        { type: 'IMAGE' },
        { type: 'DIVIDER' },
      ]);

      expect(blocks.map((block) => block.type)).toEqual(['DIVIDER']);
    });

    it('skips a list with nothing in it, and an item that is not a list of runs', () => {
      const blocks = read([
        { type: 'LIST', ordered: false, items: [] },
        { type: 'LIST', items: ['plain text', [{ text: 'ok' }]] },
      ]);

      expect(blocks).toEqual([{ type: 'LIST', ordered: false, items: [[{ text: 'ok' }]] }]);
    });

    it('ignores a field it does not know on a block or a run, and a run with no text', () => {
      const [block] = read([
        {
          type: 'PARAGRAPH',
          style: 'fancy',
          inlines: [
            { text: 'kept', color: 'red', href: 7, bold: 'yes' },
            { bold: true },
            { text: 42 },
          ],
        },
      ]);

      expect(block).toEqual({ type: 'PARAGRAPH', inlines: [{ text: 'kept' }] });
    });

    it('keeps markup in a body as text', () => {
      const [block] = read([
        {
          type: 'PARAGRAPH',
          inlines: [{ text: '<script>alert(1)</script><img src=x onerror=1>' }],
        },
      ]);

      expect(block).toEqual({
        type: 'PARAGRAPH',
        inlines: [{ text: '<script>alert(1)</script><img src=x onerror=1>' }],
      });
    });

    it('reads a body that is missing as no blocks', () => {
      expect(parseEditorialDetail(detail({ blocks: undefined }), resolveUrl).blocks).toEqual([]);
    });
  });

  describe('album images', () => {
    it('reads the pictures of an album with their captions', () => {
      const read = parseEditorialDetail(
        detail({
          kind: 'GALLERY_ALBUM',
          images: [
            { media: image(), caption: 'The hall' },
            { media: image({ alt: 'Second' }) },
            { media: { kind: 'IMAGE', renditions: [] }, caption: 'Broken' },
            { caption: 'No media' },
            'nonsense',
          ],
        }),
        resolveUrl,
      );

      expect(read.images).toHaveLength(2);
      expect(read.images[0].caption).toBe('The hall');
      expect(read.images[1].caption).toBeUndefined();
    });
  });

  describe('vacancy', () => {
    it('reads the employment type, the one contact and the branch', () => {
      const read = parseEditorialDetail(
        detail({
          kind: 'VACANCY',
          vacancy: {
            employmentType: 'PART_TIME',
            apply: { type: 'PHONE', value: '+998901234567' },
            branch: {
              locationId: '10000000-0000-0000-0000-000000000003',
              name: 'Chilonzor',
              addressLine: 'Bunyodkor 1',
              city: 'Tashkent',
            },
          },
        }),
        resolveUrl,
      );

      expect(read.vacancy).toEqual({
        employmentType: 'PART_TIME',
        apply: { type: 'PHONE', value: '+998901234567' },
        branch: {
          locationId: '10000000-0000-0000-0000-000000000003',
          name: 'Chilonzor',
          addressLine: 'Bunyodkor 1',
          city: 'Tashkent',
        },
      });
    });

    it('reads a vacancy with no contact and no branch', () => {
      const read = parseEditorialDetail(
        detail({ vacancy: { employmentType: 'INTERNSHIP' } }),
        resolveUrl,
      );

      expect(read.vacancy).toEqual({ employmentType: 'INTERNSHIP' });
    });

    it('reads an employment type it does not know as full time, and drops a contact it cannot read', () => {
      const read = parseEditorialDetail(
        detail({
          vacancy: { employmentType: 'WHENEVER', apply: { type: 'FAX', value: '12345' } },
        }),
        resolveUrl,
      );

      expect(read.vacancy).toEqual({ employmentType: 'FULL_TIME' });
    });

    it('drops a contact with no value, and a branch with no name', () => {
      const read = parseEditorialDetail(
        detail({
          vacancy: {
            employmentType: 'SHIFT',
            apply: { type: 'EMAIL', value: '' },
            branch: { locationId: '10000000-0000-0000-0000-000000000003' },
          },
        }),
        resolveUrl,
      );

      expect(read.vacancy).toEqual({ employmentType: 'SHIFT' });
    });
  });
});
