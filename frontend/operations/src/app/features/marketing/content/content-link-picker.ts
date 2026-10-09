import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { LINK_TYPES, LinkType, LinkValue, linkNeedsTarget } from './content-document';
import { ContentReferences, hostOf } from './content-references';

const TYPE_KEYS: Readonly<Record<LinkType, MessageKey>> = {
  NONE: 'marketing.content.link.type.NONE',
  PRODUCT: 'marketing.content.link.type.PRODUCT',
  CATEGORY: 'marketing.content.link.type.CATEGORY',
  CART: 'marketing.content.link.type.CART',
  PROMOTION: 'marketing.content.link.type.PROMOTION',
  EXTERNAL: 'marketing.content.link.type.EXTERNAL',
};

/**
 * Where a tap goes (ADR 0157, Decision 3): nowhere, a dish, a category, the cart, a live promotion or
 * an address on the brand's list of allowed links.
 *
 * The link is a closed union, so this is a type choice and then the one thing that type needs: a
 * search over the brand's own dishes, categories or live promotions (nothing is typed by hand into
 * an id), or an `https` address. The address is checked as it is typed -- `https` only, and whether
 * its host is on the allowlist right now -- and the platform checks it again at publication and on
 * every read, so a host that later leaves the list stops being linked within a cache window.
 */
@Component({
  selector: 'q-content-link-picker',
  imports: [Combobox, TPipe],
  templateUrl: './content-link-picker.html',
  styleUrl: './content-link-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentLinkPicker {
  protected readonly refs = inject(ContentReferences);

  readonly link = input.required<LinkValue>();
  readonly disabled = input(false, { transform: booleanAttribute });
  readonly label = input<string | null>(null);

  readonly linkChange = output<LinkValue>();

  protected readonly types = LINK_TYPES;
  protected readonly query = signal('');

  protected readonly needsTarget = computed(() => linkNeedsTarget(this.link().type));

  protected readonly options = computed<readonly ComboboxOption[]>(() => {
    const query = this.query();
    switch (this.link().type) {
      case 'PRODUCT':
        return this.refs.products(query);
      case 'CATEGORY':
        return this.refs.categories(query);
      case 'PROMOTION':
        return this.refs.promotions(query);
      default:
        return [];
    }
  });

  /** The chosen target's name, or null when none is chosen. */
  protected readonly targetLabel = computed(() => {
    const { type, targetId } = this.link();
    if (targetId === null) {
      return null;
    }
    switch (type) {
      case 'PRODUCT':
        return this.refs.productLabel(targetId);
      case 'CATEGORY':
        return this.refs.categoryLabel(targetId);
      case 'PROMOTION':
        return this.refs.promotionLabel(targetId);
      default:
        return null;
    }
  });

  protected readonly placeholderKey = computed<MessageKey>(() => {
    switch (this.link().type) {
      case 'PRODUCT':
        return 'marketing.content.link.searchProduct';
      case 'CATEGORY':
        return 'marketing.content.link.searchCategory';
      default:
        return 'marketing.content.link.searchPromotion';
    }
  });

  /** `ok`, `notHttps` or `hostNotAllowed`: the address as typed against the rule and the list. */
  protected readonly urlState = computed<'empty' | 'ok' | 'notHttps' | 'hostNotAllowed'>(() => {
    const url = this.link().url.trim();
    if (url === '') {
      return 'empty';
    }
    if (hostOf(url) === null) {
      return 'notHttps';
    }
    return this.refs.hostAllowed(url) ? 'ok' : 'hostNotAllowed';
  });

  protected typeKey(type: LinkType): MessageKey {
    return TYPE_KEYS[type];
  }

  protected setType(value: string): void {
    const type = LINK_TYPES.find((candidate) => candidate === value) ?? 'NONE';
    this.query.set('');
    // Changing the type drops what the old type named: a category id is not a product id.
    this.linkChange.emit({ type, targetId: null, url: '' });
  }

  protected choose(option: ComboboxOption): void {
    this.query.set('');
    this.linkChange.emit({ ...this.link(), targetId: option.id });
  }

  protected clearTarget(): void {
    this.linkChange.emit({ ...this.link(), targetId: null });
  }

  protected setUrl(url: string): void {
    this.linkChange.emit({ ...this.link(), url });
  }
}
