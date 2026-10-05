import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { Toasts } from '../../../shared/ui/toast';
import { ConfigurationApi, ConfigurationResolutionView } from '../configuration-api';
import { CatalogSettingsPage } from './catalog-settings-page';

const TENANT_ID = 'tenant-1';

const USE_STOCK_LOGIC_OFF: ConfigurationResolutionView = {
  keyCode: 'catalog.use_stock_logic',
  value: false,
  cameFromDefault: true,
  source: 'CODE_DEFAULT',
  winningScope: null,
  inspectedLevels: [{ scopeType: 'TENANT', outcome: 'NOT_SET' }],
  describe: 'catalog.use_stock_logic -> CODE_DEFAULT',
  currentVersionAtScope: null,
};

const QR_KIOSK_OFF: ConfigurationResolutionView = {
  keyCode: 'catalog.qr_kiosk_price_plane',
  value: false,
  cameFromDefault: true,
  source: 'CODE_DEFAULT',
  winningScope: null,
  inspectedLevels: [{ scopeType: 'TENANT', outcome: 'NOT_SET' }],
  describe: 'catalog.qr_kiosk_price_plane -> CODE_DEFAULT',
  currentVersionAtScope: null,
};

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>(TENANT_ID);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CatalogSettingsPage', () => {
  let fixture: ComponentFixture<CatalogSettingsPage>;
  let api: { resolution: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      resolution: vi
        .fn()
        .mockImplementation((_tenantId: string, code: string) =>
          Promise.resolve(code === 'catalog.use_stock_logic' ? USE_STOCK_LOGIC_OFF : QR_KIOSK_OFF),
        ),
      setValue: vi.fn().mockResolvedValue({
        id: 'value-1',
        keyCode: 'catalog.qr_kiosk_price_plane',
        scopeType: 'TENANT',
        value: true,
        explicitNull: false,
        version: 1,
      }),
    };

    await TestBed.configureTestingModule({
      imports: [CatalogSettingsPage],
      providers: [
        { provide: ConfigurationApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CatalogSettingsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('reads both tenant-wide switches at TENANT scope, never brand or location', () => {
    expect(api.resolution).toHaveBeenCalledWith(TENANT_ID, 'catalog.use_stock_logic', 'TENANT');
    expect(api.resolution).toHaveBeenCalledWith(
      TENANT_ID,
      'catalog.qr_kiosk_price_plane',
      'TENANT',
    );
  });

  it('renders catalog.use_stock_logic through the same inherited-field control as the other switch, with its hint', () => {
    expect(
      fixture.nativeElement.querySelector('[data-testid="use-stock-logic-hint"]'),
    ).toBeTruthy();
    // Both switches now use the identical q-inherited-field control (batch 11: use_stock_logic is real, not read-only).
    expect(fixture.nativeElement.querySelectorAll('q-inherited-field').length).toBe(2);
  });

  it('publishes catalog.use_stock_logic with a required reason, at TENANT scope', async () => {
    const overrideButtons = fixture.nativeElement.querySelectorAll(
      'q-inherited-field .field__action',
    ) as NodeListOf<HTMLButtonElement>;
    overrideButtons[0].click();
    fixture.detectChanges();

    const checkbox = fixture.nativeElement.querySelector(
      'input[type="checkbox"]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));

    const reasonInput = fixture.nativeElement.querySelector(
      '#use-stock-logic-reason',
    ) as HTMLInputElement;
    reasonInput.value = 'Reconciled counts for the pilot menu';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const publishButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement;
    expect(publishButton.disabled).toBe(false);
    publishButton.click();
    await flushMicrotasks();

    expect(api.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'catalog.use_stock_logic',
      expect.objectContaining({
        scopeType: 'TENANT',
        booleanValue: true,
        reason: 'Reconciled counts for the pilot menu',
      }),
    );
  });

  it('publishes catalog.qr_kiosk_price_plane with a required reason, at TENANT scope', async () => {
    const overrideButtons = fixture.nativeElement.querySelectorAll(
      'q-inherited-field .field__action',
    ) as NodeListOf<HTMLButtonElement>;
    overrideButtons[1].click();
    fixture.detectChanges();

    const checkbox = fixture.nativeElement.querySelector(
      'input[type="checkbox"]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));

    const reasonInput = fixture.nativeElement.querySelector('#qr-kiosk-reason') as HTMLInputElement;
    reasonInput.value = 'Enable ahead of the automatic price-plane wiring';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const publishButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement;
    expect(publishButton.disabled).toBe(false);
    publishButton.click();
    await flushMicrotasks();

    expect(api.setValue).toHaveBeenCalledWith(
      TENANT_ID,
      'catalog.qr_kiosk_price_plane',
      expect.objectContaining({
        scopeType: 'TENANT',
        booleanValue: true,
        reason: 'Enable ahead of the automatic price-plane wiring',
      }),
    );
  });
  describe('confirming a save in the toast host (row X.1)', () => {
    let toasts: Toasts;

    beforeEach(() => {
      toasts = TestBed.inject(Toasts);
      toasts.clear();
    });

    const messages = (): string[] => toasts.visible().map((toast) => toast.message);

    function overrideAndPublish(fieldIndex: number, reasonId: string): void {
      (
        fixture.nativeElement.querySelectorAll(
          'q-inherited-field .field__action',
        ) as NodeListOf<HTMLButtonElement>
      )[fieldIndex].click();
      fixture.detectChanges();
      const checkbox = fixture.nativeElement.querySelector(
        'input[type="checkbox"]',
      ) as HTMLInputElement;
      checkbox.checked = true;
      checkbox.dispatchEvent(new Event('change'));
      const reason = fixture.nativeElement.querySelector(`#${reasonId}`) as HTMLInputElement;
      reason.value = 'because';
      reason.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      (
        Array.from(
          (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
        ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement
      ).click();
    }

    it('names the whole company, and the new value, when a tenant-wide switch is published', async () => {
      api.resolution.mockImplementation((_tenantId: string, code: string) =>
        Promise.resolve(
          code === 'catalog.use_stock_logic'
            ? { ...USE_STOCK_LOGIC_OFF, value: true }
            : QR_KIOSK_OFF,
        ),
      );

      overrideAndPublish(0, 'use-stock-logic-reason');
      await flushMicrotasks();

      expect(messages()).toEqual(['Use stock logic: Yes — set for the whole company']);
    });

    it('says the QR and kiosk switch was published too', async () => {
      api.resolution.mockImplementation((_tenantId: string, code: string) =>
        Promise.resolve(
          code === 'catalog.qr_kiosk_price_plane'
            ? { ...QR_KIOSK_OFF, value: true }
            : USE_STOCK_LOGIC_OFF,
        ),
      );

      overrideAndPublish(1, 'qr-kiosk-reason');
      await flushMicrotasks();

      expect(messages()).toEqual([
        'QR and kiosk take hall prices: Yes — set for the whole company',
      ]);
    });

    it('stays silent when the write is refused', async () => {
      api.setValue.mockRejectedValue(new Error('refused'));

      overrideAndPublish(0, 'use-stock-logic-reason');
      await flushMicrotasks();

      expect(messages()).toEqual([]);
    });
  });
});
