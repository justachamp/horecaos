import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { AttachedModifierGroup, ModifierGroupSummary, ProductDetail } from './catalog-domain';
import {
  ATTACHMENT_FULFILLMENT_MODES,
  AttachmentFulfillmentMode,
  AttachmentPolicyRequest,
  AttachmentVisibility,
  CompositeApi,
  ModifierAttachmentView,
} from './composite-api';

/** An empty or malformed number field means "no override", never zero. */
function integerOrNull(text: string): number | null {
  const parsed = Number.parseInt(text, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * The product editor's group settings (ADR 0136), under the Modifiers tab: how
 * this product offers each group it has attached, and which groups a variant of
 * it offers when it is chosen as a modifier.
 *
 * **Overrides live on the attachment, never on the group.** A group is shared
 * across products, and `catalog.md` states the rule this design keeps: editing
 * a shared group from one product's screen would silently change another
 * product. So the group's own values are shown read-only, and the second,
 * separate affordance writes this product's own required/minimum/maximum — a
 * field left on "group's own" is `null` on the wire, which is the fallback.
 *
 * **A hidden group is a charge, not a choice.** The customer is never shown it;
 * its one option is applied to every order of the picked types and itemised on
 * the order. That is why it takes fulfilment modes and why the panel says what
 * makes it publishable (required, exactly one active option).
 *
 * **The nested level is one level.** The groups attached to a variant are
 * offered under it when that variant is the target of another product's
 * modifier option; a third level is refused at publication.
 *
 * The product, the library and the version each write sends as `If-Match` come
 * from the editor; this panel raises {@link changed} so the editor reloads
 * what it holds and the next write carries the new version.
 */
@Component({
  selector: 'q-product-modifier-policy-panel',
  imports: [TPipe],
  templateUrl: './product-modifier-policy-panel.html',
  styleUrl: './product-modifier-policy-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductModifierPolicyPanel implements OnInit {
  private readonly api = inject(CompositeApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  readonly product = input.required<ProductDetail>();
  readonly library = input.required<readonly ModifierGroupSummary[]>();
  /** The catalog locale (`ru`/`uz`/`en`) variant names are read in. */
  readonly locale = input.required<string>();

  /** An attachment write landed: the editor re-reads the product so the next write carries the new version. */
  readonly changed = output<void>();

  protected readonly modes = ATTACHMENT_FULFILLMENT_MODES;
  protected readonly saving = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);

  /** What the operator has chosen and not yet saved, per group id. */
  private readonly visibilityDrafts = signal<Readonly<Record<string, AttachmentVisibility>>>({});
  private readonly modeDrafts = signal<
    Readonly<Record<string, readonly AttachmentFulfillmentMode[]>>
  >({});

  /** The groups attached to each variant of this product itself — the nested level — by variant id. */
  protected readonly variantAttachments = signal<
    Readonly<Record<string, readonly ModifierAttachmentView[]>>
  >({});

  protected readonly attached = computed(() => this.product().modifierGroups);

  async ngOnInit(): Promise<void> {
    await this.loadVariantAttachments();
  }

  protected groupOf(groupId: string): ModifierGroupSummary | undefined {
    return this.library().find((group) => group.groupId === groupId);
  }

  protected modeLabel(mode: AttachmentFulfillmentMode): string {
    return this.i18n.t(`catalog.editor.policy.mode.${mode}`);
  }

  protected groupName(groupId: string): string {
    return this.groupOf(groupId)?.name ?? groupId;
  }

  protected visibilityOf(attached: AttachedModifierGroup): AttachmentVisibility {
    return this.visibilityDrafts()[attached.groupId] ?? attached.visibility ?? 'VISIBLE';
  }

  protected modesOf(attached: AttachedModifierGroup): readonly AttachmentFulfillmentMode[] {
    return (
      this.modeDrafts()[attached.groupId] ??
      attached.applicableFulfillmentModes ??
      ATTACHMENT_FULFILLMENT_MODES
    );
  }

  protected isHidden(attached: AttachedModifierGroup): boolean {
    return this.visibilityOf(attached) === 'HIDDEN_AUTO_SELECT';
  }

  protected setVisibility(attached: AttachedModifierGroup, value: string): void {
    this.visibilityDrafts.update((drafts) => ({
      ...drafts,
      [attached.groupId]: value as AttachmentVisibility,
    }));
  }

  protected toggleMode(attached: AttachedModifierGroup, mode: AttachmentFulfillmentMode): void {
    const current = this.modesOf(attached);
    const next = current.includes(mode)
      ? current.filter((existing) => existing !== mode)
      : ATTACHMENT_FULFILLMENT_MODES.filter(
          (candidate) => candidate === mode || current.includes(candidate),
        );
    this.modeDrafts.update((drafts) => ({ ...drafts, [attached.groupId]: next }));
  }

  /** A hidden group that names no order type would apply to none: refused before it is sent. */
  protected hasNoMode(attached: AttachedModifierGroup): boolean {
    return this.isHidden(attached) && this.modesOf(attached).length === 0;
  }

  /**
   * A hidden group needs exactly one active option, and to be required: the
   * server's own `HIDDEN_MODIFIER_GROUP_AMBIGUOUS_DEFAULT` rule, mirrored from
   * what the library already tells us so the warning is on screen before a
   * publish refuses it.
   */
  protected hiddenIsAmbiguous(attached: AttachedModifierGroup): boolean {
    const group = this.groupOf(attached.groupId);
    if (!this.isHidden(attached) || !group) {
      return false;
    }
    const required = attached.requiredOverride ?? group.required;
    return !required || group.optionCount !== 1;
  }

  protected requiredText(attached: AttachedModifierGroup): string {
    return attached.requiredOverride === true
      ? 'yes'
      : attached.requiredOverride === false
        ? 'no'
        : 'own';
  }

  protected groupValues(groupId: string): { required: boolean; min: number; max: number } | null {
    const group = this.groupOf(groupId);
    return group
      ? { required: group.required, min: group.minimumSelections, max: group.maximumSelections }
      : null;
  }

  protected variantLabel(variantId: string): string {
    const variant = this.product().variants.find((candidate) => candidate.variantId === variantId);
    return (
      variant?.translations[this.locale()]?.name ||
      variant?.sku ||
      this.product().translations[this.locale()]?.name ||
      variantId
    );
  }

  protected attachmentsOfVariant(variantId: string): readonly ModifierAttachmentView[] {
    return this.variantAttachments()[variantId] ?? [];
  }

  /** Library groups this variant does not offer yet. */
  protected attachableTo(variantId: string): readonly ModifierGroupSummary[] {
    const held = new Set(this.attachmentsOfVariant(variantId).map((a) => a.modifierGroupId));
    return this.library().filter((group) => !held.has(group.groupId));
  }

  // --------------------------------------------------------------- writes

  protected async save(
    attached: AttachedModifierGroup,
    requiredText: string,
    minimumText: string,
    maximumText: string,
  ): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || this.hasNoMode(attached)) {
      return;
    }
    const hidden = this.isHidden(attached);
    const selected = this.modesOf(attached);
    const request: AttachmentPolicyRequest = {
      visibility: this.visibilityOf(attached),
      // Every mode is the null default: a group that names all three is just as universal as one
      // that names none, and the server stores the compact form.
      applicableFulfillmentModes:
        hidden && selected.length < ATTACHMENT_FULFILLMENT_MODES.length ? selected : null,
      requiredOverride: requiredText === 'yes' ? true : requiredText === 'no' ? false : null,
      minimumSelectionsOverride: integerOrNull(minimumText),
      maximumSelectionsOverride: integerOrNull(maximumText),
    };
    await this.run(`policy:${attached.groupId}`, async () => {
      await firstValueFrom(
        this.api.setProductAttachmentPolicy(
          scope,
          this.product().productId,
          attached.groupId,
          attached.version ?? 1,
          request,
        ),
      );
      this.visibilityDrafts.update(({ [attached.groupId]: _kept, ...rest }) => rest);
      this.modeDrafts.update(({ [attached.groupId]: _kept, ...rest }) => rest);
      this.changed.emit();
    });
  }

  protected async attachToVariant(variantId: string, groupId: string): Promise<void> {
    const scope = this.brand.scope();
    if (!scope || !groupId) {
      return;
    }
    await this.run(`variant:${variantId}`, async () => {
      const attachment = await firstValueFrom(
        this.api.attachToVariant(
          scope,
          variantId,
          groupId,
          this.attachmentsOfVariant(variantId).length,
        ),
      );
      this.variantAttachments.update((all) => ({
        ...all,
        [variantId]: [
          ...(all[variantId] ?? []).filter((a) => a.modifierGroupId !== groupId),
          attachment,
        ],
      }));
      this.changed.emit();
    });
  }

  private async loadVariantAttachments(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    const entries = await Promise.all(
      this.product().variants.map(async (variant) => {
        try {
          return [
            variant.variantId,
            await firstValueFrom(this.api.variantAttachments(scope, variant.variantId)),
          ] as const;
        } catch {
          return [variant.variantId, [] as readonly ModifierAttachmentView[]] as const;
        }
      }),
    );
    this.variantAttachments.set(Object.fromEntries(entries));
  }

  private async run(field: string, write: () => Promise<void>): Promise<void> {
    this.saving.set(field);
    this.message.set(null);
    try {
      await write();
    } catch (error) {
      this.message.set(
        error instanceof ApiError
          ? describeApiError(error, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.saving.set(null);
    }
  }
}
