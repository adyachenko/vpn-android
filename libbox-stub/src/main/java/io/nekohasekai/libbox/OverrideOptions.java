package io.nekohasekai.libbox;

public class OverrideOptions {
    private boolean autoRedirect;
    private StringIterator includePackage;
    private StringIterator excludePackage;

    public OverrideOptions() {}

    public boolean getAutoRedirect() { return autoRedirect; }
    public void setAutoRedirect(boolean autoRedirect) { this.autoRedirect = autoRedirect; }

    public StringIterator getIncludePackage() { return includePackage; }
    public void setIncludePackage(StringIterator includePackage) { this.includePackage = includePackage; }

    public StringIterator getExcludePackage() { return excludePackage; }
    public void setExcludePackage(StringIterator excludePackage) { this.excludePackage = excludePackage; }
}
