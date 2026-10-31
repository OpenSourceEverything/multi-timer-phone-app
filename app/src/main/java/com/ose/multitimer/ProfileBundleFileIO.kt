package com.ose.multitimer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Bounded stream operations for the Android document-picker boundary.
 *
 * The caller owns and closes each stream. These helpers deliberately do not
 * ask a document provider for a file size: that metadata may be missing or
 * inaccurate, so the actual bytes read are the authority. Keeping this code
 * independent of Android Uri objects makes the size and I/O failure rules
 * straightforward to unit test.
 */
object ProfileBundleFileIO {
    /**
     * Reads at most the configured document limit plus one detection byte.
     *
     * The extra byte distinguishes a document exactly at the limit from one
     * that exceeds it, without ever retaining the oversized remainder in RAM.
     * A provider that reports no size or lies about it receives the same bound.
     *
     * @param input stream opened for one user-selected document; remains open
     * @return the complete document when its actual byte count is allowed
     * @throws IOException if reading fails or the document exceeds the limit
     */
    @JvmStatic
    @Throws(IOException::class)
    fun readBounded(input: InputStream): ByteArray {
        val maximumBytes = ProfileSharingLimits.MAX_DOCUMENT_BYTES
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8_192))
        val buffer = ByteArray(8_192)

        while (true) {
            // Ask the provider for no more than one byte past the remaining
            // allowance; the first extra byte proves rejection is necessary.
            val remainingBytes = maximumBytes - output.size()
            val requestedBytes = minOf(buffer.size, remainingBytes + 1)
            val bytesRead = input.read(buffer, 0, requestedBytes)
            if (bytesRead < 0) {
                return output.toByteArray()
            }
            if (bytesRead == 0) {
                // InputStream permits a zero-length progress result. Read one
                // byte directly so a provider cannot make this loop spin.
                val nextByte = input.read()
                if (nextByte < 0) {
                    return output.toByteArray()
                }
                if (output.size() == maximumBytes) {
                    throw IOException("Profile bundle exceeds $maximumBytes bytes")
                }
                output.write(nextByte)
                continue
            }
            if (bytesRead > remainingBytes) {
                throw IOException("Profile bundle exceeds $maximumBytes bytes")
            }
            output.write(buffer, 0, bytesRead)
        }
    }

    /**
     * Writes one already-encoded bounded bundle and flushes it to the provider.
     *
     * The caller still owns the stream and should close it with a resource
     * scope, which signals completion to providers that finalize on close.
     *
     * @param output stream opened for the destination selected by the user
     * @param documentBytes complete v1 JSON bytes from ProfileBundleCodec
     * @throws IOException if the byte bound is violated or provider writing fails
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeBounded(output: OutputStream, documentBytes: ByteArray) {
        val maximumBytes = ProfileSharingLimits.MAX_DOCUMENT_BYTES
        if (documentBytes.size > maximumBytes) {
            throw IOException("Profile bundle exceeds $maximumBytes bytes")
        }
        output.write(documentBytes)
        output.flush()
    }
}
