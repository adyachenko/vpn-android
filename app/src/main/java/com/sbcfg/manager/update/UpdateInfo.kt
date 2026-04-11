package com.sbcfg.manager.update

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseUrl: String,
    val releaseNotes: String?,
    val fileSize: Long
)
