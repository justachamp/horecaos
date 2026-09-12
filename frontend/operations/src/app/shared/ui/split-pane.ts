import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  numberAttribute,
  signal,
} from '@angular/core';

/** The `localStorage` namespace this component owns. One key per `sectionKey`. */
export const SPLIT_PANE_STORAGE_PREFIX = 'horecaos.operations.splitPane.';

/**
 * The narrowest a detail pane may be dragged to. 320px is the width the five
 * hand-written copies already fall back to under their shared media query.
 */
export const SPLIT_PANE_MIN_SECONDARY_PX = 320;

/** The widest, as a fraction of the host. The list must never become the smaller half. */
const MAX_SECONDARY_FRACTION = 0.7;

/**
 * Master-detail, resizable, and remembered (ADR 0101, row `X.30`).
 *
 * Five sections — orders, inbox, customers, settings locations, catalog — each
 * carry a copy of the same grid, the same `420px` second column and the same
 * `@media (max-width: 1100px)` fallback, and the copies have already drifted:
 * two of them collapse the dock with `display: none` and the others do not.
 * None of them is resizable, so an operator whose delivery addresses wrap is
 * stuck with them wrapping.
 *
 * **Width is per section, not per application.** `orders` and `inbox` hold
 * different things at different densities, and an operator who widens the
 * order detail to read an address has said nothing about the inbox. So the
 * key is `horecaos.operations.splitPane.<sectionKey>`, and `sectionKey` is
 * required rather than defaulted — a default would silently pool every
 * section onto one width.
 *
 * **A corrupt stored value is ignored, never rendered.** `localStorage` is a
 * string store shared with every other tab on the origin; `readStoredWidth`
 * refuses anything that is not a finite number inside the current clamp, and
 * the pane falls back to its default. A pane that renders `NaNpx` is a blank
 * screen, and this is a screen an operator uses during service.
 *
 * **`display: none`, not zero width, when nothing is docked.** A zero-width
 * grid column still lays its contents out and still lets a screen reader find
 * them; two of the five copies got that right and three did not.
 */
@Component({
  selector: 'q-split-pane',
  templateUrl: './split-pane.html',
  styleUrl: './split-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SplitPane {
  /** The `localStorage` discriminator. `orders`, `inbox` — never shared between sections. */
  readonly sectionKey = input.required<string>();

  /** Whether the secondary pane holds anything. False collapses it to nothing. */
  readonly docked = input(false, { transform: booleanAttribute });

  /** The width the section starts at before an operator has ever dragged. */
  readonly defaultSecondaryPx = input(420, { transform: numberAttribute });

  /** The separator's accessible name, already translated. */
  readonly handleLabel = input.required<string>();

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly destroyRef = inject(DestroyRef);

  /** `null` until a stored or default width has been resolved for the current section. */
  private readonly width = signal<number | null>(null);

  protected readonly secondaryWidth = computed(() => this.width() ?? this.defaultSecondaryPx());

  /**
   * The custom property's value, unit included.
   *
   * Composed here rather than with Angular's `[style.x.px]` unit suffix, which
   * applies to standard properties and not to a `--custom-property` — the
   * binding would emit a bare number and the grid column would silently be
   * invalid.
   */
  protected readonly secondaryWidthCss = computed(() => `${this.secondaryWidth()}px`);

  protected readonly dragging = signal(false);

  constructor() {
    // Keyed on `sectionKey`, so a component reused across two sections — which
    // the router does whenever two sibling routes share a layout — re-reads
    // rather than carrying the previous section's width into the new one.
    effect(() => {
      const key = this.sectionKey();
      const stored = readStoredWidth(key, this.hostWidth());
      this.width.set(stored);
    });

    this.destroyRef.onDestroy(() => this.detachPointerListeners());
  }

  protected onHandlePointerDown(event: PointerEvent): void {
    event.preventDefault();
    this.dragging.set(true);
    document.addEventListener('pointermove', this.onPointerMove);
    document.addEventListener('pointerup', this.onPointerUp);
  }

  protected onHandleKeydown(event: KeyboardEvent): void {
    // The split is a real control, so it answers to the keyboard. An operator
    // who cannot use a pointer — or is wearing gloves in a kitchen — still has
    // to be able to widen the pane they are reading.
    const step = event.shiftKey ? 48 : 16;
    switch (event.key) {
      case 'ArrowLeft':
        event.preventDefault();
        this.commit(this.secondaryWidth() + step);
        return;
      case 'ArrowRight':
        event.preventDefault();
        this.commit(this.secondaryWidth() - step);
        return;
      case 'Home':
        event.preventDefault();
        this.commit(this.defaultSecondaryPx());
        return;
      default:
        return;
    }
  }

  private readonly onPointerMove = (event: PointerEvent): void => {
    const rect = this.host.nativeElement.getBoundingClientRect();
    // The secondary pane is on the trailing edge, so its width is whatever is
    // left between the pointer and that edge.
    this.commit(rect.right - event.clientX, { persist: false });
  };

  private readonly onPointerUp = (): void => {
    this.dragging.set(false);
    this.detachPointerListeners();
    // Persisted once, on release, rather than on every pointermove: a drag is
    // fifty writes to `localStorage`, which is synchronous and on the main
    // thread of a console being used during service.
    writeStoredWidth(this.sectionKey(), this.secondaryWidth());
  };

  private detachPointerListeners(): void {
    document.removeEventListener('pointermove', this.onPointerMove);
    document.removeEventListener('pointerup', this.onPointerUp);
  }

  private commit(candidate: number, options: { persist?: boolean } = {}): void {
    const next = clampWidth(candidate, this.hostWidth());
    this.width.set(next);
    if (options.persist !== false) {
      writeStoredWidth(this.sectionKey(), next);
    }
  }

  /**
   * The host's own width, or `null` before layout. jsdom reports zero for
   * everything, and a zero host would clamp every width to the minimum, so a
   * non-positive measurement is treated as "not measured yet".
   */
  private hostWidth(): number | null {
    const width = this.host.nativeElement.getBoundingClientRect().width;
    return width > 0 ? width : null;
  }
}

/** The clamp, exported so the spec asserts the same arithmetic the component uses. */
export function clampWidth(candidate: number, hostWidth: number | null): number {
  const max =
    hostWidth === null
      ? Number.POSITIVE_INFINITY
      : Math.max(SPLIT_PANE_MIN_SECONDARY_PX, hostWidth * MAX_SECONDARY_FRACTION);
  return Math.round(Math.min(Math.max(candidate, SPLIT_PANE_MIN_SECONDARY_PX), max));
}

/**
 * The stored width for a section, or `null` when there is nothing usable.
 *
 * Every failure is the same answer — use the default — because there is no
 * useful distinction on this screen between "no preference yet", "another tab
 * wrote rubbish", and "storage is disabled on this kiosk profile".
 */
export function readStoredWidth(sectionKey: string, hostWidth: number | null): number | null {
  let raw: string | null = null;
  try {
    raw = globalThis.localStorage?.getItem(SPLIT_PANE_STORAGE_PREFIX + sectionKey) ?? null;
  } catch {
    return null;
  }
  if (raw === null) {
    return null;
  }
  const parsed = Number(raw);
  // `Number('')` is 0 and `Number('  ')` is 0, so the emptiness test has to come
  // first or a blank value reads as a zero-width pane.
  if (raw.trim() === '' || !Number.isFinite(parsed) || parsed <= 0) {
    return null;
  }
  return clampWidth(parsed, hostWidth);
}

export function writeStoredWidth(sectionKey: string, width: number): void {
  try {
    globalThis.localStorage?.setItem(SPLIT_PANE_STORAGE_PREFIX + sectionKey, String(width));
  } catch {
    // A kiosk profile with storage disabled loses the width between sessions.
    // That is a worse experience, not a broken application — the same trade
    // `I18n.setLocale` already makes for the locale.
  }
}
