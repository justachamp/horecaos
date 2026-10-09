import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import type { ContentLink } from '../../types/content.types';
import { uuid } from '../../testing/content-fixtures';
import { ContentLinkComponent } from './content-link.component';

@Component({
  standalone: true,
  imports: [ContentLinkComponent],
  template: `<app-content-link [link]="link" (activated)="activations = activations + 1">
    <span class="inner">Inside</span>
  </app-content-link>`,
})
class HostComponent {
  link: ContentLink | null = null;
  activations = 0;
}

function render(link: ContentLink | null) {
  TestBed.configureTestingModule({
    providers: [provideRouter([{ path: '**', children: [] }])],
  });
  const fixture = TestBed.createComponent(HostComponent);
  fixture.componentInstance.link = link;
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const anchor = host.querySelector<HTMLAnchorElement>('a');
  return { fixture, host, anchor };
}

describe('ContentLinkComponent: a tap on content goes only where the client allows', () => {
  it('links a PRODUCT to its page, around what is inside', () => {
    const { anchor } = render({ type: 'PRODUCT', targetId: uuid(5) });

    expect(anchor?.getAttribute('href')).toBe(`/product/${uuid(5)}`);
    expect(anchor?.querySelector('.inner')?.textContent).toBe('Inside');
    expect(anchor?.hasAttribute('target')).toBe(false);
  });

  it('links a CATEGORY to the home screen with that category chosen', () => {
    const { anchor } = render({ type: 'CATEGORY', targetId: uuid(6) });

    expect(anchor?.getAttribute('href')).toBe(`/home?category=${uuid(6)}`);
  });

  it('links CART to the cart', () => {
    expect(render({ type: 'CART' }).anchor?.getAttribute('href')).toBe('/cart');
  });

  it('opens an https address in a new tab that cannot reach back to this one', () => {
    const { anchor } = render({ type: 'EXTERNAL', url: 'https://example.uz/offer' });

    expect(anchor?.getAttribute('href')).toBe('https://example.uz/offer');
    expect(anchor?.getAttribute('target')).toBe('_blank');
    expect(anchor?.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it.each([
    ['javascript:alert(1)'],
    ['data:text/html,<b>x</b>'],
    ['http://example.uz/plain'],
    ['//example.uz/x'],
    ['https://user:pw@example.uz/'],
  ])('does not link an EXTERNAL address of %s: the content shows, nothing is a link', (url) => {
    const { host, anchor } = render({ type: 'EXTERNAL', url });

    expect(anchor).toBeNull();
    expect(host.querySelector('[href]')).toBeNull();
    expect(host.querySelector('.inner')?.textContent).toBe('Inside');
  });

  it.each<[string, ContentLink | null]>([
    ['NONE', { type: 'NONE' }],
    [
      'PROMOTION, which this storefront has no screen for',
      { type: 'PROMOTION', targetId: uuid(7) },
    ],
    ['a PRODUCT with no target', { type: 'PRODUCT' }],
    ['no link at all', null],
  ])('does not link %s', (_label, link) => {
    const { host, anchor } = render(link);

    expect(anchor).toBeNull();
    expect(host.querySelector('.inner')?.textContent).toBe('Inside');
  });

  it('says it was activated when a real link is tapped, and not for what is not a link', () => {
    const link = render({ type: 'CART' });
    link.anchor?.click();
    expect(link.fixture.componentInstance.activations).toBe(1);

    TestBed.resetTestingModule();
    const plain = render({ type: 'NONE' });
    plain.host.querySelector<HTMLElement>('.inner')?.click();
    expect(plain.fixture.componentInstance.activations).toBe(0);
  });
});
