import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { FeatureFlags } from '../../core/feature-flags';
import { I18n } from '../../core/i18n/i18n';
import { BrandView } from './brand-profile/brand-profile-api';
import { LocationView } from './locations/locations-api';
import { SettingsScope } from './settings-scope';
import { SettingsShell } from './settings-shell';

const BRANDS: readonly BrandView[] = [
  { id: 'brand-1', tenantId: 't1', code: 'A', slug: 'a', displayName: 'Rayhon', status: 'ACTIVE' },
  {
    id: 'brand-2',
    tenantId: 't1',
    code: 'B',
    slug: 'b',
    displayName: 'Rayhon Yunusabad',
    status: 'ACTIVE',
  },
];

class FakeSettingsScope {
  readonly brands = signal<readonly BrandView[]>(BRANDS);
  readonly locations = signal<readonly LocationView[]>([]);
  readonly brandId = signal('brand-1');
  readonly locationId = signal<string | null>(null);
  readonly level = signal<'BRAND' | 'LOCATION'>('BRAND');
  readonly showBrandPicker = signal(true);
  setBrand = (): void => {};
  setLocation = (): void => {};
}

describe('SettingsShell', () => {
  let fixture: ComponentFixture<SettingsShell>;
  let scope: FakeSettingsScope;

  beforeEach(async () => {
    scope = new FakeSettingsScope();
    await TestBed.configureTestingModule({
      imports: [SettingsShell],
      providers: [
        provideRouter([]),
        {
          provide: FeatureFlags,
          useValue: { isOn: () => false, ensureLoaded: () => Promise.resolve() },
        },
        { provide: SettingsScope, useValue: scope },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SettingsShell);
    fixture.detectChanges();
  });

  it('renders the scope bar instead of a raw brand/location UUID pair', () => {
    expect(fixture.nativeElement.querySelector('q-scope-bar')).toBeTruthy();
    // No stray UUID text nodes from the pattern this replaced.
    expect(fixture.nativeElement.textContent).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-/);
  });

  it("preserves the scope bar's query params when navigating between rail screens", () => {
    const links: HTMLAnchorElement[] = [
      ...fixture.nativeElement.querySelectorAll('.settings__item'),
    ];
    expect(links.length).toBeGreaterThan(0);
    for (const link of links) {
      expect(link.getAttribute('queryParamsHandling')).toBe('preserve');
    }
  });
});
