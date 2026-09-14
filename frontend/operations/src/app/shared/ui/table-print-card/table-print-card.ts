import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';

/**
 * A table's printable QR card (rows `10.5b`/`X.36`, wave P38) — what a
 * manager hands to whoever is walking the room with a printer after
 * issuing or rotating a table's code.
 *
 * **Stands in for `X.35`'s `q-qr-code` (wave P17), not merged yet.** This
 * wave's brief names `q-qr-code` as the component that renders the token as
 * a scannable code; P17 owns building it and has not landed in this
 * worktree's base (`shared/ui` carries no `qr-code` component at all). Per
 * the standing instruction for exactly this situation — "build the minimum
 * you need locally … rather than building that wave" — this component
 * renders the plaintext token as a labelled, monospaced block a staff
 * member can type into whatever scans it, inside the same card layout
 * `q-qr-code`'s own consumer will eventually fill with a real scannable
 * mark. Swapping the placeholder block for `<q-qr-code [value]="qrToken()">`
 * once P17 merges is the entire migration — nothing else about this
 * component's contract needs to change.
 *
 * **Never caches the token.** `qrToken` is only ever the plaintext
 * `FloorPlanController` just minted, held by the host component for exactly
 * as long as the rotation dialog stays open — this component does not
 * store it anywhere itself, matching `FloorPlanController`'s own doc that
 * there is no endpoint that will return a table's token a second time.
 */
@Component({
  selector: 'q-table-print-card',
  imports: [TPipe],
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
