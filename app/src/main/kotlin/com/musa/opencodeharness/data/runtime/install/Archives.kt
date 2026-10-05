package com.musa.opencodeharness.data.runtime.install

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** What kind of container a downloaded artifact file is, from its magic bytes. */
enum class ContainerKind {
    /** gzipped tar: `.tgz`, `.tar.gz`, `.apk` (Alpine packages are plain tar.gz). */
    TAR_GZ,
    /** `ar` archive holding a `data.tar.xz` payload: Debian/Termux `.deb`. */
    DEB_XZ,
}

class UnsupportedContainerException(message: String) : RuntimeException(message)

/**
 * Peeks the first bytes to classify the container. Throws [UnsupportedContainerException] for
 * anything else — deliberately no silent fallback: guessing the format from the file NAME was
 * the bug this type exists to prevent.
 */
fun detectContainer(file: File): ContainerKind {
    val magic = ByteArray(8)
    FileInputStream(file).use { it.read(magic) }
    if (magic.size >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
        return ContainerKind.TAR_GZ
    }
    val arMagic = "!<arch>\n".toByteArray(Charsets.US_ASCII)
    if (magic.size >= arMagic.size && magic.copyOfRange(0, arMagic.size).contentEquals(arMagic)) {
        return ContainerKind.DEB_XZ
    }
    throw UnsupportedContainerException("${file.name}: not a gzip stream or ar archive")
}

/**
 * Minimal `ar`-format reader for a `.deb`. Returns the byte stream of one member (e.g.
 * `data.tar.xz`) without buffering the rest. `ar` member payloads are length-prefixed by the
 * header, so we can slice exactly — no scanning, no materialising.
 */
class DebArchive(file: File) : Closeable {
    private val input = BufferedInputStream(FileInputStream(file))

    /** Streams the member named [name] (exact match). Does not buffer the whole member. */
    fun memberStream(name: String): InputStream {
        val global = ByteArray(8)
        val readGlobal = input.read(global)
        require(readGlobal == 8 && String(global, Charsets.US_ASCII) == "!<arch>\n") {
            "not an ar archive"
        }
        while (true) {
            val header = ByteArray(60)
            var read = 0
            while (read < 60) {
                val n = input.read(header, read, 60 - read)
                if (n < 0) throw UnsupportedContainerException("member $name not found (EOF in headers)")
                read += n
            }
            val memberName = String(header, 0, 16, Charsets.US_ASCII).trim().removeSuffix("/")
            val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLong()
            val endMagic = String(header, 58, 2, Charsets.US_ASCII)
            require(endMagic == "`\n") { "corrupt ar member header" }
            if (memberName == name) {
                val remaining = longArrayOf(size)
                return object : FilterInputStream(input) {
                    override fun read(): Int {
                        if (remaining[0] <= 0) return -1
                        val b = super.read()
                        if (b >= 0) remaining[0]--
                        return b
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining[0] <= 0) return -1
                        val n = super.read(b, off, minOf(len.toLong(), remaining[0]).toInt())
                        if (n > 0) remaining[0] -= n
                        return n
                    }
                }
            }
            // Skip this member's payload (+ padding byte to even offset).
            var skip = size + (size % 2)
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) throw UnsupportedContainerException("EOF skipping ar member $memberName")
                skip -= n
            }
        }
    }

    override fun close() = input.close()
}

/**
 * The payload tar stream of a container as a streaming entry iterator.
 *
 * `.tgz`/`.tar.gz`/`.apk` are GZIP tars, decodable by the JDK. `.deb` payloads are XZ
 * (`data.tar.xz`), and the JDK has no xz decoder — so we use commons-compress + org.tukaani:xz,
 * which is why those two dependencies exist. LZMA2 is not something to hand-roll.
 *
 * Iteration is entry-by-entry via [TarArchiveInputStream.getNextEntry]; entries you do not
 * read are skipped in place, never materialised — this is what keeps the ~199 MB opencode
 * member STREAMABLE instead of OOMing the phone.
 */
fun openPayloadTar(file: File): TarArchiveInputStream = when (detectContainer(file)) {
    ContainerKind.TAR_GZ -> TarArchiveInputStream(
        GZIPInputStream(BufferedInputStream(FileInputStream(file)))
    )
    ContainerKind.DEB_XZ -> {
        val deb = DebArchive(file)
        val xz = XZCompressorInputStream(deb.memberStream("data.tar.xz"))
        TarArchiveInputStream(object : FilterInputStream(xz) {
            override fun close() {
                super.close()
                deb.close()
            }
        })
    }
}
