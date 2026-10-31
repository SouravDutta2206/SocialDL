package com.socialdl.src.core.ffmpeg

import com.socialdl.src.core.runtime.NativeBinaryProvider
import com.socialdl.src.core.runtime.NativeComponentResolver
import com.socialdl.src.core.runtime.NativeComponents
import javax.inject.Inject
import javax.inject.Singleton

/** FFmpeg from a trusted helper APK when one is installed, otherwise from this module's jniLibs. */
@Singleton
open class FFmpegBinaryProvider @Inject constructor(
    // Nullable only as a JVM-test seam; Dagger always supplies the resolver.
    resolver: NativeComponentResolver?,
) : NativeBinaryProvider(NativeComponents.FFMPEG, resolver)
