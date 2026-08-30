import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SupportService, type SocialMediaItem } from '../../../services/support.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { TranslateService } from '../../../services/translate.service';
import { BackDirective } from '../../../shared/back/back.directive';

@Component({
  selector: 'app-profile-support',
  imports: [CommonModule, TranslatePipe, BackDirective],
  templateUrl: './profile-support.html',
  styleUrl: './profile-support.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileSupportComponent implements OnInit {
  private readonly supportService = inject(SupportService);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly socials = signal<SocialMediaItem[]>([]);

  /**
   * The icon URL, as the platform already gives it.
   *
   * The platform returns a root-relative path to its own storefront media
   * endpoint, which resolves against this origin -- the page is same-origin
   * with the API by design. It used to be re-based against the legacy backend's
   * host, which is why this needed the old API origin at all.
   */
  getImageUrl(image: string): string {
    return image ?? '';
  }

  onImageError(e: Event): void {
    const img = e.target as HTMLImageElement;
    img.style.display = 'none';
    const next = img.nextElementSibling as HTMLElement;
    if (next) {
      next.classList.remove('hidden');
      next.classList.add('flex');
    }
  }

  /**
   * Reads the brand's own links from the platform.
   *
   * These were hardcoded here -- a YouTube, an Instagram and a Telegram written
   * into the component -- which meant changing where customers are sent was a
   * release. They are now `support.social_links`, published per brand, with the
   * URL constrained at the database to http(s), tel: and mailto: so nothing an
   * operator types can become a `javascript:` link on a customer's screen.
   */
  ngOnInit(): void {
    this.loading.set(true);
    this.error.set(null);
    this.supportService
      .socialLinks()
      .then((links) => this.socials.set(links))
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.loading.set(false));
  }
}
