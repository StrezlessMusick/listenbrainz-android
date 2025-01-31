package org.listenbrainz.android.presentation.features.brainzplayer.ui.components

/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.DrawModifier
import androidx.compose.ui.focus.FocusEventModifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.flow.collectLatest
import org.listenbrainz.android.presentation.features.brainzplayer.ui.components.MarqueeAnimationMode.Companion.Immediately
import org.listenbrainz.android.presentation.features.brainzplayer.ui.components.MarqueeAnimationMode.Companion.WhileFocused

const val DefaultMarqueeIterations: Int = 3
const val DefaultMarqueeDelayMillis: Int = 1_200
val DefaultMarqueeSpacing: MarqueeSpacing = MarqueeSpacing.fractionOfContainer(1f / 3f)
val DefaultMarqueeVelocity: Dp = 30.dp
/**
 * Applies an animated marquee effect to the modified content if it's too wide to fit in the
 * available space. This modifier has no effect if the content fits in the max constraints. The
 * content will be measured with unbounded width.
 *
 * When the animation is running, it will restart from the initial state any time:
 *  - any of the parameters to this modifier change, or
 *  - the content or container size change.
 *
 * The animation only affects the drawing of the content, not its position. The offset returned by
 * the [LayoutCoordinates] of anything inside the marquee is undefined relative to anything outside
 * the marquee, and may not match its drawn position on screen. This modifier also does not
 * currently support content that accepts position-based input such as pointer events.
 *
 *
 * @param iterations The number of times to repeat the animation. `Int.MAX_VALUE` will repeat
 * forever, and 0 will disable animation.
 * @param animationMode Whether the marquee should start animating [Immediately] or only
 * [WhileFocused].
 * @param delayMillis The duration to wait before starting each subsequent iteration, in millis.
 * @param initialDelayMillis The duration to wait before starting the first iteration of the
 * animation, in millis. By default, there will be no initial delay if [animationMode] is
 * [Immediately], otherwise the initial delay will be [delayMillis].
 * @param spacing A [MarqueeSpacing] that specifies how much space to leave at the end of the
 * content before showing the beginning again.
 * @param velocity The speed of the animation in dps / second.
 */

fun Modifier.basicMarquee(
    iterations: Int = DefaultMarqueeIterations,
    animationMode: MarqueeAnimationMode = Immediately,
    delayMillis: Int = DefaultMarqueeDelayMillis,
    initialDelayMillis: Int = if (animationMode == Immediately) delayMillis else 0,
    spacing: MarqueeSpacing = DefaultMarqueeSpacing,
    velocity: Dp = DefaultMarqueeVelocity
): Modifier = composed(
    inspectorInfo = debugInspectorInfo {
        name = "basicMarquee"
        properties["iterations"] = iterations
        properties["animationMode"] = animationMode
        properties["delayMillis"] = delayMillis
        properties["initialDelayMillis"] = initialDelayMillis
        properties["spacing"] = spacing
        properties["velocity"] = velocity
    }
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val modifier = remember(
        iterations,
        delayMillis,
        initialDelayMillis,
        velocity,
        spacing,
        animationMode,
        density,
        layoutDirection,
    ) {
        MarqueeModifier(
            iterations = iterations,
            delayMillis = delayMillis,
            initialDelayMillis = initialDelayMillis,
            velocity = velocity * if (layoutDirection == Ltr) 1f else -1f,
            spacing = spacing,
            animationMode = animationMode,
            density = density
        )
    }

    LaunchedEffect(modifier) {
        modifier.runAnimation()
    }

    return@composed modifier
}

private class MarqueeModifier(
    private val iterations: Int,
    private val delayMillis: Int,
    private val initialDelayMillis: Int,
    private val velocity: Dp,
    private val spacing: MarqueeSpacing,
    private val animationMode: MarqueeAnimationMode,
    private val density: Density,
) : Modifier.Element, LayoutModifier, DrawModifier, FocusEventModifier {

    private var contentWidth by mutableStateOf(0)
    private var containerWidth by mutableStateOf(0)
    private var hasFocus by mutableStateOf(false)
    private val offset = Animatable(0f)
    private val direction = sign(velocity.value)
    private val spacingPx by derivedStateOf {
        with(spacing) {
            density.calculateSpacing(contentWidth, containerWidth)
        }
    }
    private val firstCopyVisible by derivedStateOf {
        when (direction) {
            1f -> offset.value < contentWidth
            else -> offset.value < containerWidth
        }
    }
    private val secondCopyVisible by derivedStateOf {
        when (direction) {
            1f -> offset.value > (contentWidth + spacingPx) - containerWidth
            else -> offset.value > spacingPx
        }
    }
    private val secondCopyOffset: Float by derivedStateOf {
        when (direction) {
            1f -> contentWidth + spacingPx
            else -> -contentWidth - spacingPx
        }.toFloat()
    }

    private val contentWidthPlusSpacing: Float?
        get() {
            // Don't animate if content fits. (Because coroutines, the int will get boxed anyway.)
            if (contentWidth <= containerWidth) return null
            if (animationMode == WhileFocused && !hasFocus) return null
            return (contentWidth + spacingPx).toFloat()
        }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val childConstraints = constraints.copy(maxWidth = Constraints.Infinity)
        val placeable = measurable.measure(childConstraints)
        containerWidth = constraints.constrainWidth(placeable.width)
        contentWidth = placeable.width
        return layout(containerWidth, placeable.height) {
            // Placing the marquee content in a layer means we don't invalidate the parent draw
            // scope on every animation frame.
            placeable.placeWithLayer(x = (-offset.value * direction).roundToInt(), y = 0)
        }
    }

    override fun ContentDrawScope.draw() {
        val clipOffset = offset.value * direction
        clipRect(left = clipOffset, right = clipOffset + containerWidth) {
            if (firstCopyVisible) {
                this@draw.drawContent()
            }
            if (secondCopyVisible) {
                translate(left = secondCopyOffset) {
                    this@draw.drawContent()
                }
            }
        }
    }

    override fun onFocusEvent(focusState: FocusState) {
        hasFocus = focusState.hasFocus
    }

    suspend fun runAnimation() {
        if (iterations <= 0) {
            // No animation.
            return
        }

        snapshotFlow { contentWidthPlusSpacing }.collectLatest { contentWithSpacingWidth ->
            // Don't animate when the content fits.
            if (contentWithSpacingWidth == null) return@collectLatest

            val spec = createMarqueeAnimationSpec(
                iterations,
                contentWithSpacingWidth,
                initialDelayMillis,
                delayMillis,
                velocity,
                density
            )

            offset.snapTo(0f)
            try {
                offset.animateTo(contentWithSpacingWidth, spec)
            } finally {
                offset.snapTo(0f)
            }
        }
    }
}

private fun createMarqueeAnimationSpec(
    iterations: Int,
    targetValue: Float,
    initialDelayMillis: Int,
    delayMillis: Int,
    velocity: Dp,
    density: Density
): AnimationSpec<Float> {
    val pxPerSec = with(density) { velocity.toPx() }
    val singleSpec = velocityBasedTween(
        velocity = pxPerSec.absoluteValue,
        targetValue = targetValue,
        delayMillis = delayMillis
    )
    // Need to cancel out the non-initial delay.
    val startOffset = StartOffset(-delayMillis + initialDelayMillis)
    return if (iterations == Int.MAX_VALUE) {
        infiniteRepeatable(singleSpec, initialStartOffset = startOffset)
    } else {
        repeatable(iterations, singleSpec, initialStartOffset = startOffset)
    }
}

/**
 * Calculates a float [TweenSpec] that moves at a constant [velocity] for an animation from 0 to
 * [targetValue].
 *
 * @param velocity Speed of animation in px / sec.
 */
private fun velocityBasedTween(
    velocity: Float,
    targetValue: Float,
    delayMillis: Int
): TweenSpec<Float> {
    val pxPerMilli = velocity / 1000f
    return tween(
        durationMillis = ceil(targetValue / pxPerMilli).toInt(),
        easing = LinearEasing,
        delayMillis = delayMillis
    )
}

/** Specifies when the [basicMarquee] animation runs. */
@JvmInline
value class MarqueeAnimationMode private constructor(private val value: Int) {

    override fun toString(): String = when (this) {
        Immediately -> "Immediately"
        WhileFocused -> "WhileFocused"
        else -> error("invalid value: $value")
    }

    companion object {
        /** Starts animating immediately, irrespective of focus state. */
        @Suppress("OPT_IN_MARKER_ON_WRONG_TARGET")
        val Immediately = MarqueeAnimationMode(0)

        /**
         * Only animates while the marquee has focus. This includes when a focusable child in the
         * marquee's content is focused.
         */
        @Suppress("OPT_IN_MARKER_ON_WRONG_TARGET")
        val WhileFocused = MarqueeAnimationMode(1)
    }
}

/**
 * A [MarqueeSpacing] with a fixed size.
 */
fun MarqueeSpacing(spacing: Dp): MarqueeSpacing = MarqueeSpacing { _, _ -> spacing.roundToPx() }

/**
 * Defines a [calculateSpacing] method that determines the space after the end of [basicMarquee]
 * content before drawing the content again.
 */
fun interface MarqueeSpacing {
    /**
     * Calculates the space after the end of [basicMarquee] content before drawing the content
     * again.
     *
     * This is a restartable method: any state used to calculate the result will cause the spacing
     * to be re-calculated when it changes.
     *
     * @param contentWidth The width of the content inside the marquee, in pixels. Will always be
     * larger than [containerWidth].
     * @param containerWidth The width of the marquee itself, in pixels. Will always be smaller than
     * [contentWidth].
     * @return The space in pixels between the end of the content and the beginning of the content
     * when wrapping.
     */
    fun Density.calculateSpacing(
        contentWidth: Int,
        containerWidth: Int
    ): Int

    companion object {
        /**
         * A [MarqueeSpacing] that is a fraction of the container's width.
         */
        fun fractionOfContainer(fraction: Float): MarqueeSpacing = MarqueeSpacing { _, width ->
            (fraction * width).roundToInt()
        }
    }
}