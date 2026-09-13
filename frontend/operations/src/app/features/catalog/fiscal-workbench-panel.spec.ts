import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from './catalog-api';
import { FiscalWorkbenchPanel } from './fiscal-workbench-panel';

const SCOPE = { tenantId: 't1', brandId: 'b1' };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function configure(catalogApi: Partial<CatalogApi>): void {
  TestBed.configureTestingModule({
    imports: [FiscalWorkbenchPanel],
    providers: [{ provide: CatalogApi, useValue: catalogApi }],
  });
  TestBed.inject(I18n).setLocale('ru');
}

describe('FiscalWorkbenchPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does nothing while closed — no coverage call, nothing rendered', async () => {
    const fiscalCoverage = vi.fn();
    configure({ fiscalCoverage });
    const fixture = TestBed.createComponent(FiscalWorkbenchPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('open', false);
    fixture.detectChanges();
    await flushMicrotasks();

    expect(fiscalCoverage).not.toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-drawer"]'),
    ).toBeFalsy();
  });

  it('loads and shows the node-level "N of M" coverage once opened', async () => {
    configure({
      fiscalCoverage: () =>
        of({
          totalNodes: 12,
          unclassifiedCount: 7,
          nodes: [
            {
              nodeType: 'VARIANT',
              nodeId: 'v1',
              name: 'Плов',
              categoryName: 'Горячее',
              locationCount: 2,
            },
            { nodeType: 'FEE', nodeId: 'fee-1', name: null, categoryName: null, locationCount: 0 },
          ],
        }),
    });
    const fixture = TestBed.createComponent(FiscalWorkbenchPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const summary = host.querySelector('[data-testid="fiscal-workbench-summary"]');
    expect(summary?.textContent).toContain('7');
    expect(summary?.textContent).toContain('12');
    expect(host.querySelectorAll('[data-testid="dg-row"]').length).toBe(2);
  });

  it('renders the denied state on a 403 rather than an empty grid', async () => {
    configure({
      fiscalCoverage: () =>
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });
    const fixture = TestBed.createComponent(FiscalWorkbenchPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-inline-alert"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="dg-row"]')).toBeFalsy();
  });

  it('shows the "every node classified" state once the worklist is empty', async () => {
    configure({ fiscalCoverage: () => of({ totalNodes: 5, unclassifiedCount: 0, nodes: [] }) });
    const fixture = TestBed.createComponent(FiscalWorkbenchPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="dg-row"]'),
    ).toBeFalsy();
  });

  it('emits dismiss when the drawer closes', async () => {
    configure({ fiscalCoverage: () => of({ totalNodes: 0, unclassifiedCount: 0, nodes: [] }) });
    const fixture = TestBed.createComponent(FiscalWorkbenchPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('open', true);
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    (
      (fixture.nativeElement as HTMLElement).querySelector('.q-overlay__backdrop') as HTMLElement
    ).dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    (
      (fixture.nativeElement as HTMLElement).querySelector('.q-overlay__backdrop') as HTMLElement
    ).click();

    expect(dismissed).toBe(true);
  });
});
