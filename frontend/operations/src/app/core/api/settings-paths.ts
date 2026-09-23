import { LocationScope } from './operations-paths';

/**
 * Where the Settings section's endpoints live.
 *
 * `docs/operations-spec/settings.md` §1.1 wants one scope bar and one origin
 * story for every field, backed by ADR 0030's resolver — wave P31 closed
 * that gap with {@link configurationKeys}/{@link configurationResolution}/
 * {@link configurationValues}, over the new `OperationsConfigurationController`
 * and the two capabilities its own Javadoc anticipated
 * (`TENANT_CONFIGURATION_READ`/`WRITE`). Everything else here predates that
 * and is the registries and policies the platform genuinely exposes today,
 * several of them cross-surface: a
 * meaningful share of the tenant's own configuration (brands and their
 * locations' provisioning fields, sales channels, the order acceptance
 * policy, legal entities) was built for control-plane before this wave and
 * still answers only on {@link CONTROL_PLANE}. Cancellation/completion
 * reasons left this list in wave P37: {@link orderOutcomeReasons} now calls
 * `OperationsOrderOutcomeReasonController`, the same wave-53 shape {@link
 * integrationInstallations} used, because this screen was the control-plane
 * path's only caller. Moving any one of the paths still on this list is a
 * breaking change —
 * `OpenApiContractTests` enforces that every published path stays published,
 * and refuses even a baseline refresh that would drop one — so this app calls
 * them where they already live, the same cross-surface shape ADR 0065 already
 * established for {@link merchantBindings} being called from a control-plane
 * screen before this wave, now mirrored in the other direction.
 *
 * `OPERATIONS`-native additions from the wave that added Brand/Location profile
 * editing: {@link brand}, {@link brands}, {@link locations}, {@link location},
 * {@link locationServiceSummary} and the four write endpoints it summarises,
 * plus the pre-existing {@link notificationTemplates} tree.
 *
 * Wave P32 fixed the place write's data-loss bug (see `locationPlace`'s own
 * doc) and added the brand's writable profile ({@link brandRevise}, {@link
 * brandProfileWrite}, both cross-surface for the reason `locationPlace` above
 * already is) and the branch list's batched state read ({@link
 * locationsServiceStates}, operations-native, closing the N+1 `locations`
 * alone used to leave the list without a state column, a state filter, or a
 * close/open row action).
 *
 * Wave 53 added a second, different kind of move for {@link
 * integrationInstallations}, {@link integrationSecrets}, {@link
 * integrationConnectFields} and {@link integrationInstallationRotate}: unlike
 * every `CONTROL_PLANE` path above, `ProviderInstallationController` and
 * `SecretIngressController`'s original control-plane-prefixed mappings had no
 * caller to preserve cross-surface — the control-plane app never called them —
 * so the platform grew an operations-native mirror
 * (`OperationsProviderInstallationController`, `OperationsSecretIngressController`)
 * and this file now points at that instead. The old paths stay published
 * (`OpenApiContractTests` again) but this app no longer reaches across
 * surfaces for them.
 */
const OPERATIONS = '/api/v1/operations';
const CONTROL_PLANE = '/api/v1/control-plane';
const TENANT = '/api/v1/tenants';

export const settingsPaths = {
  // ---------------------------------------------------------- 10.1 Brand profile

  /** `OperationsBrandController.list` — the scope bar's brand picker and 10.1's index. */
  brands(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/brands`;
  },

  /** `OperationsBrandController.get`. */
  brand(scope: LocationScope): string {
    return `${this.brands(scope)}/${enc(scope.brandId)}`;
  },

  /**
   * `TenantControlPlaneController.reviseBrand` (control-plane surface),
   * cross-surface — already built and shipped for the control-plane console
   * before wave P32, which gave this app the operations route and form to
   * reach it. Requires `If-Match`.
   */
  brandRevise(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}`;
  },

  /**
   * `TenantControlPlaneController.updateBrandProfile` (control-plane
   * surface), wave P32 — contact phone, Telegram handle, logo, banner and
   * the 10.12 supported-locale set. Cross-surface for the same reason {@link
   * locationPlace} is: the write belongs beside the brand's other
   * provisioning-era fields, and this app calls it the same way {@link
   * brandRevise} calls its neighbour.
   */
  brandProfileWrite(scope: LocationScope): string {
    return `${this.brandRevise(scope)}/profile`;
  },

  // ---------------------------------------------------------- 10.2 Locations

  /** `OperationsBrandController.locations` — the branch list and the scope bar's location picker. */
  locations(scope: LocationScope): string {
    return `${this.brand(scope)}/locations`;
  },

  /**
   * `OperationsBrandController.locationServiceStates`, wave P32 — every
   * location's own manual-override state, batched. Before this wave the
   * branch list had no state column, no state filter and no close/open row
   * action, because answering any of them one location at a time was the N+1
   * this app's own comment on `locations-page.ts` named as the reason it
   * shipped without them.
   */
  locationsServiceStates(scope: LocationScope): string {
    return `${this.locations(scope)}/service-states`;
  },

  /**
   * `OperationsBrandController.bulkChangeServiceState`, wave 9 — the same
   * batched-read path as {@link locationsServiceStates}, `POST`ed to instead
   * of `GET`: the branch list's bulk close/open bar over several locations
   * at once, reusing the single-location write's own request shape.
   */
  locationsServiceStatesBulk(scope: LocationScope): string {
    return this.locationsServiceStates(scope);
  },

  /**
   * `OperationsBrandController.locationLegalEntities`, wave 9 — every
   * location's own assigned legal entity, batched, for the branch list's INN
   * filter. The same batched-over-a-brand shape {@link locationsServiceStates}
   * already uses for the state column.
   */
  locationsLegalEntities(scope: LocationScope): string {
    return `${this.locations(scope)}/legal-entities`;
  },

  /**
   * `OperationsBrandController.locationChannels`, wave 9 — every location's
   * own active sales-channel codes, batched, for the branch list's channel
   * filter.
   */
  locationsChannels(scope: LocationScope): string {
    return `${this.locations(scope)}/channels`;
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

  /** Row 10.4a: a channel's own social links, whole-set replace like the other matrices. */
  salesChannelSocialLinks(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/social-links`;
  },

  salesChannelArchive(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/archive`;
  },

  salesChannelDeactivate(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/deactivate`;
  },

  salesChannelReactivate(scope: LocationScope, channelId: string): string {
    return `${this.salesChannel(scope, channelId)}/reactivate`;
  },

  // ---------------------------------------------------------------- 10.6 Payment methods

  /** `PaymentMethodController` (operations surface, wave P33) -- the tenant-scoped registry. */
  paymentMethods(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/payment-methods`;
  },

  paymentMethod(scope: LocationScope, methodId: string): string {
    return `${this.paymentMethods(scope)}/${enc(methodId)}`;
  },

  paymentMethodTranslations(scope: LocationScope, methodId: string): string {
    return `${this.paymentMethod(scope, methodId)}/translations`;
  },

  paymentMethodActivate(scope: LocationScope, methodId: string): string {
    return `${this.paymentMethod(scope, methodId)}/activate`;
  },

  paymentMethodDisable(scope: LocationScope, methodId: string): string {
    return `${this.paymentMethod(scope, methodId)}/disable`;
  },

  // ---------------------------------------------------------- 10.7 Fiscalization

  /**
   * `OperationsLegalEntityController` — wave P34 moved this off `CONTROL_PLANE`
   * (`LegalEntityController`), which published the identical six operations and
   * had no caller left to preserve cross-surface, the same move wave 53 made
   * for {@link integrationInstallations}. The control-plane path stays
   * published (`OpenApiContractTests`) but this app no longer reaches across
   * surfaces for it.
   */
  legalEntities(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/legal-entities`;
  },

  legalEntity(scope: LocationScope, entityId: string): string {
    return `${this.legalEntities(scope)}/${enc(entityId)}`;
  },

  legalEntityActivate(scope: LocationScope, entityId: string): string {
    return `${this.legalEntity(scope, entityId)}/activate`;
  },

  /** Wave P34: there was no HTTP surface for this on either controller before. */
  legalEntitySuspend(scope: LocationScope, entityId: string): string {
    return `${this.legalEntity(scope, entityId)}/suspend`;
  },

  /** Wave P34: there was no HTTP surface for this on either controller before. */
  legalEntityArchive(scope: LocationScope, entityId: string): string {
    return `${this.legalEntity(scope, entityId)}/archive`;
  },

  legalEntityAssign(scope: LocationScope, entityId: string): string {
    return `${this.legalEntity(scope, entityId)}/assignments`;
  },

  legalEntityAssignmentHistory(scope: LocationScope): string {
    return `${this.legalEntities(scope)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/assignments`;
  },

  // ------------------------------------------------- 10.7 Fiscal terminals (Tab 2)

  /** `OperationsFiscalTerminalController` — wave P34, new this wave. */
  fiscalTerminals(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/fiscal-terminals`;
  },

  fiscalTerminal(scope: LocationScope, terminalId: string): string {
    return `${this.fiscalTerminals(scope)}/${enc(terminalId)}`;
  },

  fiscalTerminalHealthCheck(scope: LocationScope, terminalId: string): string {
    return `${this.fiscalTerminal(scope, terminalId)}/health-checks`;
  },

  fiscalTerminalSuspend(scope: LocationScope, terminalId: string): string {
    return `${this.fiscalTerminal(scope, terminalId)}/suspend`;
  },

  fiscalTerminalReactivate(scope: LocationScope, terminalId: string): string {
    return `${this.fiscalTerminal(scope, terminalId)}/reactivate`;
  },

  fiscalTerminalRetire(scope: LocationScope, terminalId: string): string {
    return `${this.fiscalTerminal(scope, terminalId)}/retire`;
  },

  // ------------------------------------------------- 10.7 Fiscal coverage (Tab 3)

  /**
   * `CatalogQueryController.fiscalCoverage` — control-plane surface, wave P34.
   * The minimum this wave builds locally in place of `P21`'s not-yet-merged
   * fiscal workbench: a per-brand unclassified count and node list. Cross-surface
   * for the same reason `salesChannels` and `orderOutcomeReasons` above are: the
   * endpoint's controller has no operations-native mirror.
   */
  catalogFiscalCoverage(scope: LocationScope): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/catalog/fiscal-coverage`;
  },

  /** `CatalogAuthoringController.classifyFee` — control-plane surface, pre-existing. */
  catalogFeeFiscalClassification(scope: LocationScope, feeCode: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/catalog/fees/${enc(feeCode)}/fiscal-classification`;
  },

  /** `CatalogAuthoringController.classifyVariant` — control-plane surface, pre-existing. */
  catalogVariantFiscalClassification(scope: LocationScope, variantId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/catalog/variants/${enc(variantId)}/fiscal-classification`;
  },

  /** `CatalogAuthoringController.classifyModifierOption` — control-plane surface, pre-existing. */
  catalogModifierOptionFiscalClassification(scope: LocationScope, optionId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/catalog/modifier-options/${enc(optionId)}/fiscal-classification`;
  },

  // ---------------------------------------------------------- 10.8 Integrations (moved from control-plane)

  /**
   * `OperationsProviderInstallationController` — installations, connect fields, rotation.
   * Wave 53 moved this off `CONTROL_PLANE`: nothing else in this file's own opening doc
   * comment applies to it, because unlike the other cross-surface paths above,
   * `ProviderInstallationController`'s original control-plane-prefixed mapping had no
   * caller of its own to preserve — it stays published only because `OpenApiContractTests`
   * forbids dropping a path, not because anything still depends on that URL. This app now
   * calls the operations-native mirror instead of reaching across surfaces for it.
   */
  integrationInstallations(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/integrations`;
  },

  /**
   * `OperationsSecretIngressController` — the ADR 0065 write-only door, same wave-53 move
   * as {@link integrationInstallations}.
   */
  integrationSecrets(scope: LocationScope): string {
    return `${this.integrationInstallations(scope)}/secrets`;
  },

  integrationConnectFields(scope: LocationScope): string {
    return `${this.integrationInstallations(scope)}/connect-fields`;
  },

  integrationInstallationRotate(scope: LocationScope, installationId: string): string {
    return `${this.integrationInstallations(scope)}/${enc(installationId)}/secret-rotations/value`;
  },

  /**
   * `OperationsProviderInstallationController.registerWebhook` (delegate:
   * `ProviderInstallationController.registerWebhook`) — ADR 0058: (re)registers
   * a `TELEGRAM_BOT_API` installation's Telegram webhook. Refused server-side
   * for any other provider type or a non-`ACTIVE` installation; re-running
   * rotates the webhook secret, the supported recovery from a mismatched or
   * leaked one.
   */
  integrationInstallationWebhookRegistration(scope: LocationScope, installationId: string): string {
    return `${this.integrationInstallations(scope)}/${enc(installationId)}/webhook-registration`;
  },

  /**
   * `OperationsProviderInstallationController.bind` (delegate:
   * `ProviderInstallationController.bind`) — ADR 0026's binding step, wired
   * into the connect drawer for the first time in wave 66. Before this wave
   * nothing in this app ever called it: a tenant could connect a provider and
   * had no screen at all that would bind it to a brand or location afterward.
   */
  integrationInstallationBindings(scope: LocationScope, installationId: string): string {
    return `${this.integrationInstallations(scope)}/${enc(installationId)}/bindings`;
  },

  /**
   * `OperationsProviderInstallationController.effectiveBindings` — gap-map
   * row 10.8a's per-branch install model, surfaced: for every location of a
   * brand, which installation actually handles each capability there,
   * whether bound to the branch itself or inherited from the brand's own
   * default. `brandId` travels as a query parameter (`{ params: { brandId } }`
   * on the call), the same shape {@link legalEntityActivate}'s own
   * `expectedVersion` uses, rather than a second path segment.
   */
  integrationBranchesEffectiveBindings(scope: LocationScope): string {
    return `${this.integrationInstallations(scope)}/branches/effective-bindings`;
  },

  /**
   * `OperationsProviderInstallationController.activateBinding` — ADR 0106,
   * gap-map row 10.8a. The controller's own doc says a binding is created
   * SUSPENDED (see {@link integrationInstallationBindings}'s POST); before
   * this wave nothing in this app ever called the one endpoint that brings
   * it live, so a tenant could connect and bind a provider and never
   * activate what they had just created.
   */
  integrationInstallationBindingActivate(
    scope: LocationScope,
    installationId: string,
    bindingId: string,
  ): string {
    return `${this.integrationInstallationBindings(scope, installationId)}/${enc(bindingId)}/activate`;
  },

  /** `OperationsProviderInstallationController.suspendBinding` — the rollback path beside it. */
  integrationInstallationBindingSuspend(
    scope: LocationScope,
    installationId: string,
    bindingId: string,
  ): string {
    return `${this.integrationInstallationBindings(scope, installationId)}/${enc(bindingId)}/suspend`;
  },

  /**
   * `OperationsProviderInstallationController.reconcileCapabilities` — ADR
   * 0106, gap-map row 10.8a and row X.14: records a fresh preflight (the
   * secret resolves, the wired adapter declares each capability) and, since
   * this wave, stamps `secretLastUsedAt` on success.
   */
  integrationInstallationCapabilityReconciliation(
    scope: LocationScope,
    installationId: string,
  ): string {
    return `${this.integrationInstallations(scope)}/${enc(installationId)}/capability-reconciliation`;
  },

  /**
   * `OperationsProviderInstallationController.settings`/`updateSettings` —
   * ADR 0106, gap-map row 10.8a. Today's one field is Clopos's own
   * order-acceptance toggle (Q7); refused for any other provider type, so
   * the console only renders this for a `clopos` installation.
   */
  integrationInstallationSettings(scope: LocationScope, installationId: string): string {
    return `${this.integrationInstallations(scope)}/${enc(installationId)}/settings`;
  },

  /**
   * `MarketplaceOperationsController.liveness` — ADR 0106, gap-map row
   * 10.8c: the locations-by-bindings liveness matrix, built before this wave
   * and never rendered anywhere in this app.
   */
  marketplaceLiveness(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/marketplace/liveness`;
  },

  /**
   * `OperationsIntegrationFailureController` — ADR 0106, gap-map row 10.8c: a
   * merchant's own error taxonomy and inbox replay, tenant-checked rather
   * than trusting an optional filter the platform-wide surface accepts.
   */
  integrationFailureTaxonomy(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/integrations/failures/taxonomy`;
  },

  integrationFailureInbox(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/integrations/failures/inbox`;
  },

  integrationFailureReplay(scope: LocationScope, consumerName: string, eventId: string): string {
    return `${this.integrationFailureInbox(scope)}/${enc(consumerName)}/${enc(eventId)}/replay`;
  },

  /**
   * `PartnerApiClientController` — ADR 0106, gap-map row 10.8d: issue, list,
   * rotate and revoke a `partner.api_clients` credential over a real
   * Keycloak `client_credentials` client. Only meaningful for a `MARKETPLACE`
   * installation (the controller itself refuses any other category).
   */
  partnerApiClients(scope: LocationScope, installationId: string): string {
    return `${this.integrationInstallations(scope)}/${enc(installationId)}/partner-clients`;
  },

  partnerApiClientRotate(scope: LocationScope, installationId: string, clientId: string): string {
    return `${this.partnerApiClients(scope, installationId)}/${enc(clientId)}/secret-rotations`;
  },

  partnerApiClientRevoke(scope: LocationScope, installationId: string, clientId: string): string {
    return `${this.partnerApiClients(scope, installationId)}/${enc(clientId)}`;
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

  /** `NotificationTemplateController.testSend` — wave P36, SMS only today. */
  notificationTemplateTestSend(
    scope: LocationScope,
    templateId: string,
    versionNumber: number,
  ): string {
    return `${this.notificationTemplateVersion(scope, templateId, versionNumber)}/test-send`;
  },

  /**
   * `NotificationTemplateController.variableCatalogue` — wave P36, gap map
   * `X.27`: fixes the `variablesSchema: {}` defect that made the editor
   * unable to author a variable-bearing template at all.
   */
  notificationVariableCatalogue(scope: LocationScope): string {
    return `${this.notificationTemplates(scope)}/variable-catalogue`;
  },

  // ------------------------------------------- 10.9 Notifications, Tab 2 routing

  /**
   * `TelegramRoutingController` — wave P36, gap map `10.9b`, over ADR 0058's
   * bindings. Same `/api/v1/tenants/**` surface as {@link notificationTemplates},
   * not the `OPERATIONS`-prefixed one — this is a brand-new controller with
   * no cross-surface caller to preserve, so it lands directly where its
   * sibling notification-template endpoints already live.
   */
  notificationRouting(scope: LocationScope): string {
    return `${TENANT}/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/notification-routing`;
  },

  notificationRoutingEventClasses(scope: LocationScope): string {
    return `${this.notificationRouting(scope)}/event-classes`;
  },

  notificationRoutingBindings(scope: LocationScope): string {
    return `${this.notificationRouting(scope)}/bindings`;
  },

  notificationRoutingSubscription(scope: LocationScope, bindingId: string): string {
    return `${this.notificationRoutingBindings(scope)}/${enc(bindingId)}/subscriptions`;
  },

  notificationRoutingTopic(scope: LocationScope, bindingId: string): string {
    return `${this.notificationRoutingBindings(scope)}/${enc(bindingId)}/topic`;
  },

  notificationRoutingUnbind(scope: LocationScope, bindingId: string): string {
    return `${this.notificationRoutingBindings(scope)}/${enc(bindingId)}/unbind`;
  },

  // ---------------------------------------------------------- 10.10 Reference data

  /**
   * `OperationsOrderOutcomeReasonController` — moved off `CONTROL_PLANE` this
   * wave (P37), the same wave-53 shape {@link integrationInstallations} used:
   * `OrderOutcomeReasonController`'s original control-plane-prefixed mapping
   * had no caller of its own to preserve cross-surface (this screen was its
   * only caller, and it was reaching across surfaces to reach it), so this
   * app now calls the operations-native mirror instead.
   */
  orderOutcomeReasons(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/order-outcome-reasons`;
  },

  orderOutcomeReasonCategories(scope: LocationScope): string {
    return `${this.orderOutcomeReasons(scope)}/categories`;
  },

  orderOutcomeReason(scope: LocationScope, reasonId: string): string {
    return `${this.orderOutcomeReasons(scope)}/${enc(reasonId)}`;
  },

  // ---------------------------------------------------------- 10.10d Branch tags

  /** `BranchTagController` — the tenant-wide registry and assignment read. */
  branchTags(scope: LocationScope): string {
    return `${OPERATIONS}/tenants/${enc(scope.tenantId)}/branch-tags`;
  },

  branchTagAssignments(scope: LocationScope): string {
    return `${this.branchTags(scope)}/assignments`;
  },

  branchTagArchive(scope: LocationScope, tagId: string): string {
    return `${this.branchTags(scope)}/${enc(tagId)}/archive`;
  },

  /** One branch's own tags — set as a whole, per `BranchTagController.setTagsOfLocation`. */
  locationBranchTags(scope: LocationScope): string {
    return `${this.location(scope)}/branch-tags`;
  },

  // ---------------------------------------------------------- 10.12 Terms of service

  /**
   * `OperationsTermsController` (ADR 0067) — a bare `tenantId`/`brandId` pair,
   * not a `LocationScope`: `TERMS_READ`/`TERMS_MANAGE` are granted only to
   * `tenant-owner`, a `TENANT`-scoped bundle with no `BRAND`- or
   * `LOCATION`-scoped grant row, so this screen resolves its tenant from
   * `CurrentTenant` and its brand from its own picker rather than
   * `CurrentLocation` — see `terms-page.ts`'s own doc for why.
   */
  termsDocuments(tenantId: string, brandId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/brands/${enc(brandId)}/terms-documents`;
  },

  /** The version in force right now, or `published: false` if the brand has never published. */
  termsDocumentCurrent(tenantId: string, brandId: string): string {
    return `${this.termsDocuments(tenantId, brandId)}/current`;
  },

  /** One historical version, read-only, for the publish history's preview. */
  termsDocumentVersion(tenantId: string, brandId: string, version: number): string {
    return `${this.termsDocuments(tenantId, brandId)}/${version}`;
  },

  // ---------------------------------------------------------- 1.1/1.2 Scope bar + InheritedField

  /**
   * `OperationsConfigurationController.keys` — every ADR 0030 key this
   * tenant may see, filtered to `tenantVisible()`. The `/` find-a-setting
   * registry and `q-inherited-field`'s own list of settable scopes.
   */
  configurationKeys(tenantId: string): string {
    return `${OPERATIONS}/tenants/${enc(tenantId)}/configuration/keys`;
  },

  /** `OperationsConfigurationController.resolution` — one key, one scope, and why. */
  configurationResolution(tenantId: string, code: string): string {
    return `${this.configurationKeys(tenantId)}/${enc(code)}/resolution`;
  },

  /** `OperationsConfigurationController.setValue`. */
  configurationValues(tenantId: string, code: string): string {
    return `${this.configurationKeys(tenantId)}/${enc(code)}/values`;
  },

  // ---------------------------------------------------------- 10.0 Readiness

  /**
   * `OnboardingController.current` — cross-surface like the rest of the
   * `{@link CONTROL_PLANE}`-prefixed paths above: `TENANT_READ` already
   * covers it (`ConfigurationController`'s own surface split does not apply
   * to onboarding, which has never had an operations-native mirror to
   * prefer), and this app is where the settings.md §10.0 readiness panel
   * lives.
   */
  onboardingCurrentRun(tenantId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(tenantId)}/onboarding-runs/current`;
  },

  /** `OnboardingController.validate` — the dry run the readiness panel reshapes. */
  onboardingValidate(tenantId: string, runId: string): string {
    return `${CONTROL_PLANE}/tenants/${enc(tenantId)}/onboarding-runs/${enc(runId)}/validate`;
  },

  // ---------------------------------------------------------- 10.11 Data & privacy (ADR 0109)

  /**
   * `CustomerController.tenantErasureRequests` — the tenant-wide DSAR
   * worklist. Reuses `CustomerErasureService`'s existing per-account
   * lifecycle (`V0178`); this is the only new read the worklist needed.
   *
   * `CustomerController`'s class-level `@RequestMapping` is `/customers`, so
   * the real route is `/api/v1/tenants/{tenantId}/customers/erasure-requests`
   * — every other customer-nested path in this frontend already includes
   * that segment (see `operationsPaths.customers`); this one previously
   * didn't, and 404'd on every load.
   */
  erasureRequests(tenantId: string): string {
    return `${TENANT}/${enc(tenantId)}/customers/erasure-requests`;
  },

  /** `ConsentTypeController.list` — the tenant's own consent-purpose registry. */
  consentTypes(tenantId: string): string {
    return `${TENANT}/${enc(tenantId)}/consent-types`;
  },
} as const;

function enc(value: string): string {
  return encodeURIComponent(value);
}
