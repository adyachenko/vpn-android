package io.nekohasekai.libbox;

public class SetupOptions {
    private String basePath;
    private String workingPath;
    private String tempPath;
    private boolean debug;
    private boolean fixAndroidStack;

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public String getWorkingPath() { return workingPath; }
    public void setWorkingPath(String workingPath) { this.workingPath = workingPath; }

    public String getTempPath() { return tempPath; }
    public void setTempPath(String tempPath) { this.tempPath = tempPath; }

    public boolean getDebug() { return debug; }
    public void setDebug(boolean debug) { this.debug = debug; }

    public boolean getFixAndroidStack() { return fixAndroidStack; }
    public void setFixAndroidStack(boolean fixAndroidStack) { this.fixAndroidStack = fixAndroidStack; }
}
