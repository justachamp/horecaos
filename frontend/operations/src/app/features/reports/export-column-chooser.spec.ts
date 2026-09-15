import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { ExportColumnChooser, ExportColumnOption } from './export-column-chooser';

const COLUMNS: readonly ExportColumnOption[] = [
  { key: 'accountId', labelKey: 'reports.exportCentre.column.accountId', pii: false },
  { key: 'status', labelKey: 'reports.exportCentre.column.status', pii: false },
  { key: 'phone', labelKey: 'reports.exportCentre.column.phone', pii: true },
];

function fakeCapabilities(held: readonly Capability[]): { has: (c: Capability) => boolean } {
  return { has: (capability) => held.includes(capability) };
}

function render(held: readonly Capability[], selected: readonly string[] = []) {
  TestBed.configureTestingModule({
    providers: [{ provide: SessionCapabilities, useValue: fakeCapabilities(held) }],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ExportColumnChooser);
  fixture.componentRef.setInput('columns', COLUMNS);
  fixture.componentRef.setInput('selected', selected);
  fixture.detectChanges();
  return fixture;
}

describe('ExportColumnChooser (row 7.2e)', () => {
  it('hides the PII group entirely when the operator lacks customer.pii.export', () => {
    const fixture = render(['REPORT_EXPORT']);
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="export-column-phone"]')).toBeNull();
    expect(element.querySelector('[data-testid="export-column-pii-group"]')).toBeNull();
    expect(element.querySelector('[data-testid="export-column-pii-hidden"]')).not.toBeNull();

    // The non-PII columns are unaffected — only the PII group is gated.
    expect(element.querySelector('[data-testid="export-column-accountId"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="export-column-status"]')).not.toBeNull();
  });

  it('shows the PII group when the operator holds customer.pii.export', () => {
    const fixture = render(['REPORT_EXPORT', 'CUSTOMER_PII_EXPORT']);
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('[data-testid="export-column-phone"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="export-column-pii-group"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="export-column-pii-hidden"]')).toBeNull();
  });

  it('emits the updated selection when a standard column is toggled', () => {
    const fixture = render(['REPORT_EXPORT'], ['accountId']);
    let emitted: readonly string[] | undefined;
    fixture.componentInstance.selectedChange.subscribe((value) => (emitted = value));

    const checkbox = fixture.nativeElement.querySelector(
      '[data-testid="export-column-status"]',
    ) as HTMLInputElement;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));

    expect(emitted).toEqual(['accountId', 'status']);
  });

  it('never emits the PII column key while the group is hidden, even for a stale selection', () => {
    // A caller that pre-selects "phone" without the capability (e.g. a
    // restored draft) must not be able to have it toggled back on through
    // this component — there is no checkbox to click, by construction.
    const fixture = render(['REPORT_EXPORT'], ['accountId', 'phone']);
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="export-column-phone"]')).toBeNull();
  });
});
