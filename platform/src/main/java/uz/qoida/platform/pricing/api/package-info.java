/**
 * What pricing exposes to other modules: accepting a quote and reading the
 * priced result, and nothing of the price books, tax profiles, or the engine
 * that produced it (ADR 0018).
 */
@org.springframework.modulith.NamedInterface("api")
package uz.qoida.platform.pricing.api;
