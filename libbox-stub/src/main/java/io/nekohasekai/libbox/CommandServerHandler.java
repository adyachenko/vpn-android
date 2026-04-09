package io.nekohasekai.libbox;

public interface CommandServerHandler {
    SystemProxyStatus getSystemProxyStatus() throws Exception;
    void serviceReload() throws Exception;
    void serviceStop() throws Exception;
    void setSystemProxyEnabled(boolean enabled) throws Exception;
    void writeDebugMessage(String message);
}
