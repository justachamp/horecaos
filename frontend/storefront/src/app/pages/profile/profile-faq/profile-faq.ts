import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { SupportService, type FaqCategory, type FaqItem } from '../../../services/support.service';
import { BackDirective } from '../../../shared/back/back.directive';
import { TranslateService } from '../../../services/translate.service';

@Component({
  selector: 'app-profile-faq',
  standalone: true,
  imports: [CommonModule, BackDirective],
  templateUrl: './profile-faq.html',
  styleUrl: './profile-faq.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfileFaqComponent implements OnInit {
  private readonly supportService = inject(SupportService);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly categories = signal<FaqCategory[]>([]);
  expandedItemId: string | null = null;

  ngOnInit(): void {
    this.supportService
      .faq()
      .then((data) => this.categories.set(data))
      .catch(() => this.error.set(this.translate.get('errors.generic')))
      .finally(() => this.loading.set(false));
  }

  toggle(item: FaqItem): void {
    this.expandedItemId = this.expandedItemId === item.id ? null : item.id;
  }

  isExpanded(item: FaqItem): boolean {
    return this.expandedItemId === item.id;
  }
}
