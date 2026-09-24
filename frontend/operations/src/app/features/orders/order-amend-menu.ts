import { ChangeDetectionStrategy, Component, output } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';
import { BuiltAmendmentCommandType, FINANCIAL_AMENDMENT_COMMAND_TYPES } from './order-amendments';

/** The same `orders.amendment.command.*` keys `order-amendments.ts#amendmentCommandLabel` reads for a history row — reused here so a menu button and its later history label are always the identical string. */
const FINANCIAL_COMMAND_LABEL_KEYS: Readonly<Record<BuiltAmendmentCommandType, MessageKey>> = {
  SET_KITCHEN_NOTE: 'orders.detail.details.kitchenNote',
  SET_CALLBACK_REQUESTED: 'orders.detail.details.callback',
  SET_CASH_TENDERED: 'orders.detail.details.cashTendered',
  SET_COURIER_NOTE: 'orders.detail.comments.courier',
  SET_INTERNAL_NOTE: 'orders.detail.comments.internal',
  ADD_LINES: 'orders.amendment.command.ADD_LINES',
  CHANGE_LINE_QUANTITY: 'orders.amendment.command.CHANGE_LINE_QUANTITY',
  CHANGE_PAYMENT_METHOD: 'orders.amendment.command.CHANGE_PAYMENT_METHOD',
  CHANGE_DELIVERY_ADDRESS: 'orders.amendment.command.CHANGE_DELIVERY_ADDRESS',
  CHANGE_FULFILLMENT_TIME: 'orders.amendment.command.CHANGE_FULFILLMENT_TIME',
  CHANGE_CONTACT: 'orders.amendment.command.CHANGE_CONTACT',
};

/**
 * `Изменить` (orders.md §4.4) — what `AMEND` opens (`OrderActionCode.AMEND`,
 * `OrderActionsPolicy.AMEND_EMISSION_ENABLED`, ADR 0105/0113, wave P10;
 * financial commands wave 10, rows `1.2c`/`2.1d`).
 *
 * Lists **exactly** the eleven built commands — the five originals in the
 * order §4.4's own table lists them, then the six wave-10 financial ones in
 * {@link FINANCIAL_AMENDMENT_COMMAND_TYPES}'s own order — never all twelve:
 * `REMOVE_LINES` is still declared in `AmendmentCommandType` and refused by
 * name at the server (`OrderAmendmentService.requireBuilt` — no ADR 0017
 * return/write-off primitive exists yet), and the wave brief's own trap is a
 * client that offers a refused command anyway. `order-amend-menu.spec.ts`
 * asserts `REMOVE_LINES` never renders here.
 */
@Component({
  selector: 'q-order-amend-menu',
  imports: [TPipe, Modal],
  templateUrl: './order-amend-menu.html',
  styleUrl: './order-amend-menu.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderAmendMenu {
  readonly select = output<BuiltAmendmentCommandType>();
  readonly dismiss = output<void>();

  protected readonly financialCommands = FINANCIAL_AMENDMENT_COMMAND_TYPES;

  protected financialCommandLabelKey(type: BuiltAmendmentCommandType): MessageKey {
    return FINANCIAL_COMMAND_LABEL_KEYS[type];
  }
}
