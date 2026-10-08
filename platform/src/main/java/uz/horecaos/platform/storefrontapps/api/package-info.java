/**
 * What other modules and the web configuration may hold of a storefront app
 * (ADR 0070): the header names a client sends, and the identity a request that
 * passed the check carries. Never the registry's rows or the app's secret.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.storefrontapps.api;
