import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { ReadinessApi, ValidationOutcome } from './readiness-api';
import { SettingsHomePage } from './settings-home-page';

const TENANT_ID = 'tenant-1';

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function render(
  readiness: Partial<ReadinessApi>,
): Promise<ComponentFixture<SettingsHomePage>> {
  await TestBed.configureTestingModule({
    imports: [SettingsHomePage],
    providers: [
      provideRouter([]),
      {
        provide: CurrentTenant,
        useValue: {
          tenantId: () => TENANT_ID,
          denied: () => false,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: ReadinessApi, useValue: readiness },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(SettingsHomePage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

const PASSING: ValidationOutcome = {
  allPassed: true,
  checks: [
    {
      stepKey: 'BRANDS_AND_LOCATIONS_VALIDATE',
      passed: true,
      errorCode: null,
      detail: null,
      locationId: null,
    },
  ],
};

describe('SettingsHomePage', () => {
  it('renders every P-tier screen and the moved Integrations screen as a tile', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const labels = [...fixture.nativeElement.querySelectorAll('.tile__label')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(labels).toContain('Brand profile');
    expect(labels).toContain('Locations');
    expect(labels).toContain('Sales channels');
    expect(labels).toContain('Order policy');
    expect(labels).toContain('Fiscalization');
    expect(labels).toContain('Notifications');
    expect(labels).toContain('Integrations');
    expect(labels).toContain('Reference data');
    expect(labels).toContain('Data & privacy');
  });

  it('badges a screen that has no built route yet', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const tiles = [...fixture.nativeElement.querySelectorAll('.tile')];
    const channelSetup = tiles.find((tile: Element) => tile.textContent?.includes('Channel setup'));
    expect(channelSetup?.querySelector('.tile__badge')).toBeTruthy();

    const salesChannels = tiles.find((tile: Element) =>
      tile.textContent?.includes('Sales channels'),
    );
    expect(salesChannels?.querySelector('.tile__badge')).toBeFalsy();
  });

  it('groups tiles under the spec nav groups, not an alphabetical list', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    const groupLabels = [...fixture.nativeElement.querySelectorAll('.group h2')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(groupLabels).toEqual([
      'The business',
      'Selling',
      'Money and tax',
      'Messages',
      'Connections',
      'Reference',
      'Privacy',
    ]);
  });

  it('shows the success empty state when every readiness check passes', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    expect(fixture.nativeElement.querySelector('.readiness__clear')?.textContent).toContain(
      'Everything is set up to take orders',
    );
  });

  it('counts every offending location as its own row, not one row for the whole check', async () => {
    const outcome: ValidationOutcome = {
      allPassed: false,
      checks: [
        {
          stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
          passed: false,
          errorCode: 'NO_LEGAL_ENTITY',
          detail: 'Location CHI has no active legal entity assigned',
          locationId: 'loc-1',
        },
        {
          stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
          passed: false,
          errorCode: 'NO_LEGAL_ENTITY',
          detail: 'Location YUN has no active legal entity assigned',
          locationId: 'loc-2',
        },
      ],
    };
    const fixture = await render({ validate: () => Promise.resolve(outcome) });

    const rows = fixture.nativeElement.querySelectorAll('.readiness__row');
    expect(rows.length).toBe(2);
  });

  it('deep-links a location-scoped finding into that location', async () => {
    const outcome: ValidationOutcome = {
      allPassed: false,
      checks: [
        {
          stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
          passed: false,
          errorCode: 'NO_LEGAL_ENTITY',
          detail: 'Location CHI has no active legal entity assigned',
          locationId: 'loc-1',
        },
      ],
    };
    const fixture = await render({ validate: () => Promise.resolve(outcome) });

    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('.readiness__row a');
    expect(link.getAttribute('href')).toBe('/settings/locations/loc-1');
  });

  it('renders the denied state on a 403 from the readiness check', async () => {
    const fixture = await render({
      validate: () => Promise.reject(new ApiError('INSUFFICIENT_CAPABILITY', 403, null, null)),
    });
    expect(fixture.nativeElement.textContent).toContain('owner and administrators');
  });

  it('filters the index by "/" find-a-setting text, across label and description', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });

    const input: HTMLInputElement = fixture.nativeElement.querySelector('.search__input');
    input.value = 'fiscalization';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const labels = [...fixture.nativeElement.querySelectorAll('.tile__label')].map(
      (node: Element) => node.textContent?.trim(),
    );
    expect(labels).toEqual(['Fiscalization']);
  });

  it('shows the empty search state when nothing matches', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });

    const input: HTMLInputElement = fixture.nativeElement.querySelector('.search__input');
    input.value = 'zzzz-no-such-setting';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('.tile__label').length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('No setting matches');
  });

  it('focuses the search box when "/" is pressed anywhere on the page', async () => {
    const fixture = await render({ validate: () => Promise.resolve(PASSING) });
    document.body.appendChild(fixture.nativeElement);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: '/', bubbles: true }));

    const input: HTMLInputElement = fixture.nativeElement.querySelector('.search__input');
    expect(document.activeElement).toBe(input);

    fixture.nativeElement.remove();
  });
});
