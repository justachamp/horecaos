import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { DineInService } from '../../../services/dine-in.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

/**
 * The scan landing route: `/dine-in/:tableToken`, the URL a table's printed
 * QR code encodes (ADR 0047, row `10.5`'s dine-in facet).
 *
 * Does one thing -- exchanges the printed token for a guest token through
 * `DineInService.exchange` -- and then leaves. On success it replaces this
 * URL with the token-free `/dine-in/table` (`DineInTableComponent`), so the
 * one-time table token never sits in browser history, a bookmark, or a
 * `Referer` header past the single request that spends it; a reload of the
 * table screen afterward resumes from the guest token `DineInService`
 * already persisted, not from this route. On failure -- an unknown, rotated
 * or archived code, or a branch that takes no QR orders at all, all
 * answered identically by the platform, per `QrEntryController.exchange`'s
 * own doc -- this renders the one honest message rather than guessing which.
 */
@Component({
  selector: 'app-dine-in-scan',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe],
  templateUrl: './dine-in-scan.component.html',
  styleUrl: './dine-in-scan.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
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
