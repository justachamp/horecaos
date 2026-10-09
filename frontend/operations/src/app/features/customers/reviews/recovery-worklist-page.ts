import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CursorState, firstPage, nextPage } from '../../../core/api/page';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Board } from '../../../shared/ui/board/board';
import { BoardCard } from '../../../shared/ui/board-card/board-card';
import { BoardColumn, BoardColumnDropEvent } from '../../../shared/ui/board-column/board-column';
import { StatusPill } from '../../../shared/ui/status-pill';
import { describeApiError } from '../../orders/order-errors';
import { LocationsApi, LocationView } from '../../settings/locations/locations-api';
import { CloseIntent, RecoveryCaseDrawer } from './recovery-case-drawer';
import {
  CASE_STATES,
  CaseCounts,
  CaseFilters,
  CaseState,
  CaseSummary,
  RecoveryApi,
  SlaState,
} from './recovery-api';
import {
  isClosed,
  slaKey,
  slaTone,
  stars,
  stateKey,
  stateTone,
  staffLabel,
} from './recovery-presentation';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';
type View = 'board' | 'table';

interface Column {
  readonly items: readonly CaseSummary[];
  readonly cursor: CursorState | null;
  readonly loading: boolean;
}

const EMPTY_COLUMN: Column = { items: [], cursor: null, loading: false };
const COLUMN_PAGE_SIZE = 25;
const TABLE_PAGE_SIZE = 50;
const SLA_STATES: readonly SlaState[] = ['ON_TRACK', 'AT_RISK', 'BREACHED', 'MET'];
const FALLBACK_TIME_ZONE = 'Asia/Tashkent';

function emptyColumns(): Record<CaseState, Column> {
  return {
    NEW: EMPTY_COLUMN,
    IN_PROGRESS: EMPTY_COLUMN,
    RESOLVED: EMPTY_COLUMN,
    CLOSED_UNRESOLVED: EMPTY_COLUMN,
  };
}

/**
 * 5.4, the service-recovery worklist (ADR 0159, Decision 8): the cases the platform opened from
 * low reviews, as a four-column board and as a table, with the two clocks on every card.
 *
 * <p>The board is the IA's: New, In progress, Resolved, Closed unresolved. A drag is a shortcut for
 * the moves that need nothing more than the move (take a case, hand it back) and opens the drawer
 * for the ones that need an outcome. The page never decides what is legal; the server refuses an edge
 * the machine does not have and the page says so.
 *
 * <p>Scoped by {@link CurrentBrand}: the brand list is brand scope, and a case is worked at its own
 * location, which the drawer reads from the case itself.
 */
@Component({
  selector: 'q-recovery-worklist-page',
  imports: [TPipe, Board, BoardColumn, BoardCard, StatusPill, RecoveryCaseDrawer],
  templateUrl: './recovery-worklist-page.html',
  styleUrl: './recovery-worklist-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecoveryWorklistPage {
  private readonly api = inject(RecoveryApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly brand = inject(CurrentBrand);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly view = signal<View>('board');
  protected readonly errorText = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly counts = signal<CaseCounts | null>(null);
  protected readonly locations = signal<readonly LocationView[]>([]);

  protected readonly locationFilter = signal('');
  protected readonly slaFilter = signal<SlaState | ''>('');
  protected readonly stateFilter = signal<CaseState | ''>('');

  protected readonly columns = signal<Record<CaseState, Column>>(emptyColumns());
  protected readonly tableRows = signal<readonly CaseSummary[]>([]);
  protected readonly tableCursor = signal<CursorState | null>(null);
  protected readonly tableLoading = signal(false);

  protected readonly selected = signal<CaseSummary | null>(null);
  protected readonly intent = signal<CloseIntent | null>(null);

  protected readonly caseStates = CASE_STATES;
  protected readonly slaStates = SLA_STATES;
  protected readonly stars = stars;
  protected readonly stateKey = stateKey;
  protected readonly slaKey = slaKey;
  protected readonly slaTone = slaTone;
  protected readonly stateTone = stateTone;
  protected readonly isClosed = isClosed;
  protected readonly ownerLabel = staffLabel;

  protected readonly openCount = computed(() => {
    const byState = this.counts()?.byState;
    return (byState?.NEW ?? 0) + (byState?.IN_PROGRESS ?? 0);
  });

  protected readonly scope = computed(() => this.brand.scope());

  constructor() {
    void this.load();
  }

  // ----------------------------------------------------------------------------- navigation

  protected setView(view: View): void {
    if (this.view() === view) {
      return;
    }
    this.view.set(view);
    void this.loadList();
  }

  protected retry(): void {
    void this.load();
  }

  protected onLocationFilter(value: string): void {
    this.locationFilter.set(value);
    void this.refresh();
  }

  protected onSlaFilter(value: string): void {
    this.slaFilter.set(value as SlaState | '');
    void this.refresh();
  }

  protected onStateFilter(value: string): void {
    this.stateFilter.set(value as CaseState | '');
    void this.loadList();
  }

  protected open(summary: CaseSummary, intent: CloseIntent | null = null): void {
    this.notice.set(null);
    this.intent.set(intent);
    this.selected.set(summary);
  }

  protected closeDrawer(): void {
    this.selected.set(null);
    this.intent.set(null);
  }

  /** A mutation in the drawer succeeded: read the board and the counts again. */
  protected onChanged(): void {
    void this.refresh();
  }

  protected columnOf(state: CaseState): Column {
    return this.columns()[state];
  }

  // ----------------------------------------------------------------------------------- drag

  /**
   * A card dropped on a column. Taking a case and handing it back are complete in themselves; closing
   * needs an outcome, so it opens the drawer on the close form. Any other move is not on the machine.
   */
  protected async onDropped(event: BoardColumnDropEvent<unknown>): Promise<void> {
    const card = event.card as CaseSummary;
    const from = event.fromColumnId as CaseState;
    const to = event.toColumnId as CaseState;
    if (from === to) {
      return;
    }
    if (from === 'NEW' && to === 'IN_PROGRESS') {
      await this.quickMove(card, 'IN_PROGRESS');
    } else if (from === 'IN_PROGRESS' && to === 'NEW') {
      await this.quickMove(card, 'NEW');
    } else if (from === 'IN_PROGRESS' && (to === 'RESOLVED' || to === 'CLOSED_UNRESOLVED')) {
      this.open(card, to);
    } else if (from === 'NEW' && to === 'CLOSED_UNRESOLVED') {
      this.open(card, 'DISMISS');
    } else {
      this.notice.set(this.i18n.t('customers.recovery.error.edge'));
    }
  }

  private async quickMove(card: CaseSummary, to: 'NEW' | 'IN_PROGRESS'): Promise<void> {
    const scope = this.scope();
    if (!scope) {
      return;
    }
    this.notice.set(null);
    try {
      await this.api.transition(scope, card.locationId, card.id, { to }, card.version);
    } catch (error) {
      this.errorText.set(this.describe(error));
    }
    await this.refresh();
  }

  // ------------------------------------------------------------------------------ paging

  protected async loadMoreColumn(state: CaseState): Promise<void> {
    const scope = this.scope();
    const column = this.columns()[state];
    if (!scope || column.cursor === null || column.loading) {
      return;
    }
    this.patchColumn(state, { ...column, loading: true });
    try {
      const page = await this.api.list(scope, column.cursor, this.filters(state));
      const next = nextPage(column.cursor, page);
      this.patchColumn(state, {
        items: [...column.items, ...page.items],
        cursor: next,
        loading: false,
      });
    } catch (error) {
      this.patchColumn(state, { ...column, loading: false });
      this.errorText.set(this.describe(error));
    }
  }

  protected async loadMoreRows(): Promise<void> {
    const scope = this.scope();
    const cursor = this.tableCursor();
    if (!scope || cursor === null || this.tableLoading()) {
      return;
    }
    this.tableLoading.set(true);
    try {
      const page = await this.api.list(
        scope,
        cursor,
        this.filters(this.stateFilter() || undefined),
      );
      this.tableRows.update((rows) => [...rows, ...page.items]);
      this.tableCursor.set(nextPage(cursor, page));
    } catch (error) {
      this.errorText.set(this.describe(error));
    } finally {
      this.tableLoading.set(false);
    }
  }

  // ------------------------------------------------------------------------------ display

  protected locationName(locationId: string): string {
    return this.locations().find((l) => l.id === locationId)?.displayName ?? locationId;
  }

  protected zoneOf(locationId: string): string {
    return this.locations().find((l) => l.id === locationId)?.timezone ?? FALLBACK_TIME_ZONE;
  }

  protected formatInstant(iso: string, locationId: string): string {
    return formatDateTime(new Date(iso), this.zoneOf(locationId));
  }

  /** The clock a card is waiting on: the first answer until it is given, then the resolution. */
  protected dueLine(summary: CaseSummary): string {
    if (summary.closedAt !== null) {
      return this.formatInstant(summary.closedAt, summary.locationId);
    }
    if (summary.firstResponseAt === null) {
      return this.i18n.t('customers.recovery.due.first', {
        when: this.formatInstant(summary.firstResponseDueAt, summary.locationId),
      });
    }
    return this.i18n.t('customers.recovery.due.resolution', {
      when: this.formatInstant(summary.resolutionDueAt, summary.locationId),
    });
  }

  // ---------------------------------------------------------------------------------- loading

  private filters(state?: CaseState): CaseFilters {
    return {
      state,
      locationId: this.locationFilter() || undefined,
      slaState: this.slaFilter() || undefined,
    };
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    this.errorText.set(null);
    await this.brand.ensureLoaded();
    const scope = this.scope();
    if (!scope) {
      this.state.set(this.brand.denied() ? 'denied' : 'error');
      return;
    }
    try {
      await this.refresh();
      if (this.state() === 'loading') {
        this.state.set('ready');
        void this.loadLocationsOnce();
      }
    } catch (error) {
      this.fail(error);
    }
  }

  /** The counts and whichever view is showing, read again. */
  private async refresh(): Promise<void> {
    const scope = this.scope();
    if (!scope) {
      return;
    }
    try {
      const [counts] = await Promise.all([
        this.api.counts(scope, this.locationFilter() || undefined),
        this.loadList(),
      ]);
      this.counts.set(counts);
    } catch (error) {
      this.fail(error);
    }
  }

  private async loadList(): Promise<void> {
    const scope = this.scope();
    if (!scope) {
      return;
    }
    if (this.view() === 'board') {
      const results = await Promise.all(
        CASE_STATES.map(async (state) => {
          const start = firstPage(COLUMN_PAGE_SIZE);
          const page = await this.api.list(scope, start, this.filters(state));
          return [
            state,
            { items: page.items, cursor: nextPage(start, page), loading: false } satisfies Column,
          ] as const;
        }),
      );
      this.columns.set(Object.fromEntries(results) as Record<CaseState, Column>);
    } else {
      const start = firstPage(TABLE_PAGE_SIZE);
      const page = await this.api.list(scope, start, this.filters(this.stateFilter() || undefined));
      this.tableRows.set(page.items);
      this.tableCursor.set(nextPage(start, page));
    }
  }

  private patchColumn(state: CaseState, column: Column): void {
    this.columns.update((current) => ({ ...current, [state]: column }));
  }

  private async loadLocationsOnce(): Promise<void> {
    const scope = this.scope();
    if (!scope || this.locations().length > 0) {
      return;
    }
    try {
      // `LocationsApi.list` wants a LocationScope but the brand route never reads its locationId.
      this.locations.set(await this.locationsApi.list({ ...scope, locationId: '' }));
    } catch {
      // Non-critical: the board loaded. The location filter shows no options and a card shows its id.
    }
  }

  private fail(error: unknown): void {
    if (
      error instanceof ApiError &&
      (error.status === 403 || error.code === ApiErrorCode.INSUFFICIENT_CAPABILITY)
    ) {
      this.state.set('denied');
      return;
    }
    this.errorText.set(this.describe(error));
    this.state.set('error');
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return error.status === 403
        ? this.i18n.t('customers.recovery.error.denied')
        : describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    throw error;
  }
}
