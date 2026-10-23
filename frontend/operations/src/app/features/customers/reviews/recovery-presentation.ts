import { MessageKey } from '../../../core/i18n/messages.en';
import { TimelineEntry, TimelineTone } from '../../../shared/ui/timeline';
import { StatusTone } from '../../../shared/ui/status-pill';
import {
  ApologyShape,
  CaseEventKind,
  CaseOutcome,
  CaseState,
  CaseTimelineEntry,
  CompensationKind,
  SlaState,
  StaffRef,
} from './recovery-api';

/** Where a clock stands, as a pill tone: a breached clock is the only red thing on the board. */
export function slaTone(sla: SlaState): StatusTone {
  switch (sla) {
    case 'BREACHED':
      return 'danger';
    case 'AT_RISK':
      return 'warning';
    case 'MET':
      return 'success';
    case 'ON_TRACK':
      return 'none';
  }
}

export function stateTone(state: CaseState): StatusTone {
  switch (state) {
    case 'NEW':
      return 'info';
    case 'IN_PROGRESS':
      return 'warning';
    case 'RESOLVED':
      return 'success';
    case 'CLOSED_UNRESOLVED':
      return 'none';
  }
}

/** A case that has reached a terminal state is final: nothing about it changes any more except a note. */
export function isClosed(state: CaseState): boolean {
  return state === 'RESOLVED' || state === 'CLOSED_UNRESOLVED';
}

export function stars(rating: number): string {
  return '★'.repeat(rating) + '☆'.repeat(Math.max(0, 5 - rating));
}

/** What the person sees for a staff member: the name when there is one, else the non-personal reference. */
export function staffLabel(staff: StaffRef | null): string | null {
  if (staff === null) {
    return null;
  }
  return staff.name.trim() !== '' ? staff.name : staff.displayReference;
}

const BREACH_KINDS: ReadonlySet<CaseEventKind> = new Set([
  'FIRST_RESPONSE_BREACHED',
  'RESOLUTION_BREACHED',
]);

export interface TimelineText {
  /** Already-translated word for an event kind. */
  readonly kind: (kind: CaseEventKind) => string;
  /** Already-translated outcome word. */
  readonly outcome: (code: CaseOutcome) => string;
  readonly compensation: (code: string) => string;
  /** Already-formatted instant. */
  readonly time: (iso: string) => string;
  /** The word on the red badge of a clock that passed its due time. */
  readonly breached: string;
}

const OUTCOMES: ReadonlySet<string> = new Set<CaseOutcome>([
  'REMEDY_GRANTED',
  'OFFLINE_GESTURE',
  'EXPLAINED_NO_REMEDY',
  'NO_REMEDY_NEEDED',
  'CUSTOMER_UNREACHABLE',
  'CUSTOMER_DECLINED',
  'COMPLAINT_NOT_UPHELD',
  'NOT_ACTIONABLE',
]);

/**
 * One drawer timeline line, the shape `q-timeline` renders. The actor is a staff member (the
 * name, else the reference) or, for a fact nobody caused -- a clock passing its due time -- no
 * actor at all, which the timeline renders as a pure system line.
 *
 * `detailCode` is an outcome, a compensation kind or a staff member's non-personal reference
 * depending on the kind; the note text is the only free text and appears only on a `NOTE`.
 */
export function timelineEntryOf(entry: CaseTimelineEntry, text: TimelineText): TimelineEntry {
  const staff = staffLabel(entry.actor);
  const system = entry.actorType === 'SYSTEM' || entry.actorType === 'SYSTEM_JOB';
  let detail: string | null = null;
  switch (entry.kind) {
    case 'NOTE':
      detail = entry.note;
      break;
    case 'RESOLVED':
    case 'CLOSED_UNRESOLVED':
      detail =
        entry.detailCode !== null && OUTCOMES.has(entry.detailCode)
          ? text.outcome(entry.detailCode as CaseOutcome)
          : null;
      break;
    case 'COMPENSATION_ATTACHED':
      detail = entry.detailCode === null ? null : text.compensation(entry.detailCode);
      break;
    case 'STARTED':
    case 'ASSIGNED':
      detail = entry.detailCode;
      break;
    default:
      detail = null;
  }
  const tone: TimelineTone = BREACH_KINDS.has(entry.kind) ? 'danger' : 'default';
  return {
    id: entry.id,
    timestamp: text.time(entry.occurredAt),
    actor: system
      ? null
      : { kind: 'USER', displayName: staff, subject: entry.actor?.displayReference ?? null },
    title: text.kind(entry.kind),
    detail,
    badge: tone === 'danger' ? { label: text.breached, tone } : null,
  };
}

/** The message key for a state, outcome or similar enum value, kept typed so a rename fails the build. */
export function stateKey(state: CaseState): MessageKey {
  return `customers.recovery.state.${state}`;
}

export function slaKey(sla: SlaState): MessageKey {
  return `customers.recovery.sla.${sla}`;
}

export function outcomeKey(outcome: CaseOutcome): MessageKey {
  return `customers.recovery.outcome.${outcome}`;
}

export function kindKey(kind: CaseEventKind): MessageKey {
  return `customers.recovery.timeline.${kind}`;
}

export function sourceKey(source: 'AUTO' | 'MANUAL'): MessageKey {
  return `customers.recovery.source.${source}`;
}

/** The word for a linked compensation in the drawer's list. */
export function compensationKey(kind: CompensationKind): MessageKey {
  return `customers.recovery.compensation.${kind}`;
}

/** The longer wording of a compensation kind in the link form's picker. */
export function compensateKey(kind: CompensationKind): MessageKey {
  return `customers.recovery.compensate.${kind}`;
}

/** The word for a kind of remedy recorded against an order (`RemedyType`): a code the platform sends. */
export function remedyTypeKey(label: string): MessageKey | null {
  switch (label) {
    case 'ORDER_REFUND':
      return 'customers.recovery.remedy.type.ORDER_REFUND';
    case 'DELIVERY_FEE_REIMBURSEMENT':
      return 'customers.recovery.remedy.type.DELIVERY_FEE_REIMBURSEMENT';
    case 'FUTURE_DISCOUNT':
      return 'customers.recovery.remedy.type.FUTURE_DISCOUNT';
    default:
      return null;
  }
}

export function remedyStateKey(state: string): MessageKey | null {
  switch (state) {
    case 'RECORDED':
      return 'customers.recovery.remedy.state.RECORDED';
    case 'EXECUTED':
      return 'customers.recovery.remedy.state.EXECUTED';
    default:
      return null;
  }
}

/** The shape of an apology code, in the picker's and the mint form's words. */
export function grantShapeKey(shape: ApologyShape): MessageKey {
  return `customers.recovery.grant.shape.${shape}`;
}

/** Where a grant stands (`ACTIVE`, `REDEEMED`, `EXPIRED`, `REVOKED`); null for a status this console does not know. */
export function grantStatusKey(status: string): MessageKey | null {
  switch (status) {
    case 'ACTIVE':
      return 'customers.recovery.grant.status.ACTIVE';
    case 'REDEEMED':
      return 'customers.recovery.grant.status.REDEEMED';
    case 'EXPIRED':
      return 'customers.recovery.grant.status.EXPIRED';
    case 'REVOKED':
      return 'customers.recovery.grant.status.REVOKED';
    default:
      return null;
  }
}
