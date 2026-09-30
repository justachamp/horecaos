import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { OrderPosExportView } from './order-pos-export-api';

/**
 * What the order detail pane shows inside its POS export section (§3.11, row
 * 1.2i): the export's state, the reassurance and last error, the fix-mapping
 * link, and the manual push with its reason.
 *
 * Presentation only. The export read, every label, the push draft and the push
 * itself belong to the pane (`OrderDetailPane`); this component shows what it is
 * given and raises what the operator asks for. It is its own component so the
 * panel's rules do not count against the pane's component-style budget.
 */
@Component({
  selector: 'q-order-pos-export-panel',
  imports: [TPipe],
  templateUrl: './order-pos-export-panel.html',
  styleUrl: './order-pos-export-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPosExportPanel {
  readonly posExport = input.required<OrderPosExportView | null>();
  /** The outcome of the push just made, or null when there is none to report. */
  readonly resultLabel = input.required<string | null>();
  readonly stateLabel = input.required<string | null>();
  readonly showsReassurance = input.required<boolean>();
  readonly hasMappingDeepLink = input.required<boolean>();
  readonly pushOpen = input.required<boolean>();
  readonly pushReason = input.required<string>();
  readonly pushError = input.required<string | null>();
  readonly pushSubmitting = input.required<boolean>();
  readonly canSubmitPush = input.required<boolean>();

  readonly mappingRequested = output<void>();
  readonly pushOpened = output<void>();
  readonly pushCancelled = output<void>();
  readonly pushSubmitted = output<void>();
  readonly reasonChanged = output<string>();
}
