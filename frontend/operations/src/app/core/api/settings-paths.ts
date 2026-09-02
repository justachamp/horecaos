import { LocationScope } from './operations-paths';

/**
 * Where the Settings section's endpoints live.
 *
 * `docs/operations-spec/settings.md` §1.1 wants one scope bar and one origin
 * story for every field, backed by ADR 0030's resolver — but ADR 0030's own
 * checklist still lists "control-plane read and write APIs, and the
 * resolution trace endpoint" as open, so nothing in this file resolves an
 * inherited value. What is here instead is the registries and policies the
 * platform genuinely exposes today, several of them cross-surface: a
 * meaningful share of the tenant's own configuration (brands and their
 * locations' provisioning fields, sales channels, the order acceptance
 * policy, legal entities, cancellation/completion reasons) was built for
 * control-plane before this wave and still answers only on
 * {@link CONTROL_PLANE}. Moving any one of those paths is a breaking change —
 * `OpenApiContractTests` enforces that every published path stays published,
 * and refuses even a baseline refresh that would drop one — so this app calls
 * them where they already live, the same cross-surface shape ADR 0065 already
 * established for {@link merchantBindings} being called from a control-plane
 * screen before this wave, now mirrored in the other direction.
 *
 * `OPERATIONS`-native additions from this wave: {@link brand}, {@link
 * brands}, {@link locations}, {@link location}, {@link
 * locationServiceSummary} and the four write endpoints it summarises, plus
 * the pre-existing {@link notificationTemplates} tree.
 */
const OPERATIONS = '/api/v1/operations';
const CONTROL_PLANE = '/api/v1/control-plane';

export const settingsPaths = {
  // ---------------------------------------------------------- 10.1 Brand profile

  /** `OperationsBrandController.list` — the scope bar's brand picker and 10.1's index. */
  brands(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/brands`;
  },

  /** `OperationsBrandController.get` — read-only until a profile-write endpoint exists. */
  brand(scope: LocationScope): string {
    return `${this.brands(scope)}/${enc(scope.brandId)}`;
  },

  // ---------------------------------------------------------- 10.2 Locations

  /** `OperationsBrandController.locations` — the branch list and the scope bar's location picker. */
  locations(scope: LocationScope): string {
    return `${this.brand(scope)}/locations`;
  },

  /** `LocationServiceOperationsController.profile` — one branch's own fields. */
  location(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}`;
  },

  /**
   * `LocationServiceOperationsController.serviceSummary` — manual override,
   * every bound schedule's full grid, preparation bands, live capacity. Reads
   * what the four write endpoints below already persist.
   */
  locationServiceSummary(scope: LocationScope): string {
    return `${this.location(scope)}/service-summary`;
  },

  locationServiceState(scope: LocationScope): string {
    return `${this.location(scope)}/service-state`;
  },

  locationCapacity(scope: LocationScope): string {
    return `${this.location(scope)}/capacity`;
  },

  locationServiceBindings(scope: LocationScope): string {
    return `${this.location(scope)}/service-bindings`;
  },

  locationPreparationBands(scope: LocationScope): string {
    return `${this.location(scope)}/preparation-bands`;
  },

  /**
   * `TenantControlPlaneController.describeLocation` (control-plane surface) —
   * address, telephone and map pin. The one write this wave reuses from that
   * controller rather than adding new: everything else on it is provisioning
   * (create/activate), out of scope for a self-service Settings screen.
   */
  locationPlace(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/place`;
  },

  /** `ServiceScheduleController` (control-plane surface) — the Hours tab's editor. */
  brandServiceSchedules(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/service-schedules`;
  },

  scheduleRules(scope: LocationScope, scheduleId: string): string {
    return `${this.brandServiceSchedules(scope)}/${enc(scheduleId)}/rules`;
  },

  scheduleExceptions(scope: LocationScope, scheduleId: string): string {
    return `${this.brandServiceSchedules(scope)}/${enc(scheduleId)}/exceptions`;
  },

  // ---------------------------------------------------------- 10.3 Order policy

  /** `OrderAcceptancePolicyController` (control-plane surface). */
  orderAcceptancePolicy(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/order-acceptance-policy`;
  },

  // ---------------------------------------------------------- 10.4 Sales channels

  /** `SalesChannelController` (control-plane surface). */
  salesChannels(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/sales-channels`;
  },

  salesChannel(scope: LocationScope, channelId: string): string {
    return `${this.salesChannels(scope)}/${enc(channelId)}`;
  },

  salesChannelMatrices(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/matrices`;
  },

  salesChannelPaymentMethods(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/payment-methods`;
  },

  salesChannelFulfillmentModes(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/fulfillment-modes`;
  },

  salesChannelLocations(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/locations`;
  },

  salesChannelArchive(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/archive`;
  },

  // ---------------------------------------------------------- 10.7 Fiscalization

  /** `LegalEntityController` (control-plane surface). */
  legalEntities(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/legal-entities`;
  },

  legalEntity(scope: LocationScope, entityId: string): string {
    return `${this.legalEntities(scope)}/${enc(entityId)}`;
  },

  legalEntityActivate(scope: LocationScope, entityId: string): string {
    return `${this.legalEntity(scope, entityId)}/activate`;
  },

  legalEntityAssign(scope: LocationScope, entityId: string): string {
    return `${this.legalEntity(scope, entityId)}/assignments`;
  },

  legalEntityAssignmentHistory(scope: LocationScope): string {
    return `${this.legalEntities(scope)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/assignments`;
  },

  // ---------------------------------------------------------- 10.8 Integrations (moved from control-plane)

  /** `ProviderInstallationController` (control-plane surface) — installations, connect fields, rotation. */
  integrationInstallations(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/integrations`;
  },

  /** `SecretIngressController` — the ADR 0065 write-only door. Same controller family, same surface. */
  integrationSecrets(scope: LocationScope): string {
    return `${this.integrationInstallations(scope)}/secrets`;
  },

  integrationConnectFields(scope: LocationScope): string {
    return `${this.integrationInstallations(scope)}/connect-fields`;
  },

  integrationInstallationRotate(scope: LocationScope, installationId: string): string {
    return `${this.integrationInstallations(scope)}/${enc(installationId)}/secret-rotations/value`;
  },

  /** `MerchantBindingController` — already operations surface (ADR 0065's one resolved tension). */
  merchantBindings(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/merchant-bindings`;
  },

  merchantBinding(scope: LocationScope, bindingId: string): string {
    return `${this.merchantBindings(scope)}/${enc(bindingId)}`;
  },

  merchantBindingRotate(scope: LocationScope, bindingId: string): string {
    return `${this.merchantBinding(scope, bindingId)}/secret-rotations`;
  },

  merchantBindingArchive(scope: LocationScope, bindingId: string): string {
    return `${this.merchantBinding(scope, bindingId)}/archive`;
  },

  // ---------------------------------------------------------- 10.9 Notifications

  /** `NotificationTemplateController` — already operations surface (`/api/v1/tenants/**`). */
  notificationTemplates(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/notification-templates`;
  },

  notificationTemplate(scope: LocationScope, templateId: string): string {
    return `${this.notificationTemplates(scope)}/${enc(templateId)}`;
  },

  notificationTemplateVersions(scope: LocationScope, templateId: string): string {
    return `${this.notificationTemplate(scope, templateId)}/versions`;
  },

  notificationTemplateVersion(
    scope: LocationScope,
    templateId: string,
    versionNumber: number,
  ): string {
    return `${this.notificationTemplateVersions(scope, templateId)}/${versionNumber}`;
  },

  notificationTemplateActivate(
    scope: LocationScope,
    templateId: string,
    versionNumber: number,
  ): string {
    return `${this.notificationTemplateVersion(scope, templateId, versionNumber)}/activate`;
  },

  // ---------------------------------------------------------- 10.10 Reference data

  /** `OrderOutcomeReasonController` (control-plane surface). */
  orderOutcomeReasons(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/order-outcome-reasons`;
  },

  orderOutcomeReasonCategories(scope: LocationScope): string {
    return `${this.orderOutcomeReasons(scope)}/categories`;
  },

  orderOutcomeReason(scope: LocationScope, reasonId: string): string {
    return `${this.orderOutcomeReasons(scope)}/${enc(reasonId)}`;
  },
} as const;

function enc(value: string): string {
  return encodeURIComponent(value);
}
