import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';
import {
  BuiltAmendmentCommandType,
  FINANCIAL_AMENDMENT_COMMAND_TYPES,
  GATED_AMENDMENT_COMMAND_TYPES,
} from './order-amendments';

/** The same `orders.amendment.command.*` keys `order-amendments.ts#amendmentCommandLabel` reads for a history row — reused here so a menu button and its later history label are always the identical string. */
const FINANCIAL_COMMAND_LABEL_KEYS: Readonly<Record<BuiltAmendmentCommandType, MessageKey>> = {
  SET_KITCHEN_NOTE: 'orders.detail.details.kitchenNote',
  SET_CALLBACK_REQUESTED: 'orders.detail.details.callback',
  SET_CASH_TENDERED: 'orders.detail.details.cashTendered',
  SET_COURIER_NOTE: 'orders.detail.comments.courier',
  SET_INTERNAL_NOTE: 'orders.detail.comments.internal',
  ADD_LINES: 'orders.amendment.command.ADD_LINES',
  CHANGE_LINE_QUANTITY: 'orders.amendment.command.CHANGE_LINE_QUANTITY',
  REMOVE_LINES: 'orders.amendment.command.REMOVE_LINES',
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
 * Lists the five originals in the order §4.4's own table lists them, then the
 * financial commands in {@link FINANCIAL_AMENDMENT_COMMAND_TYPES}'s own order.
 * `REMOVE_LINES` (ADR 0169) is built but behind the brand's
 * `ordering.amendment_line_removal_enabled` gate, which the server refuses it
 * under by name while off; the wave brief's own trap is a client that offers a
 * refused command anyway, so it renders here only when {@link
 * lineRemovalEnabled} says the order's brand has it on. `order-amend-menu.spec.ts`
 * asserts both states.
 */
@Component({
  selector: 'q-order-amend-menu',
  imports: [TPipe, Modal],
  templateUrl: './order-amend-menu.html',
  styleUrl: './order-amend-menu.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderAmendMenu {
  /** ADR 0169: whether the order's brand has the removal path on (`OrderDetailResponse.lineRemovalEnabled`). */
  readonly lineRemovalEnabled = input(false);

  readonly select = output<BuiltAmendmentCommandType>();
  readonly dismiss = output<void>();

  protected readonly financialCommands = computed(() =>
    FINANCIAL_AMENDMENT_COMMAND_TYPES.filter(
      (type) =>
        this.lineRemovalEnabled() ||
        !(GATED_AMENDMENT_COMMAND_TYPES as readonly string[]).includes(type),
    ),
  );

  protected financialCommandLabelKey(type: BuiltAmendmentCommandType): MessageKey {
    return FINANCIAL_COMMAND_LABEL_KEYS[type];
  }
}
