package com.ose.multitimer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies byte limits and provider stream failures without Android widgets. */
class ProfileBundleFileIOTest {
    @Test
    fun readAcceptsAFileExactlyAtTheLimitWithoutAskingItsReportedSize() {
        val bytes = ByteArray(ProfileSharingLimits.MAX_DOCUMENT_BYTES) { (it % 251).toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun available(): Int {
                throw AssertionError("Document size metadata must not be trusted")
            }
        }

        assertArrayEquals(bytes, ProfileBundleFileIO.readBounded(input))
    }

    @Test
    fun readRejectsTheFirstByteBeyondTheLimit() {
        val bytes = ByteArray(ProfileSharingLimits.MAX_DOCUMENT_BYTES + 1)

        val failure = assertThrows(IOException::class.java) {
            ProfileBundleFileIO.readBounded(ByteArrayInputStream(bytes))
        }

        assertEquals("Profile bundle exceeds ${ProfileSharingLimits.MAX_DOCUMENT_BYTES} bytes",
            failure.message)
    }

    @Test
    fun readPropagatesProviderFailure() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("provider read failed")
        }

        val failure = assertThrows(IOException::class.java) {
            ProfileBundleFileIO.readBounded(input)
        }

        assertEquals("provider read failed", failure.message)
    }

    @Test
    fun writeFlushesTheCompleteBoundedDocument() {
        val document = byteArrayOf(1, 2, 3)
        val output = ByteArrayOutputStream()

        ProfileBundleFileIO.writeBounded(output, document)

        assertArrayEquals(document, output.toByteArray())
    }

    @Test
    fun writeRejectsOversizedBytesBeforeTouchingDestination() {
        val output = ByteArrayOutputStream()
        val oversized = ByteArray(ProfileSharingLimits.MAX_DOCUMENT_BYTES + 1)

        assertThrows(IOException::class.java) {
            ProfileBundleFileIO.writeBounded(output, oversized)
        }

        assertEquals(0, output.size())
    }

    @Test
    fun writePropagatesProviderFailure() {
        val output = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("provider write failed")
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                throw IOException("provider write failed")
            }
        }

        val failure = assertThrows(IOException::class.java) {
            ProfileBundleFileIO.writeBounded(output, byteArrayOf(1))
        }

        assertEquals("provider write failed", failure.message)
    }
}
