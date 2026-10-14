import { TestBed } from '@angular/core/testing';

import { ReducedMotionService } from '../../services/reduced-motion.service';
import { TranslateService } from '../../services/translate.service';
import type { ContentBrandSwitcherEntry } from '../../types/content.types';
import { ContentBrandSwitcherComponent } from './content-brand-switcher.component';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function entry(
  n: number,
  overrides: Partial<ContentBrandSwitcherEntry> = {},
): ContentBrandSwitcherEntry {
  return {
    targetBrandId: `brand-${n}`,
    name: `Brand ${n}`,
    url: `https://brand${n}.example.uz/`,
    ...overrides,
  };
}

const LOGO = {
  kind: 'IMAGE' as const,
  alt: 'Logo',
  renditions: [{ widthPx: 64, url: '/m/logo?variant=w64' }],
};

function render(entries: readonly ContentBrandSwitcherEntry[]) {
  TestBed.configureTestingModule({
    imports: [ContentBrandSwitcherComponent],
    providers: [
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: ReducedMotionService, useValue: { reduced: () => false } },
    ],
  });
  const fixture = TestBed.createComponent(ContentBrandSwitcherComponent);
  document.body.appendChild(fixture.nativeElement);
  fixture.componentRef.setInput('entries', entries);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, links: () => Array.from(host.querySelectorAll<HTMLAnchorElement>('a')) };
}

describe('ContentBrandSwitcherComponent (ADR 0183 D17)', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('draws nothing at all for a brand that offers no other brand', () => {
    const { host } = render([]);

    expect(host.querySelector('nav')).toBeNull();
    expect((host.textContent ?? '').trim()).toBe('');
  });

  it('is a labelled navigation with one link per brand, in the order given, to the address given', () => {
    const { host, links } = render([entry(1), entry(2)]);

    expect(host.querySelector('nav')?.getAttribute('aria-label')).toBe('content.brandSwitcher');
    expect(links().map((link) => link.getAttribute('href'))).toEqual([
      'https://brand1.example.uz/',
      'https://brand2.example.uz/',
    ]);
    expect(links().map((link) => (link.textContent ?? '').trim())).toEqual([
      expect.stringContaining('Brand 1'),
      expect.stringContaining('Brand 2'),
    ]);
  });

  it('leaves this storefront safely: the link carries noopener and noreferrer', () => {
    const { links } = render([entry(1)]);

    expect(links()[0].getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('draws the logo from its renditions, and the first letter of the name when there is none', () => {
    const { host } = render([entry(1, { logo: LOGO }), entry(2)]);

    const images = Array.from(host.querySelectorAll('img'));
    expect(images).toHaveLength(1);
    expect(images[0].getAttribute('srcset')).toBe('/m/logo?variant=w64 64w');
    const initials = Array.from(host.querySelectorAll('[data-testid="brand-switcher-initial"]'));
    expect(initials.map((node) => node.textContent)).toEqual(['B']);
  });

  it('replaces a logo that fails to load with the initial; the brand stays reachable', () => {
    const { fixture, host, links } = render([entry(1, { logo: LOGO })]);

    host.querySelector('img')?.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    expect(host.querySelector('img')).toBeNull();
    expect(host.querySelector('[data-testid="brand-switcher-initial"]')?.textContent).toBe('B');
    expect(links()).toHaveLength(1);
  });
});
