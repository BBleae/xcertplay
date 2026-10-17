package com.shilapi.xcertplay.ultra.egl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraPixelRect
import com.shilapi.xcertplay.ultra.UltraSceneBackground
import com.shilapi.xcertplay.ultra.UltraSceneSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap

/** One EGL producer timeline; even a failed swap consumes its assigned value. */
internal class EglProducerTimestampAllocator(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var lastAssigned = 0L

    fun next(): Long {
        check(lastAssigned < Long.MAX_VALUE) { "EGL producer timestamp space exhausted" }
        val next = maxOf(1L, clockNanos(), lastAssigned + 1L)
        lastAssigned = next
        return next
    }
}

/** API 28-compatible EGL14/GLES2 implementation. Every entry point is render-thread confined. */
internal class AndroidEgl14Driver : UltraEglDriver {
    private class Ingress(
        val textureId: Int,
    ) : UltraEglDriverIngress {
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        var listenerCleared = false
        var surfaceReleased = true
        var surfaceTextureReleased = true
        var textureRetired = false
        val transform = FloatArray(16).apply {
            this[0] = 1f
            this[5] = 1f
            this[10] = 1f
            this[15] = 1f
        }
        override val producerEndpoint: Any
            get() = checkNotNull(surface) { "External ingress producer Surface is unavailable" }
        override var hasUsableFrame: Boolean = false
        override var lastAcquiredTimestampNanos: Long? = null
    }

    private class InstrumentIngress(
        val textureId: Int,
        val destination: DisplayDestination,
        val sizePixels: PixelSize,
    ) : UltraEglDriverIngress {
        override val producerEndpoint: Any get() = Unit
        override var hasUsableFrame: Boolean = false
        var textureRetired = false
        val staging = InstrumentRgbaUploadStaging(sizePixels)
    }

    private class Output(
        val destination: DisplayDestination,
        val nativeWindow: Surface,
        val eglSurface: EGLSurface,
        val sizePixels: PixelSize,
    ) : UltraEglDriverOutput

    private var ownerThread: Thread? = null
    private var initialized = false
    private var closed = false
    private var eglInitialized = false
    private var eglTerminated = false
    private var contextUsable = false
    private val threadBinding = EglThreadBindingRetirement()
    private lateinit var display: EGLDisplay
    private lateinit var config: EGLConfig
    private lateinit var context: EGLContext
    private lateinit var rootSurface: EGLSurface
    private var program = 0
    private var instrumentProgram = 0
    private var instrumentPositionLocation = -1
    private var instrumentTextureCoordinateLocation = -1
    private var instrumentTextureMatrixLocation = -1
    private var instrumentOpacityLocation = -1
    private var instrumentSamplerLocation = -1
    private var positionLocation = -1
    private var textureCoordinateLocation = -1
    private var textureMatrixLocation = -1
    private var opacityLocation = -1
    private var samplerLocation = -1
    private var supportsPresentationTime = false
    private val producerTimestamps = EglProducerTimestampAllocator()
    private val ingresses = Collections.newSetFromMap(
        IdentityHashMap<Ingress, Boolean>(),
    )
    private val instrumentIngresses = Collections.newSetFromMap(
        IdentityHashMap<InstrumentIngress, Boolean>(),
    )
    private val outputs = Collections.newSetFromMap(
        IdentityHashMap<Output, Boolean>(),
    )
    // eglCreateWindowSurface can succeed while size validation fails. If the immediate destroy
    // then fails, this set keeps the exact EGLSurface reachable for a later closeWithProof retry.
    private val orphanedOutputSurfaces = Collections.newSetFromMap(
        IdentityHashMap<EGLSurface, Boolean>(),
    )

    override fun initialize() {
        check(ownerThread == null) { "Android EGL driver already initialized" }
        ownerThread = Thread.currentThread()
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay returned EGL_NO_DISPLAY" }
            val versions = IntArray(2)
            checkEgl(EGL14.eglInitialize(display, versions, 0, versions, 1), "eglInitialize")
            eglInitialized = true

            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            checkEgl(
                EGL14.eglChooseConfig(
                    display,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.size,
                    configCount,
                    0,
                ),
                "eglChooseConfig",
            )
            check(configCount[0] == 1 && configs[0] != null) { "No compatible EGL config" }
            config = checkNotNull(configs[0])

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            checkCreatedContext("eglCreateContext")
            rootSurface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            checkCreatedSurface(rootSurface, null, "eglCreatePbufferSurface")
            contextUsable = true
            makeCurrent(rootSurface, null)

            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            check(extensions.split(' ').contains("GL_OES_EGL_image_external")) {
                "GL_OES_EGL_image_external is unavailable"
            }
            program = linkProgram(VERTEX_SHADER, EXTERNAL_FRAGMENT_SHADER)
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
            opacityLocation = GLES20.glGetUniformLocation(program, "uOpacity")
            samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
            check(
                positionLocation >= 0 &&
                    textureCoordinateLocation >= 0 &&
                    textureMatrixLocation >= 0 &&
                    opacityLocation >= 0 &&
                    samplerLocation >= 0,
            ) { "Required GLES shader locations are unavailable" }
            instrumentProgram = linkProgram(VERTEX_SHADER, INSTRUMENT_FRAGMENT_SHADER)
            instrumentPositionLocation = GLES20.glGetAttribLocation(instrumentProgram, "aPosition")
            instrumentTextureCoordinateLocation = GLES20.glGetAttribLocation(
                instrumentProgram, "aTexCoord")
            instrumentTextureMatrixLocation = GLES20.glGetUniformLocation(
                instrumentProgram, "uTexMatrix")
            instrumentOpacityLocation = GLES20.glGetUniformLocation(instrumentProgram, "uOpacity")
            instrumentSamplerLocation = GLES20.glGetUniformLocation(instrumentProgram, "uTexture")
            check(instrumentPositionLocation >= 0 && instrumentTextureCoordinateLocation >= 0 &&
                instrumentTextureMatrixLocation >= 0 && instrumentOpacityLocation >= 0 &&
                instrumentSamplerLocation >= 0
            ) { "Required instrument GLES shader locations are unavailable" }
            checkGl("initialize shader program")

            supportsPresentationTime = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
                .orEmpty()
                .split(' ')
                .contains("EGL_ANDROID_presentation_time")
            initialized = true
        } catch (problem: Throwable) {
            val proof = closeWithProof(Long.MAX_VALUE)
            if (!proof.retired) proof.failure?.let(problem::addSuppressed)
            throw problem
        }
    }

    override fun createExternalIngress(
        key: UltraEglIngressKey,
        sizePixels: PixelSize?,
        onFrameAvailable: (UltraEglDriverIngress) -> Unit,
    ): UltraEglDriverIngress {
        require((key is UltraEglIngressKey.Remote) == (sizePixels == null)) {
            "Remote EGL ingress size is decoder-owned; local/overlay require physical pixels"
        }
        checkReady()
        makeRootCurrent()
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        checkGl("glGenTextures for $key")
        val textureId = names[0]
        check(textureId != 0) { "GLES returned texture name 0" }
        val ingress = Ingress(textureId)
        ingresses += ingress
        try {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            checkGl("configure external texture for $key")
            val surfaceTexture = SurfaceTexture(textureId)
            ingress.surfaceTexture = surfaceTexture
            ingress.surfaceTextureReleased = false
            if (sizePixels != null) {
                surfaceTexture.setDefaultBufferSize(sizePixels.widthPixels, sizePixels.heightPixels)
            }
            ingress.surface = Surface(surfaceTexture)
            ingress.surfaceReleased = false
            surfaceTexture.setOnFrameAvailableListener { onFrameAvailable(ingress) }
            return ingress
        } catch (problem: Throwable) {
            try {
                destroyIngress(ingress)
            } catch (cleanup: Throwable) {
                throw UltraEglCandidateResourceRetainedException(problem, cleanup)
            }
            throw UltraEglDriverException("Unable to create external OES ingress for $key", problem)
        }
    }

    override fun updateIngressFrame(ingress: UltraEglDriverIngress) {
        checkReady()
        val resource = requireIngress(ingress)
        makeRootCurrent()
        try {
            val surfaceTexture = checkNotNull(resource.surfaceTexture)
            surfaceTexture.updateTexImage()
            // Read the acquired buffer's actual timestamp on the EGL owner thread, immediately
            // after updateTexImage. A decoder PTS or callback time is not this observation.
            val acquiredTimestamp = surfaceTexture.timestamp
            surfaceTexture.getTransformMatrix(resource.transform)
            resource.lastAcquiredTimestampNanos = acquiredTimestamp.takeIf { it > 0L }
            resource.hasUsableFrame = true
        } catch (problem: RuntimeException) {
            resource.lastAcquiredTimestampNanos = null
            throw UltraEglDriverException("SurfaceTexture frame update failed", problem)
        }
    }

    override fun createInstrumentIngress(
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): UltraEglDriverIngress {
        require(destination.role == com.shilapi.xcertplay.ultra.DisplayDestinationRole.CLUSTER)
        require(sizePixels.widthPixels.toLong() * sizePixels.heightPixels <= 1_600_000L)
        checkReady()
        makeRootCurrent()
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        checkGl("glGenTextures for instrument")
        val ingress = InstrumentIngress(names[0], destination, sizePixels)
        check(names[0] != 0) { "GLES returned instrument texture name 0" }
        instrumentIngresses += ingress
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ingress.textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                sizePixels.widthPixels, sizePixels.heightPixels, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            checkGl("allocate instrument texture")
            return ingress
        } catch (problem: Throwable) {
            try { destroyIngress(ingress) } catch (cleanup: Throwable) {
                throw UltraEglCandidateResourceRetainedException(problem, cleanup)
            }
            throw problem
        }
    }

    override fun uploadInstrumentFrame(ingress: UltraEglDriverIngress, argbPixels: IntArray) {
        checkReady()
        val raster = ingress as? InstrumentIngress
            ?: throw IllegalArgumentException("Instrument upload requires a raster texture")
        check(raster in instrumentIngresses && !raster.textureRetired)
        val size = raster.sizePixels
        raster.staging.upload(argbPixels) { bytes ->
            makeRootCurrent()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, raster.textureId)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                size.widthPixels, size.heightPixels, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                bytes)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            // The next frame may rewrite the same client-memory buffer. Finish this transfer
            // even when no output scene currently replays and supplies its own finish fence.
            GLES20.glFinish()
            checkGl("replace instrument texture")
        }
        raster.hasUsableFrame = true
    }

    override fun createWindowOutput(
        destination: DisplayDestination,
        nativeWindow: Any,
        sizePixels: PixelSize,
    ): UltraEglDriverOutput {
        checkReady()
        val surface = nativeWindow as? Surface
            ?: throw IllegalArgumentException("Android EGL output requires android.view.Surface")
        check(surface.isValid) { "Android EGL output Surface is invalid for $destination" }
        val eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        checkCreatedSurface(eglSurface, destination, "eglCreateWindowSurface")
        orphanedOutputSurfaces += eglSurface
        try {
            val actualWidth = querySurface(eglSurface, EGL14.EGL_WIDTH, destination)
            val actualHeight = querySurface(eglSurface, EGL14.EGL_HEIGHT, destination)
            check(actualWidth == sizePixels.widthPixels && actualHeight == sizePixels.heightPixels) {
                "EGL output size ${actualWidth}x$actualHeight does not match declared physical " +
                    "pixels ${sizePixels.widthPixels}x${sizePixels.heightPixels} for $destination"
            }
            return Output(destination, surface, eglSurface, sizePixels).also {
                outputs += it
                orphanedOutputSurfaces.remove(eglSurface)
            }
        } catch (problem: Throwable) {
            if (!EGL14.eglDestroySurface(display, eglSurface)) {
                val cleanupError = EGL14.eglGetError()
                throw UltraEglCandidateResourceRetainedException(
                    problem,
                    UltraEglDriverException(
                        "eglDestroySurface after output creation failure returned 0x" +
                            cleanupError.toString(16),
                    ),
                )
            }
            orphanedOutputSurfaces.remove(eglSurface)
            throw problem
        }
    }

    override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) {
        renderAndCollectLocalDraws(output, scene)
    }

    override fun renderWithLocalDrawReceipt(
        output: UltraEglDriverOutput,
        scene: UltraEglResolvedScene,
    ): Set<UltraEglLocalLayerDraw> = renderAndCollectLocalDraws(output, scene).localDraws

    override fun renderWithPresentationReceipt(
        output: UltraEglDriverOutput,
        scene: UltraEglResolvedScene,
    ): Long? = renderAndCollectLocalDraws(output, scene).producerTimestampNanos

    override fun renderWithLocalDrawPresentationReceipt(
        output: UltraEglDriverOutput,
        scene: UltraEglResolvedScene,
    ): UltraEglDriverRenderedFrame = renderAndCollectLocalDraws(output, scene)

    private fun renderAndCollectLocalDraws(
        output: UltraEglDriverOutput,
        scene: UltraEglResolvedScene,
    ): UltraEglDriverRenderedFrame {
        checkReady()
        val target = requireOutput(output)
        check(target.destination == scene.destination) { "EGL scene destination mismatch" }
        check(target.sizePixels == scene.viewportPixels) { "EGL scene viewport mismatch" }
        validateOutputSize(target, scene.viewportPixels)
        makeCurrent(target.eglSurface, target.destination)
        prepareFrame(scene.viewportPixels, scene.background)
        val localDraws = LinkedHashSet<UltraEglLocalLayerDraw>()
        scene.layers.forEachIndexed { layerIndex, resolved ->
            when (resolved.layer.source) {
                UltraSceneSource.PunchThrough -> clearLayerRect(
                    resolved.layer.framePixels,
                    resolved.layer.clipPixels,
                    scene.viewportPixels,
                )
                is UltraSceneSource.Instrument -> {
                    val raster = resolved.ingress as? InstrumentIngress
                        ?: throw IllegalStateException("Instrument layer has no dedicated raster")
                    require(raster.destination == scene.destination &&
                        raster.sizePixels == scene.viewportPixels &&
                        raster in instrumentIngresses
                    ) { "Instrument raster differs from the exact CLUSTER viewport" }
                    if (raster.hasUsableFrame) {
                        drawInstrumentLayer(resolved.layer, raster, scene.viewportPixels)
                    }
                }
                else -> {
                    val ingress = requireIngress(
                        checkNotNull(resolved.ingress) {
                            "Textured EGL layer has no resolved ingress"
                        },
                    )
                    // Sampling an external texture before its first updateTexImage is undefined.
                    if (shouldSampleExternalIngress(ingress)) {
                        val drawn = drawExternalLayer(resolved.layer, ingress, scene.viewportPixels)
                        val local = resolved.layer.source as? UltraSceneSource.Local
                        if (drawn && local != null) localDraws += UltraEglLocalLayerDraw(
                            scene.destination, UltraEglIngressKey.Local(local.contentId),
                            layerIndex, resolved.layer,
                        )
                    }
                }
            }
        }
        val timestamp = present(target)
        return UltraEglDriverRenderedFrame(localDraws, timestamp)
    }

    override fun clear(
        output: UltraEglDriverOutput,
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ) {
        checkReady()
        val target = requireOutput(output)
        check(target.destination == destination && target.sizePixels == sizePixels) {
            "EGL clear target mismatch"
        }
        validateOutputSize(target, sizePixels)
        makeCurrent(target.eglSurface, target.destination)
        prepareFrame(sizePixels, UltraSceneBackground.Transparent)
        present(target)
    }

    override fun clearWithPresentationReceipt(
        output: UltraEglDriverOutput,
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): Long? {
        checkReady()
        val target = requireOutput(output)
        check(target.destination == destination && target.sizePixels == sizePixels) {
            "EGL clear target mismatch"
        }
        validateOutputSize(target, sizePixels)
        makeCurrent(target.eglSurface, target.destination)
        prepareFrame(sizePixels, UltraSceneBackground.Transparent)
        return present(target)
    }

    override fun finish() {
        checkThread()
        if (!initialized || !contextUsable) return
        // Output retirement intentionally unbinds EGL before destroying a window surface.
        // Later finish calls must not rely on any previous current context still being bound.
        makeRootCurrent()
        GLES20.glFinish()
        checkGl("glFinish")
    }

    override fun destroyIngress(ingress: UltraEglDriverIngress) {
        checkThread()
        if (ingress is InstrumentIngress) {
            if (ingress !in instrumentIngresses) return
            if (!ingress.textureRetired && contextUsable) {
                makeRootCurrent()
                GLES20.glDeleteTextures(1, intArrayOf(ingress.textureId), 0)
                checkGl("glDeleteTextures(instrument)")
                ingress.textureRetired = true
            }
            if (!contextUsable) ingress.textureRetired = true
            if (ingress.textureRetired) {
                ingress.staging.retire()
                instrumentIngresses.remove(ingress)
            }
            return
        }
        val resource = ingress as? Ingress ?: return
        if (resource !in ingresses) return
        val surfaceTexture = resource.surfaceTexture
        if (!resource.listenerCleared && surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null)
            resource.listenerCleared = true
        }
        if (!resource.surfaceReleased) {
            checkNotNull(resource.surface).release()
            resource.surfaceReleased = true
            resource.surface = null
        }
        if (!resource.surfaceTextureReleased) {
            checkNotNull(surfaceTexture).release()
            resource.surfaceTextureReleased = true
            resource.surfaceTexture = null
        }
        if (!resource.textureRetired && contextUsable) {
            makeRootCurrent()
            GLES20.glDeleteTextures(1, intArrayOf(resource.textureId), 0)
            checkGl("glDeleteTextures")
            resource.textureRetired = true
        }
        if (!contextUsable) resource.textureRetired = true
        if (
            resource.surfaceReleased &&
            resource.surfaceTextureReleased &&
            resource.textureRetired
        ) ingresses.remove(resource)
    }

    override fun destroyOutput(output: UltraEglDriverOutput) {
        checkThread()
        val resource = output as? Output ?: return
        if (resource !in outputs) return
        if (eglInitialized && !eglTerminated) {
            // eglDestroySurface may only mark a still-current window surface for deletion.
            // The caller may release its borrowed native window only after this succeeds.
            unbindCurrentThread()
            checkEglAllowContextLoss(
                EGL14.eglDestroySurface(display, resource.eglSurface),
                "eglDestroySurface",
            )
        }
        outputs.remove(resource)
    }

    override fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize) {
        checkReady()
        val target = requireOutput(output)
        check(target.sizePixels == expectedSize) { "EGL output lease size mismatch" }
        checkOutputWindow(target)
        val width = querySurface(target.eglSurface, EGL14.EGL_WIDTH, target.destination)
        val height = querySurface(target.eglSurface, EGL14.EGL_HEIGHT, target.destination)
        if (width <= 0 || height <= 0) {
            throw UltraEglSurfaceLostException(
                target.destination,
                "EGL output Surface has non-positive actual size ${width}x$height",
            )
        }
        val actualSize = PixelSize(width, height)
        if (actualSize != expectedSize) {
            throw UltraEglOutputSizeChangedException(target.destination, expectedSize, actualSize)
        }
    }

    override fun closeWithProof(deadlineNanos: Long): UltraEglDriverRetirementProof {
        checkThread()
        if (closed) return UltraEglDriverRetirementProof(retired = true)
        var firstFailure: Throwable? = null

        fun beforeDeadline(): Boolean =
            deadlineNanos == Long.MAX_VALUE || System.nanoTime() - deadlineNanos < 0L

        ingresses.toList().forEach { ingress ->
            if (!beforeDeadline()) return@forEach
            try {
                destroyIngress(ingress)
            } catch (problem: Throwable) {
                if (firstFailure == null) firstFailure = problem
            }
        }
        instrumentIngresses.toList().forEach { ingress ->
            if (!beforeDeadline()) return@forEach
            try { destroyIngress(ingress) } catch (problem: Throwable) {
                if (firstFailure == null) firstFailure = problem
            }
        }
        outputs.toList().forEach { output ->
            if (!beforeDeadline()) return@forEach
            try {
                destroyOutput(output)
            } catch (problem: Throwable) {
                if (firstFailure == null) firstFailure = problem
            }
        }
        orphanedOutputSurfaces.toList().forEach { orphan ->
            if (!beforeDeadline()) return@forEach
            try {
                unbindCurrentThread()
                if (!EGL14.eglDestroySurface(display, orphan)) {
                    throw eglFailure("eglDestroySurface(orphaned output)")
                }
                orphanedOutputSurfaces.remove(orphan)
            } catch (problem: Throwable) {
                if (firstFailure == null) firstFailure = problem
            }
        }

        if (eglInitialized && !eglTerminated && beforeDeadline()) {
            if (contextUsable) {
                try {
                    makeRootCurrent()
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (instrumentProgram != 0) GLES20.glDeleteProgram(instrumentProgram)
                    GLES20.glFinish()
                } catch (problem: Throwable) {
                    if (firstFailure == null) firstFailure = problem
                }
            }
            val unbound = try {
                unbindCurrentThread()
                true
            } catch (problem: Throwable) {
                if (firstFailure == null) firstFailure = problem
                false
            }
            // Do not terminate the display after an ambiguous unbind. EGL permits current
            // surfaces/contexts to outlive eglDestroy* and eglTerminate until thread release.
            // Retain every remaining handle so a later closeWithProof can retry the unbind.
            if (unbound) {
                try {
                    if (
                        ::rootSurface.isInitialized &&
                        rootSurface != EGL14.EGL_NO_SURFACE
                    ) {
                        if (EGL14.eglDestroySurface(display, rootSurface)) {
                            rootSurface = EGL14.EGL_NO_SURFACE
                            contextUsable = false
                        } else if (firstFailure == null) {
                            firstFailure = eglFailure("eglDestroySurface(root)")
                        }
                    }
                    if (
                        ::context.isInitialized &&
                        context != EGL14.EGL_NO_CONTEXT
                    ) {
                        if (EGL14.eglDestroyContext(display, context)) {
                            context = EGL14.EGL_NO_CONTEXT
                            contextUsable = false
                        } else if (firstFailure == null) {
                            firstFailure = eglFailure("eglDestroyContext")
                        }
                    }
                    if (EGL14.eglTerminate(display)) {
                        eglTerminated = true
                        contextUsable = false
                        outputs.clear()
                        orphanedOutputSurfaces.clear()
                        ingresses.forEach { it.textureRetired = true }
                        instrumentIngresses.forEach { it.textureRetired = true }
                    } else if (firstFailure == null) {
                        firstFailure = eglFailure("eglTerminate")
                    }
                } catch (problem: Throwable) {
                    if (firstFailure == null) firstFailure = problem
                }
            }
        } else if (!eglInitialized) {
            eglTerminated = true
        }

        // EGL termination retires GL objects, but Java producer endpoints still require release.
        if (eglTerminated) {
            ingresses.toList().forEach { ingress ->
                if (!beforeDeadline()) return@forEach
                try {
                    destroyIngress(ingress)
                } catch (problem: Throwable) {
                    if (firstFailure == null) firstFailure = problem
                }
            }
            instrumentIngresses.toList().forEach { ingress ->
                if (!beforeDeadline()) return@forEach
                try { destroyIngress(ingress) } catch (problem: Throwable) {
                    if (firstFailure == null) firstFailure = problem
                }
            }
        }
        val retired = eglTerminated &&
            (!eglInitialized || threadBinding.isReleased) &&
            outputs.isEmpty() &&
            orphanedOutputSurfaces.isEmpty() &&
            ingresses.isEmpty()
            && instrumentIngresses.isEmpty()
        if (retired) {
            initialized = false
            closed = true
        }
        return UltraEglDriverRetirementProof(retired, firstFailure)
    }

    private fun prepareFrame(size: PixelSize, background: UltraSceneBackground) {
        GLES20.glViewport(0, 0, size.widthPixels, size.heightPixels)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        val clear = eglClearColorFor(background)
        GLES20.glClearColor(clear.red, clear.green, clear.blue, clear.alpha)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Android Surface producers use premultiplied alpha.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        checkGl("prepare EGL output frame")
    }

    private fun drawExternalLayer(
        layer: com.shilapi.xcertplay.ultra.UltraSceneLayer,
        ingress: Ingress,
        viewport: PixelSize,
    ): Boolean {
        val geometry = UltraEglLayerGeometry.project(layer, viewport) ?: return false
        applyScissor(geometry.scissorPixels, viewport)
        val vertices = floatBufferOf(*geometry.normalizedTriangleStrip(viewport))
        val textureCoordinates = floatBufferOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ingress.textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glUniform1f(opacityLocation, layer.opacity.toFloat())
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, ingress.transform, 0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glVertexAttribPointer(
            textureCoordinateLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureCoordinates,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureCoordinateLocation)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        checkGl("draw external OES layer")
        return true
    }

    /** Top-down ARGB rows are sampled with an explicit V flip into the exact viewport. */
    private fun drawInstrumentLayer(
        layer: com.shilapi.xcertplay.ultra.UltraSceneLayer,
        ingress: InstrumentIngress,
        viewport: PixelSize,
    ) {
        require(layer.framePixels == UltraPixelRect(
            0, 0, viewport.widthPixels, viewport.heightPixels,
        ) && layer.clipPixels == null && layer.opacity == 1.0 &&
            layer.transformPixels.isIdentity) {
            "Dedicated instrument texture cannot be cropped, blended, or overlaid"
        }
        val vertices = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val coordinates = floatBufferOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        GLES20.glUseProgram(instrumentProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ingress.textureId)
        GLES20.glUniform1i(instrumentSamplerLocation, 0)
        GLES20.glUniform1f(instrumentOpacityLocation, 1f)
        GLES20.glUniformMatrix4fv(instrumentTextureMatrixLocation, 1, false,
            IDENTITY_TEXTURE_MATRIX, 0)
        GLES20.glEnableVertexAttribArray(instrumentPositionLocation)
        GLES20.glVertexAttribPointer(instrumentPositionLocation, 2, GLES20.GL_FLOAT, false,
            0, vertices)
        GLES20.glEnableVertexAttribArray(instrumentTextureCoordinateLocation)
        GLES20.glVertexAttribPointer(instrumentTextureCoordinateLocation, 2,
            GLES20.GL_FLOAT, false, 0, coordinates)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(instrumentPositionLocation)
        GLES20.glDisableVertexAttribArray(instrumentTextureCoordinateLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        checkGl("draw dedicated instrument texture")
    }

    private fun clearLayerRect(
        frame: UltraPixelRect,
        clip: UltraPixelRect?,
        viewport: PixelSize,
    ) {
        val clearRect = effectiveClip(frame, clip, viewport) ?: return
        applyScissor(clearRect, viewport)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        checkGl("clear punch-through layer")
    }

    private fun present(output: Output): Long? {
        var assignedTimestamp: Long? = null
        if (supportsPresentationTime) {
            assignedTimestamp = producerTimestamps.next()
            if (!EGLExt.eglPresentationTimeANDROID(
                    display, output.eglSurface, assignedTimestamp,
                )) {
                throwEgl("eglPresentationTimeANDROID", output.destination)
            }
        }
        val swapped = EGL14.eglSwapBuffers(display, output.eglSurface)
        if (!swapped) throwEgl("eglSwapBuffers", output.destination)
        return assignedTimestamp
    }

    private fun effectiveClip(
        frame: UltraPixelRect,
        requestedClip: UltraPixelRect?,
        viewport: PixelSize,
    ): UltraPixelRect? {
        val clip = requestedClip ?: frame
        val left = maxOf(0L, frame.xPixels.toLong(), clip.xPixels.toLong())
        val top = maxOf(0L, frame.yPixels.toLong(), clip.yPixels.toLong())
        val right = minOf(
            viewport.widthPixels.toLong(),
            frame.xPixels.toLong() + frame.widthPixels,
            clip.xPixels.toLong() + clip.widthPixels,
        )
        val bottom = minOf(
            viewport.heightPixels.toLong(),
            frame.yPixels.toLong() + frame.heightPixels,
            clip.yPixels.toLong() + clip.heightPixels,
        )
        if (right <= left || bottom <= top) return null
        return UltraPixelRect(
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt(),
        )
    }

    private fun applyScissor(rect: UltraPixelRect, viewport: PixelSize) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(
            rect.xPixels,
            viewport.heightPixels - rect.yPixels - rect.heightPixels,
            rect.widthPixels,
            rect.heightPixels,
        )
    }

    private fun checkOutputWindow(output: Output) {
        if (!output.nativeWindow.isValid) {
            throw UltraEglSurfaceLostException(
                output.destination,
                "Android output Surface became invalid for ${output.destination}",
            )
        }
    }

    private fun requireIngress(value: UltraEglDriverIngress): Ingress {
        val ingress = value as? Ingress
            ?: throw IllegalArgumentException("Ingress belongs to another EGL driver")
        check(ingress in ingresses) { "EGL ingress is no longer live" }
        return ingress
    }

    private fun requireOutput(value: UltraEglDriverOutput): Output {
        val output = value as? Output
            ?: throw IllegalArgumentException("Output belongs to another EGL driver")
        check(output in outputs) { "EGL output is no longer live" }
        return output
    }

    private fun querySurface(
        surface: EGLSurface,
        attribute: Int,
        destination: DisplayDestination,
    ): Int {
        val value = IntArray(1)
        if (!EGL14.eglQuerySurface(display, surface, attribute, value, 0)) {
            throwEgl("eglQuerySurface", destination)
        }
        return value[0]
    }

    private fun makeRootCurrent() = makeCurrent(rootSurface, null)

    private fun makeCurrent(surface: EGLSurface, destination: DisplayDestination?) {
        if (!contextUsable) throw UltraEglContextLostException("EGL context is unavailable")
        threadBinding.markBoundOrUnknown()
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throwEgl("eglMakeCurrent", destination)
        }
    }

    private fun unbindCurrentThread() {
        var makeCurrentFailure: Throwable? = null
        if (!threadBinding.release {
                val unbound = EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (unbound) {
                    true
                } else {
                    makeCurrentFailure = eglFailure("eglMakeCurrent(NO_CONTEXT)")
                    // eglReleaseThread also releases the current context and both surfaces.
                    // Its checked success can recover an otherwise ambiguous unbind.
                    val released = EGL14.eglReleaseThread()
                    if (!released) {
                        makeCurrentFailure?.addSuppressed(eglFailure("eglReleaseThread"))
                    }
                    released
                }
            }
        ) throw makeCurrentFailure ?: UltraEglDriverException("EGL thread unbind failed")
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        check(result != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
        val log = GLES20.glGetProgramInfoLog(result)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(result)
            throw UltraEglDriverException("Unable to link external texture program: $log")
        }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw UltraEglDriverException("Unable to compile external texture shader: $log")
        }
        return shader
    }

    private fun checkCreatedContext(operation: String) {
        if (context == EGL14.EGL_NO_CONTEXT) throwEgl(operation, null)
    }

    private fun checkCreatedSurface(
        surface: EGLSurface,
        destination: DisplayDestination?,
        operation: String,
    ) {
        if (surface == EGL14.EGL_NO_SURFACE) throwEgl(operation, destination)
    }

    private fun checkEgl(success: Boolean, operation: String) {
        if (!success) throwEgl(operation, null)
    }

    private fun checkEglAllowContextLoss(success: Boolean, operation: String) {
        if (success) return
        val error = EGL14.eglGetError()
        if (error == EGL14.EGL_CONTEXT_LOST) {
            contextUsable = false
            throw UltraEglContextLostException("$operation lost the EGL context")
        }
        throw UltraEglDriverException("$operation failed with EGL error 0x${error.toString(16)}")
    }

    private fun throwEgl(operation: String, destination: DisplayDestination?): Nothing {
        val error = EGL14.eglGetError()
        if (error == EGL14.EGL_CONTEXT_LOST) {
            contextUsable = false
            throw UltraEglContextLostException("$operation lost the EGL context")
        }
        if (
            destination != null &&
            (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW)
        ) {
            throw UltraEglSurfaceLostException(
                destination,
                "$operation lost the EGL output Surface (0x${error.toString(16)})",
            )
        }
        throw UltraEglDriverException("$operation failed with EGL error 0x${error.toString(16)}")
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            if (error == GL_CONTEXT_LOST) {
                contextUsable = false
                throw UltraEglContextLostException("$operation lost the GLES context")
            }
            throw UltraEglDriverException(
                "$operation failed with GLES error 0x${error.toString(16)}",
            )
        }
    }

    private fun checkReady() {
        checkThread()
        check(initialized && !closed) { "Android EGL driver is not open" }
        check(contextUsable) { "Android EGL context is unavailable" }
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) {
            "Android EGL driver accessed from a non-render thread"
        }
    }

    private fun eglFailure(operation: String): Throwable {
        val error = EGL14.eglGetError()
        return if (error == EGL14.EGL_CONTEXT_LOST) {
            contextUsable = false
            UltraEglContextLostException("$operation lost the EGL context")
        } else {
            UltraEglDriverException(
                "$operation failed with EGL error 0x${error.toString(16)}",
            )
        }
    }

    private fun floatBufferOf(vararg values: Float) =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private companion object {
        const val GL_CONTEXT_LOST = 0x0507
        val IDENTITY_TEXTURE_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val EXTERNAL_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float uOpacity;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = color * uOpacity;
            }
        """

        const val INSTRUMENT_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uOpacity;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord) * uOpacity;
            }
        """
    }
}

internal fun shouldSampleExternalIngress(ingress: UltraEglDriverIngress): Boolean =
    ingress.hasUsableFrame

/** A failed EGL unbind is never evidence that a current surface/context has retired. */
internal class EglThreadBindingRetirement {
    var isReleased: Boolean = false
        private set

    fun markBoundOrUnknown() {
        isReleased = false
    }

    fun release(unbind: () -> Boolean): Boolean {
        // Even if an earlier call succeeded, a failed new attempt leaves the current binding
        // ambiguous. A successful retry is required before native owners may be released.
        isReleased = false
        if (!unbind()) return false
        isReleased = true
        return true
    }
}

internal data class UltraEglClearColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
)

/** Direct normalized channel encoding; output Surface colorimetry is not proven by this path. */
internal fun eglClearColorFor(background: UltraSceneBackground): UltraEglClearColor = when (background) {
    UltraSceneBackground.Transparent -> UltraEglClearColor(0f, 0f, 0f, 0f)
    is UltraSceneBackground.OpaqueSrgb -> UltraEglClearColor(
        background.color.red.toFloat(),
        background.color.green.toFloat(),
        background.color.blue.toFloat(),
        1f,
    )
}
