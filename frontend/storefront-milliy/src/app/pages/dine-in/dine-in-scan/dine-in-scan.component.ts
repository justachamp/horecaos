import { ChangeDetectionStrategy, Component, type OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { DineInService } from '../../../services/dine-in.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

/**
 * The scan landing route: `/dine-in/:tableToken`, the URL a table's printed QR
 * code encodes (ADR 0047).
 *
 * Does one thing -- exchanges the printed token for a guest admission through
 * `DineInService.exchange` -- and then leaves. On success it replaces this URL
 * with the token-free `/dine-in/table` (`DineInTableComponent`), so the
 * one-time table token never sits in browser history, a bookmark, or a
 * `Referer` header past the single request that spends it; a reload of the
 * table screen afterwards resumes from the admission `DineInService` already
 * persisted, not from this route. On failure -- an unknown, rotated or archived
 * code, or a branch that takes no QR orders at all, all answered identically by
 * the platform -- this renders the one honest message rather than guessing
 * which.
 *
 * Ported from `frontend/storefront`'s component of the same name.
 */
@Component({
  selector: 'app-dine-in-scan',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dine-in-scan.component.html',
  styleUrl: './dine-in-scan.component.scss',
})
export class DineInScanComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dineIn = inject(DineInService);

  readonly failed = signal(false);

  ngOnInit(): void {
    const token = this.route.snapshot.paramMap.get('tableToken');
    if (!token) {
      this.failed.set(true);
      return;
    }
    this.dineIn
      .exchange(token)
      .then(() => this.router.navigate(['/dine-in', 'table'], { replaceUrl: true }))
      .catch(() => this.failed.set(true));
  }
}
