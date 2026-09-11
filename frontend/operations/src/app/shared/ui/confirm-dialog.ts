import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  booleanAttribute,
  computed,
  input,
  output,
  viewChild,
} from '@angular/core';

import { OverlayBehaviour, nextDomId } from './overlay';

/** Whether the confirmed action destroys something. Drives the confirm button's tone, nothing else. */
export type ConfirmTone = 'default' | 'destructive';

/**
 * "Are you sure?" — the one overlay in this library that owns its whole
 * content, because there is nothing to project (ADR 0101).
 *
 * A title, a sentence, two buttons. Every other overlay here is chrome around
 * a caller's markup; this one is the complete component, which is what makes it
 * worth having at all — the alternative is each feature writing the same two
 * buttons and getting the destructive one's tone, its label and its focus order
 * slightly differently each time.
 *
 * **Focus starts on the cancelling control, not the confirming one**, whenever
 * the action is destructive. `OverlayBehaviour` focuses the first tabbable
 * descendant, so the template puts Cancel first in document order and the CSS
 * puts Confirm last visually with `order`. An operator who opens a
 * "cancel this order?" dialog and hits Enter by reflex must not cancel the
 * order.
 */
@Component({
  selector: 'q-confirm-dialog',
  templateUrl: './confirm-dialog.html',
  styleUrls: ['./overlay.css', './confirm-dialog.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog implements AfterViewInit {
  /** The question, already translated. */
  readonly title = input.required<string>();
  /** One line of consequence under the question, already translated. Optional. */
  readonly body = input<string | null>(null);
  readonly confirmLabel = input.required<string>();
  readonly cancelLabel = input.required<string>();
  readonly tone = input<ConfirmTone>('default');

  /** True while the confirmed request is in flight: both buttons go disabled and Escape stops closing. */
  readonly busy = input(false, { transform: booleanAttribute });

  readonly confirm = output<void>();
  readonly cancel = output<void>();

  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly panelElement = computed(() => this.panel()?.nativeElement);

  protected readonly titleId = nextDomId('confirm-title');
  protected readonly bodyId = nextDomId('confirm-body');

  private pressedOnBackdrop = false;

  private readonly overlay = new OverlayBehaviour({
    panel: this.panelElement,
    // Escape is the same answer as Cancel. A dialog whose Escape did something
    // a visible control does not is a dialog nobody can predict.
    onEscape: () => this.cancel.emit(),
    closeOnEscape: () => !this.busy(),
  });

  ngAfterViewInit(): void {
    this.overlay.focusInitial();
  }

  protected onBackdropPress(event: MouseEvent): void {
    this.pressedOnBackdrop = event.target === event.currentTarget;
  }

  protected onBackdropClick(event: MouseEvent): void {
    const onBackdrop = this.pressedOnBackdrop && event.target === event.currentTarget;
    this.pressedOnBackdrop = false;
    if (onBackdrop && !this.busy()) {
      this.cancel.emit();
    }
  }
}
