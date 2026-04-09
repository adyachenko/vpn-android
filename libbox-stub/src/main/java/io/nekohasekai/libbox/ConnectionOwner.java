package io.nekohasekai.libbox;

public class ConnectionOwner {
    private int userId;
    private String userName;
    private String processPath;
    private StringIterator androidPackageNames;

    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getProcessPath() { return processPath; }
    public void setProcessPath(String processPath) { this.processPath = processPath; }

    public StringIterator androidPackageNames() { return androidPackageNames; }
    public void setAndroidPackageNames(StringIterator names) { this.androidPackageNames = names; }
}
