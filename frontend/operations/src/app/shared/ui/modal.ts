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

/**
 * The console's one centred overlay: a backdrop, a panel, a labelled heading
 * and whatever the caller projects into it (ADR 0101).
 *
 * **It owns the chrome, not the content.** Every dialog in this application
 * already knows its own fields, its own validation and its own submit; what
 * none of them had was Escape, a focus trap, focus restore and a heading wired
 * to `aria-labelledby`. So this component projects two slots — the default one
 * for the body and `[qModalActions]` for the button row — and has no opinion at
 * all about what is inside them. That is what let `q-modal` absorb both
 * `order-reason-dialog` and `create-customer-dialog` without either of them
 * changing a field.
 *
 * **`aria-labelledby`, not `aria-label`.** The two migrated dialogs both wrote
 * `[attr.aria-label]="titleKey() | t"` beside a visible `<h2>` carrying the
 * identical string, which is the same sentence twice — and the two drift the
 * moment one of them is edited. Pointing the dialog at the heading it already
 * renders cannot drift.
 *
 * **The title arrives translated.** Not as a `MessageKey`: `q-modal` is also
 * the shape a dialog titled with tenant data needs (an order number, a
 * customer's own label), and a component that only accepts keys cannot render
 * one. Callers pipe through `| t` at the call site, which is what they already
 * did.
 */
@Component({
  selector: 'q-modal',
  templateUrl: './modal.html',
  // Two sheets, not one: the scrim and panel chrome are `overlay.css`, shared
  // with `q-drawer` and `q-confirm-dialog`, and this file holds only what makes
  // a modal a modal. A global rule would have been reachable from every
  // template in the application; a copy in three files would have drifted.
  styleUrls: ['./overlay.css', './modal.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Modal implements AfterViewInit {
  /** The visible heading, already translated. Also the dialog's accessible name. */
  readonly title = input.required<string>();

  /**
   * Whether Escape and a backdrop click dismiss.
   *
   * `false` while a mutation is in flight: closing a dialog whose request has
   * already left means the operator never learns whether it landed. Both
   * migrated call sites bind this to the negation of their own `busy()`.
   */
  readonly dismissible = input(true, { transform: booleanAttribute });

  /** Escape, the backdrop, or the close control. Never emitted while `dismissible` is false. */
  readonly dismiss = output<void>();

  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly panelElement = computed(() => this.panel()?.nativeElement);

  /** The heading's `id`, so `aria-labelledby` names the string the operator can see. */
  protected readonly titleId = nextDomId('modal-title');

  /**
   * Whether the press that produced the pending click started on the backdrop.
   *
   * A `click` fires on the nearest common ancestor of the `mousedown` and
   * `mouseup` targets, so a drag that begins inside the panel — selecting an
   * order number to copy it — and releases over the backdrop arrives here as a
   * click *on the backdrop*. Dismissing on that loses whatever the operator had
   * typed. Recording where the press landed is the only way to tell the two
   * apart.
   */
  private pressedOnBackdrop = false;

  private readonly overlay = new OverlayBehaviour({
    panel: this.panelElement,
    onEscape: () => this.dismiss.emit(),
    closeOnEscape: () => this.dismissible(),
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
    if (onBackdrop && this.dismissible()) {
      this.dismiss.emit();
    }
  }
}
