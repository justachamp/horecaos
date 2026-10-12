import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Drawer } from '../../../shared/ui/drawer';
import { StatusPill } from '../../../shared/ui/status-pill';
import { Timeline, TimelineEntry } from '../../../shared/ui/timeline';
import { describeApiError } from '../../orders/order-errors';
import {
  CASE_STATES,
  CaseDetail,
  CaseOutcome,
  CaseState,
  CaseSummary,
  CompensationKind,
  OUTCOMES_BY_CLOSING_STATE,
  RecoveryApi,
  TransitionInput,
} from './recovery-api';
import {
  compensateKey,
  compensationKey,
  isClosed,
  kindKey,
  outcomeKey,
  slaKey,
  slaTone,
  sourceKey,
  stars,
  stateKey,
  stateTone,
  staffLabel,
  timelineEntryOf,
} from './recovery-presentation';

/** What the worklist asks the drawer to open straight into: a drop on a closing column. */
export type CloseIntent = 'RESOLVED' | 'CLOSED_UNRESOLVED' | 'DISMISS';

const COMPENSATION_KINDS: readonly CompensationKind[] = ['ORDER_REMEDY', 'BENEFIT_GRANT'];

/**
 * One case, open (ADR 0159, Decisions 8 to 11): the rating, the tags the customer tapped, the
 * comment (or the marker that it is withheld from this reader), the clocks, the order, the
 * compensation linked so far and the history -- and the five things an operator does to it.
 *
 * <p>Everything here is read from the drawer's own `GET`, the only place a note or the comment is
 * returned. A mutation answers with a state and a version, so after each one the drawer reads again
 * rather than patching what it holds: what the person sees is always what the server has.
 *
 * <p>The client does not decide which move is legal. It offers the moves the four-state machine
 * has from the current state and lets the server refuse the rest; a lost race (`STALE_VERSION`)
 * reloads the case and says so.
 */
@Component({
  selector: 'q-recovery-case-drawer',
  imports: [TPipe, Drawer, StatusPill, Timeline, RouterLink],
  templateUrl: './recovery-case-drawer.html',
  styleUrl: './recovery-case-drawer.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecoveryCaseDrawer {
  private readonly api = inject(RecoveryApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  readonly summary = input.required<CaseSummary>();
  /** The IANA zone the branch keeps; times are shown in it. */
  readonly timeZone = input('Asia/Tashkent');
  /** Open straight into a close form, as a drop on a closing column asks for. */
  readonly intent = input<CloseIntent | null>(null);

  readonly dismiss = output<void>();
  /** A mutation succeeded: the board behind the drawer is stale. */
  readonly changed = output<void>();

  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly detail = signal<CaseDetail | null>(null);
  protected readonly busy = signal(false);
  protected readonly errorText = signal<string | null>(null);

  protected readonly closing = signal<'RESOLVED' | 'CLOSED_UNRESOLVED' | null>(null);
  /** The dismissal is asked for twice: it closes a case nobody has answered. */
  protected readonly dismissing = signal(false);
  protected readonly outcome = signal<CaseOutcome | ''>('');
  protected readonly noteText = signal('');
  protected readonly compensationKind = signal<CompensationKind>('ORDER_REMEDY');
  protected readonly compensationRef = signal('');

  protected readonly compensationKinds = COMPENSATION_KINDS;
  protected readonly stars = stars;
  protected readonly slaKey = slaKey;
  protected readonly stateKey = stateKey;
  protected readonly outcomeKey = outcomeKey;
  protected readonly sourceKey = sourceKey;
  protected readonly compensationKey = compensationKey;
  protected readonly compensateKey = compensateKey;
  protected readonly slaTone = slaTone;
  protected readonly stateTone = stateTone;

  /** The case as the drawer last read it; the row it was opened from until the read lands. */
  protected readonly current = computed<CaseSummary>(
    () => this.detail()?.summary ?? this.summary(),
  );

  protected readonly closed = computed(() => isClosed(this.current().state));

  protected readonly outcomes = computed<readonly CaseOutcome[]>(() => {
    const target = this.closing();
    return target === null ? [] : OUTCOMES_BY_CLOSING_STATE[target];
  });

  protected readonly orderNumber = computed(
    () => this.detail()?.order?.publicOrderNumber ?? this.current().orderNumber ?? '—',
  );

  protected readonly owner = computed(() => staffLabel(this.current().owner));

  protected readonly entries = computed<readonly TimelineEntry[]>(() => {
    const loaded = this.detail();
    if (loaded === null) {
      return [];
    }
    // The timeline reads its words from the active locale; depending on it here re-maps on a switch.
    this.i18n.locale();
    return loaded.timeline.map((entry) =>
      timelineEntryOf(entry, {
        kind: (kind) => this.i18n.t(kindKey(kind)),
        outcome: (code) => this.i18n.t(outcomeKey(code)),
        compensation: (code) =>
          code === 'ORDER_REMEDY' || code === 'BENEFIT_GRANT'
            ? this.i18n.t(compensationKey(code))
            : code,
        time: (iso) => this.formatInstant(iso),
        breached: this.i18n.t('customers.recovery.sla.BREACHED'),
      }),
    );
  });

  protected readonly canStart = computed(() => this.current().state === 'NEW');
  protected readonly canRelease = computed(() => this.current().state === 'IN_PROGRESS');
  protected readonly canResolve = computed(() => this.current().state === 'IN_PROGRESS');
  protected readonly canCloseUnresolved = computed(() => this.current().state === 'IN_PROGRESS');
  protected readonly canDismiss = computed(() => this.current().state === 'NEW');

  /** `REMEDY_GRANTED` is a fact, not the operator's word: it needs something linked. */
  protected readonly outcomeNeedsLink = computed(
    () => this.outcome() === 'REMEDY_GRANTED' && (this.detail()?.compensations.length ?? 0) === 0,
  );

  protected readonly canConfirmClose = computed(
    () => !this.busy() && this.outcome() !== '' && !this.outcomeNeedsLink(),
  );

  protected readonly canAttach = computed(
    () => !this.busy() && !this.closed() && this.compensationRef().trim() !== '',
  );

  protected readonly canAddNote = computed(() => !this.busy() && this.noteText().trim() !== '');

  constructor() {
    effect(() => {
      const summary = this.summary();
      untracked(() => void this.load(summary));
    });
  }

  protected formatInstant(iso: string): string {
    return formatDateTime(new Date(iso), this.timeZone());
  }

  protected tagLabel(labels: Readonly<Record<string, string>>, code: string): string {
    return labels[this.i18n.locale()] ?? labels['en'] ?? Object.values(labels)[0] ?? code;
  }

  protected stateIndex(state: CaseState): number {
    return CASE_STATES.indexOf(state);
  }

  // ----------------------------------------------------------------------------------- actions

  protected start(): Promise<void> {
    return this.transition({ to: 'IN_PROGRESS' });
  }

  protected release(): Promise<void> {
    return this.transition({ to: 'NEW' });
  }

  protected askToDismiss(): void {
    this.errorText.set(null);
    this.dismissing.set(true);
  }

  protected cancelDismiss(): void {
    this.dismissing.set(false);
  }

  protected async dismissCase(): Promise<void> {
    await this.transition({ to: 'CLOSED_UNRESOLVED', outcomeCode: 'NOT_ACTIONABLE' });
    if (this.errorText() === null) {
      this.dismissing.set(false);
    }
  }

  protected openClose(target: 'RESOLVED' | 'CLOSED_UNRESOLVED'): void {
    this.errorText.set(null);
    this.outcome.set('');
    this.closing.set(target);
  }

  protected cancelClose(): void {
    this.closing.set(null);
    this.outcome.set('');
  }

  protected onOutcomeChange(value: string): void {
    this.outcome.set(value as CaseOutcome | '');
  }

  protected async confirmClose(): Promise<void> {
    const target = this.closing();
    const outcome = this.outcome();
    if (target === null || outcome === '' || !this.canConfirmClose()) {
      return;
    }
    await this.transition({ to: target, outcomeCode: outcome });
    if (this.errorText() === null) {
      this.closing.set(null);
      this.outcome.set('');
    }
  }

  protected async addNote(): Promise<void> {
    const note = this.noteText().trim();
    if (note === '' || this.busy()) {
      return;
    }
    await this.mutate(async () => {
      await this.api.addNote(this.scope(), this.current().locationId, this.current().id, note);
      this.noteText.set('');
    });
  }

  protected async attach(): Promise<void> {
    const ref = this.compensationRef().trim();
    if (ref === '' || !this.canAttach()) {
      return;
    }
    await this.mutate(async () => {
      await this.api.attach(
        this.scope(),
        this.current().locationId,
        this.current().id,
        this.compensationKind(),
        ref,
      );
      this.compensationRef.set('');
    });
  }

  protected onCompensationKindChange(value: string): void {
    this.compensationKind.set(value as CompensationKind);
  }

  // ------------------------------------------------------------------------------------ plumbing

  private async transition(input: TransitionInput): Promise<void> {
    const version = this.detail()?.version ?? this.summary().version;
    await this.mutate(async () => {
      await this.api.transition(
        this.scope(),
        this.current().locationId,
        this.current().id,
        input,
        version,
      );
    });
  }

  /** Runs one mutation, then reads the case again; a lost race reloads and says so. */
  private async mutate(action: () => Promise<void>): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.errorText.set(null);
    try {
      await action();
      await this.reload();
      this.changed.emit();
    } catch (error) {
      if (error instanceof ApiError) {
        this.errorText.set(
          error.code === ApiErrorCode.STALE_VERSION
            ? this.i18n.t('customers.recovery.error.stale')
            : describeApiError(error, (key, values) => this.i18n.t(key, values)),
        );
        if (error.code === ApiErrorCode.STALE_VERSION) {
          await this.reload().catch(() => undefined);
          this.changed.emit();
        }
      } else {
        throw error;
      }
    } finally {
      this.busy.set(false);
    }
  }

  private async load(summary: CaseSummary): Promise<void> {
    this.state.set('loading');
    this.detail.set(null);
    this.errorText.set(null);
    try {
      this.detail.set(await this.api.detail(this.scope(), summary.locationId, summary.id));
      this.state.set('ready');
      this.applyIntent();
    } catch (error) {
      if (!(error instanceof ApiError)) {
        throw error;
      }
      this.errorText.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      this.state.set('error');
    }
  }

  private async reload(): Promise<void> {
    const summary = this.current();
    this.detail.set(await this.api.detail(this.scope(), summary.locationId, summary.id));
  }

  private applyIntent(): void {
    const intent = this.intent();
    if (intent === 'DISMISS') {
      if (this.current().state === 'NEW') {
        this.askToDismiss();
      }
    } else if (intent !== null && this.current().state === 'IN_PROGRESS') {
      this.openClose(intent);
    }
  }
}
