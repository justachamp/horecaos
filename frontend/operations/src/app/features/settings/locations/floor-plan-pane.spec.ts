import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import {
  DineInApi,
  DineInSettingsView,
  QrRotationView,
  SectionView,
  TableView,
} from './dinein-api';
import { FloorPlanPane } from './floor-plan-pane';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const SETTINGS: DineInSettingsView = {
  locationId: 'l1',
  qrMode: 'ORDER_AND_PAY',
  turnaroundMinutes: 15,
  guestSessionTtlMinutes: 240,
  serviceChargeRateBp: 1000,
  version: 1,
};

const SECTION: SectionView = {
  sectionId: 's1',
  code: 'MAIN',
  displayName: 'Main hall',
  sortOrder: 1,
  status: 'ACTIVE',
  version: 1,
};

const TABLE: TableView = {
  tableId: 'tb1',
  sectionId: 's1',
  code: 'T1',
  displayName: 'Table 1',
  seats: 4,
  joinable: false,
  layoutX: 40,
  layoutY: 40,
  status: 'ACTIVE',
  qrIssued: false,
  qrRotatedAt: null,
  version: 1,
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('FloorPlanPane', () => {
  let fixture: ComponentFixture<FloorPlanPane>;

  async function render(api: Partial<DineInApi> = {}): Promise<HTMLElement> {
    const defaults: Partial<DineInApi> = {
      settings: () => Promise.resolve(SETTINGS),
      sections: () => Promise.resolve([SECTION]),
      tables: () => Promise.resolve([TABLE]),
      ...api,
    };
    await TestBed.configureTestingModule({
      imports: [FloorPlanPane],
      providers: [{ provide: DineInApi, useValue: defaults }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FloorPlanPane);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('branchName', 'Chilonzor');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  // couriers.md/settings.md/ADR 0047: SETTLE_OPEN_TICKET is refused, not
  // missing — it must appear, disabled, with its reason, never omitted.
  it('renders SETTLE_OPEN_TICKET as a disabled option with its refusal reason, never as a missing mode', async () => {
    const host = await render();

    // Open the settings editor.
    Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent?.trim() === 'Edit')!
      .click();
    fixture.detectChanges();

    const option = host.querySelector<HTMLOptionElement>(
      '[data-testid="floorplan-qr-mode-option-SETTLE_OPEN_TICKET"]',
    );
    expect(option).not.toBeNull();
    expect(option!.disabled).toBe(true);
    expect(option!.textContent).toContain('Settle an open ticket');

    const reason = host.querySelector('[data-testid="floorplan-qr-mode-settle-reason"]');
    expect(reason?.textContent).toContain('ADR 0011/0047');

    // The two real modes stay selectable.
    expect(
      host.querySelector<HTMLOptionElement>('[data-testid="floorplan-qr-mode-option-VIEW_ONLY"]')
        ?.disabled,
    ).toBe(false);
    expect(
      host.querySelector<HTMLOptionElement>(
        '[data-testid="floorplan-qr-mode-option-ORDER_AND_PAY"]',
      )?.disabled,
    ).toBe(false);
  });

  it('renders the branch’s sections as tabs and the active section’s tables on the canvas', async () => {
    const host = await render();

    expect(host.textContent).toContain('Main hall');
    expect(host.querySelector('[data-testid="table-token-tb1"]')).not.toBeNull();
  });

  it('dragging a table on the canvas saves the new position through the API', async () => {
    const moveTable = vi
      .fn()
      .mockResolvedValue({ ...TABLE, layoutX: 200, layoutY: 150, version: 2 });
    const host = await render({ moveTable });

    const token = host.querySelector<HTMLElement>('[data-testid="table-token-tb1"]')!;
    token.dispatchEvent(
      new PointerEvent('pointerdown', { bubbles: true, clientX: 0, clientY: 0, pointerId: 1 }),
    );
    token.dispatchEvent(
      new PointerEvent('pointermove', { bubbles: true, clientX: 160, clientY: 110, pointerId: 1 }),
    );
    token.dispatchEvent(
      new PointerEvent('pointerup', { bubbles: true, clientX: 160, clientY: 110, pointerId: 1 }),
    );
    await flushMicrotasks();
    fixture.detectChanges();

    // Table started at (40, 40); dragged by (+160, +110) to (200, 150).
    expect(moveTable).toHaveBeenCalledWith(SCOPE, 'tb1', 200, 150, expect.any(String), 1);
  });

  it('issuing a QR code shows the printable card, the revoked-session count, and the token exactly once', async () => {
    const rotation: QrRotationView = {
      tableId: 'tb1',
      qrToken: 'plaintext-token-abc',
      rotatedAt: '2026-09-14T12:00:00Z',
      version: 2,
      revokedGuestSessions: 3,
    };
    const rotateQrToken = vi.fn().mockResolvedValue(rotation);
    const host = await render({ rotateQrToken });

    host
      .querySelector<HTMLElement>('[data-testid="table-token-tb1"]')!
      .dispatchEvent(
        new PointerEvent('pointerdown', { bubbles: true, clientX: 0, clientY: 0, pointerId: 1 }),
      );
    fixture.detectChanges();

    const reasonInput = host.querySelector<HTMLInputElement>(
      '[data-testid="floorplan-rotate-reason"]',
    )!;
    reasonInput.value = 'First issue for this table';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    host.querySelector<HTMLButtonElement>('[data-testid="floorplan-rotate-button"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(rotateQrToken).toHaveBeenCalledWith(SCOPE, 'tb1', 'First issue for this table', 1);
    expect(host.querySelector('[data-testid="floorplan-revoked-count"]')?.textContent).toContain(
      '3',
    );
    expect(host.querySelector('[data-testid="table-print-card-token"]')?.textContent).toContain(
      'plaintext-token-abc',
    );
  });

  it('shows "no sections yet" rather than an empty canvas when the branch has none configured', async () => {
    const host = await render({ sections: () => Promise.resolve([]) });

    expect(host.textContent).toContain('No sections yet');
    expect(host.querySelector('[data-testid="floor-plan-canvas"]')).toBeNull();
  });
});
