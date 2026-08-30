import { Component, OnInit, inject, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { UiCartService } from '../../../services/ui-cart.service';
import { TranslateService } from '../../../services/translate.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

@Component({
  selector: 'app-cart-items',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslatePipe],
  templateUrl: './cart-items.component.html',
  styleUrl: './cart-items.component.scss'
})
export class CartItemsComponent implements OnInit {
  showCommentInput = true;
  isCommentFocused = false;

  @ViewChild('commentTextarea') commentTextareaRef?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('scrollContainer') scrollContainerRef?: ElementRef<HTMLDivElement>;

  private readonly translate = inject(TranslateService);

  formatPrice(n: number): string {
    const c = this.translate.get('common.currency') || "so'm";
    return n.toLocaleString('uz-UZ') + ' ' + c;
  }

  constructor(
    public cart: UiCartService,
    private router: Router
  ) {}

  ngOnInit(): void {
    void this.cart.load();
  }

  openComment(): void {
    this.showCommentInput = !this.showCommentInput;
    if (this.showCommentInput) {
      setTimeout(() => {
        const el = this.commentTextareaRef?.nativeElement;
        if (el) {
          this.scrollToBottom();
          el.focus();
        }
      }, 100);
    }
  }

  onCommentFocus(): void {
    this.isCommentFocused = true;
    setTimeout(() => this.scrollToBottom(), 350);
  }

  onCommentBlur(): void {
    this.isCommentFocused = false;
  }

  private scrollToBottom(): void {
    const container = this.scrollContainerRef?.nativeElement;
    if (container) {
      container.scrollTop = container.scrollHeight;
    }
  }

  continue(): void {
    const comment = this.cart.orderComment?.trim();
    const firstItem = this.cart.items()[0];
    if (comment && firstItem) {
      // The note is written with the line, because the platform's PUT replaces
      // it: there is no note-only endpoint. It cannot be read back afterwards --
      // a line reports that a note exists and never what it says.
      this.cart
        .add(firstItem.variant_id, firstItem.quantity, comment)
        .finally(() => this.router.navigate(['/cart/confirmation']));
    } else {
      this.router.navigate(['/cart/confirmation']);
    }
  }
}
