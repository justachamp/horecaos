import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Marketing section's frame: a sub-nav strip over a routed child, the same
 * shape `CatalogShell`/`FinanceShell`/`DeliveryShell` already use.
 *
 * `frontend-information-architecture.md` §6 lists eight Marketing screens; its
 * own tier legend puts every one of 6.1, 6.2, 6.4, 6.5, 6.7, 6.8 at tier 2 —
 * the owner directed the tier-2 build, unlike Finance's §8.3-8.6, which are
 * explicitly "Wave 2" and carry no tab here at all. 6.3 Loyalty was tier 3 and
 * stayed out through wave 39; wave 44 built it (`LoyaltyPage`, ADR 0046) as
 * one of the last two unbuilt tier-3 rows, alongside Customers §5.5. 6.6
 * Referrals was the one tier-3 row still out; wave 47 built its reward half
 * (a new ADR, riding on ADR 0046's loyalty ledger).
 *
 * Three tabs now have a real backend to render honestly. 6.4 Campaigns:
 * ADR 0044's audiences, campaign lifecycle, and suppression machinery, with
 * `RFM targeting` folded in as this row's own audience-definition step rather
 * than a separate IA row. 6.3 Loyalty: `LoyaltyOperationsController`'s
 * balance/liability reads already served Customer detail (wave 39); wave 44
 * added `LoyaltyPolicyController`'s accrual-rule and redemption-policy
 * authoring, the `LOYALTY_POLICY_MANAGE` capability's first caller. Deposit
 * accounts and POS balance sync render as honest not-built panels inside that
 * same screen rather than a separate route, because they are sub-bullets of
 * one IA row, not rows of their own — see `LoyaltyPage`'s own doc for why
 * each is not built. 6.6 Referrals: a new ADR's `ReferralPolicyController`
 * authors which reward shape a brand runs (both sides, or the referrer
 * only), and `ReferralOperationsController` reads what has actually
 * happened; website/Telegram acquisition links render as an honest
 * not-built panel inside that same screen — see `ReferralsPage`'s own doc.
 * The other four tabs route to the shared `NotBuiltPage`, each naming its
 * own IA subsection — 6.1 Promotions and 6.2 Promo codes have
 * a schema (`V0093`) and nothing above it (no authoring service, no
 * controller); 6.5 Automations' trigger engine, and 6.7/6.8's merchandising
 * slots, have neither schema nor service. Each is a subsystem in its own
 * right, not a small gap a couple of reserved migrations could close
 * honestly — see wave 44's final report.
 */
@Component({
  selector: 'q-marketing-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './marketing-shell.html',
  styleUrl: './marketing-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarketingShell {}
