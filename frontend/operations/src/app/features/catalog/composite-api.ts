import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import { CatalogStatus } from './catalog-domain';

/**
 * ADR 0136 — composite products. Types mirror `CompositeProductAuthoringController`'s
 * request and response records, hand-copied from the Java source like every
 * other catalog client here.
 */

/** `CompositeProducts.Visibility`: a choice the customer makes, or a charge the server applies. */
export type AttachmentVisibility = 'VISIBLE' | 'HIDDEN_AUTO_SELECT';

/** `FulfillmentMode` as `location_offerings` states it — the vocabulary a hidden group's modes name. */
export type AttachmentFulfillmentMode = 'DELIVERY' | 'PICKUP' | 'DINE_IN';

export const ATTACHMENT_FULFILLMENT_MODES: readonly AttachmentFulfillmentMode[] = [
  'DELIVERY',
  'PICKUP',
  'DINE_IN',
];

/** `ComboComponentResponse`. */
export interface ComboComponentView {
  readonly componentId: string;
  readonly comboGroupId: string;
  readonly componentVariantId: string;
  /** Units one pick of this component puts on the order; its price is per unit. */
  readonly defaultQuantity: number;
  readonly sortOrder: number;
  readonly status: CatalogStatus;
  readonly version: number;
}

/** `ComboGroupResponse`. */
export interface ComboGroupView {
  readonly comboGroupId: string;
  readonly containerVariantId: string;
  readonly code: string;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameComponentMultipleTimes: boolean;
  readonly sortOrder: number;
  readonly status: CatalogStatus;
  readonly version: number;
  readonly components: readonly ComboComponentView[];
  /** The heading in every locale it has one in, keyed by the catalog's plain `ru`/`uz`/`en` tags. */
  readonly names?: Readonly<Record<string, string>>;
}

/** `CreateComboGroupRequest`. */
export interface CreateComboGroupRequest {
  readonly containerVariantId: string;
  readonly code: string;
  readonly name: string;
  readonly locale: string;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameComponentMultipleTimes: boolean;
  readonly sortOrder?: number;
}

/** `UpdateComboGroupRequest` — the whole mutable set, replaced. */
export interface UpdateComboGroupRequest {
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameComponentMultipleTimes: boolean;
  readonly sortOrder: number;
  readonly status: CatalogStatus;
}

/** `AddComboComponentRequest`. */
export interface AddComboComponentRequest {
  readonly componentVariantId: string;
  readonly defaultQuantity?: number;
  readonly sortOrder?: number;
}

/** `UpdateComboComponentRequest`. */
export interface UpdateComboComponentRequest {
  readonly defaultQuantity: number;
  readonly sortOrder: number;
  readonly status: CatalogStatus;
}

/** `AttachmentPolicyRequest`. `null` modes means every mode; a `null` override falls back to the shared group's own value. */
export interface AttachmentPolicyRequest {
  readonly visibility: AttachmentVisibility;
  readonly applicableFulfillmentModes: readonly AttachmentFulfillmentMode[] | null;
  readonly requiredOverride: boolean | null;
  readonly minimumSelectionsOverride: number | null;
  readonly maximumSelectionsOverride: number | null;
}

/** `ModifierAttachmentResponse` — one group attached to a product or a variant, and how. */
export interface ModifierAttachmentView {
  readonly ownerId: string;
  readonly ownerType: 'PRODUCT' | 'VARIANT';
  readonly modifierGroupId: string;
  readonly sortOrder: number;
  readonly visibility: AttachmentVisibility;
  readonly applicableFulfillmentModes?: readonly AttachmentFulfillmentMode[] | null;
  readonly requiredOverride?: boolean | null;
  readonly minimumSelectionsOverride?: number | null;
  readonly maximumSelectionsOverride?: number | null;
  readonly version: number;
}

/**
 * `GET/POST/PUT .../catalog/(combo-groups|combo-components|variants/.../(combo|modifier)-groups|products/.../overrides)`
 * — `CompositeProductAuthoringController`. Same `control-plane` prefix as
 * `CatalogApi`; see `catalog-paths.ts`. Every update carries the version the
 * caller read as `If-Match`, and every mutation an `Idempotency-Key`.
 */
@Injectable({ providedIn: 'root' })
export class CompositeApi {
  private readonly api = inject(ApiClient);

  /** Every combo group a container variant offers, with its components. Empty for a variant that is no container. */
  comboGroupsOf(scope: BrandScope, variantId: string): Observable<readonly ComboGroupView[]> {
    return this.api
      .get<readonly ComboGroupView[]>(catalogPaths.variantComboGroups(scope, variantId))
      .pipe(map((result) => result.value));
  }

  createComboGroup(
    scope: BrandScope,
    request: CreateComboGroupRequest,
  ): Observable<ComboGroupView> {
    return this.api.post<CreateComboGroupRequest, ComboGroupView>(
      catalogPaths.comboGroups(scope),
      command(request),
    );
  }

  /** Replaces the group's range, repeat rule, order and status; answers the group as it now stands, components included. */
  updateComboGroup(
    scope: BrandScope,
    comboGroupId: string,
    expectedVersion: number,
    request: UpdateComboGroupRequest,
  ): Observable<ComboGroupView> {
    return this.api.put<UpdateComboGroupRequest, ComboGroupView>(
      catalogPaths.comboGroup(scope, comboGroupId),
      command(request),
      { expectedVersion },
    );
  }

  addComponent(
    scope: BrandScope,
    comboGroupId: string,
    request: AddComboComponentRequest,
  ): Observable<ComboComponentView> {
    return this.api.post<AddComboComponentRequest, ComboComponentView>(
      catalogPaths.comboGroupComponents(scope, comboGroupId),
      command(request),
    );
  }

  updateComponent(
    scope: BrandScope,
    componentId: string,
    expectedVersion: number,
    request: UpdateComboComponentRequest,
  ): Observable<ComboComponentView> {
    return this.api.put<UpdateComboComponentRequest, ComboComponentView>(
      catalogPaths.comboComponent(scope, componentId),
      command(request),
      { expectedVersion },
    );
  }

  /** The groups attached to a variant itself — the nested level of ADR 0136. */
  variantAttachments(
    scope: BrandScope,
    variantId: string,
  ): Observable<readonly ModifierAttachmentView[]> {
    return this.api
      .get<readonly ModifierAttachmentView[]>(catalogPaths.variantModifierGroups(scope, variantId))
      .pipe(map((result) => result.value));
  }

  /** Attaches a group to a variant, or re-sorts it if it is already there. */
  attachToVariant(
    scope: BrandScope,
    variantId: string,
    groupId: string,
    sortOrder: number,
  ): Observable<ModifierAttachmentView> {
    return this.api.put<{ readonly sortOrder: number }, ModifierAttachmentView>(
      catalogPaths.variantModifierGroup(scope, variantId, groupId),
      command({ sortOrder }),
    );
  }

  /**
   * How a product offers an attached group (visibility, the fulfilment modes a
   * hidden group applies to, and its own required/min/max). The shared group is
   * never edited from here — another product attaching it is unaffected.
   */
  setProductAttachmentPolicy(
    scope: BrandScope,
    productId: string,
    groupId: string,
    expectedVersion: number,
    request: AttachmentPolicyRequest,
  ): Observable<ModifierAttachmentView> {
    return this.api.put<AttachmentPolicyRequest, ModifierAttachmentView>(
      catalogPaths.productModifierGroupOverrides(scope, productId, groupId),
      command(request),
      { expectedVersion },
    );
  }
}
