/*
 * SPDX-FileCopyrightText: 2026 Gallbladderz
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gallbladderz.openkick.features.player.components

import android.app.Activity
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.gallbladderz.openkick.R
import com.gallbladderz.openkick.core.ui.utils.formatTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val VOLUME_BOOST_MAX_PERCENT = 200
private const val FULLSCREEN_SWIPE_THRESHOLD_DP = 48
private const val SEEK_GESTURE_SECONDS_PER_SCREEN_WIDTH = 90f

private enum class GestureHud { None, Brightness, Volume, Seek }

/**
 * PiliNara 风格播放器手势（仅全屏）：
 * - 左 1/3 上下滑：亮度；右 1/3 上下滑：音量（100% 以上用播放器增益突破）；
 * - 中间上滑进全屏、下滑退出全屏。点按穿透到下面显示控制层。
 */
@Composable
fun PlayerGestureOverlay(
    player: Player?,
    isFullscreen: Boolean,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val density = LocalDensity.current
    val maxStreamVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    var hud by remember { mutableIntStateOf(0) } // GestureHud ordinal
    var hudPercent by remember { mutableFloatStateOf(0f) }
    var fullscreenDragAccum by remember { mutableFloatStateOf(0f) }
    var seekDragStartPosMs by remember { mutableFloatStateOf(-1f) }
    var seekAccumMs by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(hud) {
        if (hud != GestureHud.None.ordinal) {
            kotlinx.coroutines.delay(1200)
            hud = GestureHud.None.ordinal
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isFullscreen) {
                // 方案 A：全屏横向拖动快进/快退（VOD 才有 duration）。拖动中只更新预览 HUD，松手才真正 seekTo 一次。
                detectHorizontalDragGestures(
                    onDragStart = {
                        val duration = player?.duration ?: 0L
                        seekDragStartPosMs = if (duration > 0) (player?.currentPosition ?: 0L).toFloat() else -1f
                        seekAccumMs = 0f
                    },
                    onDragEnd = {
                        if (seekDragStartPosMs >= 0f && seekAccumMs != 0f) {
                            val target = (seekDragStartPosMs + seekAccumMs)
                                .roundToLong()
                                .coerceIn(0L, player?.duration ?: 0L)
                            player?.seekTo(target)
                        }
                        seekDragStartPosMs = -1f
                        seekAccumMs = 0f
                        hud = GestureHud.None.ordinal
                    },
                    onDragCancel = {
                        seekDragStartPosMs = -1f
                        seekAccumMs = 0f
                        hud = GestureHud.None.ordinal
                    }
                ) { change, dragAmount ->
                    change.consume()
                    if (seekDragStartPosMs >= 0f) {
                        seekAccumMs += dragAmount / size.width *
                            SEEK_GESTURE_SECONDS_PER_SCREEN_WIDTH * 1000f
                        hud = GestureHud.Seek.ordinal
                        hudPercent = seekAccumMs / 1000f
                    }
                }
            }
            .pointerInput(isFullscreen, maxStreamVolume) {
                val widthPx = size.width.toFloat()
                val thresholdPx = with(density) {
                    (FULLSCREEN_SWIPE_THRESHOLD_DP.dp.toPx())
                }
                detectVerticalDragGestures(
                    onDragStart = { fullscreenDragAccum = 0f },
                    onDragEnd = {
                        if (abs(fullscreenDragAccum) > thresholdPx) {
                            if (fullscreenDragAccum < 0 && !isFullscreen) onFullscreenToggle()
                            else if (fullscreenDragAccum > 0 && isFullscreen) onFullscreenToggle()
                        }
                        fullscreenDragAccum = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val x = change.position.x
                    val dy = dragAmount
                    when {
                        x < widthPx / 3f -> {
                            val window = (view.context as? Activity)?.window ?: return@detectVerticalDragGestures
                            val current = window.attributes.screenBrightness
                            val base = if (current == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) 0.5f else current
                            val next = (base - dy / size.height).coerceIn(0f, 1f)
                            window.attributes = window.attributes.apply {
                                screenBrightness = next
                            }
                            hud = GestureHud.Brightness.ordinal
                            hudPercent = next * 100f
                        }
                        x > widthPx * 2f / 3f -> {
                            val percent = ((hudPercent.takeIf { hud == GestureHud.Volume.ordinal } ?: 100f)
                                - dy / size.height * 200f)
                                .coerceIn(0f, VOLUME_BOOST_MAX_PERCENT.toFloat())
                            applyVolume(audioManager, player, maxStreamVolume, percent)
                            hud = GestureHud.Volume.ordinal
                            hudPercent = percent
                        }
                        else -> fullscreenDragAccum += dy
                    }
                }
            }
    ) {
        if (hud != GestureHud.None.ordinal) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (hud) {
                        GestureHud.Brightness.ordinal -> Icons.Default.BrightnessLow
                        GestureHud.Seek.ordinal -> Icons.Default.FastForward
                        else -> Icons.Default.VolumeUp
                    },
                    contentDescription = null,
                    tint = Color.White
                )
                Text(
                    text = when (hud) {
                        GestureHud.Brightness.ordinal ->
                            stringResource(R.string.gesture_brightness_percent, hudPercent.roundToInt())
                        GestureHud.Seek.ordinal -> {
                            val targetMs = ((seekDragStartPosMs + seekAccumMs)
                                .coerceIn(0f, (player?.duration ?: 0L).toFloat())).roundToInt()
                            stringResource(
                                R.string.gesture_seek_target,
                                "%+ds".format(hudPercent.roundToInt()),
                                formatTime(targetMs.toLong())
                            )
                        }
                        else ->
                            stringResource(R.string.gesture_volume_percent, hudPercent.roundToInt())
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** ≤100% 走系统音量；>100% 系统音量拉满 + 播放器线性增益突破（ExoPlayer AudioTrack gain） */
private fun applyVolume(
    audioManager: AudioManager,
    player: Player?,
    maxStreamVolume: Int,
    percent: Float
) {
    val streamPercent = (percent.coerceAtMost(100f) / 100f * maxStreamVolume).roundToInt()
        .coerceIn(0, maxStreamVolume)
    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != streamPercent) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamPercent, 0)
    }
    player?.volume = if (percent > 100f) percent / 100f else 1f
}
