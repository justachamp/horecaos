import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { TermsSectionsPipe } from '../../shared/pipes/terms-sections.pipe';
import { BackDirective } from '../../shared/back/back.directive';
import { LangService } from '../../services/lang.service';
import { TERMS_UZ_CONTENT } from './content/terms-uz.content';
import { TERMS_EN_CONTENT } from './content/terms-en.content';
import { TERMS_RU_CONTENT } from './content/terms-ru.content';

@Component({
  selector: 'app-terms-of-conditions',
  standalone: true,
  imports: [CommonModule, TranslatePipe, TermsSectionsPipe, BackDirective],
  templateUrl: './terms-of-conditions.component.html',
  styleUrl: './terms-of-conditions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TermsOfConditionsComponent {
  private readonly lang = inject(LangService);

  /** Raw Uzbek terms content - split into sections by numbered points */
  readonly termsUzContent = TERMS_UZ_CONTENT;
  /** Raw English terms content - split into sections by numbered points */
  readonly termsEnContent = TERMS_EN_CONTENT;
  /** Raw Russian terms content - split into sections by numbered points */
  readonly termsRuContent = TERMS_RU_CONTENT;

  readonly selectedLangId = this.lang.langId;
}
