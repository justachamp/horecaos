import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ContentEventsService } from '../../services/content-events.service';
import type { ContentGroup } from '../../types/content.types';
import type { MenuItem } from '../../types/home.types';
import type { MenuSection } from '../../utils/content-groups';
import { FoodCarouselComponent } from '../food-carousel/food-carousel.component';
import { ContentGroupsComponent } from './content-groups.component';

/** The real carousel draws food cards, which need the cart, favourites and session. Not under test. */
@Component({
  selector: 'app-food-carousel',
  template: `
    @for (dish of items(); track dish.id) {
      <a class="dish" href="/product/{{ dish.id }}" [attr.data-id]="dish.id">{{ dish.name }}</a>
    }
    <button type="button" class="add">add</button>
  `,
})
class StubFoodCarouselComponent {
  readonly items = input<MenuItem[]>([]);
}

function dish(id: string): MenuItem {
  return { id, name: `Dish ${id}` } as MenuItem;
}

const MENU: MenuSection[] = [
  { id: 'c1', name: 'Mains', items: [dish('p1'), dish('p2'), dish('p3')] },
  { id: 'c2', name: 'Drinks', items: [dish('p4'), dish('p5')] },
];

function manual(id: string, ...productIds: string[]): ContentGroup {
  return {
    id,
    revisionNo: 2,
    title: `Group ${id}`,
    textLocale: 'ru',
    source: { kind: 'MANUAL', productIds },
  };
}

function byCategory(id: string, categoryId: string, limit?: number): ContentGroup {
  return {
    id,
    revisionNo: 2,
    title: `Group ${id}`,
    textLocale: 'ru',
    source: {
      kind: 'BY_CATEGORY',
      categoryId,
      order: 'MENU_ORDER',
      ...(limit === undefined ? {} : { limit }),
    },
  };
}

function render(groups: ContentGroup[], menu: MenuSection[] = MENU) {
  const events = { impression: vi.fn(), click: vi.fn() };
  TestBed.configureTestingModule({
    providers: [{ provide: ContentEventsService, useValue: events }],
  });
  TestBed.overrideComponent(ContentGroupsComponent, {
    remove: { imports: [FoodCarouselComponent] },
    add: { imports: [StubFoodCarouselComponent] },
  });
  const fixture = TestBed.createComponent(ContentGroupsComponent);
  fixture.componentRef.setInput('groups', groups);
  fixture.componentRef.setInput('menu', menu);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const dishIds = (section: number) =>
    Array.from(host.querySelectorAll('section')[section].querySelectorAll('.dish')).map((d) =>
      d.getAttribute('data-id'),
    );
  return { fixture, host, events, dishIds };
}

describe('ContentGroupsComponent', () => {
  it('draws a titled section for a group, with the dishes under it', () => {
    const { host, dishIds } = render([manual('g1', 'p1', 'p2')]);

    expect(host.querySelectorAll('section')).toHaveLength(1);
    expect(host.querySelector('section h2')?.textContent).toContain('Group g1');
    expect(dishIds(0)).toEqual(['p1', 'p2']);
  });

  it('marks the language the title is written in', () => {
    const { host } = render([manual('g1', 'p1')]);

    expect(host.querySelector('section')?.getAttribute('lang')).toBe('ru');
  });

  it('a manual group keeps its own order, not the menu order', () => {
    const { dishIds } = render([manual('g1', 'p5', 'p1', 'p3')]);

    expect(dishIds(0)).toEqual(['p5', 'p1', 'p3']);
  });

  it('a manual group skips an id the menu lacks', () => {
    const { dishIds } = render([manual('g1', 'p1', 'gone', 'p3')]);

    expect(dishIds(0)).toEqual(['p1', 'p3']);
  });

  it('a by-category group takes that category from the same menu, capped at its limit', () => {
    const { dishIds } = render([byCategory('g1', 'c1', 2)]);

    expect(dishIds(0)).toEqual(['p1', 'p2']);
  });

  it('a group that ends up empty renders nothing: no title, no section', () => {
    const { host, events } = render([manual('g1', 'gone', 'also-gone'), byCategory('g2', 'nope')]);

    expect(host.querySelector('section')).toBeNull();
    expect(host.querySelector('h2')).toBeNull();
    expect(events.impression).not.toHaveBeenCalled();
  });

  it('draws the groups that have dishes and none of the empty ones, in order', () => {
    const { host } = render([manual('g1', 'gone'), manual('g2', 'p1'), byCategory('g3', 'c2')]);

    expect(Array.from(host.querySelectorAll('h2')).map((h) => h.textContent?.trim())).toEqual([
      'Group g2',
      'Group g3',
    ]);
  });

  it('renders nothing against a menu that has not loaded', () => {
    const { host } = render([manual('g1', 'p1')], []);

    expect(host.querySelector('section')).toBeNull();
  });

  it('draws the dishes when the menu arrives after the groups', () => {
    const { fixture, host } = render([manual('g1', 'p1')], []);

    fixture.componentRef.setInput('menu', MENU);
    fixture.detectChanges();

    expect(host.querySelectorAll('section')).toHaveLength(1);
  });

  it('counts an impression of the group and its revision', () => {
    const { events } = render([manual('g1', 'p1')]);

    expect(events.impression).toHaveBeenCalledWith('g1', 2);
  });

  it('counts a click when a dish link inside the group is taken', () => {
    const { host, events } = render([manual('g1', 'p1')]);

    host
      .querySelector<HTMLAnchorElement>('.dish')
      ?.addEventListener('click', (e) => e.preventDefault());
    host.querySelector<HTMLAnchorElement>('.dish')?.click();

    expect(events.click).toHaveBeenCalledTimes(1);
    expect(events.click).toHaveBeenCalledWith('g1', 2);
  });

  it('counts no click for a tap that is not a link', () => {
    const { host, events } = render([manual('g1', 'p1')]);

    host.querySelector<HTMLButtonElement>('.add')?.click();
    host.querySelector('h2')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(events.click).not.toHaveBeenCalled();
  });
});
