import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Finance section's frame: a sub-nav strip over a routed child, the same
 * shape `CatalogShell` established for a multi-screen IA section (see that
 * component's own doc).
 *
 * IA §8 lists six Finance screens; only 8.1 Payments & settlements and 8.2
 * Fiscal receipts are pilot-tier (`frontend-information-architecture.md`'s
 * own tier legend — `P` is a go-live blocker, and 8.3-8.6 are explicitly
 * "Wave 2" in that document's Part 3). This shell therefore carries two tabs,
 * not six: the other four are not a smaller version of this screen, they are
 * not in scope for this wave at all, the same way Settings 10.11 has no rail
 * entry rather than a greyed-out one. See `operations-spec/finance.md` §0.
 */
@Component({
  selector: 'q-finance-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './finance-shell.html',
  styleUrl: './finance-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FinanceShell {}
