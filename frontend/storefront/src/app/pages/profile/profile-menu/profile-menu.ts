import { ChangeDetectionStrategy, Component, input, output, computed, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LangService, LANG_LABELS } from '../../../services/lang.service';
import { ThemeService, type ThemeMode } from '../../../services/theme.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { FEATURES } from '../../../core/config/features';

export interface ProfileMenuItem {
  id: string;
  labelKey: string;
  value?: string;
  route?: string;
  isLogout?: boolean;
  isTheme?: boolean;
  /** Show only when user is authorized */
  authorizedOnly?: boolean;
}

@Component({
  selector: 'app-profile-menu',
  imports: [CommonModule, RouterLink, TranslatePipe],
  templateUrl: './profile-menu.html',
  styleUrl: './profile-menu.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfileMenuComponent implements OnInit {
  private readonly lang = inject(LangService);
  readonly theme = inject(ThemeService);

  readonly isAuthorized = input<boolean>(false);
  readonly logoutClick = output<void>();

  private readonly allItems: ProfileMenuItem[] = [
    { id: 'favorites', labelKey: 'profile.favorites', route: '/profile/favorites', authorizedOnly: true },
    { id: 'locations', labelKey: 'profile.locations', route: '/locations/list', authorizedOnly: true },
    { id: 'language', labelKey: 'profile.language', route: '/profile/language' },
    { id: 'theme', labelKey: 'profile.theme', isTheme: true },
    { id: 'support', labelKey: 'profile.support', route: '/profile/support' },
    { id: 'logout', labelKey: 'profile.logout', isLogout: true }
  ];

  readonly items = computed(() => {
    const authorized = this.isAuthorized();
    let list = authorized
      ? this.allItems
      : this.allItems.filter((i) => !i.isLogout && !i.authorizedOnly);
    // Favourites has no backend yet -- see FEATURES.favourites. Hidden here
    // rather than left to fail after a tap, and `favouritesEnabledGuard`
    // covers a customer who reaches the route another way.
    if (!FEATURES.favourites) {
      list = list.filter((i) => i.id !== 'favorites');
    }
    const id = this.lang.langId();
    const langValue = LANG_LABELS[id] ?? LANG_LABELS['ru'];
    return list.map((item) =>
      item.id === 'language' ? { ...item, value: langValue } : item
    );
  });

  ngOnInit(): void {
    this.lang.load();
  }

  setTheme(mode: ThemeMode): void {
    this.theme.setMode(mode);
  }

  onLogout(): void {
    this.logoutClick.emit();
  }
}
