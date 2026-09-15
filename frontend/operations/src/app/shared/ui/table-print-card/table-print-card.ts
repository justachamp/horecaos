import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { QQrCode } from '../qr-code';

/**
 * A table's printable QR card (rows `10.5b`/`X.36`, wave P38) — what a
 * manager hands to whoever is walking the room with a printer after
 * issuing or rotating a table's code.
 *
 * Renders the token through `q-qr-code` (`X.35`, wave P17), which landed in
 * this same integration — `qr-code.ts`'s own doc names this card as its
 * third call site. A staff member handed the printed card can scan it
 * directly; the token also still prints as text underneath, for the rare
 * scan-fails case `shared.tablePrintCard.tokenLabel` already existed for.
 *
 * **Never caches the token.** `qrToken` is only ever the plaintext
 * `FloorPlanController` just minted, held by the host component for exactly
 * as long as the rotation dialog stays open — this component does not
 * store it anywhere itself, matching `FloorPlanController`'s own doc that
 * there is no endpoint that will return a table's token a second time.
 */
@Component({
  selector: 'q-table-print-card',
  imports: [TPipe, QQrCode],
  templateUrl: './table-print-card.html',
  styleUrl: './table-print-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TablePrintCard {
  readonly branchName = input.required<string>();
  readonly tableCode = input.required<string>();
  readonly tableDisplayName = input.required<string>();
  /** The plaintext token, shown exactly once — null renders "no code issued yet". */
  readonly qrToken = input<string | null>(null);
}
