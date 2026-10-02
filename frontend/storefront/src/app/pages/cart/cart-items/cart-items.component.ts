import { Component, OnInit, inject, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { UiCartService } from '../../../services/ui-cart.service';
import { TranslateService } from '../../../services/translate.service';
import { LangService } from '../../../services/lang.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import type { CartResponseComboComponent, CartResponseItem } from '../../../types/cart.types';
import { presetLabelFor } from '../../../utils/preset-label';
import { catchweightEstimateGrams, formatQuantity, formatWeight } from '../../../utils/physical';

@Component({
  selector: 'app-cart-items',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslatePipe],
  templateUrl: './cart-items.component.html',
  styleUrl: './cart-items.component.scss',
})
export class CartItemsComponent implements OnInit {
  showCommentInput = true;
  isCommentFocused = false;

  @ViewChild('commentTextarea') commentTextareaRef?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('scrollContainer') scrollContainerRef?: ElementRef<HTMLDivElement>;

  private readonly translate = inject(TranslateService);
  private readonly lang = inject(LangService);

  formatPrice(n: number): string {
    const c = this.translate.get('common.currency') || "so'm";
    return n.toLocaleString('uz-UZ') + ' ' + c;
  }

  /** The chosen modifiers for one line, as a single readable row. */
  modifiersSummary(item: CartResponseItem): string {
    return item.modifiers.map((m) => m.label || m.groupName).join(', ');
  }

  /** ADR 0136: one component of a combo line, as `Cola 0.5 L ×2` -- the units the line puts on the order for one combo. */
  comboComponentLabel(component: CartResponseComboComponent): string {
    const name = component.variantName
      ? `${component.name} ${component.variantName}`
      : component.name;
    return component.quantity > 1 ? `${name} ×${component.quantity}` : name;
  }

  /** `0,5 порц.`, `3 шт` — the quantity as the customer's language writes it (ADR 0137). */
  quantityLabel(item: CartResponseItem): string {
    const unit = this.translate.get(
      item.physical?.splittable ? 'physical.portionsUnit' : 'common.itemsUnit',
    );
    return `${formatQuantity(item.quantity, this.lang.langId())} ${unit}`;
  }

  /** The line's amount through the cart, marked as an estimate when it is sold by weight. */
  lineTotal(item: CartResponseItem): string {
    const amount = this.formatPrice(this.cart.lineAmount(item));
    return item.physical?.catchweight ? `≈ ${amount}` : amount;
  }

  /** What a weighed line is estimated at: every unit at its nominal weight, until it is weighed at handover. */
  estimateText(item: CartResponseItem): string | null {
    const grams = catchweightEstimateGrams(item.physical);
    return grams === null
      ? null
      : this.translate.getWithParams('physical.estimateLine', {
          weight: formatWeight(grams * item.quantity, this.lang.langId()),
        });
  }

  /** Row 2.1b: the line's checked comment presets, in the customer's own language. */
  presetsSummary(item: CartResponseItem): string {
    return item.commentPresets.map((preset) => this.presetLabel(preset)).join(', ');
  }

  private presetLabel(preset: CartResponseItem['commentPresets'][number]): string {
    return presetLabelFor(preset, this.lang.langId());
  }

  constructor(
    public cart: UiCartService,
    private router: Router,
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
      //
      // The line's own modifierOptionIds and commentPresetCodes (row 2.1b)
      // are resent here too, and must be: this PUT replaces the whole line,
      // so leaving either out would silently strip whatever the customer
      // chose while only meaning to add a note.
      this.cart
        .add(
          firstItem.variant_id,
          firstItem.quantity,
          comment,
          firstItem.modifierOptionIds,
          firstItem.commentPresetCodes,
        )
        .finally(() => this.router.navigate(['/cart/confirmation']));
    } else {
      this.router.navigate(['/cart/confirmation']);
    }
  }
}
