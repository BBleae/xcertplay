package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.PixelSize
import java.nio.ByteBuffer

/** One upload buffer belongs to one instrument ingress, never to a destination or GL context. */
internal class InstrumentRgbaUploadStaging(
    sizePixels: PixelSize,
    private val allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect,
) {
    private val ownerThread = Thread.currentThread()
    private val pixelCount: Int
    private val byteCount: Int
    private var bytes: ByteBuffer? = null
    private var inFlight = false
    private var retired = false

    init {
        val count = sizePixels.widthPixels.toLong() * sizePixels.heightPixels
        require(count in 1L..1_600_000L) { "Instrument staging size is out of bounds" }
        pixelCount = count.toInt()
        byteCount = pixelCount * 4
    }

    /**
     * The caller is confined to the EGL thread and must finish the GL upload before returning.
     * A failed/uncertain native upload poisons this staging object instead of recycling its bytes.
     */
    fun upload(argbPixels: IntArray, consumeAndFinish: (ByteBuffer) -> Unit) {
        checkOwnerThread()
        check(!retired && !inFlight) { "Instrument staging is retired or still in flight" }
        require(argbPixels.size == pixelCount && argbPixels.all { it ushr 24 == 0xff }) {
            "Instrument upload must replace one complete opaque ARGB frame"
        }
        val buffer = bytes ?: allocate(byteCount).also {
            check(it.isDirect && !it.isReadOnly && it.capacity() == byteCount) {
                "Instrument staging requires an exact direct RGBA buffer"
            }
            bytes = it
        }
        buffer.clear()
        argbPixels.forEach { pixel ->
            buffer.put((pixel ushr 16).toByte())
            buffer.put((pixel ushr 8).toByte())
            buffer.put(pixel.toByte())
            buffer.put(0xff.toByte())
        }
        buffer.flip()
        inFlight = true
        try {
            consumeAndFinish(buffer)
        } catch (problem: Throwable) {
            retired = true
            bytes = null
            throw problem
        } finally {
            inFlight = false
        }
    }

    fun retire() {
        checkOwnerThread()
        check(!inFlight) { "Instrument upload is still in flight" }
        retired = true
        bytes = null
    }

    private fun checkOwnerThread() {
        check(Thread.currentThread() === ownerThread) {
            "Instrument staging must remain on its ingress EGL thread"
        }
    }
}
