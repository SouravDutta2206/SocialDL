package com.socialdl.src.core.ffmpeg

sealed interface FFmpegEvent {
    data object Completed : FFmpegEvent

    data class Failed(
        val exitCode: Int,
        val errorLog: String? = null
    ) : FFmpegEvent
}
