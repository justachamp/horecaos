import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { FeatureFlags } from '../../core/feature-flags';
import { I18n } from '../../core/i18n/i18n';
import { BrandView } from './brand-profile/brand-profile-api';
import { LocationView } from './locations/locations-api';
import { SettingsScope } from './settings-scope';
import { SettingsShell } from './settings-shell';

const BRANDS: readonly BrandView[] = [
  {
    id: 'brand-1',
    tenantId: 't1',
    code: 'A',
    slug: 'a',
    displayName: 'Rayhon',
    status: 'ACTIVE',
    contactPhone: null,
    telegramHandle: null,
    logoAssetId: null,
    bannerAssetId: null,
    locales: [],
    version: 0,
  },
  {
    id: 'brand-2',
    tenantId: 't1',
    code: 'B',
    slug: 'b',
    displayName: 'Rayhon Yunusabad',
    status: 'ACTIVE',
    contactPhone: null,
    telegramHandle: null,
    logoAssetId: null,
    bannerAssetId: null,
    locales: [],
    version: 0,
  },
];

class FakeSettingsScope {
  readonly brands = signal<readonly BrandView[]>(BRANDS);
  readonly locations = signal<readonly LocationView[]>([]);
  readonly brandId = signal('brand-1');
  readonly locationId = signal<string | null>(null);
  readonly level = signal<'TENANT' | 'BRAND' | 'LOCATION'>('BRAND');
  readonly tenantWide = signal(false);
  readonly showBrandPicker = signal(true);
  setBrand = (): void => {};
  setLocation = (): void => {};
  setTenantLevel = (): void => {
    this.tenantWide.set(true);
  };
  leaveTenantLevel = (): void => {
    this.tenantWide.set(false);
  };
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
        { provide: SessionCapabilities, useValue: { has: () => true } },
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

  // Row 10.3b: the scope bar's TENANT/per-brand pill pair.

  it('switches to TENANT level when the company-wide pill is clicked', () => {
    (
      fixture.nativeElement.querySelector(
        '[data-testid="scope-bar-tenant-wide"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(scope.tenantWide()).toBe(true);
  });

  it('leaves TENANT level when the per-brand pill is clicked', () => {
    scope.tenantWide.set(true);
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="scope-bar-per-brand"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(scope.tenantWide()).toBe(false);
  });
});

describe('SettingsShell for a branch manager who pairs kiosks (ADR 0162)', () => {
  let fixture: ComponentFixture<SettingsShell>;

  async function railFor(held: readonly Capability[]): Promise<string[]> {
    await TestBed.configureTestingModule({
      imports: [SettingsShell],
      providers: [
        provideRouter([]),
        {
          provide: FeatureFlags,
          useValue: {
            isOn: (flag: string) => flag === 'feature.kiosk_devices',
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: SettingsScope, useValue: new FakeSettingsScope() },
        {
          provide: SessionCapabilities,
          useValue: { has: (capability: Capability) => held.includes(capability) },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SettingsShell);
    fixture.detectChanges();
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('.settings__item')].map(
      (link) => link.textContent?.trim() ?? '',
    );
  }

  it('offers her the kiosks entry and none of the tenant’s settings she cannot read', async () => {
    expect(await railFor(['KIOSK_DEVICE_MANAGE', 'IAM_GRANT_MANAGE'])).toEqual(['Kiosks']);
  });

  it('offers her no scope bar: the screens she is given work the branch she is in, and read none of it', async () => {
    await railFor(['KIOSK_DEVICE_MANAGE', 'IAM_GRANT_MANAGE']);

    expect(fixture.nativeElement.querySelector('q-scope-bar')).toBeNull();
  });

  it('offers the owner the whole rail, kiosks among it, and the scope bar', async () => {
    const rail = await railFor(['TENANT_READ', 'KIOSK_DEVICE_MANAGE']);

    expect(rail).toContain('Kiosks');
    expect(rail).toContain('Brand profile');
    expect(fixture.nativeElement.querySelector('q-scope-bar')).not.toBeNull();
  });
});
