import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { CurrentLocation } from '../../../core/auth/current-location';
import { TPipe } from '../../../core/i18n/t.pipe';
import { KioskCard } from './kiosk-card';

/**
 * The branch's kiosks as a screen of their own (ADR 0162, Decision 6): the KIOSK card, mounted for the working branch
 * without the Channel setup hub around it.
 *
 * The card also sits in each KIOSK channel's hub, which reads the channel (`channel.read`) under a Settings section
 * that is the tenant's (`tenant.read`). The person the record has pair and revoke kiosks, a branch manager, holds
 * `kiosk.device.manage` at her branch and neither of those. Every call the card makes is under the branch and needs
 * `kiosk.device.manage` there, so this screen needs nothing else (`NavItem.alsoAdmittedAt` admits it by its path).
 */
@Component({
  selector: 'q-kiosks-page',
  imports: [TPipe, KioskCard],
  templateUrl: './kiosks-page.html',
  styleUrl: './kiosks-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KiosksPage implements OnInit {
  private readonly location = inject(CurrentLocation);

  protected readonly ready = signal(false);
  protected readonly scope = this.location.scope;
  protected readonly branchName = computed(() => {
    const id = this.scope()?.locationId;
    return this.location.options().find((option) => option.id === id)?.displayName ?? null;
  });

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    this.ready.set(true);
  }
}
