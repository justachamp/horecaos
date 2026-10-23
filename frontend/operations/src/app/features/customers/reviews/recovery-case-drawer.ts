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
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { formatDateTime } from '../../../core/format/datetime';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Drawer } from '../../../shared/ui/drawer';
import { StatusPill } from '../../../shared/ui/status-pill';
import { Timeline, TimelineEntry } from '../../../shared/ui/timeline';
import { describeApiError } from '../../orders/order-errors';
import {
  ApologyShape,
  CASE_STATES,
  CaseDetail,
  CaseOutcome,
  CaseState,
  CaseSummary,
  CompensationCandidates,
  CompensationKind,
  MintedApologyCode,
  OUTCOMES_BY_CLOSING_STATE,
  RecoveryApi,
  TransitionInput,
} from './recovery-api';
import {
  compensateKey,
  compensationKey,
  grantShapeKey,
  grantStatusKey,
  isClosed,
  kindKey,
  outcomeKey,
  remedyStateKey,
  remedyTypeKey,
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
const APOLOGY_SHAPES: readonly ApologyShape[] = [
  'FIXED_AMOUNT_OFF_ORDER',
  'PERCENTAGE_OFF_ORDER',
  'FREE_DELIVERY',
];
const DEFAULT_VALID_DAYS = '30';
const MAX_VALID_DAYS = 365;

/** One thing the picker offers: the identifier the link is made with, and the words that describe it. */
interface PickerOption {
  readonly id: string;
  readonly label: string;
}

/**
 * One case, open (ADR 0159, Decisions 8 to 11): the rating, the tags the customer tapped, the
 * comment (or the marker that it is withheld from this reader), the clocks, the order, the
 * compensation linked so far and the history -- and the five things an operator does to it.
 *
 * <p>Everything here is read from the drawer's own `GET`, the only place a note or the comment is
 * returned. A mutation answers with a state and a version, so after each one the drawer reads again
 * rather than patching what it holds: what the person sees is always what the server has.
 *
 * <p>Linking compensation is a pick, never a paste (Decision 9): the drawer reads what exists for this case's
 * order and customer and offers it, and a code is minted from here for whoever holds
 * `pricing.promotion.manage` (ADR 0181, D10) and nobody else -- a case worker without it is told so rather than
 * handed a control that would be refused. The plaintext code is shown once, in this component's memory only.
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
  private readonly capabilities = inject(SessionCapabilities);
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

  protected readonly candidates = signal<CompensationCandidates | null>(null);
  protected readonly candidatesState = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');

  // The apology-code mint (D10): open on demand, never prefilled with anything the customer typed.
  protected readonly mintOpen = signal(false);
  protected readonly mintShape = signal<ApologyShape>('FIXED_AMOUNT_OFF_ORDER');
  protected readonly mintValue = signal('');
  protected readonly mintMinBasket = signal('');
  protected readonly mintDays = signal(DEFAULT_VALID_DAYS);
  /** The code just minted. Shown once and dropped with the drawer: nothing else holds it. */
  protected readonly minted = signal<MintedApologyCode | null>(null);
  /** The expiry instant the last submission of the mint form chose; see {@link mintExpiry}. */
  private mintAnchor: { readonly signature: string; readonly expiresAt: string | null } | null =
    null;
  protected readonly copied = signal(false);

  protected readonly compensationKinds = COMPENSATION_KINDS;
  protected readonly apologyShapes = APOLOGY_SHAPES;
  protected readonly grantShapeKey = grantShapeKey;
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

  /** Whether this operator may issue a code at all: the capability, not a guess from the page. */
  protected readonly canMint = computed(() => this.capabilities.has('PRICING_PROMOTION_MANAGE'));

  /** The finance console opens on the case's order only for someone who can read payments there. */
  protected readonly canOpenRemedyConsole = computed(() => this.capabilities.has('PAYMENT_READ'));

  /** The currency the order was paid in: a fixed-amount code is minted in it. */
  protected readonly mintCurrency = computed(() => this.detail()?.order?.currency ?? null);

  /** What the picker offers for the chosen kind, with the ones already on a case left out. */
  protected readonly pickable = computed<readonly PickerOption[]>(() => {
    const found = this.candidates();
    if (found === null) {
      return [];
    }
    // The wording follows the active locale; depending on it here re-words the options on a switch.
    this.i18n.locale();
    if (this.compensationKind() === 'ORDER_REMEDY') {
      return found.remedies
        .filter((r) => !r.taken)
        .map((r) => ({ id: r.refId, label: this.remedyText(r) }));
    }
    return found.grants
      .filter((g) => !g.taken)
      .map((g) => ({ id: g.grantId, label: this.grantText(g) }));
  });

  protected readonly mintValueNeeded = computed(() => this.mintShape() !== 'FREE_DELIVERY');

  protected readonly canSubmitMint = computed(() => {
    if (this.busy() || this.closed() || !this.canMint()) {
      return false;
    }
    const days = Number(this.mintDays());
    if (
      this.mintDays().trim() !== '' &&
      !(Number.isInteger(days) && days >= 1 && days <= MAX_VALID_DAYS)
    ) {
      return false;
    }
    const basket = this.mintMinBasket().trim();
    if (basket !== '' && !(Number.isInteger(Number(basket)) && Number(basket) >= 0)) {
      return false;
    }
    const shape = this.mintShape();
    if (shape === 'FREE_DELIVERY') {
      return true;
    }
    const value = Number(this.mintValue());
    if (!Number.isInteger(value) || value < 1) {
      return false;
    }
    return shape === 'PERCENTAGE_OFF_ORDER' ? value <= 100 : this.mintCurrency() !== null;
  });

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
    this.compensationRef.set('');
  }

  /** Reads what exists for this case's order and customer again (a remedy recorded in the finance console, say). */
  protected async loadCandidates(): Promise<void> {
    const summary = this.current();
    this.candidatesState.set('loading');
    try {
      this.candidates.set(await this.api.candidates(this.scope(), summary.locationId, summary.id));
      this.candidatesState.set('ready');
    } catch (error) {
      if (!(error instanceof ApiError)) {
        throw error;
      }
      this.candidatesState.set('error');
    }
  }

  // ------------------------------------------------------------------------------- apology code

  protected toggleMint(): void {
    this.mintOpen.update((open) => !open);
  }

  protected onMintShapeChange(value: string): void {
    this.mintShape.set(value as ApologyShape);
    this.mintValue.set('');
  }

  /** What the amount typed so far is, written the way the customer will read it. */
  protected mintPreview(): string | null {
    const currency = this.mintCurrency();
    const value = Number(this.mintValue());
    if (
      this.mintShape() !== 'FIXED_AMOUNT_OFF_ORDER' ||
      currency === null ||
      !Number.isInteger(value) ||
      value < 1
    ) {
      return null;
    }
    return formatMoney({ amountMinor: value, currency }, this.i18n.locale(), { withUnit: true });
  }

  /**
   * Issues the code for this case's customer. The case is the grant's source, so the grant says why it exists; the
   * link to the case is a separate, explicit pick, made from the list this call refreshes.
   */
  protected async mint(): Promise<void> {
    if (!this.canSubmitMint()) {
      return;
    }
    const shape = this.mintShape();
    const entered = Number(this.mintValue());
    const basket = this.mintMinBasket().trim();
    const days = this.mintDays().trim();
    const expiresAt = this.mintExpiry(days, [shape, entered, basket]);
    const issued = await this.mutate(() =>
      this.api.mintApologyCode(this.scope(), {
        customerAccountId: this.current().customerAccountId,
        shape,
        // The form speaks in whole percent; the platform keeps basis points.
        value:
          shape === 'FREE_DELIVERY'
            ? 0
            : shape === 'PERCENTAGE_OFF_ORDER'
              ? entered * 100
              : entered,
        currency: shape === 'FREE_DELIVERY' ? null : this.mintCurrency(),
        minBasketMinor: basket === '' ? 0 : Number(basket),
        sourceId: this.current().id,
        expiresAt,
      }),
    );
    if (issued === undefined) {
      return;
    }
    this.mintAnchor = null;
    this.minted.set(issued);
    this.copied.set(false);
    this.mintOpen.set(false);
    this.mintValue.set('');
    await this.loadCandidates();
    // The code just issued is the one the operator is about to link.
    this.compensationKind.set('BENEFIT_GRANT');
    this.compensationRef.set(issued.grantId);
  }

  /**
   * The instant the code lapses, fixed by the first submission of this form and kept for as long as the form is
   * unchanged. A moving `Date.now()` here would make a retry after a lost response a different request from the
   * first, and the platform would issue a second code (ADR 0031): the key only repeats when the body does. Any
   * edit to the form is a new request and takes a new instant; an issued code and a switch of case drop it.
   */
  private mintExpiry(days: string, others: readonly unknown[]): string | null {
    const signature = JSON.stringify([days, ...others]);
    if (this.mintAnchor?.signature !== signature) {
      this.mintAnchor = {
        signature,
        expiresAt:
          days === '' ? null : new Date(Date.now() + Number(days) * 86_400_000).toISOString(),
      };
    }
    return this.mintAnchor.expiresAt;
  }

  protected async copyMinted(): Promise<void> {
    const code = this.minted()?.plaintextCode;
    if (!code) {
      return;
    }
    try {
      await navigator.clipboard.writeText(code);
      this.copied.set(true);
    } catch {
      // No clipboard in this context: the code is on screen to be read out.
    }
  }

  protected forgetMinted(): void {
    this.minted.set(null);
    this.copied.set(false);
  }

  private remedyText(remedy: CompensationCandidates['remedies'][number]): string {
    const typeKey = remedyTypeKey(remedy.label);
    const stateKey = remedyStateKey(remedy.state);
    const parts = [typeKey === null ? remedy.label : this.i18n.t(typeKey)];
    if (remedy.amountMinor > 0) {
      parts.push(
        formatMoney(
          { amountMinor: remedy.amountMinor, currency: remedy.currency },
          this.i18n.locale(),
          {
            withUnit: true,
          },
        ),
      );
    }
    parts.push(stateKey === null ? remedy.state : this.i18n.t(stateKey));
    return parts.join(' · ');
  }

  private grantText(grant: CompensationCandidates['grants'][number]): string {
    const statusKey = grantStatusKey(grant.status);
    const value =
      grant.benefitType === 'PERCENTAGE_OFF_ORDER'
        ? `${grant.value / 100}%`
        : grant.benefitType === 'FIXED_AMOUNT_OFF_ORDER' && grant.currency !== null
          ? formatMoney(
              { amountMinor: grant.value, currency: grant.currency },
              this.i18n.locale(),
              {
                withUnit: true,
              },
            )
          : '';
    return [
      [this.i18n.t(grantShapeKey(grant.benefitType)), value]
        .filter((part) => part !== '')
        .join(' '),
      `…${grant.codeHint}`,
      statusKey === null ? grant.status : this.i18n.t(statusKey),
    ].join(' · ');
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

  /**
   * Runs one mutation, then reads the case again; a lost race reloads and says so. Answers what the mutation
   * answered, or nothing when it did not run or did not succeed (the reason is then in {@link errorText}).
   */
  private async mutate<T>(action: () => Promise<T>): Promise<T | undefined> {
    if (this.busy()) {
      return undefined;
    }
    this.busy.set(true);
    this.errorText.set(null);
    try {
      const result = await action();
      await this.reload();
      this.changed.emit();
      return result;
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
      return undefined;
    } finally {
      this.busy.set(false);
    }
  }

  private async load(summary: CaseSummary): Promise<void> {
    this.state.set('loading');
    this.detail.set(null);
    this.candidates.set(null);
    this.candidatesState.set('idle');
    this.minted.set(null);
    this.mintOpen.set(false);
    this.mintAnchor = null;
    this.errorText.set(null);
    try {
      this.detail.set(await this.api.detail(this.scope(), summary.locationId, summary.id));
      this.state.set('ready');
      this.applyIntent();
      if (!isClosed(this.current().state)) {
        void this.loadCandidates();
      }
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
