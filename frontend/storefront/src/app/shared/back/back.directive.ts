import { Directive, inject, input } from '@angular/core';
import { NavigationHistoryService } from '../../services/navigation-history.service';

/** Navigates to the previous in-app page. Optional value is a fallback route. */
@Directive({
  selector: '[appBack]',
  host: {
    '(click)': 'onClick($event)',
  },
})
export class BackDirective {
  private readonly history = inject(NavigationHistoryService);

  /** Fallback path when there is no previous in-app page. */
  readonly appBack = input('/home');

  onClick(event: Event): void {
    event.preventDefault();
    this.history.back(this.appBack() || '/home');
  }
}
