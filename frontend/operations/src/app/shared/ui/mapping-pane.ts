import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from './combobox';
import { InlineAlert } from './inline-alert';
import { StatusPill, StatusTone } from './status-pill';

/** A linked pair, as `PosMappingController.MappingView` carries it. */
export interface MappingPaneRow {
  readonly mappingId: string;
  readonly horecaosEntityId: string;
  readonly horecaosName: string | null;
  readonly externalEntityId: string;
  readonly status: string;
  readonly version: number;
}

/** One HorecaOS-side candidate, not yet mapped — the dual list's left column. */
export interface MappingPaneCandidate {
  readonly id: string;
  readonly name: string | null;
}

/** One provider-side candidate, not yet mapped — the dual list's right column. */
export interface MappingPaneExternalCandidate {
  readonly externalId: string;
  readonly name: string | null;
}

/** Two or more candidates sharing one name on either side — the two-sided conflict card's own data. */
export interface MappingPaneConflict {
  readonly name: string;
  readonly externalIds: readonly string[];
  readonly horecaosEntityIds: readonly string[];
}

/** What the operator chose to link — one id from each unmapped list. */
export interface MappingPaneLinkIntent {
  readonly horecaosId: string;
  readonly externalId: string;
}

/**
 * The mapping pane (ADR 0012/0026, gap-map row X.24) — the single most
 * repeated integration screen the IA names (row `10.8`'s six pairings and
 * `4.5`'s POS product mapping share it).
 *
 * **Dual list, not one table with a picker column.** An unmapped HorecaOS
 * record and an unmapped provider record are shown side by side, each
 * pickable with `q-combobox`; linking sets both selections and appears in the
 * linked-pairs table below. **Unmapped-only** hides that table by default —
 * the pane opens on the work still to do, and a caller with hundreds of
 * settled pairs is not scrolling past them to find the three gaps.
 *
 * **A two-sided conflict card, never last-write-wins.** `conflicts` comes
 * from a bulk auto-match the caller already ran; each entry names every
 * candidate id on both sides that shared one name, and this pane lets an
 * operator resolve one pair from inside a conflict at a time — the same
 * `link` output a plain dual-list pick uses — rather than the pane guessing
 * which pairing was meant.
 *
 * **Entirely controlled.** No API call lives here: the caller supplies every
 * list and reacts to `link`/`unlink`/`bulkAutoMatch`/`resolveConflict`, the
 * "dumb panel, page does the async work" shape most of this app's other
 * panels use.
 */
@Component({
  selector: 'q-mapping-pane',
  imports: [TPipe, Combobox, StatusPill, InlineAlert],
  templateUrl: './mapping-pane.html',
  styleUrl: './mapping-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MappingPane {
  /** Currently linked pairs — typically ACTIVE only; the caller decides which statuses to pass. */
  readonly rows = input<readonly MappingPaneRow[]>([]);
  readonly horecaosCandidates = input<readonly MappingPaneCandidate[]>([]);
  readonly externalCandidates = input<readonly MappingPaneExternalCandidate[]>([]);
  /** False when this build cannot read the provider's list for this entity type at all. */
  readonly externalSourced = input<boolean>(true);
  readonly externalSourcedDetail = input<string | null>(null);
  readonly conflicts = input<readonly MappingPaneConflict[]>([]);
  readonly loading = input<boolean>(false);
  /** True while a link/unlink/bulk-auto-match request is in flight. */
  readonly busy = input<boolean>(false);
  readonly errorMessage = input<string | null>(null);

  readonly link = output<MappingPaneLinkIntent>();
  readonly unlink = output<MappingPaneRow>();
  readonly bulkAutoMatch = output<void>();
  /** The operator dismissed one conflict card without resolving it — the caller drops it from {@link conflicts}. */
  readonly dismissConflict = output<MappingPaneConflict>();

  protected readonly showLinked = signal(false);

  protected readonly leftQuery = signal('');
  protected readonly rightQuery = signal('');
  protected readonly selectedLeft = signal<ComboboxOption | null>(null);
  protected readonly selectedRight = signal<ComboboxOption | null>(null);

  protected readonly leftOptions = computed<readonly ComboboxOption[]>(() =>
    filterCandidates(this.horecaosCandidates(), this.leftQuery()).map((candidate) => ({
      id: candidate.id,
      label: candidate.name ?? candidate.id,
    })),
  );

  protected readonly rightOptions = computed<readonly ComboboxOption[]>(() =>
    filterExternalCandidates(this.externalCandidates(), this.rightQuery()).map((candidate) => ({
      id: candidate.externalId,
      label: candidate.name ?? candidate.externalId,
    })),
  );

  protected readonly canLink = computed(
    () => this.selectedLeft() !== null && this.selectedRight() !== null && !this.busy(),
  );

  protected onLeftQueryInput(text: string): void {
    this.leftQuery.set(text);
    this.selectedLeft.set(null);
  }

  protected onRightQueryInput(text: string): void {
    this.rightQuery.set(text);
    this.selectedRight.set(null);
  }

  protected chooseLeft(option: ComboboxOption): void {
    this.selectedLeft.set(option);
    this.leftQuery.set(option.label);
  }

  protected chooseRight(option: ComboboxOption): void {
    this.selectedRight.set(option);
    this.rightQuery.set(option.label);
  }

  protected confirmLink(): void {
    const left = this.selectedLeft();
    const right = this.selectedRight();
    if (left === null || right === null || this.busy()) {
      return;
    }
    this.link.emit({ horecaosId: left.id, externalId: right.id });
    this.selectedLeft.set(null);
    this.selectedRight.set(null);
    this.leftQuery.set('');
    this.rightQuery.set('');
  }

  protected requestUnlink(row: MappingPaneRow): void {
    if (!this.busy()) {
      this.unlink.emit(row);
    }
  }

  protected requestBulkAutoMatch(): void {
    if (!this.busy()) {
      this.bulkAutoMatch.emit();
    }
  }

  protected linkFromConflict(
    conflict: MappingPaneConflict,
    horecaosId: string,
    externalId: string,
  ): void {
    if (!this.busy()) {
      this.link.emit({ horecaosId, externalId });
    }
  }

  protected toggleLinked(): void {
    this.showLinked.update((value) => !value);
  }

  protected statusTone(status: string): StatusTone {
    switch (status) {
      case 'ACTIVE':
        return 'success';
      case 'CONFLICTED':
        return 'danger';
      case 'RETIRED':
        return 'none';
      default:
        return 'info';
    }
  }
}

function filterCandidates(
  candidates: readonly MappingPaneCandidate[],
  query: string,
): readonly MappingPaneCandidate[] {
  const trimmed = query.trim().toLowerCase();
  if (trimmed === '') {
    return candidates;
  }
  return candidates.filter((candidate) =>
    (candidate.name ?? candidate.id).toLowerCase().includes(trimmed),
  );
}

function filterExternalCandidates(
  candidates: readonly MappingPaneExternalCandidate[],
  query: string,
): readonly MappingPaneExternalCandidate[] {
  const trimmed = query.trim().toLowerCase();
  if (trimmed === '') {
    return candidates;
  }
  return candidates.filter((candidate) =>
    (candidate.name ?? candidate.externalId).toLowerCase().includes(trimmed),
  );
}
