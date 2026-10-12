/**
 * Printing and receipts (ADR 0154): turns facts other modules own into a single 1-bit bitmap per
 * document and delivers it, at least once, to a printer a branch's own agent can reach.
 *
 * <p>The module renders and delivers and decides nothing commercial. It computes no money, no tax and
 * no fiscal identifier: every figure on paper is read from the owning module's {@code api} port
 * (ordering, kitchen, payments, tenancy, media) and printed as stored. It owns printers, routes, a
 * brand's receipt profile, print jobs and the renderer, and nothing depends on it: events flow into it
 * and never out, which {@code ModularArchitectureTests} holds.
 *
 * <p>A print agent is a device principal (ADR 0079) and a byte pipe. It pulls jobs over outbound HTTPS,
 * receives ESC/POS bytes and a printer alias, and reports an outcome; it holds no template, no language
 * and no business rule, and the platform never sends it an address to dial.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Printing")
package uz.horecaos.platform.printing;
