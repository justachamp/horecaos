import { ChangeDetectionStrategy, Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { ProfileMenuComponent } from './profile-menu/profile-menu';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { Session } from '../../core/auth/session';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { CustomerOtp } from '../../core/session/customer-otp';
import { FavouritesService } from '../../services/favourites.service';
import { AvatarService } from '../../services/avatar.service';
import { TelegramWebappService } from '../../services/telegram-webapp.service';
import { TelegramLinkService } from '../../services/telegram-link.service';
import packageJson from '../../../../package.json';

interface UserProfile {
  first_name?: string;
  last_name?: string;
  image?: string;
  phone?: string;
  [key: string]: unknown;
}

@Component({
  selector: 'app-profile',
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.scss',
  imports: [CommonModule, RouterLink, ProfileMenuComponent, TranslatePipe],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfileComponent implements OnInit {
  readonly phoneNumber = signal<string>('');
  readonly appVersion = signal<string>(packageJson.version);
  readonly isAuthorized = signal(false);
  readonly user = signal<UserProfile | null>(null);

  /**
   * Always null: there is no customer-facing media endpoint on the platform.
   *
   * The legacy value was the old backend's origin plus a path off the profile
   * document. Both are gone, so the template falls through to its placeholder
   * person icon -- which is what a customer with no photo always saw.
   */
  readonly avatarUrl = computed<string | null>(() => null);

  readonly displayName = computed(() => {
    const u = this.user();
    if (u?.first_name || u?.last_name) {
      return [u.first_name, u.last_name].filter(Boolean).join(' ').trim();
    }
    return this.phoneNumber();
  });

  protected readonly telegramWebapp = inject(TelegramWebappService);
  private readonly session = inject(Session);
  private readonly profileService = inject(CustomerProfileService);
  private readonly otp = inject(CustomerOtp);
  private readonly favourites = inject(FavouritesService);
  private readonly avatars = inject(AvatarService);
  private readonly telegramLink = inject(TelegramLinkService);

  constructor(
    private router: Router
  ) {}

  ngOnInit(): void {
    const authorized = this.session.isAuthenticated();
    this.isAuthorized.set(authorized);

    // The phone number is deliberately not shown any more, and it cannot be.
    //
    // The legacy screen decoded it out of the JWT's `phone_number` claim. The
    // platform's token is opaque -- 256 random bits behind a `qcs1.` prefix --
    // and `GET /me` reports contact points by kind and verification state and
    // never by value, because a phone number is ADR 0029 personal data whose
    // decrypt is recorded against a purpose. Painting it on this screen would
    // decrypt it on every render.
    //
    // Keeping a copy of what the customer typed at sign-in would work and is
    // not done: a shared handset's storage is not a place to leave somebody's
    // number. If this row has to come back, it needs an endpoint that reveals
    // it deliberately and records why.
    this.phoneNumber.set('');

    if (authorized) {
      this.profileService.load()
        .then((profile) => this.user.set((profile ?? {}) as unknown as UserProfile))
        .catch(() => this.user.set(null));
    }
  }

  private formatPhone(digits: string): string {
    const d = digits.replace(/\D/g, '').slice(-9);
    if (d.length < 9) return digits;
    return `+998 ${d.slice(0, 2)} ${d.slice(2, 5)}-${d.slice(5, 7)}-${d.slice(7)}`;
  }

  /**
   * Ends the session on the platform as well as in this tab.
   *
   * Dropping the token locally is not signing out. The session row survives for
   * thirty days and the token is a bearer: anything that saw it can still spend
   * it. `CustomerOtp.signOut` calls `DELETE /sessions/current` and clears the
   * local half whatever the platform answered -- a customer who pressed sign out
   * and got a network error must not still be signed in on the screen in front
   * of them.
   */
  onLogout(): void {
    this.profileService.forget();
    // The next customer on this handset must not inherit this one's list.
    this.favourites.forget();
    this.avatars.forget();
    this.telegramLink.forget();
    this.user.set(null);
    this.isAuthorized.set(false);
    this.otp.signOut().finally(() => {
      this.router.navigate(['/auth/login']).catch(() => {});
    });
  }
}
