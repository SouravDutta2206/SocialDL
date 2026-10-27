package com.socialdl.src.core.cookies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookieJarWriterTest {

    private fun harvest(url: String, domain: String, header: String) =
        NetscapeCookieJarWriter.Harvest(url = url, cookieDomain = domain, header = header)

    // ---- parseHeader ----

    @Test
    fun parseHeader_splitsSimplePairs() {
        val pairs = NetscapeCookieJarWriter.parseHeader(
            "VISITOR_INFO1_LIVE=abc123; SID=xyz; SAPISID=token"
        )
        assertEquals(3, pairs.size)
        assertEquals("VISITOR_INFO1_LIVE" to "abc123", pairs[0])
        assertEquals("SID" to "xyz", pairs[1])
        assertEquals("SAPISID" to "token", pairs[2])
    }

    @Test
    fun parseHeader_keepsEqualsSignsInsideValues() {
        val pairs = NetscapeCookieJarWriter.parseHeader("TOKEN=a=b==c")
        assertEquals(1, pairs.size)
        assertEquals("TOKEN" to "a=b==c", pairs[0].first to pairs[0].second)
        assertEquals("a=b==c", pairs[0].second)
    }

    @Test
    fun parseHeader_skipsBlankAndNamelessSegments() {
        val pairs = NetscapeCookieJarWriter.parseHeader(";  ; novalue; ;=noname; GOOD=1;")
        assertEquals(1, pairs.size)
        assertEquals("GOOD" to "1", pairs[0])
    }

    @Test
    fun parseHeader_rejectsRawLineBreaksInsidePairs() {
        // A tab/CR/LF inside a pair would corrupt the line-oriented file.
        val pairs = NetscapeCookieJarWriter.parseHeader("EVIL=has\ttab; GOOD=ok")
        assertEquals(1, pairs.size)
        assertEquals("GOOD" to "ok", pairs[0])
    }

    @Test
    fun parseHeader_emptyHeaderYieldsNothing() {
        assertTrue(NetscapeCookieJarWriter.parseHeader("").isEmpty())
        assertTrue(NetscapeCookieJarWriter.parseHeader("   ").isEmpty())
    }

    // ---- buildJarContent ----

    @Test
    fun buildJarContent_emitsNetscapeHeaderAndExactlySevenColumns() {
        val content = NetscapeCookieJarWriter.buildJarContent(
            listOf(harvest("https://www.youtube.com", ".youtube.com", "SAPISID=S; SID=S2"))
        )!!
        val lines = content.lines()
        assertEquals(NetscapeCookieJarWriter.NETSCAPE_HEADER, lines[0])
        val dataLines = lines.drop(2).filter { it.isNotBlank() }
        assertEquals(2, dataLines.size)
        dataLines.forEach { line ->
            assertEquals(7, line.split("\t").size)
        }
        val sapisid = dataLines[0].split("\t")
        assertEquals(".youtube.com", sapisid[0])
        assertEquals("TRUE", sapisid[1]) // includeSubdomains
        assertEquals("/", sapisid[2])
        assertEquals("TRUE", sapisid[3]) // secure default
        assertEquals(NetscapeCookieJarWriter.FAR_FUTURE_EXPIRY_SECONDS.toString(), sapisid[4])
        assertEquals("SAPISID", sapisid[5])
        assertEquals("S", sapisid[6])
    }

    @Test
    fun buildJarContent_keepsSameCookieForDifferentDomains() {
        // A cookie scoped to .google.com and .accounts.google.com are two distinct
        // cookies; deduping on (name, value) alone would silently drop one.
        val content = NetscapeCookieJarWriter.buildJarContent(
            listOf(
                harvest("https://www.google.com", ".google.com", "SID=abc; NID=g1"),
                harvest("https://accounts.google.com", ".accounts.google.com", "SID=abc; SMSV=sv"),
            )
        )!!
        val dataLines = content.lines().drop(2).filter { it.isNotBlank() }
        assertEquals(4, dataLines.size) // SID x2, NID, SMSV
        assertEquals(
            2,
            dataLines.count { it.split("\t")[5] == "SID" && it.split("\t")[6] == "abc" }
        )
    }

    @Test
    fun buildJarContent_dedupesSameCookieWithinOneDomain() {
        val content = NetscapeCookieJarWriter.buildJarContent(
            listOf(
                harvest("https://www.google.com", ".google.com", "SID=abc; SID=abc"),
            )
        )!!
        val dataLines = content.lines().drop(2).filter { it.isNotBlank() }
        assertEquals(1, dataLines.size)
    }

    @Test
    fun buildJarContent_keepsSameNameDifferentValues() {
        // Same name but a distinct value is a legitimately distinct cookie.
        val content = NetscapeCookieJarWriter.buildJarContent(
            listOf(
                harvest("https://www.google.com", ".google.com", "NID=n1"),
                harvest("https://accounts.google.com", ".accounts.google.com", "NID=n2"),
            )
        )!!
        assertEquals(2, content.lines().drop(2).count { it.isNotBlank() })
    }

    @Test
    fun buildJarContent_returnsNullWhenNothingSurvives() {
        assertNull(NetscapeCookieJarWriter.buildJarContent(emptyList()))
        assertNull(NetscapeCookieJarWriter.buildJarContent(listOf(harvest("u", "d", ""))))
        assertNull(NetscapeCookieJarWriter.buildJarContent(listOf(harvest("u", "d", "novalue"))))
    }

    // ---- validateNetscapeFile (manual import) ----

    @Test
    fun validateNetscapeFile_acceptsWellFormedFile() {
        val content = """
            # Netscape HTTP Cookie File
            # This is a generated file! Do not edit.

            .youtube.com	TRUE	/	TRUE	4102444800	SAPISID	tok
            #HttpOnly_.youtube.com	TRUE	/	TRUE	4102444800	__Secure-3PSID	tok2
        """.trimIndent()

        val validation = NetscapeCookieJarWriter.validateNetscapeFile(content)
        assertFalse(validation.hasInvalidLines)
        assertEquals(2, validation.validLines.size)
        assertTrue(validation.validLines[1].startsWith("#HttpOnly_"))
    }

    @Test
    fun validateNetscapeFile_flagsWrongColumnCount() {
        // 6 tab-delimited columns — classic broken export.
        val content = ".youtube.com\tTRUE\t/\tTRUE\t4102444800\tSAPISID"
        val validation = NetscapeCookieJarWriter.validateNetscapeFile(content)
        assertTrue(validation.hasInvalidLines)
        assertEquals(listOf(1), validation.invalidLineNumbers)
        assertTrue(validation.validLines.isEmpty())
    }

    @Test
    fun validateNetscapeFile_flagsNonNumericExpiry_butAcceptsHostOnlyDomain() {
        val content = """
            .youtube.com	TRUE	/	TRUE	far-future	SAPISID	tok
            youtube.com	TRUE	/	TRUE	4102444800	SID	tok
        """.trimIndent()
        val validation = NetscapeCookieJarWriter.validateNetscapeFile(content)
        // Line 1: non-numeric expiry. Line 2: host-only domain (no dot prefix) is
        // LEGAL in the Netscape format — browser exports contain it routinely.
        assertEquals(listOf(1), validation.invalidLineNumbers)
        assertEquals(1, validation.validLines.size)
    }

    @Test
    fun validateNetscapeFile_acceptsZeroExpirySessionCookies() {
        val content = ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\ttok"
        val validation = NetscapeCookieJarWriter.validateNetscapeFile(content)
        assertFalse(validation.hasInvalidLines)
        assertEquals(1, validation.validLines.size)
    }

    @Test
    fun validateNetscapeFile_flagsWhitespaceOnlyName() {
        val content = ".youtube.com\tTRUE\t/\tTRUE\t4102444800\t\ttok"
        val validation = NetscapeCookieJarWriter.validateNetscapeFile(content)
        assertTrue(validation.hasInvalidLines)
    }

    @Test
    fun validateNetscapeFile_ignoresCommentsAndBlankLines() {
        val content = "\n\n# comment\n   \n.youtube.com\tTRUE\t/\tTRUE\t1\tA\tb\n"
        val validation = NetscapeCookieJarWriter.validateNetscapeFile(content)
        assertFalse(validation.hasInvalidLines)
        assertEquals(1, validation.validLines.size)
    }
}
