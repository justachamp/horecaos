import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-section-header',
  imports: [CommonModule, RouterLink],
  templateUrl: './section-header.component.html',
  styleUrl: './section-header.component.scss'
})
export class SectionHeaderComponent {
  readonly title = input.required<string>();
  readonly actionLabel = input<string>('Hammasi');
  readonly actionHref = input<string | null>(null);
  readonly actionRouterLink = input<string | string[] | null>(null);
  /** When set, stored in sessionStorage as mar_selected_cat on action click */
  readonly actionCategory = input<{ id: string; name: string } | null>(null);

  onActionClick(): void {
    const category = this.actionCategory();
    if (category) {
      sessionStorage.setItem('mar_selected_cat', JSON.stringify(category));
    }
  }
}
