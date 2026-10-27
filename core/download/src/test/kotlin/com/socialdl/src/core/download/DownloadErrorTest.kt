package com.socialdl.src.core.download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DownloadErrorTest {

    @Test
    fun `http statuses map to specific user errors`() {
        assertEquals(DownloadError.NotFound, DownloadError.from(HttpStatusException(404, null, "not found")))
        assertEquals(DownloadError.NotFound, DownloadError.from(HttpStatusException(410, null, "gone")))
        assertEquals(DownloadError.AccessDenied, DownloadError.from(HttpStatusException(403, null, "forbidden")))
        assertEquals(DownloadError.RateLimited, DownloadError.from(HttpStatusException(429, 1000, "slow down")))
        assertEquals(DownloadError.ServerError, DownloadError.from(HttpStatusException(500, null, "oops")))
    }

    @Test
    fun `storage and permission failures are not reported as network errors`() {
        assertEquals(
            DownloadError.StorageFull,
            DownloadError.from(IOException("write failed: ENOSPC (No space left on device)"))
        )
        assertEquals(
            DownloadError.StoragePermission,
            DownloadError.from(SecurityException("MediaStore insert denied"))
        )
    }

    @Test
    fun `transport failures remain network errors`() {
        assertEquals(DownloadError.NetworkFailure, DownloadError.from(SocketTimeoutException("timeout")))
        assertEquals(DownloadError.NetworkFailure, DownloadError.from(IOException("connection reset")))
    }
}
