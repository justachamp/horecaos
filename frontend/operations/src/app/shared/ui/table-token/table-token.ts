import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

export interface TableTokenView {
  readonly tableId: string;
  readonly code: string;
  readonly displayName: string;
  readonly seats: number;
  readonly status: 'ACTIVE' | 'OUT_OF_SERVICE' | 'ARCHIVED';
  readonly layoutX: number | null;
  readonly layoutY: number | null;
  readonly qrIssued: boolean;
}

/** The position `(positionChange)` emits — always the token's real canvas coordinates, never a delta. */
export interface TableTokenPosition {
  readonly x: number;
  readonly y: number;
}

/**
 * One table on the floor plan canvas (`X.36`, wave P38) — a draggable chip
 * carrying its own code, seat count, status and QR-issued state.
 *
 * **Pointer events, not HTML5 drag-and-drop.** Native `draggable` is built
 * for discrete drop targets (`q-drag-drop-assign`'s own column-to-column
 * case) and gives no continuous position while dragging — a floor plan is
 * the opposite: free placement anywhere on the canvas, with the token
 * following the pointer the whole time. `pointerdown`/`pointermove`/
 * `pointerup` plus `setPointerCapture` is the standard shape for that, and
 * it is what every other free-position canvas in the browser platform uses.
 *
 * **The token tracks its own drag locally and reports once, on release.**
 * While dragging, `renderX`/`renderY` (this component's own signals) lead
 * the input's `table().layoutX/Y` so the chip visibly follows the pointer
 * without waiting on a round trip; `positionChange` fires exactly once, on
 * `pointerup`, with the token's real, absolute canvas position — never a
 * stream of intermediate values a caller would have to debounce. The host
 * owns what happens next: call the API, and either the `table` input comes
 * back with the new `layoutX/Y` (success) or unchanged (refused), and
 * either way this component simply reflects whatever it is given next.
 */
@Component({
  selector: 'q-table-token',
  templateUrl: './table-token.html',
  styleUrl: './table-token.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TableToken {
  readonly table = input.required<TableTokenView>();
  readonly disabled = input(false);

  /** Fires once, on release, with the token's real absolute canvas position. */
  readonly positionChange = output<TableTokenPosition>();
  /** Fires on pointerdown — the host's own cue to select this table (e.g. to open its QR action). */
  readonly selected = output<void>();

  private readonly dragOffset = signal<{ dx: number; dy: number } | null>(null);
  private dragStart: { pointerX: number; pointerY: number } | null = null;

  protected readonly dragging = computed(() => this.dragOffset() !== null);

  protected readonly renderX = computed(() => {
    const base = this.table().layoutX ?? 0;
    const offset = this.dragOffset();
    return offset ? base + offset.dx : base;
  });

  protected readonly renderY = computed(() => {
    const base = this.table().layoutY ?? 0;
    const offset = this.dragOffset();
    return offset ? base + offset.dy : base;
  });

  protected onPointerDown(event: PointerEvent): void {
    if (this.disabled()) {
      return;
    }
    this.selected.emit();
    this.dragStart = { pointerX: event.clientX, pointerY: event.clientY };
    this.dragOffset.set({ dx: 0, dy: 0 });
    // Pointer capture keeps move/up events targeted at this element even
    // once the pointer leaves it mid-drag — not implemented in every test
    // DOM, so a capture failure there must never block the drag itself.
    try {
      (event.target as Element).setPointerCapture(event.pointerId);
    } catch {
      // Unsupported in this environment (e.g. jsdom) — the drag still works
      // via document-level bubbling of subsequent pointer events.
    }
    event.preventDefault();
  }

  protected onPointerMove(event: PointerEvent): void {
    const start = this.dragStart;
    if (!start) {
      return;
    }
    this.dragOffset.set({
      dx: event.clientX - start.pointerX,
      dy: event.clientY - start.pointerY,
    });
  }

  protected onPointerUp(event: PointerEvent): void {
    if (!this.dragStart) {
      return;
    }
    const x = this.renderX();
    const y = this.renderY();
    this.dragStart = null;
    this.dragOffset.set(null);
    try {
      (event.target as Element).releasePointerCapture(event.pointerId);
    } catch {
      // See onPointerDown's own note.
    }
    this.positionChange.emit({ x: Math.round(x), y: Math.round(y) });
  }
}
