import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';

import type { MenuCategory } from '../../types/home.types';
import { trapTab } from '../../utils/focus-trap';
import { IconComponent } from '../icon/icon.component';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The marketplace layout's «all categories» overlay (ADR 0183 D17): every category of the menu in
 * one list, opened from the compact rail.
 *
 * A modal dialog named by its title. Focus goes to the close button, Tab stays inside, and focus
 * returns to the control that opened it. Escape, the close button and the backdrop each close it;
 * choosing a category reports the choice (`null` for «All», the same value the rail uses for «no
 * filter») and the screen closes it.
 */
@Component({
  selector: 'app-categories-overlay',
  standalone: true,
  imports: [IconComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'closed.emit()' },
  templateUrl: './categories-overlay.component.html',
  styleUrl: './categories-overlay.component.scss',
})
export class CategoriesOverlayComponent {
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  private readonly dialog = viewChild<ElementRef<HTMLElement>>('dialog');
  private readonly closeButton = viewChild<ElementRef<HTMLButtonElement>>('closeButton');
  private readonly restoreTo = this.document.activeElement as HTMLElement | null;

  readonly categories = input.required<readonly MenuCategory[]>();
  readonly selectedId = input<string | null>(null);

  readonly chosen = output<string | null>();
  readonly closed = output<void>();

  constructor() {
    afterNextRender(() => this.closeButton()?.nativeElement.focus(), { injector: this.injector });
    // Gives focus back to what opened the overlay when it goes away.
    inject(DestroyRef).onDestroy(() => {
      if (this.restoreTo?.isConnected) {
        this.restoreTo.focus();
      }
    });
  }

  protected onKeydown(event: KeyboardEvent): void {
    const dialog = this.dialog();
    if (dialog) {
      trapTab(event, dialog.nativeElement);
    }
  }

  protected onBackdrop(event: Event): void {
    if (event.target === event.currentTarget) {
      this.closed.emit();
    }
  }
}
