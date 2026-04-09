package io.nekohasekai.libbox;

public class WIFIState {
    private String ssid;
    private String bssid;

    public WIFIState(String ssid, String bssid) {
        this.ssid = ssid;
        this.bssid = bssid;
    }

    public String getSSID() { return ssid; }
    public String getBSSID() { return bssid; }
}
