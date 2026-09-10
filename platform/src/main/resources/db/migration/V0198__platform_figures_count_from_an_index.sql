-- ADR 0084: the control plane's platform figures count the last day's orders
-- and receipts across every tenant, and every blocked receipt. The existing
-- indexes lead with tenant_id, so each of these counts would otherwise read
-- the whole table on every open of the health page.
CREATE INDEX ix_order_created_platform ON ordering.orders (created_at);
CREATE INDEX ix_fiscal_document_created_platform ON fiscal.fiscal_documents (created_at);
CREATE INDEX ix_fiscal_document_blocked_platform ON fiscal.fiscal_documents (blocked_at) WHERE status = 'BLOCKED';
