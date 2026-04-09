package io.nekohasekai.libbox;

public class CommandServer {
    public CommandServer(CommandServerHandler handler, PlatformInterface platformInterface) {
        // stub
    }

    public void start() throws Exception {
        // stub
    }

    public void startOrReloadService(String content, OverrideOptions options) throws Exception {
        // stub
    }

    public void close() {
        // stub
    }

    public void closeService() throws Exception {
        // stub
    }

    public boolean needFindProcess() {
        return false;
    }

    public boolean needWIFIState() {
        return false;
    }

    public void pause() {
        // stub
    }

    public void wake() {
        // stub
    }

    public void resetNetwork() {
        // stub
    }
}
