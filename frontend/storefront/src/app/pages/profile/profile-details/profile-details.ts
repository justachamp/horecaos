import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
  computed,
} from '@angular/core';
import { switchMap } from 'rxjs';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CustomerProfileService } from '../../../services/customer-profile.service';
import { TranslateService } from '../../../services/translate.service';
import { AvatarService } from '../../../services/avatar.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';
import { FEATURES } from '../../../core/config/features';

export interface UserProfile {
  id?: string;
  phone?: string;
  first_name?: string;
  last_name?: string;
  language?: string;
  image?: string;
  [key: string]: unknown;
}

@Component({
  selector: 'app-profile-details',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslatePipe, BackDirective],
  templateUrl: './profile-details.html',
  styleUrl: './profile-details.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileDetailsComponent implements OnInit {
  private readonly router = inject(Router);
  private readonly profileService = inject(CustomerProfileService);
  private readonly translate = inject(TranslateService);
  private readonly avatars = inject(AvatarService);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly uploadingAvatar = signal(false);
  readonly removingImage = signal(false);
  readonly error = signal<string | null>(null);

  readonly user = signal<UserProfile | null>(null);
  readonly firstName = signal('');
  readonly lastName = signal('');

  /**
   * A short-lived signed URL the platform minted for this customer's own
   * asset -- once the avatar endpoints exist on this build's backend. Until
   * then `avatarAvailable` is false, `load()` below is never called, and this
   * stays null, which is exactly what a customer with no photo always saw.
   */
  readonly avatarUrl = computed<string | null>(() => this.avatars.url());

  ngOnInit(): void {
    this.loading.set(true);
    this.error.set(null);
    this.profileService.load()
      .then(() => {
        // The platform stores one displayName; this form has two fields. The
        // service splits on the first space and rejoins on save, so opening the
        // screen and pressing save never rewrites a name on its own.
        this.firstName.set(this.profileService.firstName());
        this.lastName.set(this.profileService.lastName());
      })
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.loading.set(false));
    // The signed URL expires, so it is fetched when the screen opens rather
    // than cached with the profile. Skipped while FEATURES.avatar is off --
    // the endpoint it calls does not exist on this build's backend yet.
    if (FEATURES.avatar) {
      this.avatars.load().catch(() => {
        // A customer with no picture, or a guest. Neither is worth a message.
      });
    }
  }

  onFirstNameChange(value: string): void {
    this.firstName.set(value);
  }

  onLastNameChange(value: string): void {
    this.lastName.set(value);
  }

  /**
   * Saves the name.
   *
   * Both fields go every time, and so does the language and the timezone the
   * service echoes: the platform's PATCH writes all three columns it owns, so
   * an omitted field is a cleared one.
   */
  async saveProfile(): Promise<void> {
    if (this.saving()) return;
    this.error.set(null);
    this.saving.set(true);
    try {
      await this.profileService.update({
        firstName: this.firstName().trim(),
        lastName: this.lastName().trim(),
      });
      this.router.navigate(['/profile'], { replaceUrl: true }).catch(() => {});
    } catch {
      this.error.set(this.translate.get('errors.generic'));
    } finally {
      this.saving.set(false);
    }
  }

  /** Gates the upload/remove UI until the platform's avatar endpoints exist. See `FEATURES`. */
  readonly avatarAvailable = FEATURES.avatar;

  /**
   * Uploads a chosen file and attaches it.
   *
   * The input is cleared whatever happens, so picking the same file again after
   * a failure still fires a change event -- without that, a retry of the exact
   * same photo silently does nothing.
   */
  onAvatarSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!this.avatarAvailable || !file || !file.type.startsWith('image/') || this.uploadingAvatar()) {
      return;
    }
    this.error.set(null);
    this.uploadingAvatar.set(true);
    this.avatars
      .replace(file)
      .catch(() => this.error.set(this.translate.get('profile.errors.avatarFailed')))
      .finally(() => this.uploadingAvatar.set(false));
  }

  onAvatarError(e: Event): void {
    const img = e.target as HTMLImageElement;
    img.style.display = 'none';
  }

  removeAvatar(): void {
    if (!this.avatarAvailable || this.removingImage()) {
      return;
    }
    this.error.set(null);
    this.removingImage.set(true);
    this.avatars
      .remove()
      .catch(() => this.error.set(this.translate.get('profile.errors.avatarFailed')))
      .finally(() => this.removingImage.set(false));
  }
}
