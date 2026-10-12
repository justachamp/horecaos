import type { ContentLink } from '../types/content.types';
import { resolveContentLink, safeHttpsUrl } from './content-link';

const ID = '3f2c1d9e-8a47-4b6c-9d10-5e7f8a9b0c1d';

describe('resolveContentLink: where a tap on content may go', () => {
  it('gives nothing for NONE, for a missing link and for a link of a type this client does not know', () => {
    expect(resolveContentLink({ type: 'NONE' })).toBeNull();
    expect(resolveContentLink(null)).toBeNull();
    expect(resolveContentLink(undefined)).toBeNull();
    expect(resolveContentLink({ type: 'TELEPORT' } as unknown as ContentLink)).toBeNull();
  });

  it('sends a PRODUCT link to the product page of that id', () => {
    expect(resolveContentLink({ type: 'PRODUCT', targetId: ID })).toEqual({
      kind: 'route',
      commands: ['/product', ID],
    });
  });

  it('sends a CATEGORY link to the home screen with that category chosen', () => {
    expect(resolveContentLink({ type: 'CATEGORY', targetId: ID })).toEqual({
      kind: 'route',
      commands: ['/home'],
      queryParams: { category: ID },
    });
  });

  it('sends a CART link to the cart', () => {
    expect(resolveContentLink({ type: 'CART' })).toEqual({ kind: 'route', commands: ['/cart'] });
  });

  it('treats PROMOTION as no link at all: this storefront has no promotion screen', () => {
    expect(resolveContentLink({ type: 'PROMOTION', targetId: ID })).toBeNull();
  });

  it.each([
    ['no target', { type: 'PRODUCT' }],
    ['a target that is not an id', { type: 'PRODUCT', targetId: '../../cart' }],
    ['an empty target', { type: 'CATEGORY', targetId: '' }],
  ] as const)('refuses a product or category link with %s', (_label, link) => {
    expect(resolveContentLink(link as ContentLink)).toBeNull();
  });

  it('opens an EXTERNAL https link as itself', () => {
    expect(resolveContentLink({ type: 'EXTERNAL', url: 'https://example.uz/offer?a=1' })).toEqual({
      kind: 'external',
      url: 'https://example.uz/offer?a=1',
    });
  });

  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'java\nscript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'http://example.uz/plain',
    'ftp://example.uz/file',
    'tel:+998901234567',
    '//example.uz/protocol-relative',
    '/relative/path',
    'example.uz',
    'https://user:secret@example.uz/',
    'https://',
    '',
    '   ',
  ])('refuses an EXTERNAL link to %j', (url) => {
    expect(resolveContentLink({ type: 'EXTERNAL', url })).toBeNull();
  });

  it('refuses an EXTERNAL link that carries no url', () => {
    expect(resolveContentLink({ type: 'EXTERNAL' })).toBeNull();
  });
});

describe('safeHttpsUrl', () => {
  it('returns the normalised address only for an https URL that parses', () => {
    expect(safeHttpsUrl('HTTPS://Example.UZ/a b')).toBe('https://example.uz/a%20b');
    expect(safeHttpsUrl('http://example.uz')).toBeNull();
    expect(safeHttpsUrl(null)).toBeNull();
    expect(safeHttpsUrl(undefined)).toBeNull();
  });
});
