package io.nekohasekai.libbox;

public interface PlatformInterface {
    void autoDetectInterfaceControl(int fd) throws Exception;
    void clearDNSCache();
    void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) throws Exception;
    ConnectionOwner findConnectionOwner(int protocol, String srcAddr, int srcPort, String dstAddr, int dstPort) throws Exception;
    NetworkInterfaceIterator getInterfaces() throws Exception;
    boolean includeAllNetworks();
    LocalDNSTransport localDNSTransport();
    int openTun(TunOptions options) throws Exception;
    WIFIState readWIFIState();
    void sendNotification(Notification notification) throws Exception;
    void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) throws Exception;
    StringIterator systemCertificates();
    boolean underNetworkExtension();
    boolean usePlatformAutoDetectInterfaceControl();
    boolean useProcFS();
}
