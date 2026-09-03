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
 * explicitly "Wave 2" and carry no tab here at all. 6.3 Loyalty and 6.6
 * Referrals are tier 3 and, per the same legend, stay out entirely: no tab, no
 * route, the same "tier 3 stays out" this build was scoped against.
 *
 * Only 6.4 Campaigns has a real backend to render honestly: ADR 0044's
 * audiences, campaign lifecycle, and suppression machinery, with `RFM
 * targeting` folded in as this row's own audience-definition step rather than
 * a separate IA row. The other five tabs route to the shared `NotBuiltPage`,
 * each naming its own IA subsection — 6.1 Promotions and 6.2 Promo codes have
 * a schema (`V0093`) and nothing above it (no authoring service, no
 * controller); 6.5 Automations' trigger engine, and 6.7/6.8's merchandising
 * slots, have neither schema nor service. Each is a subsystem in its own
 * right, not a small gap this wave's two reserved migrations could close
 * honestly — see the wave's final report.
 */
@Component({
  selector: 'q-marketing-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './marketing-shell.html',
  styleUrl: './marketing-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MarketingShell {}
