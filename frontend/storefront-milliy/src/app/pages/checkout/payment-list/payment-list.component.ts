import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TranslatePipe } from '../../../shared/translate/translate.pipe';

/** One payment method as the picker draws it. */
export interface PaymentChoice {
  readonly code: string;
  /** The message key for its label, or empty when this deployment has none -- the code then stands in. */
  readonly labelKey: string;
}

/**
 * The payment-method rows on the checkout screen.
 *
 * Presentation only: which methods exist and which is selected are the
 * checkout's (`CheckoutComponent`), because the confirm action reads them. A tap
 * is reported through {@link choose}; this component never changes the selection
 * itself. It is its own component so the picker's rules stay out of the
 * checkout stylesheet, which had outgrown its component-style budget.
 */
@Component({
  selector: 'app-payment-list',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './payment-list.component.html',
  styleUrl: './payment-list.component.scss',
})
export class PaymentListComponent {
  readonly choices = input.required<readonly PaymentChoice[]>();
  readonly selected = input<string | null>(null);
  readonly choose = output<string>();
}
