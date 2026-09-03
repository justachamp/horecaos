import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';
import { ReportsFilterBar } from './reports-filter-bar';
import { ReportsFilterState } from './reports-filter-state';

/**
 * The Reports section's frame (IA §7). `ReportsFilterState` is provided here,
 * one instance per visit to `/statistics`, so the period and slice a manager
 * sets on the overview survives switching tabs beside it (statistics.md
 * §1.1: "the bar's state is shared across all 7.x views").
 *
 * 7.1 Business overview and 7.2 Order reports (tier P, wave 33) are joined
 * this wave (39) by every tier-2 row the IA lists: 7.3 Branch & SLA and 7.7
 * Product analytics are real reads (see those pages' own docs for exactly
 * what "real" covers); 7.4 Courier, 7.5 Staff, 7.6 Customer analytics and 7.9
 * Marketing reports route to the shared `NotBuiltPage`, each naming the fact
 * family it is missing rather than shipping a chart over nothing. 7.8 Demand
 * forecast and 7.10 Geography are tier 3 and stay off this rail entirely.
 */
@Component({
  selector: 'q-reports-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe, ReportsFilterBar],
  providers: [ReportsFilterState],
  templateUrl: './reports-shell.html',
  styleUrl: './reports-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportsShell {}
