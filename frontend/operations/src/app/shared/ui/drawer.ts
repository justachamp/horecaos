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

export type DrawerSide = 'end' | 'start';

/**
 * An edge-anchored overlay panel — `q-modal`'s sibling for content that is
 * tall rather than wide: a filter set, an audit trail, a long form (ADR 0101).
 *
 * **A drawer is not the console's master-detail pattern.** `shell.ts`'s own
 * doc states the rule this component must not break — "the queue is never
 * hidden" — and the order board and the inbox both answer it with a docked
 * column that shares the viewport (`q-split-pane`). A drawer floats over the
 * page behind a scrim, so it is for work that genuinely interrupts, and the
 * five master-detail sections stay on the split pane. Nothing in this wave
 * migrates to a drawer for exactly that reason.
 *
 * `start`/`end` rather than `left`/`right`, because the console runs in three
 * locales and CSS logical properties already know which edge that is.
 */
@Component({
  selector: 'q-drawer',
  templateUrl: './drawer.html',
  styleUrls: ['./overlay.css', './drawer.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Drawer implements AfterViewInit {
  /** The visible heading, already translated. Also the drawer's accessible name. */
  readonly title = input.required<string>();

  /** Which inline edge the panel is anchored to. `end` — the reading-trailing edge — by default. */
  readonly side = input<DrawerSide>('end');

  /** Whether Escape and a scrim click dismiss. See `q-modal.dismissible`. */
  readonly dismissible = input(true, { transform: booleanAttribute });

  readonly dismiss = output<void>();

  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly panelElement = computed(() => this.panel()?.nativeElement);

  protected readonly titleId = nextDomId('drawer-title');

  /** See `q-modal.pressedOnBackdrop` — a drag out of the panel is not a scrim click. */
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
