package com.socialdl.src.core.ffmpeg

sealed interface FFmpegEvent {
    data class Progress(
        val frame: Long? = null,
        val fps: Float? = null,
        val sizeKb: Long? = null,
        val timeSeconds: Double? = null,
        val bitrateKbps: Float? = null,
        val speed: Float? = null
    ) : FFmpegEvent

    data object Completed : FFmpegEvent

    data class Failed(
        val exitCode: Int,
        val errorLog: String? = null
    ) : FFmpegEvent
}
