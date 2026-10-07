package uz.horecaos.platform.tenancy.api.geo;

/**
 * The browser-facing half of the map provider (ADR 0145 decision 4), answered by whichever
 * adapter is configured. A separate port from {@link GeocodePort} because the two have
 * different readers: the geocoder is called by services, this is read by a screen.
 */
public interface MapClientConfigPort {

    MapClientConfig clientConfig();
}
