import type { ContentLink } from '../types/content.types';
import { rememberSelectedCategory, resolveContentLink } from './content-links';

const ID = '00000000-0000-0000-0000-000000000009';
const CATEGORIES = [
  { id: ID, name: 'Desserts' },
  { id: '00000000-0000-0000-0000-000000000008', name: 'Drinks' },
];

describe('resolveContentLink: where a tap may go', () => {
  it('NONE is not a link', () => {
    expect(resolveContentLink({ type: 'NONE' }, CATEGORIES)).toBeNull();
  });

  it('PROMOTION is not a link: this storefront has no promotion screen', () => {
    expect(resolveContentLink({ type: 'PROMOTION', targetId: ID }, CATEGORIES)).toBeNull();
  });

  it('PRODUCT goes to the product page of that id', () => {
    expect(resolveContentLink({ type: 'PRODUCT', targetId: ID }, CATEGORIES)).toEqual({
      kind: 'route',
      commands: ['/product', ID],
    });
  });

  it('CATEGORY goes to the category page and carries the category the page expects', () => {
    expect(resolveContentLink({ type: 'CATEGORY', targetId: ID }, CATEGORIES)).toEqual({
      kind: 'route',
      commands: ['/category'],
      category: { id: ID, name: 'Desserts' },
    });
  });

  it('CATEGORY the menu does not name still goes, with an empty name the page will fill itself', () => {
    expect(
      resolveContentLink(
        { type: 'CATEGORY', targetId: '00000000-0000-0000-0000-0000000000aa' },
        [],
      ),
    ).toEqual({
      kind: 'route',
      commands: ['/category'],
      category: { id: '00000000-0000-0000-0000-0000000000aa', name: '' },
    });
  });

  it('CART goes to the cart', () => {
    expect(resolveContentLink({ type: 'CART' }, CATEGORIES)).toEqual({
      kind: 'route',
      commands: ['/cart'],
    });
  });

  it('a PRODUCT or CATEGORY link with no target is not a link', () => {
    expect(resolveContentLink({ type: 'PRODUCT' }, CATEGORIES)).toBeNull();
    expect(resolveContentLink({ type: 'CATEGORY' }, CATEGORIES)).toBeNull();
  });

  it('EXTERNAL https is an external link', () => {
    expect(
      resolveContentLink({ type: 'EXTERNAL', url: 'https://example.com/a?b=1' }, CATEGORIES),
    ).toEqual({ kind: 'external', href: 'https://example.com/a?b=1' });
  });

  it.each([
    'http://example.com/',
    'javascript:alert(1)',
    'data:text/html,<b>x</b>',
    'JAVASCRIPT:alert(1)',
    'ftp://example.com/',
    '',
  ])('EXTERNAL %j is re-checked in the client and is not a link', (url) => {
    const link: ContentLink = { type: 'EXTERNAL', url };
    expect(resolveContentLink(link, CATEGORIES)).toBeNull();
  });

  it('EXTERNAL with no url is not a link', () => {
    expect(resolveContentLink({ type: 'EXTERNAL' }, CATEGORIES)).toBeNull();
  });
});

describe('rememberSelectedCategory: the category page reads the choice from session storage', () => {
  beforeEach(() => sessionStorage.clear());

  it('stores the id and name under the key the category page reads', () => {
    rememberSelectedCategory({ id: ID, name: 'Desserts' });

    expect(JSON.parse(sessionStorage.getItem('mar_selected_cat') ?? 'null')).toEqual({
      id: ID,
      name: 'Desserts',
    });
  });

  it('leaves the name out when it is not known', () => {
    rememberSelectedCategory({ id: ID, name: '' });

    expect(JSON.parse(sessionStorage.getItem('mar_selected_cat') ?? 'null')).toEqual({ id: ID });
  });

  it('does not throw when storage does', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    expect(() => rememberSelectedCategory({ id: ID, name: 'Desserts' })).not.toThrow();

    spy.mockRestore();
  });
});
