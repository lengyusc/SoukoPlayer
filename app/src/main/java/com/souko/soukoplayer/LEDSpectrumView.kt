package com.souko.soukoplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt
import kotlin.math.abs
import kotlin.math.max

class LEDSpectrumView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var fftLeft: ByteArray = byteArrayOf()
    private var fftRight: ByteArray = byteArrayOf()
    private val paint: Paint = Paint().apply {
        isAntiAlias = true
    }

    private val numLEDs = 10
    private val ledSpacing = 5f

    // 平滑衰减用的缓存数组
    private var leftLevels = FloatArray(numLEDs)
    private var rightLevels = FloatArray(numLEDs)

    private val decayStep = 0.15f

    fun updateFftData(fftData: ByteArray) {
        if (fftData.isNotEmpty()) {
            val leftList = mutableListOf<Byte>()
            val rightList = mutableListOf<Byte>()
            for (i in fftData.indices step 2) {
                leftList.add(fftData[i])
                if (i + 1 < fftData.size) {
                    rightList.add(fftData[i + 1])
                }
            }
            fftLeft = leftList.toByteArray()
            fftRight = rightList.toByteArray()

            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (fftLeft.isEmpty() || fftRight.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // 1. 去掉 * 0.5f，让 10 个 LED 加上间距刚好吃满 View 的总宽度
        val ledWidth = (viewWidth - (ledSpacing * (numLEDs - 1))) / numLEDs

        // 2. StartX 设为 0，从最左侧开始绘制，彻底去除左右两边的留白框感
        val startX = 0f

        // 3. 高度与行间距优化，让上下两排指针更贴合、更有体量感
        val ledHeight = (viewHeight - ledSpacing * 3f) / 2f // 上下两排 + 3个间距
        val topY = ledSpacing
        val bottomY = topY + ledHeight + ledSpacing

        // 原始左右声道亮灯数计算
        var leftTarget = calculateSignalStrength(fftLeft, 60f, 300f) +
                calculateSignalStrength(fftLeft, 300f, 2000f) +
                calculateSignalStrength(fftLeft, 2000f, 15000f)

        var rightTarget = calculateSignalStrength(fftRight, 60f, 300f) +
                calculateSignalStrength(fftRight, 300f, 2000f) +
                calculateSignalStrength(fftRight, 2000f, 15000f)

        // 左右声道混合处理
        val mixRatio = 0.48f
        val mixedLeft = leftTarget * (1 - mixRatio) + rightTarget * mixRatio
        val mixedRight = rightTarget * (1 - mixRatio) + leftTarget * mixRatio
        leftTarget = mixedLeft.toInt().coerceIn(0, numLEDs)
        rightTarget = mixedRight.toInt().coerceIn(0, numLEDs)

        // 平滑衰减处理
        val leftActive = smoothUpdate(leftLevels, leftTarget)
        val rightActive = smoothUpdate(rightLevels, rightTarget)

        // 绘制上排：左声道 (L)
        drawLedRow(
            canvas,
            startX,
            topY,
            ledWidth,
            ledHeight,
            leftLevels
        )

        // 绘制下排：右声道 (R)
        drawLedRow(
            canvas,
            startX,
            bottomY,
            ledWidth,
            ledHeight,
            rightLevels
        )

        // 只有在灯还没完全熄灭（仍有衰减动画）时才请求下一帧
        if (leftActive || rightActive) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * 更新 LED 等级并返回当前声道是否还有亮的灯
     */
    private fun smoothUpdate(levels: FloatArray, target: Int): Boolean {
        var hasActiveLed = false
        for (i in 0 until numLEDs) {
            val targetValue = if (i < target) 1f else 0f
            if (targetValue > levels[i]) {
                levels[i] = targetValue
            } else if (targetValue < levels[i]) {
                levels[i] = max(0f, levels[i] - decayStep)
            }
            // 低于阈值直接归零，防止微小残余值
            if (levels[i] < 0.05f) {
                levels[i] = 0f
            } else {
                hasActiveLed = true
            }
        }
        return hasActiveLed
    }

    private fun drawLedRow(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        ledWidth: Float,
        ledHeight: Float,
        levels: FloatArray
    ) {
        for (col in 0 until numLEDs) {
            val ledOn = levels[col] > 0.01f
            paint.color = when {
                ledOn && col == numLEDs - 1 -> "#FF3B30".toColorInt() // 红色峰值告警灯
                ledOn -> "#34C759".toColorInt() // 亮绿色亮灯
                else -> "#26FFFFFF".toColorInt() // 未亮起时的暗格背景（15% 透明白，完美透出毛玻璃）
            }
            val left = startX + col * (ledWidth + ledSpacing)
            val right = left + ledWidth
            val bottom = startY + ledHeight
            canvas.drawRect(left, startY, right, bottom, paint)
        }
    }

    private fun calculateSignalStrength(fftData: ByteArray, lowFreq: Float, highFreq: Float): Int {
        if (fftData.isEmpty()) return 0

        // 1. 低切与高切：过滤掉 60Hz 以下和 14000Hz 以上的边缘无效/噪音频段
        if (highFreq < 60f || lowFreq > 14000f) return 0

        // 2. 将传入的频率区间严格裁切在 [60Hz, 14000Hz] 范围内
        val safeLowFreq = lowFreq.coerceAtLeast(60f)
        val safeHighFreq = highFreq.coerceAtMost(14000f)

        val samplingRate = 48000
        val fftSize = fftData.size
        val frequencyResolution = samplingRate / fftSize.toFloat()

        val indexLow = (safeLowFreq / frequencyResolution).toInt().coerceAtLeast(0)
        val indexHigh = (safeHighFreq / frequencyResolution).toInt().coerceAtMost(fftData.size - 1)

        if (indexHigh < indexLow) return 0

        var magnitudeSum = 0
        for (i in indexLow..indexHigh) {
            magnitudeSum += abs(fftData[i].toInt())
        }
        val count = indexHigh - indexLow + 1
        val magnitudeAverage = if (count > 0) magnitudeSum / count else 0

        // 3. 门限过滤：平均幅值过低直接判为静音（防底噪）
        if (magnitudeAverage < 3) return 0

        // 4. 精确区间判别（带高低切保护后的增益分配）
        val amplifier = when {
            safeHighFreq <= 300f -> 1.3
            safeLowFreq >= 300f && safeHighFreq <= 2000f -> 1.5
            safeLowFreq >= 2000f -> 1.1
            else -> 1.0
        }

        val magnitudeAmplified = magnitudeAverage.toDouble() * amplifier
        val normalized = (magnitudeAmplified / 128.0).coerceIn(0.0, 1.0)
        return (normalized * numLEDs).toInt().coerceIn(0, numLEDs)
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val state = SavedState(superState)
        state.leftData = fftLeft
        state.rightData = fftRight
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            fftLeft = state.leftData
            fftRight = state.rightData
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    fun clear() {
        for (i in leftLevels.indices) {
            leftLevels[i] = 0f
            rightLevels[i] = 0f
        }
        fftLeft = byteArrayOf()
        fftRight = byteArrayOf()
        invalidate()
    }

    private class SavedState : BaseSavedState {
        var leftData: ByteArray = byteArrayOf()
        var rightData: ByteArray = byteArrayOf()

        constructor(superState: Parcelable?) : super(superState)

        constructor(parcel: Parcel) : super(parcel) {
            leftData = parcel.createByteArray() ?: byteArrayOf()
            rightData = parcel.createByteArray() ?: byteArrayOf()
        }

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)
            dest.writeByteArray(leftData)
            dest.writeByteArray(rightData)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> =
                object : Parcelable.Creator<SavedState> {
                    override fun createFromParcel(parcel: Parcel): SavedState {
                        return SavedState(parcel)
                    }

                    override fun newArray(size: Int): Array<SavedState?> {
                        return arrayOfNulls(size)
                    }
                }
        }
    }
}