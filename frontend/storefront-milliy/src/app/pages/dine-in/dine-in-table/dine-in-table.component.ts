import {
  ChangeDetectionStrategy,
  Component,
  type OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { DineInService } from '../../../services/dine-in.service';
import { LangService } from '../../../services/lang.service';
import { MenuService } from '../../../services/menu.service';
import { MenuGridComponent } from '../../../shared/menu-grid/menu-grid.component';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import type { CategoryItem, MenuCategory } from '../../../types/home.types';

/**
 * The table screen: `/dine-in/table`, resumed from the admission
 * `DineInScanComponent` just minted (ADR 0047).
 *
 * <h2>What this app does at a table, and what it does not</h2>
 *
 * It **shows the menu** -- the table's own location, read on the table's own
 * `QR_TABLE` channel, with every dish's sold-out and sale-window state -- for
 * every admission mode. That is the whole of `VIEW_ONLY`, which publishes a menu
 * and nothing else.
 *
 * It does **not** order. `ORDER_AND_PAY` at a table needs a `DINE_IN` cart bound
 * to the table's open session, a checkout that attaches its order to the
 * table's bill, the running bill and the ask-for-the-bill action -- the half of
 * `frontend/storefront`'s table screen that is not ported yet. Until it is, an
 * `ORDER_AND_PAY` admission shows the same menu plus a plain notice
 * (`dineIn.orderingUnavailable`), rather than a control that opens a delivery
 * basket the guest is not ordering into. For the same reason a dish here is not
 * a link to the product page, which adds to that basket.
 */
@Component({
  selector: 'app-dine-in-table',
  standalone: true,
  imports: [MenuGridComponent, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dine-in-table.component.html',
  styleUrl: './dine-in-table.component.scss',
})
export class DineInTableComponent implements OnInit {
  private readonly dineIn = inject(DineInService);
  private readonly menuService = inject(MenuService);
  private readonly lang = inject(LangService);

  protected readonly admission = computed(() => this.dineIn.admission());
  protected readonly viewOnly = computed(() => this.admission()?.mode === 'VIEW_ONLY');

  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly categories = signal<readonly MenuCategory[]>([]);
  protected readonly sections = signal<readonly CategoryItem[]>([]);
  protected readonly currency = this.menuService.currency;

  protected readonly hasDishes = computed(() =>
    this.sections().some((section) => section.items.length > 0),
  );

  async ngOnInit(): Promise<void> {
    const admission = this.admission();
    if (!admission) {
      this.loading.set(false);
      return;
    }
    try {
      const menu = await this.menuService.home(
        this.lang.langId(),
        admission.locationId,
        // The table's own channel, when the tenant registered exactly one.
        admission.channelCode ?? undefined,
      );
      this.categories.set(menu.menu.categories);
      this.sections.set(menu.menu.category_items);
    } catch {
      this.failed.set(true);
    } finally {
      this.loading.set(false);
    }
  }
}
