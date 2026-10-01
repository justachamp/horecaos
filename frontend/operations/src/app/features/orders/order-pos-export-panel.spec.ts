import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderPosExportView, PosExportView } from './order-pos-export-api';
import { OrderPosExportPanel } from './order-pos-export-panel';

function exportView(overrides: Partial<PosExportView> = {}): PosExportView {
  return {
    exportId: 'e1',
    state: 'REJECTED',
    permitsAmendment: false,
    attemptCount: 1,
    externalOrderId: null,
    requestedAt: '2026-09-30T09:00:00Z',
    firstSentAt: null,
    settledAt: null,
    lastErrorCode: null,
    lastError: null,
    resolutionKind: null,
    resolutionReason: null,
    resolvedAt: null,
    unmappedEntityType: null,
    unmappedHorecaosEntityId: null,
    unmappedBindingId: null,
    ...overrides,
  } as PosExportView;
}

interface Inputs {
  posExport: OrderPosExportView | null;
  resultLabel: string | null;
  stateLabel: string | null;
  showsReassurance: boolean;
  hasMappingDeepLink: boolean;
  pushOpen: boolean;
  pushReason: string;
  pushError: string | null;
  pushSubmitting: boolean;
  canSubmitPush: boolean;
}

function render(overrides: Partial<Inputs> = {}) {
  const inputs: Inputs = {
    posExport: { posCapable: true, export: exportView() },
    resultLabel: null,
    stateLabel: 'The till refused it',
    showsReassurance: false,
    hasMappingDeepLink: false,
    pushOpen: false,
    pushReason: '',
    pushError: null,
    pushSubmitting: false,
    canSubmitPush: false,
    ...overrides,
  };
  const fixture = TestBed.createComponent(OrderPosExportPanel);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const byTestId = (id: string) => host.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  return { fixture, host, byTestId };
}

describe('OrderPosExportPanel', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('says the order has not been sent when no export was ever opened', () => {
    const { byTestId } = render({ posExport: { posCapable: true, export: null } });

    expect(byTestId('order-detail-pos-export-not-yet')?.textContent?.trim()).toBe(
      'Not yet sent to the till',
    );
    expect(byTestId('order-detail-pos-export-state')).toBeNull();
  });

  it('shows the export state the pane worded', () => {
    const { byTestId } = render();

    expect(byTestId('order-detail-pos-export-state')?.textContent?.trim()).toBe(
      'The till refused it',
    );
  });

  it('shows the reassurance, the till’s last answer and the outcome of a push only when there is one', () => {
    const quiet = render();
    expect(quiet.byTestId('order-detail-pos-export-reassurance')).toBeNull();
    expect(quiet.byTestId('order-detail-pos-export-last-error')).toBeNull();
    expect(quiet.byTestId('order-detail-pos-export-result')).toBeNull();

    const loud = render({
      showsReassurance: true,
      resultLabel: 'Sent',
      posExport: { posCapable: true, export: exportView({ lastError: 'LINE_UNMAPPED' }) },
    });
    expect(loud.byTestId('order-detail-pos-export-reassurance')).not.toBeNull();
    expect(loud.byTestId('order-detail-pos-export-last-error')?.textContent).toContain(
      'The till answered: LINE_UNMAPPED',
    );
    expect(loud.byTestId('order-detail-pos-export-result')?.textContent?.trim()).toBe('Sent');
  });

  it('offers the mapping link only when the failure is a mapping gap, and reports the click', () => {
    expect(render().byTestId('order-detail-pos-export-fix-mapping')).toBeNull();

    const { fixture, byTestId } = render({ hasMappingDeepLink: true });
    let requests = 0;
    fixture.componentInstance.mappingRequested.subscribe(() => requests++);
    byTestId('order-detail-pos-export-fix-mapping')?.click();

    expect(requests).toBe(1);
  });

  it('offers the push toggle until the form is open, then the form in its place', () => {
    const closed = render();
    expect(closed.byTestId('order-detail-pos-export-push-toggle')).not.toBeNull();
    expect(closed.byTestId('order-detail-pos-export-push-form')).toBeNull();

    const open = render({ pushOpen: true });
    expect(open.byTestId('order-detail-pos-export-push-toggle')).toBeNull();
    expect(open.byTestId('order-detail-pos-export-push-form')).not.toBeNull();
  });

  it('reports the push toggle, the reason typed, cancel and submit', () => {
    const closed = render();
    let opened = 0;
    closed.fixture.componentInstance.pushOpened.subscribe(() => opened++);
    closed.byTestId('order-detail-pos-export-push-toggle')?.click();
    expect(opened).toBe(1);

    const { fixture, host, byTestId } = render({ pushOpen: true, canSubmitPush: true });
    const seen: string[] = [];
    fixture.componentInstance.reasonChanged.subscribe((reason) => seen.push(`reason:${reason}`));
    fixture.componentInstance.pushCancelled.subscribe(() => seen.push('cancel'));
    fixture.componentInstance.pushSubmitted.subscribe(() => seen.push('submit'));

    const input = byTestId('order-detail-pos-export-push-reason') as HTMLInputElement;
    input.value = 'Till was offline';
    input.dispatchEvent(new Event('input'));
    host.querySelector<HTMLButtonElement>('.pane__pos-export-button--secondary')?.click();
    byTestId('order-detail-pos-export-push-submit')?.click();

    expect(seen).toEqual(['reason:Till was offline', 'cancel', 'submit']);
  });

  it('holds Send back until there is a reason, and says Sending… while it goes', () => {
    const idle = render({ pushOpen: true, canSubmitPush: false });
    const submit = idle.byTestId('order-detail-pos-export-push-submit') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(submit.textContent?.trim()).toBe('Send');

    const sending = render({ pushOpen: true, pushSubmitting: true });
    expect(sending.byTestId('order-detail-pos-export-push-submit')?.textContent?.trim()).toBe(
      'Sending…',
    );
  });

  it('shows a push failure inside the form', () => {
    const { byTestId } = render({ pushOpen: true, pushError: 'The till is unreachable' });

    expect(byTestId('order-detail-pos-export-push-error')?.textContent?.trim()).toBe(
      'The till is unreachable',
    );
  });
});
