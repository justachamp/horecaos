import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Finance section's frame: a sub-nav strip over a routed child, the same
 * shape `CatalogShell` established for a multi-screen IA section (see that
 * component's own doc).
 *
 * 8.1 Payments & settlements and 8.2 Fiscal receipts (tier P, wave 34) are
 * joined this wave (39) by all four tier-2 rows: 8.3 Cash reconciliation, 8.4
 * Delivery cost reconciliation and 8.5 Courier payouts read ADR 0042's
 * already-built courier module directly rather than a reporting fact table
 * (see each page's own doc for exactly what that means); 8.6 Subscription &
 * billing reads ADR 0021's plan/entitlement/usage machinery, honestly short
 * of the period close and invoices that module's own status line says do not
 * exist yet. See `operations-spec/finance.md` §0 for 8.1/8.2's own history.
 */
@Component({
  selector: 'q-finance-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './finance-shell.html',
  styleUrl: './finance-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FinanceShell {}
