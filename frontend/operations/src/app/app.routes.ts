import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';
import { NAV_ITEMS } from './shell/navigation';

/**
 * The routing table.
 *
 * Today, Orders and Inbox plus Catalog's, Kitchen's and Delivery's own small
 * trees, and Couriers as a single page, and the rest are honest empty ones.
 * The empty ones exist because every rail entry must navigate somewhere — a
 * rail item that does nothing when clicked is a bug report — and because a
 * placeholder that names its specification is more useful to the next
 * developer than a screen half-built against it. The same principle applies
 * one level down inside a built section's own children: `catalog/import` and
 * `delivery/dispatch-rules` are each a not-built placeholder for exactly this
 * reason (see their own comments below), even though the section around them
 * is "built".
 *
 * Everything except `/login` is behind {@link authGuard}. The guard proves
 * somebody is signed in; it never decides what they may do. Authorization is the
 * server's (ADR 0025).
 */
export const routes: Routes = [
  {
    path: 'login',
    // Outside the shell and outside the guard (ADR 0062): guarding the page
    // that signs somebody in would refuse to render it to exactly the
    // visitor it exists for.
    loadComponent: () => import('./features/auth/sign-in-page').then((m) => m.SignInPage),
  },
  {
    path: '',
    loadComponent: () => import('./shell/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'today' },
      {
        path: 'today',
        loadComponent: () => import('./features/today/today-page').then((m) => m.TodayPage),
      },
      // IA 0.2 (My work): an honest not-built page, linked from 0.1's own
      // toolbar — see `today-page.ts`'s doc for why every field it would
      // show depends on data (order attribution, a staff person record)
      // this build does not have.
      {
        path: 'today/my-work',
        loadComponent: () =>
          import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
        data: {
          spec: 'frontend-information-architecture.md §0.2 (My work) — no attribution or staff-identity data',
        },
      },
      {
        path: 'orders',
        loadComponent: () => import('./features/orders/orders-page').then((m) => m.OrdersPage),
        children: [
          // Taking an order docks beside the queue like any other detail, which
          // is the point: an operator building a basket must still see 4819 go
          // late. Declared before `:orderId` so that "new" is a destination and
          // not an order whose id happens to be the word new.
          {
            path: 'new',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'operations-spec/orders.md §5 (New order)' },
          },
          // IA 1.4: carts started and never converted. Declared before
          // `:orderId` for the same reason `new` is — "drafts" must be a
          // destination, not an order whose id happens to be the word drafts.
          {
            path: 'drafts',
            loadComponent: () => import('./features/orders/drafts-page').then((m) => m.DraftsPage),
          },
          // IA 1.5 — Reservations (ADR 0047, wave 43): orders.md §7's own
          // prose says no floor-plan entity exists; it was built by a
          // previous wave and reachable by nothing until this page. Declared
          // before `:orderId` for the same reason `new` and `drafts` are.
          {
            path: 'reservations',
            loadComponent: () =>
              import('./features/orders/reservations-page').then((m) => m.ReservationsPage),
          },
          // The detail is a *child* of the board, not a sibling. That is what
          // makes it dock beside the queue instead of replacing it, and what
          // keeps it deep-linkable and back-button-correct at the same time.
          {
            path: ':orderId',
            loadComponent: () =>
              import('./features/orders/order-detail-pane').then((m) => m.OrderDetailPane),
          },
        ],
      },
      {
        // The Customers section (frontend-information-architecture.md §5):
        // `CustomersShell` (wave 39) is a small tab strip — the same shape
        // `staff-shell.ts` uses — over 5.1/5.2 (unchanged below, still
        // docking `:accountId` the same way `orders`/`locations` do) plus
        // this wave's two tier-2 additions, 5.3 Segments and 5.4 Reviews.
        // `segments` and `reviews` are declared *before* the empty-path
        // `CustomersPage` child for the same reason `staff-shell`'s `roles`
        // is declared before its own empty-path child: Angular tries this
        // array in order, and `CustomersPage`'s own `:accountId` child would
        // otherwise swallow either literal segment as an account id.
        path: 'customers',
        loadComponent: () =>
          import('./features/customers/customers-shell').then((m) => m.CustomersShell),
        children: [
          {
            path: 'segments',
            loadComponent: () =>
              import('./features/customers/segments/segments-page').then((m) => m.SegmentsPage),
          },
          // 5.4 Reviews: statistics.md's own skip table and the IA's §5.4 row
          // both point at the same gap — no review/feedback entity, no
          // owning ADR (marketing.md's Отзывы analytics is a chart over data
          // that does not exist either). Honest not-built rather than a
          // service-recovery kanban with nothing behind it.
          {
            path: 'reviews',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: {
              spec:
                'frontend-information-architecture.md §5.4 (Reviews) — no review/feedback entity, ' +
                'no owning ADR',
            },
          },
          {
            path: '',
            loadComponent: () =>
              import('./features/customers/customers-page').then((m) => m.CustomersPage),
            children: [
              // Bulk CSV import with retained provenance (§5.1) is honestly
              // not built: the backend has no generic import pipeline, only
              // the SendPulse-specific one (ADR 0059 stage 3), which is a
              // different source and a different shape entirely. Declared
              // before `:accountId` for the same reason `orders/new` is
              // declared before `:orderId` — "import" must be a
              // destination, not an account id.
              {
                path: 'import',
                loadComponent: () =>
                  import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
                data: { spec: 'frontend-information-architecture.md §5.1 (bulk CSV import)' },
              },
              {
                path: ':accountId',
                loadComponent: () =>
                  import('./features/customers/customer-detail-pane').then(
                    (m) => m.CustomerDetailPane,
                  ),
              },
            ],
          },
        ],
      },
      {
        path: 'inbox',
        loadComponent: () => import('./features/inbox/inbox-page').then((m) => m.InboxPage),
        children: [
          // Docks beside the list rather than replacing it — same reasoning
          // as the order board's own detail child route.
          {
            path: ':conversationId',
            loadComponent: () =>
              import('./features/inbox/inbox-detail-pane').then((m) => m.InboxDetailPane),
          },
        ],
      },
      {
        // The Settings section (wave 26, ADR 0065): its own shell nests a
        // second rail (§Navigation groups) beside whichever P-tier screen is
        // routed under here. Two of its own rail entries — channel-setup and
        // payment-methods — still resolve to the shared NotBuiltPage below,
        // the same "omit, do not disable" rule the top-level rail already
        // follows: a screen with a real backend gap gets an honest page that
        // names the spec section, not a greyed-out link.
        path: 'settings',
        loadComponent: () =>
          import('./features/settings/settings-shell').then((m) => m.SettingsShell),
        children: [
          {
            path: '',
            pathMatch: 'full',
            loadComponent: () =>
              import('./features/settings/settings-home/settings-home-page').then(
                (m) => m.SettingsHomePage,
              ),
          },
          {
            path: 'brand',
            loadComponent: () =>
              import('./features/settings/brand-profile/brand-profile-page').then(
                (m) => m.BrandProfilePage,
              ),
          },
          {
            path: 'locations',
            loadComponent: () =>
              import('./features/settings/locations/locations-page').then((m) => m.LocationsPage),
            children: [
              {
                path: ':locationId',
                loadComponent: () =>
                  import('./features/settings/locations/location-detail-pane').then(
                    (m) => m.LocationDetailPane,
                  ),
              },
            ],
          },
          {
            path: 'sales-channels',
            loadComponent: () =>
              import('./features/settings/sales-channels/sales-channels-page').then(
                (m) => m.SalesChannelsPage,
              ),
          },
          {
            path: 'channel-setup',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'operations-spec/settings.md §10.5 (Channel setup)' },
          },
          {
            path: 'order-policy',
            loadComponent: () =>
              import('./features/settings/order-policy/order-policy-page').then(
                (m) => m.OrderPolicyPage,
              ),
          },
          {
            path: 'payment-methods',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'operations-spec/settings.md §10.6 (Payment methods)' },
          },
          {
            path: 'fiscalization',
            loadComponent: () =>
              import('./features/settings/fiscalization/fiscalization-page').then(
                (m) => m.FiscalizationPage,
              ),
          },
          {
            path: 'notifications',
            loadComponent: () =>
              import('./features/settings/notifications/notifications-page').then(
                (m) => m.NotificationsPage,
              ),
          },
          {
            path: 'integrations',
            loadComponent: () =>
              import('./features/settings/integrations/integrations-page').then(
                (m) => m.IntegrationsPage,
              ),
          },
          {
            path: 'reference-data',
            loadComponent: () =>
              import('./features/settings/reference-data/reference-data-page').then(
                (m) => m.ReferenceDataPage,
              ),
          },
          {
            // 10.11 Data & privacy (wave 45): tenant-scoped, unlike every
            // sibling above — see `data-privacy-page.ts`'s own doc for why.
            path: 'data-privacy',
            loadComponent: () =>
              import('./features/settings/data-privacy/data-privacy-page').then(
                (m) => m.DataPrivacyPage,
              ),
          },
        ],
      },
      {
        // Staff (operations IA §9.1, staff-and-access.md): a shell — like
        // `settings-shell.ts` — for two screens that must not dock beside
        // each other, unlike a person's own Карточка, which docks beside the
        // Люди list the same way `orders/:orderId` docks beside the queue.
        //
        // `roles` is declared before the empty (Люди) child so it is tried
        // first: Angular's default 'prefix' match on `path: ''` always
        // succeeds and then hands the remaining segment down to Люди's own
        // `:subjectId` child, which would otherwise swallow the literal
        // segment "roles" as if it were a principal subject.
        path: 'staff',
        loadComponent: () => import('./features/staff/staff-shell').then((m) => m.StaffShell),
        children: [
          {
            path: 'roles',
            loadComponent: () =>
              import('./features/staff/staff-roles-page').then((m) => m.StaffRolesPage),
          },
          {
            // 9.3 Activity & audit (wave 39): declared before the empty-path
            // Люди child for the same reason `roles` is.
            path: 'activity',
            loadComponent: () =>
              import('./features/staff/activity-log-page').then((m) => m.ActivityLogPage),
          },
          {
            // 9.4 Approvals (wave 45): the maker-checker worklist over the
            // shared ADR 0027 model, declared before the empty-path Люди
            // child for the same reason `roles` and `activity` are.
            path: 'approvals',
            loadComponent: () =>
              import('./features/staff/approvals-page').then((m) => m.ApprovalsPage),
          },
          {
            path: '',
            loadComponent: () => import('./features/staff/staff-page').then((m) => m.StaffPage),
            children: [
              {
                path: ':subjectId',
                loadComponent: () =>
                  import('./features/staff/staff-member-detail-pane').then(
                    (m) => m.StaffMemberDetailPane,
                  ),
              },
            ],
          },
        ],
      },
      {
        // Finance (IA §8): 8.1/8.2 (tier P, wave 34) plus all four tier-2
        // rows (wave 39) — see `finance-shell.ts`'s own doc.
        path: 'finance',
        loadComponent: () => import('./features/finance/finance-shell').then((m) => m.FinanceShell),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'payments' },
          {
            path: 'payments',
            loadComponent: () =>
              import('./features/finance/payments/payments-page').then((m) => m.PaymentsPage),
          },
          {
            path: 'fiscal',
            loadComponent: () =>
              import('./features/finance/fiscal/fiscal-page').then((m) => m.FiscalPage),
          },
          {
            path: 'cash',
            loadComponent: () =>
              import('./features/finance/cash/cash-reconciliation-page').then(
                (m) => m.CashReconciliationPage,
              ),
          },
          {
            path: 'delivery-cost',
            loadComponent: () =>
              import('./features/finance/delivery-cost/delivery-cost-page').then(
                (m) => m.DeliveryCostPage,
              ),
          },
          {
            path: 'payouts',
            loadComponent: () =>
              import('./features/finance/payouts/courier-payouts-page').then(
                (m) => m.CourierPayoutsPage,
              ),
          },
          {
            path: 'subscription',
            loadComponent: () =>
              import('./features/finance/subscription/subscription-page').then(
                (m) => m.SubscriptionPage,
              ),
          },
        ],
      },
      {
        // Marketing (wave 37, frontend-information-architecture.md §6): a
        // sub-nav shell over six tier-2 tabs, the same shape `finance`'s and
        // `delivery`'s own shells use. Only `campaigns` (§6.4) has a real
        // backend to render — ADR 0044's audiences, campaign lifecycle, and
        // suppression machinery — with audience targeting and suppression
        // management folded into it rather than routed separately, since
        // neither is its own IA row. The other five route to the shared
        // `NotBuiltPage`: §6.1/6.2 have a schema (V0093) and no authoring
        // service or controller above it; §6.5/6.7/6.8 have neither schema
        // nor service. See `marketing-shell.ts`'s own doc.
        path: 'marketing',
        loadComponent: () =>
          import('./features/marketing/marketing-shell').then((m) => m.MarketingShell),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'campaigns' },
          {
            path: 'promotions',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'frontend-information-architecture.md §6.1 (Promotions)' },
          },
          {
            path: 'promo-codes',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'frontend-information-architecture.md §6.2 (Promo codes)' },
          },
          {
            path: 'campaigns',
            loadComponent: () =>
              import('./features/marketing/campaigns/campaigns-page').then((m) => m.CampaignsPage),
            children: [
              // Docks beside the campaign list, same reasoning as the order
              // board's own detail child route.
              {
                path: ':campaignId',
                loadComponent: () =>
                  import('./features/marketing/campaigns/campaign-detail-pane').then(
                    (m) => m.CampaignDetailPane,
                  ),
              },
            ],
          },
          {
            path: 'automations',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'frontend-information-architecture.md §6.5 (Automations)' },
          },
          {
            path: 'content',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'frontend-information-architecture.md §6.7 (Content)' },
          },
          {
            path: 'storefront',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'frontend-information-architecture.md §6.8 (Storefront merchandising)' },
          },
        ],
      },
      {
        path: 'catalog',
        loadComponent: () => import('./features/catalog/catalog-shell').then((m) => m.CatalogShell),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'products' },
          {
            path: 'products',
            loadComponent: () =>
              import('./features/catalog/products-page').then((m) => m.ProductsPage),
          },
          {
            path: 'categories',
            loadComponent: () =>
              import('./features/catalog/categories-page').then((m) => m.CategoriesPage),
          },
          {
            path: 'menus',
            loadComponent: () => import('./features/catalog/menus-page').then((m) => m.MenusPage),
          },
          // catalog.md §4.11 (Excel/POS import): the backend has no import-job
          // entity at all (ADR 0012, currently scoped to POS sources only) —
          // a whole missing subsystem, not a small gap, so this stays the
          // honest not-built page rather than a screen with nothing to call.
          {
            path: 'import',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'operations-spec/catalog.md §4.11 (Import: Excel and POS)' },
          },
          {
            path: 'publication',
            loadComponent: () =>
              import('./features/catalog/publication-page').then((m) => m.PublicationPage),
          },
          {
            path: 'prices',
            loadComponent: () =>
              import('./features/catalog/price-list-page').then((m) => m.PriceListPage),
          },
          // catalog.md §4.13: every vocabulary on this screen is honestly
          // "not built — ADR 0016" except Бренды, which already belongs to
          // tenancy and is not duplicated here — the spec's own instruction
          // is that nothing on this screen should be built before its
          // disposition is decided.
          {
            path: 'reference-data',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'operations-spec/catalog.md §4.13 (Reference data)' },
          },
          // catalog.md's own parity table: "Unowned; closest ADR 0018/0019.
          // Needs a decision before design." Zero backend anywhere.
          {
            path: 'auto-add',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: {
              spec: 'frontend-information-architecture.md §4.9 (Auto-add rules) — no backend',
            },
          },
        ],
      },
      {
        // Reports (IA PART 2 §7): 7.1 Business overview and 7.2 Order
        // reports (tier P, wave 33) plus every tier-2 row (wave 39) —
        // `ReportsShell` owns the shared filter bar and the tab strip across
        // all of them. 7.8/7.10 are tier 3 and carry no tab at all.
        path: 'statistics',
        loadComponent: () => import('./features/reports/reports-shell').then((m) => m.ReportsShell),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'overview' },
          {
            path: 'overview',
            loadComponent: () =>
              import('./features/reports/business-overview-page').then(
                (m) => m.BusinessOverviewPage,
              ),
          },
          {
            path: 'orders',
            loadComponent: () =>
              import('./features/reports/order-reports-page').then((m) => m.OrderReportsPage),
          },
          {
            // 7.3 Branch & SLA reports: real, over `.../queries` grouped by
            // LOCATION and `.../sla-buckets` — see `branch-sla-report-page.ts`.
            path: 'branches',
            loadComponent: () =>
              import('./features/reports/branch-sla-report-page').then(
                (m) => m.BranchSlaReportPage,
              ),
          },
          {
            // 7.7 Product analytics: «Продажи» is real (`.../variant-sales`,
            // new this wave); ABC/XYZ stay named-not-built inline — see
            // `product-analytics-page.ts`.
            path: 'products',
            loadComponent: () =>
              import('./features/reports/product-analytics-page').then(
                (m) => m.ProductAnalyticsPage,
              ),
          },
          // 7.4 Courier reports: `delivery_cost_variance.v1`'s own registry
          // entry declares `sourceAvailable: false` — ADR 0043's tender and
          // delivery facts do not exist in `reporting` yet, so an analytics
          // cut over courier performance has nothing to read even though
          // ADR 0042's courier module itself (shifts, ledger, settlement) is
          // real — see Finance 8.3-8.5, which reads that module directly.
          {
            path: 'couriers',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: {
              spec:
                'frontend-information-architecture.md §7.4 (Courier reports) — reporting.fact_delivery ' +
                'does not exist (ADR 0043)',
            },
          },
          // 7.5 Staff reports: `reporting.fact_order` carries no operator
          // attribution column at all (ADR 0039's own `created_by_actor_id`
          // landed on `ordering.orders`, not copied into the reporting fact
          // yet), so 7.5 cannot join to a staff dimension.
          {
            path: 'staff',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: {
              spec:
                'frontend-information-architecture.md §7.5 (Staff reports) — fact_order has no ' +
                'operator attribution column',
            },
          },
          // 7.6 Customer analytics: none of its six published-formula tiles
          // (new customers, basket depth, LTV, …) is a registered metric —
          // ADR 0043's own status line names ABC/XYZ/RFM and forecasting as
          // not built, and Band D's funnel needs `analytics.events`, which
          // cannot be backfilled. RFM itself is real, in Customers 5.3.
          {
            path: 'customers',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: {
              spec:
                'frontend-information-architecture.md §7.6 (Customer analytics) — no registered ' +
                'metric for any of its tiles; RFM lives in Customers 5.3 instead',
            },
          },
          // 7.9 Marketing reports: promo-code redemption needs
          // reporting.fact_promotion_redemption, which does not exist (no
          // promotions ADR at all, per statistics.md §7); the campaign tab
          // would need a campaign list/summary read this wave does not add,
          // to stay out of Marketing §6's own surface (a sibling wave).
          {
            path: 'marketing',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: {
              spec:
                'frontend-information-architecture.md §7.9 (Marketing reports) — no promotions ADR, ' +
                'no fact_promotion_redemption',
            },
          },
        ],
      },
      // The product editor is a *sibling* of `catalog`, not one of its
      // children — catalog.md §4.2 specifies it as a full page, unlike the
      // order board's docked detail, so opening it replaces the tabbed shell
      // (`catalog-shell.ts`) entirely rather than rendering inside it.
      {
        path: 'catalog/products/:productId',
        loadComponent: () =>
          import('./features/catalog/product-editor-page').then((m) => m.ProductEditorPage),
      },
      {
        // IA §2: 2.1 Kitchen queue (KDS) and 2.5 Stop list, the section's two
        // P-tier screens — same sub-nav-over-a-routed-child shape as `catalog`.
        path: 'kitchen',
        loadComponent: () => import('./features/kitchen/kitchen-shell').then((m) => m.KitchenShell),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'queue' },
          {
            path: 'queue',
            loadComponent: () =>
              import('./features/kitchen/kitchen-queue-page').then((m) => m.KitchenQueuePage),
          },
          {
            path: 'buffer',
            loadComponent: () => import('./features/kitchen/buffer-page').then((m) => m.BufferPage),
          },
          {
            path: 'expo',
            loadComponent: () => import('./features/kitchen/expo-page').then((m) => m.ExpoPage),
          },
          {
            path: 'vdu',
            loadComponent: () => import('./features/kitchen/vdu-page').then((m) => m.VduPage),
          },
          {
            path: 'stop-list',
            loadComponent: () =>
              import('./features/kitchen/stop-list-page').then((m) => m.StopListPage),
          },
          // IA 2.6 — Capacity & buffer settings (ADR 0041, wave 43):
          // `kitchen-shell.ts`'s own doc named this tab as deliberately
          // absent until this wave built it.
          {
            path: 'capacity',
            loadComponent: () =>
              import('./features/kitchen/capacity-page').then((m) => m.CapacityPage),
          },
        ],
      },
      {
        // IA §3: 3.1 Dispatch board, 3.6 Delivery zones, 3.7 Delivery tariffs
        // are built; 3.8 Dispatch rules is an honest not-built placeholder —
        // see `delivery-shell.ts`'s own doc for why. 3.3 Couriers is its own
        // top-level route below, per `navigation.ts`'s existing rail grouping.
        path: 'delivery',
        loadComponent: () =>
          import('./features/delivery/delivery-shell').then((m) => m.DeliveryShell),
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'dispatch' },
          {
            path: 'dispatch',
            loadComponent: () =>
              import('./features/delivery/dispatch-board-page').then((m) => m.DispatchBoardPage),
          },
          {
            path: 'map',
            loadComponent: () =>
              import('./features/delivery/live-map-page').then((m) => m.LiveMapPage),
          },
          {
            path: 'zones',
            loadComponent: () =>
              import('./features/delivery/delivery-zones-page').then((m) => m.DeliveryZonesPage),
          },
          {
            path: 'tariffs',
            loadComponent: () =>
              import('./features/delivery/delivery-tariffs-page').then(
                (m) => m.DeliveryTariffsPage,
              ),
          },
          {
            path: 'courier-rates',
            loadComponent: () =>
              import('./features/delivery/courier-types-rates-page').then(
                (m) => m.CourierTypesRatesPage,
              ),
          },
          {
            path: 'shifts',
            loadComponent: () =>
              import('./features/delivery/shifts-page').then((m) => m.ShiftsPage),
          },
          {
            path: 'courier-policy',
            loadComponent: () =>
              import('./features/delivery/courier-policy-page').then((m) => m.CourierPolicyPage),
          },
          {
            path: 'dispatch-rules',
            loadComponent: () =>
              import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
            data: { spec: 'operations-spec/couriers.md §3.8 (Dispatch rules) — no backend exists' },
          },
        ],
      },
      {
        // IA §3.3: the in-house roster. A single page, not a shell — unlike
        // Kitchen/Delivery there is only one P-tier screen here.
        path: 'couriers',
        loadComponent: () =>
          import('./features/couriers/couriers-page').then((m) => m.CouriersPage),
      },
      ...placeholderRoutes(),
      // Unknown paths land on Today rather than on a 404. There is no such thing
      // as a deleted page in this console — only one that has not been built —
      // so a 404 would be a lie about what happened.
      { path: '**', redirectTo: 'today' },
    ],
  },
];

/**
 * One placeholder per unbuilt rail entry, derived from the navigation model so
 * the two cannot drift. Adding a rail item without a route is then impossible.
 */
function placeholderRoutes(): Routes {
  const built = new Set([
    '/today',
    '/orders',
    '/inbox',
    '/settings',
    '/catalog',
    '/kitchen',
    '/delivery',
    '/couriers',
    '/customers',
    '/staff',
    '/statistics',
    '/finance',
    '/marketing',
  ]);
  return NAV_ITEMS.filter((item) => !built.has(item.path)).map((item) => ({
    path: item.path.slice(1),
    loadComponent: () => import('./features/not-built/not-built-page').then((m) => m.NotBuiltPage),
    data: { spec: item.spec ?? 'docs/operations-spec/' },
  }));
}
