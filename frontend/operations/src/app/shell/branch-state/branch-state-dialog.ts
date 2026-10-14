import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { describeApiError } from '../../features/orders/order-errors';
import {
  ChangeServiceStateRequest,
  LocationsApi,
  ServiceMode,
  ServiceSummaryResponse,
} from '../../features/settings/locations/locations-api';
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { Modal } from '../../shared/ui/modal';
import { BranchStates } from './branch-states';
import { BranchReason, CLOSE_REASONS, OPEN_REASONS, branchStateText } from './branch-state-text';
import { clockIn, endOfServiceToday, isServiceOpenNow } from './branch-time';

type Until = '30' | '60' | '120' | 'endOfDay' | 'cancelled';

const UNTIL_MINUTES: Readonly<Record<'30' | '60' | '120', number>> = {
  '30': 30,
  '60': 60,
  '120': 120,
};
const NOTE_MAX = 400;

/**
 * The branch open/closed dialog (brands-and-locations.md §6): mode, reason, comment and a duration, and a
 * submit button that says what it will do. «The presets are the feature» -- 30 minutes, an hour, two hours,
 * the end of today's service (shown as the clock time it resolves to, so nobody guesses), or until cancelled,
 * which is last and drawn as the dangerous one because nothing reopens the branch but a person.
 *
 * Reachable from the shell's control (and `c`), and opened for any branch the caller names. It writes through
 * {@link BranchStates} so the strip and the control hear about it at once. `FOLLOW_SCHEDULE` sends no reason
 * and no expiry, which the database insists on; a force-open or a close always sends a reason, and `OTHER`
 * insists on a comment.
 */
@Component({
  selector: 'q-branch-state-dialog',
  imports: [Modal],
  templateUrl: './branch-state-dialog.html',
  styleUrl: './branch-state-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BranchStateDialog implements OnInit {
  /** The branch the dialog is about. */
  readonly locationId = input.required<string>();

  /** The dialog closed, applied or not. */
  readonly closed = output<void>();

  private readonly api = inject(LocationsApi);
  private readonly location = inject(CurrentLocation);
  private readonly states = inject(BranchStates);
  private readonly i18n = inject(I18n);
  private readonly registry = inject(ShortcutRegistry);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly summary = signal<ServiceSummaryResponse | null>(null);
  protected readonly timeZone = signal('Asia/Tashkent');
  protected readonly mode = signal<ServiceMode>('FORCE_CLOSED');
  protected readonly reason = signal<BranchReason | ''>('');
  protected readonly note = signal('');
  protected readonly until = signal<Until>('60');
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected readonly noteMax = NOTE_MAX;
  protected readonly modes: readonly ServiceMode[] = [
    'FOLLOW_SCHEDULE',
    'FORCE_CLOSED',
    'FORCE_OPEN',
  ];

  protected readonly name = computed(
    () =>
      this.location.options().find((option) => option.id === this.locationId())?.displayName ??
      this.locationId(),
  );

  /** The reasons this mode offers: none when returning to the schedule. */
  protected readonly reasons = computed<readonly BranchReason[]>(() => {
    switch (this.mode()) {
      case 'FORCE_CLOSED':
        return CLOSE_REASONS;
      case 'FORCE_OPEN':
        return OPEN_REASONS;
      default:
        return [];
    }
  });

  protected readonly endOfDay = computed(() => {
    const summary = this.summary();
    return summary === null
      ? null
      : endOfServiceToday(summary.bindings, new Date(), this.timeZone());
  });

  protected readonly outsideHours = computed(() => {
    const summary = this.summary();
    return (
      summary !== null &&
      summary.effectiveMode === 'FOLLOW_SCHEDULE' &&
      !isServiceOpenNow(summary.bindings, new Date(), this.timeZone())
    );
  });

  protected readonly untilChoices = computed<readonly Until[]>(() =>
    this.endOfDay() === null
      ? ['30', '60', '120', 'cancelled']
      : ['30', '60', '120', 'endOfDay', 'cancelled'],
  );

  protected readonly noteRequired = computed(() => this.reason() === 'OTHER');

  protected readonly valid = computed(() => {
    if (this.mode() === 'FOLLOW_SCHEDULE') {
      return true;
    }
    if (this.reason() === '') {
      return false;
    }
    return !this.noteRequired() || this.note().trim() !== '';
  });

  /** The instant the choice resolves to, or null for «until cancelled» and for returning to the schedule. */
  protected readonly effectiveUntil = computed<Date | null>(() => {
    if (this.mode() === 'FOLLOW_SCHEDULE') {
      return null;
    }
    const choice = this.until();
    if (choice === 'cancelled') {
      return null;
    }
    if (choice === 'endOfDay') {
      return this.endOfDay();
    }
    return new Date(Date.now() + UNTIL_MINUTES[choice] * 60_000);
  });

  /** What the button will do, in words: «Close until 21:30». */
  protected readonly submitLabel = computed(() => {
    const until = this.effectiveUntil();
    const time = until === null ? '' : clockIn(until, this.timeZone());
    switch (this.mode()) {
      case 'FOLLOW_SCHEDULE':
        return this.text('submit.follow');
      case 'FORCE_OPEN':
        return until === null
          ? this.text('submit.forceOpen')
          : this.text('submit.forceOpenUntil', { time });
      default:
        return until === null
          ? this.text('submit.closeCancelled')
          : this.text('submit.closeUntil', { time });
    }
  });

  constructor() {
    this.registry.register(
      {
        id: 'branch-state-dialog',
        title: () => this.text('keys.title'),
        shortcuts: [1, 2, 3].map((position) => ({
          keys: [String(position)],
          caps: [String(position)],
          label: () => this.text('keys.reason'),
          overDialog: true,
          enabled: () => this.reasons()[position - 1] !== undefined && !this.busy(),
          run: () => this.reason.set(this.reasons()[position - 1]),
        })),
      },
      this.destroyRef,
    );
  }

  ngOnInit(): void {
    void this.load();
  }

  protected text(
    key: Parameters<typeof branchStateText.text>[1],
    values?: Readonly<Record<string, string | number>>,
  ): string {
    return branchStateText.text(this.i18n.locale(), key, values);
  }

  protected reasonLabel(code: BranchReason): string {
    return this.text(`reason.${code}`);
  }

  protected modeLabel(mode: ServiceMode): string {
    return this.text(
      mode === 'FOLLOW_SCHEDULE'
        ? 'mode.follow'
        : mode === 'FORCE_CLOSED'
          ? 'mode.close'
          : 'mode.open',
    );
  }

  protected untilLabel(choice: Until): string {
    if (choice === 'endOfDay') {
      const end = this.endOfDay();
      return this.text('until.endOfDay', {
        time: end === null ? '' : clockIn(end, this.timeZone()),
      });
    }
    return this.text(`until.${choice}`);
  }

  protected closedReason(): string {
    const code = this.summary()?.reasonCode;
    return code ? this.text(`reason.${code as BranchReason}`) : '';
  }

  protected pickMode(mode: ServiceMode): void {
    this.mode.set(mode);
    this.reason.set('');
    if (mode === 'FOLLOW_SCHEDULE') {
      this.note.set('');
    }
  }

  protected onEnter(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLTextAreaElement || target instanceof HTMLButtonElement) {
      return;
    }
    event.preventDefault();
    void this.submit();
  }

  protected close(): void {
    if (!this.busy()) {
      this.closed.emit();
    }
  }

  protected async submit(): Promise<void> {
    if (!this.valid() || this.busy()) {
      return;
    }
    const request: ChangeServiceStateRequest =
      this.mode() === 'FOLLOW_SCHEDULE'
        ? { mode: 'FOLLOW_SCHEDULE' }
        : {
            mode: this.mode(),
            reasonCode: this.reason() as string,
            ...(this.note().trim() === '' ? {} : { note: this.note().trim() }),
            ...(this.effectiveUntil() === null
              ? {}
              : { effectiveUntil: (this.effectiveUntil() as Date).toISOString() }),
          };
    this.busy.set(true);
    this.failure.set(null);
    try {
      await this.states.change(this.locationId(), request);
      this.closed.emit();
    } catch (error) {
      this.failure.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.text('dialog.failed'),
      );
    } finally {
      this.busy.set(false);
    }
  }

  private async load(): Promise<void> {
    const base = this.location.scope();
    if (base === null) {
      return;
    }
    const scope = { ...base, locationId: this.locationId() };
    try {
      const [summary, locations] = await Promise.all([
        this.api.serviceSummary(scope),
        this.api.list(scope),
      ]);
      this.summary.set(summary);
      const zone = locations.find((candidate) => candidate.id === this.locationId())?.timezone;
      if (zone) {
        this.timeZone.set(zone);
      }
      // Closed already: the meaningful act is reopening. Shut by the schedule: opening it takes force.
      if (summary.effectiveMode === 'FORCE_CLOSED') {
        this.pickMode('FOLLOW_SCHEDULE');
      } else if (this.outsideHours()) {
        this.pickMode('FORCE_OPEN');
      } else {
        this.pickMode('FORCE_CLOSED');
      }
    } catch (error) {
      this.failure.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.text('dialog.failed'),
      );
    }
  }
}
