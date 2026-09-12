import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  booleanAttribute,
  effect,
  inject,
  input,
  output,
  signal,
  viewChildren,
} from '@angular/core';

/** One entry in an action menu. `id` is what the menu emits; nothing else is interpreted. */
export interface ActionMenuItem {
  readonly id: string;
  /** Already translated — the menu never looks a key up. */
  readonly label: string;
  readonly disabled?: boolean;
  /** Renders the item in the error tone. Cancel, refund, delete. */
  readonly destructive?: boolean;
}

/**
 * A row's "…" overflow menu, done once (ADR 0101).
 *
 * The gap the IA names is real and it is not cosmetic: "Delever's entire order
 * UX is the '…' menu opening ~12 distinct dialogs". This console has exactly
 * two such menus, both on the order board, both hand-written, both reachable
 * only with a pointer — the buttons inside them are ordinary tab stops, so a
 * keyboard operator Tabs through every item of every open menu and the page
 * behind it, and Escape does nothing.
 *
 * **Roving tabindex, which is the pattern the ARIA menu role requires.** The
 * menu is one tab stop; inside it, exactly one item carries `tabindex="0"` and
 * every other carries `-1`, and the arrow keys move which. That is the
 * difference between "a menu" and "a stack of buttons that looks like a menu":
 * Tab leaves the menu instead of walking it, Home and End reach the ends, and
 * the wrap at both ends means an operator holding ArrowDown never falls out of
 * the bottom.
 *
 * **Not a focus trap.** A menu is not a dialog: the page behind it is not
 * inert, Tab is supposed to leave, and closing on outside click is the whole
 * contract. `OverlayBehaviour` would be wrong here, and this component
 * deliberately does not reach for it.
 *
 * **Open state belongs to the caller.** A table renders one menu per row and
 * only one may be open; that is a decision about the table, not about the
 * menu, and the two hand-written call sites already hold it in a
 * `signal<string | null>`. So `open` is an input and `openChange` an output,
 * and this component never opens itself.
 */
@Component({
  selector: 'q-action-menu',
  templateUrl: './action-menu.html',
  styleUrl: './action-menu.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:pointerdown)': 'onDocumentPointerDown($event)',
  },
})
export class ActionMenu {
  /** The trigger's accessible name, already translated. The glyph itself is `aria-hidden`. */
  readonly triggerLabel = input.required<string>();
  readonly items = input.required<readonly ActionMenuItem[]>();
  readonly open = input(false, { transform: booleanAttribute });
  readonly disabled = input(false, { transform: booleanAttribute });

  /** Requests open (`true`) or closed (`false`). The caller decides whether to honour it. */
  readonly openChange = output<boolean>();
  /** The `id` of the chosen item. A disabled item never emits. */
  readonly select = output<string>();

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly itemButtons = viewChildren<ElementRef<HTMLButtonElement>>('item');

  /** Which item currently holds the menu's single tab stop. */
  protected readonly activeIndex = signal(0);

  /**
   * Whether this open has already placed the caret.
   *
   * A plain field rather than a signal, and load-bearing: the effect below
   * depends on `itemButtons()`, which can settle in a *later* change-detection
   * pass than the one that opened the menu — and a second run that re-homed the
   * caret would undo an ArrowDown the operator had already pressed. Once per
   * open, then never again until it closes.
   */
  private homed = false;

  constructor() {
    effect(() => {
      const buttons = this.itemButtons();
      if (!this.open()) {
        this.homed = false;
        return;
      }
      if (this.homed) {
        return;
      }
      // Opening always starts at the top, whatever the last visit left behind:
      // a menu that reopens halfway down is a menu whose keyboard position the
      // operator has to check before using.
      const first = this.firstEnabledIndex();
      if (buttons.length > 0 && first !== null) {
        this.homed = true;
        this.activeIndex.set(first);
        buttons[first].nativeElement.focus();
      }
    });
  }

  protected isActive(index: number): boolean {
    return index === this.activeIndex();
  }

  protected onTriggerClick(): void {
    if (!this.disabled()) {
      this.openChange.emit(!this.open());
    }
  }

  protected onTriggerKeydown(event: KeyboardEvent): void {
    if (this.disabled() || this.open()) {
      return;
    }
    // ArrowDown on a collapsed menu opens it — the one keyboard affordance
    // that makes a menu reachable without guessing that Enter works.
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.openChange.emit(true);
    }
  }

  protected onMenuKeydown(event: KeyboardEvent): void {
    const count = this.items().length;
    if (count === 0) {
      return;
    }

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.moveBy(1);
        return;
      case 'ArrowUp':
        event.preventDefault();
        this.moveBy(-1);
        return;
      case 'Home':
        event.preventDefault();
        this.moveTo(this.firstEnabledIndex());
        return;
      case 'End':
        event.preventDefault();
        this.moveTo(this.lastEnabledIndex());
        return;
      case 'Escape':
        event.preventDefault();
        event.stopPropagation();
        this.close();
        return;
      case 'Tab':
        // Tab leaves a menu. Closing on the way out is what keeps the menu from
        // being left open behind a caret that has moved on.
        this.close();
        return;
      default:
        return;
    }
  }

  protected onItemClick(item: ActionMenuItem): void {
    if (item.disabled === true) {
      return;
    }
    this.select.emit(item.id);
    this.openChange.emit(false);
  }

  /**
   * `pointerdown`, not `click`: a click that lands on another row's trigger
   * would otherwise arrive after this menu closed and be read as opening that
   * row's menu *and* closing this one in the same gesture, which reads as
   * nothing happening at all.
   */
  protected onDocumentPointerDown(event: Event): void {
    if (!this.open()) {
      return;
    }
    const target = event.target;
    if (target instanceof Node && this.host.nativeElement.contains(target)) {
      return;
    }
    this.openChange.emit(false);
  }

  private close(): void {
    this.openChange.emit(false);
  }

  private moveBy(delta: number): void {
    const items = this.items();
    const count = items.length;
    let index = this.activeIndex();
    // At most `count` steps, so a menu whose every item is disabled terminates
    // rather than spinning.
    for (let step = 0; step < count; step += 1) {
      index = (index + delta + count) % count;
      if (items[index].disabled !== true) {
        this.moveTo(index);
        return;
      }
    }
  }

  private moveTo(index: number | null): void {
    if (index === null) {
      return;
    }
    this.activeIndex.set(index);
    this.itemButtons()[index]?.nativeElement.focus();
  }

  private firstEnabledIndex(): number | null {
    const index = this.items().findIndex((item) => item.disabled !== true);
    return index === -1 ? null : index;
  }

  private lastEnabledIndex(): number | null {
    const items = this.items();
    for (let index = items.length - 1; index >= 0; index -= 1) {
      if (items[index].disabled !== true) {
        return index;
      }
    }
    return null;
  }
}
