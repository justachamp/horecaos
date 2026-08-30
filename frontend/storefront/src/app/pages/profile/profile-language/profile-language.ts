import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LangService } from '../../../services/lang.service';
import { TranslateService } from '../../../services/translate.service';
import { CustomerProfileService } from '../../../services/customer-profile.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';

export interface LanguageOption {
  id: string;
  label: string;
  flag: string; // emoji or image URL
}

@Component({
  selector: 'app-profile-language',
  imports: [CommonModule, TranslatePipe, BackDirective],
  templateUrl: './profile-language.html',
  styleUrl: './profile-language.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfileLanguageComponent implements OnInit {
  private readonly lang = inject(LangService);
  private readonly translate = inject(TranslateService);
  private readonly profile = inject(CustomerProfileService);

  readonly languages: LanguageOption[] = [
    { id: 'uz', label: "O'zbek", flag: '🇺🇿' },
    { id: 'ru', label: 'Русский', flag: '🇷🇺' },
    { id: 'en', label: 'English', flag: '🇬🇧' }
  ];

  readonly selectedId = computed(() => this.lang.langId());

  ngOnInit(): void {
    this.lang.load().then(() => this.translate.loadTranslations());
  }

  /**
   * Switches the interface language, and tells the platform.
   *
   * The screen changes first and the write follows, because the language is a
   * local preference that happens to be persisted: a customer who is offline
   * still gets the language they picked.
   *
   * The name is no longer read out of `localStorage` and echoed back. That dance
   * existed because the legacy PUT also replaced every field, and the copy in
   * storage was whatever the last screen happened to leave there -- so a stale
   * one silently renamed the customer. `CustomerProfileService` echoes from the
   * profile it actually read.
   *
   * A guest has no profile to write to, and that is not an error: they picked a
   * language, it works, and there is nothing to persist it against until they
   * have an account.
   */
  selectLanguage(id: string): void {
    this.translate.setLang(id);
    if (!this.profile.profile()) {
      return;
    }
    this.profile.update({ locale: id }).catch(() => {
      // Reported by the error interceptor. The language is already applied.
    });
  }
}
