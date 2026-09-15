import { MessageKey } from '../../core/i18n/messages.en';
import { PosExportState } from './order-pos-export-api';

/** The i18n key carrying an operator-facing word for a POS export's own state — never the raw enum token (row `1.2i`, wave P42). */
export const POS_EXPORT_STATE_LABEL_KEYS: Readonly<Record<PosExportState, MessageKey>> = {
  PENDING: 'orders.detail.posExport.state.PENDING',
  SENT: 'orders.detail.posExport.state.SENT',
  ACCEPTED: 'orders.detail.posExport.state.ACCEPTED',
  REJECTED: 'orders.detail.posExport.state.REJECTED',
  UNCERTAIN: 'orders.detail.posExport.state.UNCERTAIN',
  AWAITING_OPERATOR: 'orders.detail.posExport.state.AWAITING_OPERATOR',
  RESOLVED_LANDED: 'orders.detail.posExport.state.RESOLVED_LANDED',
  RESOLVED_ABSENT: 'orders.detail.posExport.state.RESOLVED_ABSENT',
  ABANDONED: 'orders.detail.posExport.state.ABANDONED',
};

/**
 * States in which the till is confirmed to actually hold the ticket —
 * `ACCEPTED` (the provider answered and named the order it created) and
 * `RESOLVED_LANDED` (an operator or the recovery read established the same
 * fact later). Every other state is where the &sect;3.11 "a failed export is
 * not an order failure" reassurance belongs: the order is real regardless,
 * and only its kitchen copy is in question.
 */
const LANDED_STATES: ReadonlySet<PosExportState> = new Set(['ACCEPTED', 'RESOLVED_LANDED']);

export function posExportReachedTheTill(state: PosExportState): boolean {
  return LANDED_STATES.has(state);
}
