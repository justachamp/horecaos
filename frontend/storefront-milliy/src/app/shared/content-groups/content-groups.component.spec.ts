import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ContentEventsService } from '../../services/content-events.service';
import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentGroup } from '../../types/content.types';
import type { CategoryItem, MenuItem } from '../../types/home.types';
import { contentGroup, uuid } from '../../testing/content-fixtures';
import { ContentGroupsComponent } from './content-groups.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string) => key;
  current = () => ({});
}
class FakeLangService {
  langId = () => 'uz';
}

function dish(id: string, name: string): MenuItem {
  return {
    id,
    name,
    description: '',
    active: true,
    has_discount: false,
    preparation_time: 0,
    price: 30_000,
    price_without_discount: 30_000,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants: [],
    modifierGroups: [],
  };
}

const SOUPS: CategoryItem = {
  id: uuid(200),
  name: 'Soups',
  items: [dish(uuid(1), 'Lagman'), dish(uuid(2), 'Mastava'), dish(uuid(3), 'Shurpa')],
  items_count: 3,
};
const MAINS: CategoryItem = {
  id: uuid(201),
  name: 'Mains',
  items: [dish(uuid(4), 'Plov'), dish(uuid(5), 'Manti')],
  items_count: 2,
};

function render(
  groups: readonly ContentGroup[],
  sections: readonly CategoryItem[] = [SOUPS, MAINS],
) {
  const events = { report: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useClass: FakeLangService },
      { provide: ContentEventsService, useValue: events },
    ],
  });
  const fixture = TestBed.createComponent(ContentGroupsComponent);
  fixture.componentRef.setInput('groups', groups);
  fixture.componentRef.setInput('sections', sections);
  fixture.componentRef.setInput('currency', 'UZS');
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const names = (rail: Element) =>
    [...rail.querySelectorAll('.dish__name')].map((el) => el.textContent?.trim());
  return { fixture, host, events, names };
}

const rails = (host: HTMLElement) => [...host.querySelectorAll('[data-testid="content-group"]')];

describe('ContentGroupsComponent: home rails over the menu the page already holds', () => {
  it('draws nothing for no groups', () => {
    const { host } = render([]);

    expect(rails(host)).toEqual([]);
  });

  it('draws a titled rail of dish cards for a manual group, in the group order', () => {
    const { host, names } = render([
      contentGroup({ title: 'Popular', productIds: [uuid(4), uuid(1), uuid(5)] }),
    ]);
    const [rail] = rails(host);

    expect(rail.querySelector('[data-testid="content-group-title"]')?.textContent?.trim()).toBe(
      'Popular',
    );
    expect(names(rail)).toEqual(['Plov', 'Lagman', 'Manti']);
    expect(rail.querySelectorAll('app-dish-card')).toHaveLength(3);
    expect(rail.querySelector('a')?.getAttribute('href')).toBe(`/product/${uuid(4)}`);
  });

  it('skips an id the menu lacks', () => {
    const { host, names } = render([contentGroup({ productIds: [uuid(2), uuid(999), uuid(4)] })]);

    expect(names(rails(host)[0])).toEqual(['Mastava', 'Plov']);
  });

  it('takes a by-category group from that category in the menu order, capped by its limit', () => {
    const { host, names } = render([
      contentGroup({ categoryId: SOUPS.id, order: 'NEWEST', limit: 2 }),
    ]);

    expect(names(rails(host)[0])).toEqual(['Lagman', 'Mastava']);
  });

  it('draws nothing for a group that ends up empty, and keeps the groups that do not', () => {
    const { host } = render([
      contentGroup({ id: uuid(50), title: 'Gone', productIds: [uuid(998), uuid(999)] }),
      contentGroup({ id: uuid(51), title: 'Missing category', categoryId: uuid(997) }),
      contentGroup({ id: uuid(52), title: 'Fine', productIds: [uuid(1)] }),
    ]);

    expect(rails(host)).toHaveLength(1);
    expect(host.textContent).toContain('Fine');
    expect(host.textContent).not.toContain('Gone');
    expect(host.textContent).not.toContain('Missing category');
  });

  it('draws nothing while the menu has no dishes at all', () => {
    const { host } = render([contentGroup({ productIds: [uuid(1)] })], []);

    expect(rails(host)).toEqual([]);
  });

  it('draws a title as text, never as markup', () => {
    const { host } = render([contentGroup({ title: '<b>Hot</b>', productIds: [uuid(1)] })]);

    expect(host.querySelector('[data-testid="content-group-title"] b')).toBeNull();
    expect(host.textContent).toContain('<b>Hot</b>');
  });

  it('puts each rail under its own title for a screen reader, and says what language the title is in', () => {
    const { host } = render([
      contentGroup({ id: uuid(50), textLocale: 'ru', productIds: [uuid(1)] }),
    ]);
    const [rail] = rails(host);
    const title = rail.querySelector('[data-testid="content-group-title"]');

    expect(rail.getAttribute('aria-labelledby')).toBe(title?.id);
    expect(title?.id).toBeTruthy();
    expect(title?.getAttribute('lang')).toBe('ru');
  });

  it('reports an impression of the group, and a click when a dish in it is tapped', () => {
    const { host, events } = render([
      contentGroup({ id: uuid(50), revisionNo: 6, productIds: [uuid(1)] }),
    ]);
    expect(events.report).toHaveBeenCalledWith(uuid(50), 6, 'IMPRESSION');

    host.querySelector<HTMLElement>('a.dish')?.click();

    expect(events.report).toHaveBeenCalledWith(uuid(50), 6, 'CLICK');
  });

  it('is correct when the menu changes under it', () => {
    const { fixture, host, names } = render([contentGroup({ productIds: [uuid(1), uuid(4)] })]);

    fixture.componentRef.setInput('sections', [MAINS]);
    fixture.detectChanges();

    expect(names(rails(host)[0])).toEqual(['Plov']);
  });
});
