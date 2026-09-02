/**
 * Where the Finance section's endpoints live (`operations-spec/finance.md`
 * §8.1, §8.2).
 *
 * Every call here is `TENANT`-scoped, never `LocationScope` — `payment.read`,
 * `payment.initiate`, `refund.*` and `fiscal.document.*` are all declared at
 * `ScopeType.TENANT` (`Capability.java`), and `TENANT_FINANCE`/`TENANT_OWNER`
 * (the bundles that hold them) carry no `BRAND` or `LOCATION` grant at all.
 * That is why this file takes a bare `tenantId` rather than the
 * {@link LocationScope} every other `*-paths.ts` file in this app does — see
 * `core/auth/current-tenant.ts`'s own doc comment for the reader half of the
 * same fact.
 *
 * Two backends, kept apart the way the Java side keeps them apart:
 * `OperationsRemedyController`/`OperationsPaymentController` answer under
 * `/api/v1/operations`, `FiscalDocumentController` under the older, still-live
 * `/api/v1/tenants` prefix `orders.md`'s own path constant names as legacy —
 * see that constant's doc for why it has not moved.
 */

const OPERATIONS = '/api/v1/operations/tenants';
const TENANT = '/api/v1/tenants';

function enc(value: string): string {
  return encodeURIComponent(value);
}

export const financePaths = {
  // ---------------------------------------------------------- 8.1 Payments & settlements

  /** `OperationsPaymentController.forOrder` / `.rePresent`. */
  orderPayment(tenantId: string, orderId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/orders/${enc(orderId)}/payment`;
  },

  orderPaymentRepresentations(tenantId: string, orderId: string): string {
    return `${this.orderPayment(tenantId, orderId)}/re-presentations`;
  },

  /** `OperationsRemedyController.remediesOfOrder`. */
  orderRemedies(tenantId: string, orderId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/orders/${enc(orderId)}/remedies`;
  },

  orderRefunds(tenantId: string, orderId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/orders/${enc(orderId)}/refunds`;
  },

  orderDeliveryFeeReimbursements(tenantId: string, orderId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/orders/${enc(orderId)}/delivery-fee-reimbursements`;
  },

  orderFutureDiscounts(tenantId: string, orderId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/orders/${enc(orderId)}/future-discounts`;
  },

  remedyVerification(tenantId: string, remedyId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/remedies/${enc(remedyId)}/verification`;
  },

  /** The reconciliation worklist: attested refunds nothing has corroborated yet. */
  remediesUnverified(tenantId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/remedies/unverified`;
  },

  /** Remedy totals by type, over a `from`/`to` window. */
  remedyReport(tenantId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/reports/remedies`;
  },

  // ---------------------------------------------------------- 8.2 Fiscal receipts

  /** `FiscalDocumentController.blocked` — the worklist. */
  fiscalDocumentsBlocked(tenantId: string): string {
    return `${TENANT}/${enc(tenantId)}/fiscal/documents/blocked`;
  },

  fiscalOrderDocuments(tenantId: string, orderId: string): string {
    return `${TENANT}/${enc(tenantId)}/fiscal/orders/${enc(orderId)}/documents`;
  },

  fiscalCoverage(tenantId: string): string {
    return `${TENANT}/${enc(tenantId)}/fiscal/coverage`;
  },

  fiscalDocumentRetries(tenantId: string, documentId: string): string {
    return `${TENANT}/${enc(tenantId)}/fiscal/documents/${enc(documentId)}/retries`;
  },

  fiscalDocumentUnblocks(tenantId: string, documentId: string): string {
    return `${TENANT}/${enc(tenantId)}/fiscal/documents/${enc(documentId)}/unblocks`;
  },
} as const;
