import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { PrinterView, RouteView } from './printing-api';

/**
 * Settings · Printing and receipts · Fiscal receipt (ADR 0154 decision 5).
 *
 * A read-only statement of what a printed fiscal receipt is, plus the branch's print policy for it, derived from
 * its routes. The evidence lines are printed exactly as the fiscal operator returned them, and the platform never
 * draws a facsimile of a receipt it did not issue; the card says that, and says which routes print one without
 * being asked. Nothing here is editable: the policy is whatever the routes say, and the routes are on the card
 * above.
 *
 * It carries one honest caution. The legal wording around the evidence is the tenant's own accountant's to
 * confirm (ADR 0154 open input 3), and the platform cannot check it, so the card says to confirm it before
 * switching automatic printing of fiscal receipts on.
 */
@Component({
  selector: 'q-print-fiscal-receipt-card',
  imports: [TPipe],
  templateUrl: './fiscal-receipt-card.html',
  styleUrl: './printing-shared.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalReceiptCard {
  readonly routes = input.required<readonly RouteView[]>();
  readonly printers = input.required<readonly PrinterView[]>();

  protected readonly receiptRoutes = computed(() =>
    this.routes().filter(
      (route) => route.documentType === 'FISCAL_RECEIPT' && route.status === 'ACTIVE',
    ),
  );
  protected readonly automatic = computed(() =>
    this.receiptRoutes().some((route) => route.autoPrint),
  );

  protected printerName(id: string): string {
    return this.printers().find((printer) => printer.id === id)?.displayName ?? '—';
  }
}
