import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { ConfigurationApi } from '../settings/configuration-api';
import { MFA_REQUIREMENT_KEY, StaffMfaPolicyCard } from './staff-mfa-policy-card';

describe('StaffMfaPolicyCard (ADR 0148)', () => {
  let fixture: ComponentFixture<StaffMfaPolicyCard>;
  let config: { resolution: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };

  async function setUp(held: readonly string[], stored: string | null = null): Promise<void> {
    config = { resolution: vi.fn(), setValue: vi.fn() };
    config.resolution.mockResolvedValue({
      keyCode: MFA_REQUIREMENT_KEY,
      value: stored ?? 'OFF',
      currentVersionAtScope: stored === null ? null : 3,
    });
    await TestBed.configureTestingModule({
      imports: [StaffMfaPolicyCard],
      providers: [
        { provide: ConfigurationApi, useValue: config },
        {
          provide: CurrentTenant,
          useValue: { tenantId: () => 't1', ensureLoaded: () => Promise.resolve() },
        },
        { provide: SessionCapabilities, useValue: { has: (c: string) => held.includes(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StaffMfaPolicyCard);
    fixture.detectChanges();
    await settle();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const radios = (): HTMLInputElement[] =>
    Array.from(root().querySelectorAll('input[type="radio"]'));

  beforeEach(() => TestBed.resetTestingModule());

  it('is not shown, and reads nothing, without the right to change tenant settings', async () => {
    await setUp([]);

    expect(root().querySelector('[data-testid="mfa-policy-card"]')).toBeNull();
    expect(config.resolution).not.toHaveBeenCalled();
  });

  it('shows the three modes with the stored one chosen, and nothing to save until it changes', async () => {
    await setUp(['TENANT_CONFIGURATION_WRITE'], 'SENSITIVE_ROLES');

    expect(radios().map((radio) => radio.value)).toEqual(['OFF', 'SENSITIVE_ROLES', 'ALL_STAFF']);
    expect(radios().find((radio) => radio.checked)?.value).toBe('SENSITIVE_ROLES');
    expect(
      (root().querySelector('[data-testid="mfa-policy-save"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(root().textContent).toContain('Owner, administrator, finance and brand manager');
  });

  it('writes the chosen mode at tenant scope with the stored version, and says who is asked at next sign-in', async () => {
    await setUp(['TENANT_CONFIGURATION_WRITE'], 'OFF');
    config.setValue.mockResolvedValue({ version: 4 });

    radios()[2].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (root().querySelector('[data-testid="mfa-policy-save"]') as HTMLButtonElement).click();
    await settle();

    expect(config.setValue).toHaveBeenCalledWith(
      't1',
      MFA_REQUIREMENT_KEY,
      expect.objectContaining({
        scopeType: 'TENANT',
        stringValue: 'ALL_STAFF',
        expectedVersion: 3,
        explicitNull: false,
      }),
    );
    expect(root().querySelector('[data-testid="mfa-policy-message"]')?.textContent).toContain(
      'next sign-in',
    );
  });

  it('says it did not save when the platform refuses, and keeps the choice', async () => {
    await setUp(['TENANT_CONFIGURATION_WRITE'], 'OFF');
    config.setValue.mockRejectedValue(new Error('refused'));

    radios()[1].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (root().querySelector('[data-testid="mfa-policy-save"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(root().querySelector('[data-testid="mfa-policy-message"]')?.textContent).toContain(
      'Could not save',
    );
    expect(radios().find((radio) => radio.checked)?.value).toBe('SENSITIVE_ROLES');
  });
});
