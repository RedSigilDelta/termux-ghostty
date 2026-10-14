package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import androidx.compose.ui.geometry.Rect
import com.termux.terminal.compose.TerminalBlurRegion
import kotlin.math.max
import kotlin.math.min

private const val BlurDownsampleFactor = 4
private const val BlurPasses = 2
private const val BlurMinEdgePx = 2
private const val BlurVertexCount = 4

/** Whole surface pixels for one backdrop region; [yGl] is measured from the bottom edge as GL expects. */
internal data class BlurPixelRect(
    val x: Int,
    val yGl: Int,
    val width: Int,
    val height: Int,
    val cornerRadiusPx: Float
)

/**
 * Clamps [bounds] to the surface and converts it to GL pixel coordinates.
 *
 * Returns null when the visible part is too small to blur.
 */
internal fun blurRegionPixels(
    bounds: Rect,
    cornerRadius: Float,
    surfaceWidth: Int,
    surfaceHeight: Int
): BlurPixelRect? {
    val maxX = surfaceWidth.toFloat()
    val maxY = surfaceHeight.toFloat()
    val left = bounds.left.coerceIn(0f, maxX)
    val top = bounds.top.coerceIn(0f, maxY)
    val right = bounds.right.coerceIn(0f, maxX)
    val bottom = bounds.bottom.coerceIn(0f, maxY)
    val width = (right - left).toInt()
    val height = (bottom - top).toInt()
    if (width < BlurMinEdgePx || height < BlurMinEdgePx) return null
    return BlurPixelRect(
        x = left.toInt(),
        yGl = surfaceHeight - bottom.toInt(),
        width = width,
        height = height,
        cornerRadiusPx = cornerRadius.coerceIn(0f, min(width, height) / 2f)
    )
}

/**
 * Blurs the rendered backdrop under rounded regions as the last pass of a frame.
 *
 * Each region is copied from the default framebuffer, downsampled into a ping-pong pair, blurred
 * separably [BlurPasses] times and composited through a rounded-rect mask with premultiplied alpha.
 * Programs and targets are created on first use and released on the GL thread with the renderer
 * resources. A failure disables the pass for this surface and reports once instead of failing frames.
 */
internal class GlesBackdropBlur(
    private val reportError: (stage: String, message: String) -> Unit
) {
    private var programs: GlesBackdropPrograms? = null
    private var disabled = false
    private val copy = GlesBackdropTarget(hasFramebuffer = false)
    private val targetA = GlesBackdropTarget(hasFramebuffer = true)
    private val targetB = GlesBackdropTarget(hasFramebuffer = true)

    /** Blurs each region in place; the default framebuffer must already hold the complete frame. */
    fun draw(regions: List<TerminalBlurRegion>, surfaceWidth: Int, surfaceHeight: Int) {
        if (disabled || regions.isEmpty()) return
        val gl = programs ?: createPrograms() ?: return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindVertexArray(gl.vertexArray)
        try {
            for (region in regions) {
                val rect = blurRegionPixels(
                    bounds = region.bounds,
                    cornerRadius = region.cornerRadiusPx,
                    surfaceWidth = surfaceWidth,
                    surfaceHeight = surfaceHeight
                ) ?: continue
                blurRegion(gl, rect)
            }
        } catch (error: GlesRendererException) {
            disable("backdrop-blur", error.message)
        } finally {
            restoreDefaultState(surfaceWidth, surfaceHeight)
        }
    }

    fun release() {
        programs?.release()
        programs = null
        copy.release()
        targetA.release()
        targetB.release()
    }

    private fun createPrograms(): GlesBackdropPrograms? =
        try {
            GlesBackdropPrograms.create().also { programs = it }
        } catch (error: GlesRendererException) {
            disable("backdrop-blur-program", error.message)
            null
        }

    private fun disable(stage: String, message: String?) {
        disabled = true
        reportError(stage, message ?: "GLES backdrop blur unavailable")
    }

    private fun blurRegion(gl: GlesBackdropPrograms, rect: BlurPixelRect) {
        copyBackdrop(rect)
        val downWidth = max(1, rect.width / BlurDownsampleFactor)
        val downHeight = max(1, rect.height / BlurDownsampleFactor)
        targetA.ensureSize(downWidth, downHeight)
        targetB.ensureSize(downWidth, downHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        // The copy may be larger than the region, so the down-sample reads only its used part.
        renderTo(targetA, downWidth, downHeight)
        blurPass(
            gl.blur,
            copy.textureId,
            stepX = 0f,
            stepY = 0f,
            uvScaleX = rect.width.toFloat() / copy.width,
            uvScaleY = rect.height.toFloat() / copy.height
        )
        repeat(BlurPasses) {
            renderTo(targetB, downWidth, downHeight)
            blurPass(gl.blur, targetA.textureId, stepX = 1f / downWidth, stepY = 0f, uvScaleX = 1f, uvScaleY = 1f)
            renderTo(targetA, downWidth, downHeight)
            blurPass(gl.blur, targetB.textureId, stepX = 0f, stepY = 1f / downHeight, uvScaleX = 1f, uvScaleY = 1f)
        }
        compositePass(gl.composite, rect)
    }

    private fun copyBackdrop(rect: BlurPixelRect) {
        // Grow-only, so a steady region reuses the texture; its used part is selected by uUvScale.
        copy.ensureSize(max(copy.width, rect.width), max(copy.height, rect.height))
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, copy.textureId)
        GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, rect.x, rect.yGl, rect.width, rect.height)
    }

    private fun renderTo(target: GlesBackdropTarget, width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebufferId)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glScissor(0, 0, width, height)
    }

    private fun blurPass(
        program: GlesBackdropProgram,
        source: Int,
        stepX: Float,
        stepY: Float,
        uvScaleX: Float,
        uvScaleY: Float
    ) {
        program.use(source)
        program.setVec2("uStep", stepX, stepY)
        program.setVec2("uUvScale", uvScaleX, uvScaleY)
        drawQuad()
    }

    private fun compositePass(program: GlesBackdropProgram, rect: BlurPixelRect) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(rect.x, rect.yGl, rect.width, rect.height)
        GLES30.glScissor(rect.x, rect.yGl, rect.width, rect.height)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.use(targetA.textureId)
        program.setVec2("uSize", rect.width.toFloat(), rect.height.toFloat())
        program.setFloat("uRadius", rect.cornerRadiusPx)
        drawQuad()
    }

    private fun drawQuad() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, BlurVertexCount)
    }

    private fun restoreDefaultState(surfaceWidth: Int, surfaceHeight: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glScissor(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindVertexArray(0)
    }
}

/** RGB8 color texture for one blur stage; [hasFramebuffer] adds the framebuffer that renders into it. */
private class GlesBackdropTarget(private val hasFramebuffer: Boolean) {
    var textureId = 0
        private set
    var framebufferId = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    /** Reallocates only when the requested size differs from the current allocation. */
    fun ensureSize(requiredWidth: Int, requiredHeight: Int) {
        if (textureId != 0 && width == requiredWidth && height == requiredHeight) return
        release()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        if (textureId == 0) throw GlesResourceException("backdrop blur texture has id 0")
        width = requiredWidth
        height = requiredHeight
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGB8,
            requiredWidth,
            requiredHeight,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        if (hasFramebuffer) attachFramebuffer()
    }

    fun release() {
        if (framebufferId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        framebufferId = 0
        textureId = 0
        width = 0
        height = 0
    }

    private fun attachFramebuffer() {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebufferId = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw GlesResourceException("backdrop blur framebuffer is incomplete: 0x${status.toString(16)}")
        }
    }
}

/** Linked backdrop program; uniform locations are resolved once when the program is created. */
private class GlesBackdropProgram private constructor(
    private val programId: Int,
    private val uniforms: Map<String, Int>
) {
    /** Makes the program current and samples [textureId] from texture unit 0. */
    fun use(textureId: Int) {
        GLES30.glUseProgram(programId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uniform("uTex"), 0)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        GLES30.glUniform2f(uniform(name), x, y)
    }

    fun setFloat(name: String, value: Float) {
        GLES30.glUniform1f(uniform(name), value)
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    private fun uniform(name: String): Int = uniforms.getValue(name)

    companion object {
        @Suppress("ThrowsCount")
        fun create(tag: String, fragmentSource: String, uniformNames: List<String>): GlesBackdropProgram {
            val vertexShader = compile("$tag-vertex", GLES30.GL_VERTEX_SHADER, GlesBackdropShaderSources.VERTEX)
            val fragmentShader = try {
                compile("$tag-fragment", GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            } catch (error: GlesProgramException) {
                GLES30.glDeleteShader(vertexShader)
                throw error
            }
            val program = GLES30.glCreateProgram()
            try {
                if (program == 0) throw GlesProgramException("glCreateProgram returned 0")
                GLES30.glAttachShader(program, vertexShader)
                GLES30.glAttachShader(program, fragmentShader)
                GLES30.glLinkProgram(program)
                val status = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    throw GlesProgramException("$tag link failed: ${GLES30.glGetProgramInfoLog(program)}")
                }
                val uniforms = uniformNames.associateWith { name ->
                    val location = GLES30.glGetUniformLocation(program, name)
                    if (location < 0) throw GlesProgramException("GLES uniform is incomplete: $name")
                    location
                }
                return GlesBackdropProgram(program, uniforms)
            } catch (error: GlesProgramException) {
                GLES30.glDeleteProgram(program)
                throw error
            } finally {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
            }
        }

        private fun compile(tag: String, type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw GlesProgramException("glCreateShader returned 0")
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw GlesProgramException("$tag shader compile failed: $log")
            }
            return shader
        }
    }
}

/** Blur and composite programs plus the empty vertex array the quad passes draw with. */
private class GlesBackdropPrograms private constructor(
    val blur: GlesBackdropProgram,
    val composite: GlesBackdropProgram,
    val vertexArray: Int
) {
    fun release() {
        blur.release()
        composite.release()
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
    }

    companion object {
        fun create(): GlesBackdropPrograms {
            val blur = GlesBackdropProgram.create(
                tag = "backdrop-blur",
                fragmentSource = GlesBackdropShaderSources.BLUR_FRAGMENT,
                uniformNames = listOf("uTex", "uStep", "uUvScale")
            )
            val composite = try {
                GlesBackdropProgram.create(
                    tag = "backdrop-composite",
                    fragmentSource = GlesBackdropShaderSources.COMPOSITE_FRAGMENT,
                    uniformNames = listOf("uTex", "uSize", "uRadius")
                )
            } catch (error: GlesProgramException) {
                blur.release()
                throw error
            }
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            return GlesBackdropPrograms(blur, composite, ids[0])
        }
    }
}

internal object GlesBackdropShaderSources {
    val VERTEX = """
        #version 300 es
        precision highp float;

        out vec2 vUv;

        const vec2 P[4] = vec2[](
            vec2(0.0, 0.0),
            vec2(1.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 1.0)
        );

        void main() {
            vec2 p = P[gl_VertexID];
            vUv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    /** 9-tap Gaussian via linear sampling; uStep = (0, 0) is a plain linear copy. */
    val BLUR_FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform sampler2D uTex;
        uniform vec2 uStep;
        uniform vec2 uUvScale;

        in vec2 vUv;
        out vec4 outColor;

        void main() {
            vec2 uv = vUv * uUvScale;
            vec2 o1 = uStep * 1.3846153846;
            vec2 o2 = uStep * 3.2307692308;
            vec4 c = texture(uTex, uv) * 0.2270270270;
            c += (texture(uTex, uv + o1) + texture(uTex, uv - o1)) * 0.3162162162;
            c += (texture(uTex, uv + o2) + texture(uTex, uv - o2)) * 0.0702702703;
            outColor = vec4(c.rgb, 1.0);
        }
    """.trimIndent()

    /** Rounded-rect mask with premultiplied output for GL_ONE, GL_ONE_MINUS_SRC_ALPHA. */
    val COMPOSITE_FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform sampler2D uTex;
        uniform vec2 uSize;
        uniform float uRadius;

        in vec2 vUv;
        out vec4 outColor;

        void main() {
            vec2 p = vUv * uSize;
            vec2 q = abs(p - uSize * 0.5) - (uSize * 0.5 - vec2(uRadius));
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
            float a = clamp(0.5 - d, 0.0, 1.0);
            outColor = vec4(texture(uTex, vUv).rgb * a, a);
        }
    """.trimIndent()
}
