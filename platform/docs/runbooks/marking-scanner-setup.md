# Marked goods: scanner setup, the self-test, and turning the flag on

**Last executed:** never. No tenant sells a marked SKU yet, the pilot has not chosen a scanner (ADR 0161's
open input), and the Click sandbox has not accepted a receipt with `Labels`; what exists is the capture, the
guard and the projection, tested against synthetic keystroke profiles and a fake. Per ADR 0023 a runbook that
has never been executed is a draft.

For the person who set up the pass at a branch that sells marked goods (ADR 0161, gap map row `X.35`), or who
is turning `feature.marked_goods` on for a tenant. Nothing here is needed while the flag is off, which is
every tenant until one names a marked SKU.

**No way back for a code that reached a receipt.** A marking code on an issued receipt is consumed on
HorecaOS's side for good; a returned unit is handled in the state system and the accountant's own process
until a correction path exists (ADR 0038). Releasing a scan at the pass (below) is only possible while the
order is still `CONFIRMED`, `PREPARING` or `READY`.

## What the pass needs

Any 2D imager in **keyboard-wedge mode**, USB or Bluetooth. A 1D laser cannot read a DataMatrix. Camera
capture is a fallback only where the browser's `BarcodeDetector` reports `data_matrix`; the console offers
it by itself when it can and says nothing when it cannot.

Configure the imager, from its programming guide, so that:

| Setting | Value | Why |
|---|---|---|
| Symbology | GS1 DataMatrix **enabled** | The symbol on a marked package |
| FNC1 / group separator | **Send as GS** (ASCII 29, `Ctrl+]`) | The code has a separator after its serial; a scanner that drops it makes a valid code unparseable and the failure looks like a bad code |
| Suffix | **Enter** (or Tab) | Ends the scan; the console reads the scan up to it |
| Symbology identifier (AIM) | **Off** | The console strips `]d2` and `]d1` if they arrive, but off is cleaner |
| Inter-character delay | **None / minimum** | A gap above 100 ms between characters classes the scan as typed by hand, and a typed code is refused |
| Prefix | None | |

## The one-minute self-test

1. Open any order that is `CONFIRMED`, `PREPARING` or `READY` at the branch (or the expo screen).
2. In the **marked goods** panel, tick **Scanner self-test**. While it is ticked a scan is *not* sent
   anywhere; the panel only reports what it saw.
3. Scan one package. The panel shows, without ever showing the code itself:
   - the length it read;
   - how many group separators it saw (a GS1 code with a serial and a further element should show at least one);
   - whether an AIM identifier arrived;
   - whether the scan is shaped like a GS1 code.
4. If **separators: 0** and the code should carry one, the imager is dropping the group separator: fix the
   FNC1 setting above and scan again.
5. If the panel says the scan was typed by hand (the whole scan arrived slower than a scanner sends), lower
   the inter-character delay.
6. Untick the self-test.

## What happens at the pass

- The panel lists each marked line, `captured of required`, and arms the input on the first line that still
  needs units. Scan a unit; the count moves. A scan of a unit that is already scanned, on any order of the
  tenant, is refused.
- An order cannot leave the pass (advance to out-for-delivery or completed, from the board, the expo or in
  bulk) while a marked line is short: it answers `MARKS_INCOMPLETE` naming the line. There is no override.
- A wrong scan is taken back with **Release** and a reason (`MISSCAN`, `UNIT_REPLACED`, `UNIT_DAMAGED`). The
  unit can then be scanned again. A damaged symbol is answered by scanning another unit; if there is none,
  the order is cancelled, which frees its scans.
- A code typed or pasted by hand is refused (`MARKING_KEYED_ENTRY_REFUSED`) unless the platform sets
  `fiscal.marking.allow_keyed_entry` to `true`. The printed text on a package does not include the
  cryptographic tail, so a keyed code is a guess.

## Turning `feature.marked_goods` on for a tenant

The platform turns it on; a tenant cannot. Do it only when **all three** hold:

| | Precondition | Where it is built |
|---|---|---|
| **P1** | A cart that holds a marked good is not offered a payment method whose receipt cannot carry a code (Payme) | Built (batch 21): `CartPaymentOptions` and `CheckoutEligibilityGuard` ask `FiscalNodeFacts.requiresMarkingCapablePayment` through `MarkedCartLines`, and `PaymentIntentPort.carriesMarkingCodes` is answered from the method's receipt port; ADR 0166, Decision 7 |
| **P2** | The order-line to receipt-line projection replaces the synthetic aggregate line | Built (batch 21): `ReceiptLineBuilder` builds the lines of every order, marked or not, and the aggregate line and `MarkedOrderReceiptProjection` are deleted; ADR 0166, Decisions 6 and 9 |
| **P3** | The Click sandbox has accepted a receipt with `Labels`, and the answer to *one item with N labels, or N items of quantity one* is known | See [connect-click-payme-sandbox.md](connect-click-payme-sandbox.md); if Click refuses N labels on one item, the split goes in `ReceiptLineBuilder` and nothing else changes |

Then, in this order:

1. Replace the synthetic keystroke fixtures in `datamatrix-input.spec.ts` with the recorded profile of the
   imager the pilot actually bought.
2. Set `feature.marked_goods` to `true` for the tenant (platform operator, tenant scope).
3. Only now publish the first marked SKU. While the flag is off, publishing a classification with
   `marking_required` is refused (`MARKED_GOODS_NOT_ENABLED`), and a marked modifier option or fee is refused
   whatever the flag says (`MARKED_MODIFIER_UNSUPPORTED`).

### Check that it works

```sql
-- As a platform operator with read access. Never selects code_encrypted.
SELECT capture_stage, entry_method, gtin_checked, count(*)
  FROM ordering.order_line_marks
 WHERE tenant_id = '<tenant id>' AND released_at IS NULL
 GROUP BY 1, 2, 3;
```

`gtin_checked = false` on every row means the product's classification states no barcode, so the scan is not
compared with the product: add the barcode to the classification.

### Rolling back

Set the flag to `false`. The guard and the capture endpoints stop and new marked publications are refused;
marks already captured stay. A marked line already published stays marked in the catalog, so an order for it
completes with no capture: turn the flag off only together with the SKU.

## If `fiscal.marking.structure_check` is wrong

The repository holds no specification of the Asl Belgisi code; the server assumes the GS1 element-string
shape `01` + GTIN-14 + `21` + serial. If real packages are refused as `MARKING_CODE_MALFORMED` while the
self-test says they are well read, the assumption is wrong: set `fiscal.marking.structure_check` to `NONE`
(the server then checks only character set and length) and tell the platform owner what the real shape is.
