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
  viewChild,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { DataMatrixScan, QDatamatrixInput } from '../../shared/ui/datamatrix-input';
import { normaliseMarkingCode } from '../../shared/ui/marking-code';
import { OrderLine } from './order-detail';
import { describeApiError } from './order-errors';
import { REMOVAL_REASONS, OrderMarkingApi, RemovalReason, ScannedUnit } from './order-marking-api';

/** `MarkCaptureStage.forStatus`: the statuses at which a unit can still be scanned or taken back. */
const MARKABLE_STATUSES: ReadonlySet<string> = new Set(['CONFIRMED', 'PREPARING', 'READY']);

/** What a server refusal's `reason` is worth to an operator at the pass. */
const REFUSAL_MESSAGE: Readonly<Record<string, MessageKey>> = {
  MARKING_CODE_MALFORMED: 'orders.marking.error.malformed',
  MARKING_CODE_WRONG_PRODUCT: 'orders.marking.error.wrongProduct',
  MARKING_KEYED_ENTRY_REFUSED: 'orders.marking.error.keyed',
  LINE_MARKS_COMPLETE: 'orders.marking.error.complete',
  LINE_NOT_MARKED: 'orders.marking.error.notMarked',
  ORDER_NOT_MARKABLE: 'orders.marking.error.notMarkable',
};

interface Progress {
  readonly required: number;
  readonly captured: number;
}

interface Notice {
  readonly kind: 'ok' | 'warn' | 'error';
  readonly text: string;
}

/**
 * ADR 0161, gap map row `X.35` -- the marked goods of an order at the pass. Every marked line with how many
 * units have been scanned against it, the input that scans them, and the way to take a wrong scan back.
 *
 * **Why it exists.** A marked good is one identifier per physical unit, captured by somebody at the pass,
 * and the platform will not let the order leave it (`MARKS_INCOMPLETE`) until every unit is. This is the
 * screen that makes that bearable: it picks the line that still needs units by itself, arms the scanner, and
 * says `2 of 5 scanned` the moment a unit is taken.
 *
 * **It never keeps or shows a code.** A scan is validated here for instant feedback and sent to the server
 * in a request body; the server is the authority. What the panel holds afterwards is a count, a product
 * identifier and a sequence number. Scans that arrive faster than the server answers are queued in a plain
 * field (not a signal) and sent in order; a refused scan never stops the ones behind it.
 *
 * Self-contained, like the weighing panel beside it: the order detail and the expo screen both embed it, it
 * owns its writes, and it reports one fact upward -- {@link marksChanged} -- after which its host reads the
 * order again. A scan does not change the order's version, so the host needs no version to give it.
 */
@Component({
  selector: 'q-order-marking-panel',
  imports: [TPipe, QDatamatrixInput],
  templateUrl: './order-marking-panel.html',
  styleUrl: './order-marking-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderMarkingPanel {
  private readonly api = inject(OrderMarkingApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly orderId = input.required<string>();
  readonly status = input.required<string>();
  readonly lines = input.required<readonly OrderLine[]>();

  /** A unit was scanned or taken back: the host should read the order again. */
  readonly marksChanged = output<void>();

  private readonly scanner = viewChild(QDatamatrixInput);

  /** Progress the server answered with, which is newer than the order the host last read. */
  private readonly answered = signal<Readonly<Record<string, Progress>>>({});
  private readonly selected = signal<string | null>(null);
  protected readonly paused = signal(false);
  protected readonly busy = signal(false);
  protected readonly notice = signal<Notice | null>(null);
  protected readonly units = signal<Readonly<Record<string, readonly ScannedUnit[]>>>({});
  protected readonly releasing = signal<string | null>(null);
  protected readonly releaseReason = signal<RemovalReason>('MISSCAN');
  protected readonly selfTest = signal(false);
  protected readonly selfTestResult = signal<string | null>(null);
  protected readonly removalReasons = REMOVAL_REASONS;

  // Scans waiting to be sent. A plain field, not a signal: nothing that renders may hold a code.
  private queue: DataMatrixScan[] = [];
  private draining = false;

  protected readonly rows = computed(() => this.lines().filter((line) => line.marking != null));
  protected readonly markable = computed(() => MARKABLE_STATUSES.has(this.status()));

  /** The line the scanner is filling: the one picked, if it still needs units, else the first that does. */
  protected readonly current = computed<OrderLine | null>(() => {
    const rows = this.rows();
    const picked = rows.find((row) => row.lineId === this.selected() && this.remaining(row) > 0);
    return picked ?? rows.find((row) => this.remaining(row) > 0) ?? null;
  });

  protected readonly armed = computed(
    () => this.markable() && !this.paused() && (this.selfTest() || this.current() !== null),
  );
  protected readonly inputRemaining = computed(() => {
    const line = this.current();
    return this.selfTest() ? 1 : line === null ? 0 : this.remaining(line);
  });

  /** Whether the order holds a marked line at all. The server sends `marking` only while the flag is on. */
  private readonly hasMarkedLines = computed(() => this.rows().length > 0);

  constructor() {
    // The detail pane is reused across orders under one route: scans and notices must not carry to the next.
    effect(() => {
      const orderId = this.orderId();
      untracked(() => {
        this.answered.set({});
        this.selected.set(null);
        this.notice.set(null);
        this.units.set({});
        this.queue = [];
        this.paused.set(false);
      });
    });
    // The unit list is read only for an order that has a marked line to list units of. Every order opened in
    // the console used to cost a `GET .../marks` here, including all of them while `feature.marked_goods` is
    // off and no order carries `marking`. The effect runs again when the order changes or when its first
    // marked line appears (an amendment can add one), and not on the reads that merely refresh the counts.
    effect(() => {
      const orderId = this.orderId();
      if (this.hasMarkedLines()) {
        untracked(() => void this.reloadUnits(orderId));
      }
    });
  }

  // ------------------------------------------------------------------ what the screen says

  protected progressOf(line: OrderLine): Progress {
    return this.answered()[line.lineId] ?? line.marking ?? { required: 0, captured: 0 };
  }

  protected remaining(line: OrderLine): number {
    const progress = this.progressOf(line);
    return Math.max(0, progress.required - progress.captured);
  }

  protected progressText(line: OrderLine): string {
    const progress = this.progressOf(line);
    return progress.captured >= progress.required
      ? this.i18n.t('orders.marking.line.complete', { required: progress.required })
      : this.i18n.t('orders.marking.line.progress', {
          captured: progress.captured,
          required: progress.required,
        });
  }

  protected unitsOf(line: OrderLine): readonly ScannedUnit[] {
    return this.units()[line.lineId] ?? [];
  }

  protected unitText(unit: ScannedUnit): string {
    return this.i18n.t('orders.marking.units.item', { sequence: unit.sequence, gtin: unit.gtin });
  }

  protected stageText(unit: ScannedUnit): string {
    return this.i18n.t(`orders.marking.stage.${unit.stage}`);
  }

  protected reasonText(reason: RemovalReason): string {
    return this.i18n.t(`orders.marking.remove.${reason}`);
  }

  // ------------------------------------------------------------------ the operator's controls

  protected select(line: OrderLine): void {
    this.selected.set(line.lineId);
    this.paused.set(false);
  }

  protected resume(): void {
    this.paused.set(false);
  }

  protected setSelfTest(on: boolean): void {
    this.selfTest.set(on);
    this.selfTestResult.set(null);
    this.queue = [];
  }

  protected setReleaseReason(value: string): void {
    if ((REMOVAL_REASONS as readonly string[]).includes(value)) {
      this.releaseReason.set(value as RemovalReason);
    }
  }

  // ------------------------------------------------------------------ a scan

  protected onScanned(scan: DataMatrixScan): void {
    if (this.selfTest()) {
      this.selfTestResult.set(this.describeSelfTest(scan));
      return;
    }
    this.queue.push(scan);
    void this.drain();
  }

  /** Sends the waiting scans one at a time, in the order they were taken. */
  private async drain(): Promise<void> {
    if (this.draining) {
      return;
    }
    this.draining = true;
    this.busy.set(true);
    try {
      for (let scan = this.queue.shift(); scan !== undefined; scan = this.queue.shift()) {
        await this.send(scan);
      }
    } finally {
      this.draining = false;
      this.busy.set(false);
    }
  }

  private async send(scan: DataMatrixScan): Promise<void> {
    const line = this.current();
    if (line === null) {
      return;
    }
    const canonical = normaliseMarkingCode(scan.raw);
    if (canonical === null) {
      this.refuse('orders.marking.error.malformed');
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.capture(this.scope(), this.orderId(), line.lineId, canonical, scan.method),
      );
      this.answered.update((current) => ({
        ...current,
        [line.lineId]: { required: result.required, captured: result.captured },
      }));
      this.scanner()?.acknowledge(result.captured, result.required);
      this.notice.set(
        result.gtinChecked
          ? null
          : { kind: 'warn', text: this.i18n.t('orders.marking.accepted.unchecked') },
      );
      this.marksChanged.emit();
      void this.reloadUnits(this.orderId());
    } catch (error) {
      this.fail(error);
    }
  }

  private fail(error: unknown): void {
    if (!(error instanceof ApiError)) {
      throw error;
    }
    this.scanner()?.refuse();
    if (error.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
      this.notice.set({ kind: 'error', text: this.i18n.t('orders.marking.error.rateLimited') });
      return;
    }
    const reason = error.problem?.['reason'];
    if (reason === 'MARKING_CODE_ALREADY_CAPTURED') {
      // The same unit scanned twice on this line is "already scanned", not a duplicate: the second scan of a
      // five-unit line is the operator checking, and a response lost in transit looks exactly like this.
      const onThisLine = error.problem?.['onThisLine'] === true;
      this.notice.set({
        kind: 'error',
        text: this.i18n.t(
          onThisLine ? 'orders.marking.error.alreadyHere' : 'orders.marking.error.alreadyElsewhere',
        ),
      });
      return;
    }
    const key = typeof reason === 'string' ? REFUSAL_MESSAGE[reason] : undefined;
    this.notice.set({
      kind: 'error',
      text: key ? this.i18n.t(key) : describeApiError(error, (k, values) => this.i18n.t(k, values)),
    });
    if (reason === 'ORDER_NOT_MARKABLE') {
      this.marksChanged.emit();
    }
  }

  private refuse(key: MessageKey): void {
    this.scanner()?.refuse();
    this.notice.set({ kind: 'error', text: this.i18n.t(key) });
  }

  // ------------------------------------------------------------------ taking a unit back

  protected async release(line: OrderLine, unit: ScannedUnit): Promise<void> {
    if (this.releasing() !== null || !this.markable()) {
      return;
    }
    this.releasing.set(unit.markId);
    try {
      const result = await firstValueFrom(
        this.api.remove(
          this.scope(),
          this.orderId(),
          line.lineId,
          unit.markId,
          this.releaseReason(),
        ),
      );
      this.answered.update((current) => ({
        ...current,
        [line.lineId]: { required: result.required, captured: result.captured },
      }));
      this.notice.set(null);
      this.marksChanged.emit();
      await this.reloadUnits(this.orderId());
    } catch (error) {
      this.fail(error);
    } finally {
      this.releasing.set(null);
    }
  }

  private async reloadUnits(orderId: string): Promise<void> {
    try {
      const marks = await firstValueFrom(this.api.list(this.scope(), orderId));
      if (orderId !== this.orderId()) {
        return;
      }
      const byLine: Record<string, readonly ScannedUnit[]> = {};
      for (const line of marks.lines) {
        byLine[line.orderLineId] = line.marks;
      }
      this.units.set(byLine);
    } catch {
      // The count above is the truth the operator acts on; the unit list is a convenience that returns on the next scan.
    }
  }

  // ------------------------------------------------------------------ the one-minute self-test

  private describeSelfTest(scan: DataMatrixScan): string {
    const { diagnostic } = scan;
    const parts = [
      this.i18n.t('orders.marking.selfTest.result', {
        length: diagnostic.length,
        separators: diagnostic.groupSeparators,
        shape: this.i18n.t(
          diagnostic.gs1Shaped ? 'orders.marking.selfTest.gs1' : 'orders.marking.selfTest.notGs1',
        ),
      }),
    ];
    if (diagnostic.aimIdentifier) {
      parts.push(this.i18n.t('orders.marking.selfTest.aim'));
    }
    if (scan.method === 'KEYED') {
      parts.push(this.i18n.t('orders.marking.selfTest.keyed'));
    }
    return parts.join(' ');
  }
}
