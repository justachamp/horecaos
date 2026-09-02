import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';
import { ReportsFilterBar } from './reports-filter-bar';
import { ReportsFilterState } from './reports-filter-state';

/**
 * The Reports section's frame (IA §7, tier P rows only: 7.1 Business overview
 * and 7.2 Order reports — statistics.md §2.1/§2.2). `ReportsFilterState` is
 * provided here, one instance per visit to `/statistics`, so the period and
 * slice a manager sets on the overview survives switching to the order-report
 * tabs beside it (statistics.md §1.1: "the bar's state is shared across all
 * 7.x views").
 *
 * Everything past these two rows — 7.3 through 7.10, the export centre, the
 * metric dictionary — is tier 2/3 and stays the shared `NotBuiltPage`
 * placeholder `app.routes.ts` already routes every other unbuilt rail entry
 * to; this shell does not grow tabs for them.
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
