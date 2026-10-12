import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import type { ResolvedContentLink } from '../../utils/content-links';
import { ContentLinkComponent } from './content-link.component';

@Component({
  imports: [ContentLinkComponent],
  template: `
    <app-content-link [target]="target" anchorClass="tile" (activated)="activated()">
      <span id="inner">inside</span>
    </app-content-link>
  `,
})
class HostComponent {
  target: ResolvedContentLink | null = null;
  activated = vi.fn();
}

function render(target: ResolvedContentLink | null) {
  TestBed.configureTestingModule({
    providers: [provideRouter([{ path: '**', children: [] }])],
  });
  const fixture = TestBed.createComponent(HostComponent);
  fixture.componentInstance.target = target;
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, host1: fixture.componentInstance };
}

describe('ContentLinkComponent', () => {
  beforeEach(() => sessionStorage.clear());

  it('with no target is a plain figure around the content, not a link', () => {
    const { host } = render(null);

    expect(host.querySelector('a')).toBeNull();
    expect(host.querySelector('figure.tile #inner')).not.toBeNull();
  });

  it('a route target is an anchor to that route', () => {
    const { host } = render({ kind: 'route', commands: ['/product', 'p-1'] });

    const anchor = host.querySelector('a.tile');
    expect(anchor?.getAttribute('href')).toBe('/product/p-1');
    expect(anchor?.querySelector('#inner')).not.toBeNull();
    expect(host.querySelector('figure')).toBeNull();
  });

  it('an external target opens in a new tab without handing over the opener', () => {
    const { host } = render({ kind: 'external', href: 'https://example.com/a' });

    const anchor = host.querySelector('a.tile');
    expect(anchor?.getAttribute('href')).toBe('https://example.com/a');
    expect(anchor?.getAttribute('target')).toBe('_blank');
    expect(anchor?.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('a route link stays in the tab: no target attribute', () => {
    const { host } = render({ kind: 'route', commands: ['/cart'] });

    expect(host.querySelector('a.tile')?.hasAttribute('target')).toBe(false);
  });

  it('says it was activated when the anchor is clicked', async () => {
    const { fixture, host, host1 } = render({ kind: 'route', commands: ['/cart'] });

    host.querySelector<HTMLAnchorElement>('a.tile')?.click();
    await fixture.whenStable();

    expect(host1.activated).toHaveBeenCalledTimes(1);
  });

  it('a category link leaves the choice for the category page before it navigates', async () => {
    const { fixture, host } = render({
      kind: 'route',
      commands: ['/category'],
      category: { id: 'c-1', name: 'Desserts' },
    });

    host.querySelector<HTMLAnchorElement>('a.tile')?.click();
    await fixture.whenStable();

    expect(JSON.parse(sessionStorage.getItem('mar_selected_cat') ?? 'null')).toEqual({
      id: 'c-1',
      name: 'Desserts',
    });
  });
});
