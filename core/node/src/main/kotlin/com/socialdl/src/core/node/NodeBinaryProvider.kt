package com.socialdl.src.core.node

import com.socialdl.src.core.runtime.NativeBinaryProvider
import com.socialdl.src.core.runtime.NativeComponentResolver
import com.socialdl.src.core.runtime.NativeComponents
import javax.inject.Inject
import javax.inject.Singleton

/** Node from a trusted helper APK when one is installed, otherwise from this module's jniLibs. */
@Singleton
open class NodeBinaryProvider @Inject constructor(
    // Nullable only as a JVM-test seam; Dagger always supplies the resolver.
    resolver: NativeComponentResolver?,
) : NativeBinaryProvider(NativeComponents.NODE, resolver) {

    /** Directory that holds libnode.so. The Python bridge expects this for `node_dir`. */
    open fun binaryDir(): String = resolveRuntime()?.executable?.parent.orEmpty()
}
