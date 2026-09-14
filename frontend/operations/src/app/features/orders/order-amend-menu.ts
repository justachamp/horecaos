import { ChangeDetectionStrategy, Component, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';
import { BuiltAmendmentCommandType } from './order-amendments';

/**
 * `Изменить` (orders.md §4.4) — what `AMEND` opens (`OrderActionCode.AMEND`,
 * `OrderActionsPolicy.AMEND_EMISSION_ENABLED`, ADR 0105/0113, wave P10).
 *
 * Lists **exactly** the five built commands, in the order §4.4's own table
 * lists them, never all twelve: the seven financial commands are declared in
 * `AmendmentCommandType` and refused by name at the server
 * (`OrderAmendmentService.requireBuilt`), and the wave brief's own trap is a
 * client that offers one anyway. `order-amend-menu.spec.ts` asserts the seven
 * never render here.
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
}
