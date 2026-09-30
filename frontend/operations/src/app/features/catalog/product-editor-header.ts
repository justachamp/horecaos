import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The product editor's header: the way back to the list, the product's name in
 * the editing locale, its code and status, the save notice and Publish.
 *
 * Presentation only. What the name is, what the status is called and what Publish
 * does belong to the editor (`ProductEditorPage`). It is its own component so the
 * header's rules do not count against the editor's component-style budget.
 */
@Component({
  selector: 'q-product-editor-header',
  imports: [RouterLink, TPipe],
  templateUrl: './product-editor-header.html',
  styleUrl: './product-editor-header.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductEditorHeader {
  readonly name = input.required<string>();
  readonly code = input.required<string>();
  readonly statusLabel = input.required<string>();
  /** The last save's outcome, worded, or null when there is none to show. */
  readonly saveNotice = input.required<string | null>();

  readonly publishRequested = output<void>();
}
