package com.sbcfg.manager.speedtest

data class SpeedTestServer(
    val name: String,
    val location: String,
    val pingUrl: String,
    val downloadUrl: String,
    val uploadUrl: String? = null,
    val uploadMethod: String = "POST",
)

val DEFAULT_SERVERS = listOf(
    SpeedTestServer(
        name = "Cloudflare",
        location = "Anycast (nearest edge)",
        pingUrl = "https://speed.cloudflare.com/__down?bytes=0",
        downloadUrl = "https://speed.cloudflare.com/__down?bytes=25000000",
        uploadUrl = "https://speed.cloudflare.com/__up",
    ),
    SpeedTestServer(
        name = "Hetzner FSN",
        location = "Falkenstein, DE",
        pingUrl = "https://fsn1-speed.hetzner.com/100MB.bin",
        downloadUrl = "https://fsn1-speed.hetzner.com/100MB.bin",
    ),
    SpeedTestServer(
        name = "OVH Gravelines",
        location = "Gravelines, FR",
        pingUrl = "https://gra.proof.ovh.net/files/1Mb.dat",
        downloadUrl = "https://gra.proof.ovh.net/files/100Mb.dat",
    ),
)
