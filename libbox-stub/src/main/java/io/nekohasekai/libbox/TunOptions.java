package io.nekohasekai.libbox;

public interface TunOptions {
    boolean getAutoRoute();
    StringBox getDNSServerAddress() throws Exception;
    StringIterator getExcludePackage();
    StringIterator getIncludePackage();
    RoutePrefixIterator getInet4Address();
    RoutePrefixIterator getInet6Address();
    RoutePrefixIterator getInet4RouteAddress();
    RoutePrefixIterator getInet6RouteAddress();
    RoutePrefixIterator getInet4RouteExcludeAddress();
    RoutePrefixIterator getInet6RouteExcludeAddress();
    RoutePrefixIterator getInet4RouteRange();
    RoutePrefixIterator getInet6RouteRange();
    int getMTU();
    boolean getStrictRoute();
    boolean isHTTPProxyEnabled();
    String getHTTPProxyServer();
    int getHTTPProxyServerPort();
    StringIterator getHTTPProxyBypassDomain();
    StringIterator getHTTPProxyMatchDomain();
}
