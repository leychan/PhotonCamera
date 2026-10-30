package com.hinnka.mycamera.gallery

/** 新拍照片只保留系统相册中的成片；历史照片继续使用原有素材与编辑流程。 */
object CaptureStorage {
    const val MODE_KEY = "captureStorage"
    const val SYSTEM_FINAL = "systemFinal"
    const val MOTION_KEY = "captureMotionPhoto"
    const val LIVE_REQUESTED_KEY = "captureLiveRequested"

    fun properties(properties: Map<String, String>): Map<String, String> =
        properties + (MODE_KEY to SYSTEM_FINAL)
}

val MediaMetadata.usesSystemCaptureStorage: Boolean
    get() = customProperties[CaptureStorage.MODE_KEY] == CaptureStorage.SYSTEM_FINAL

val MediaMetadata.isFinalizedCapture: Boolean
    get() = usesSystemCaptureStorage && !sourceUri.isNullOrBlank()
