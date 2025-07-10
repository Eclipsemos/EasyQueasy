package com.leanrada.easyqueasy.ui

import AppDataOuterClass.OverlayColor
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils.clamp
import com.leanrada.easyqueasy.AppDataClient
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// 数学常数和物理参数
const val floatE = Math.E.toFloat()  // 自然常数e
val hexRatio = 2f * sqrt(3f) / 3f    // 六边形网格的宽高比系数 ≈ 1.155
val oneMeter = 1587f.dp              // 一米对应的像素密度(基于标准DPI)
val eyeZ = oneMeter.times(0.6f)      // 观察者眼睛到屏幕的距离(60cm)
val screenDepth = oneMeter.times(0.2f) // 3D空间的深度范围(20cm)
const val startEffectDurationMillis = 700L // 启动动画持续时间(毫秒)

/**
 * 预览模式枚举
 * 用于在设置界面预览不同的效果
 */
enum class PreviewMode {
    NONE,   // 正常模式
    SIZE,   // 尺寸预览模式
    SPEED   // 速度预览模式
}

/**
 * 核心覆盖层组件
 * 实现基于运动传感器的3D粒子系统，用于缓解运动病症状
 * 
 * @param appData 应用数据客户端，用于获取用户设置
 * @param peripherySize 外围区域大小，控制粒子在屏幕边缘的衰减范围
 * @param previewMode 预览模式，用于设置界面的效果预览
 */
@Composable
fun Overlay(
    appData: AppDataClient,
    peripherySize: Dp = 180.dp,
    previewMode: PreviewMode = PreviewMode.NONE,
) {
    val configuration = LocalConfiguration.current
    val isPreview = previewMode != PreviewMode.NONE

    // 从用户设置中获取覆盖层配置参数
    val overlayColor by appData.rememberOverlayColor()        // 覆盖层颜色方案
    val overlayAreaSize by appData.rememberOverlayAreaSize()  // 覆盖层区域大小(0-1)
    val overlaySpeed by appData.rememberOverlaySpeed()        // 覆盖层运动速度(0-1)
    
    // 将速度设置映射到实际的速度因子(0.6倍到2.7倍)
    val speedFactor by remember { derivedStateOf { lerp(0.6f, 2.7f, overlaySpeed) } }

    // 时间管理：记录启动时间，用于启动动画和时间计算
    val startTimeMillis by remember {
        object : State<Long> {
            override val value: Long = System.currentTimeMillis()
        }
    }
    var currentTimeMillis by remember { mutableLongStateOf(startTimeMillis) }
    var timer by remember { mutableIntStateOf(0) }

    // 3D空间中的物理状态变量
    val position = remember { mutableStateListOf(0f, 0f, 0f) }     // 当前位置 [x, y, z]
    val lastPosition = remember { mutableStateListOf(0f, 0f, 0f) } // 上一帧位置，用于计算运动轨迹
    var effectIntensity by remember { mutableFloatStateOf(0f) }     // 效果强度(0-1)，基于运动速度动态计算

    val sensorManager = ContextCompat.getSystemService(LocalContext.current, SensorManager::class.java)

    // 传感器监听和物理运动计算的核心逻辑
    DisposableEffect(sensorManager, isPreview, timer) {
        var accelerationListener: SensorEventListener? = null

        if (isPreview) {
            // 预览模式：使用模拟的运动数据
            val now = System.currentTimeMillis()
            val dt = now - currentTimeMillis
            currentTimeMillis = now
            position[0] += 0.3f * speedFactor * dt  // 模拟X轴匀速运动
            timer++
        } else {
            // 实际模式：使用设备传感器数据
            accelerationListener = object : SensorEventListener {
                var lastEventTimeNanos = 0L        // 上次传感器事件的时间戳
                var lowPass: Array<Float>? = null   // 第一级低通滤波器(快速响应)
                var lowerPass: Array<Float>? = null // 第二级低通滤波器(慢速响应，用于重力基线)
                var velocity = arrayOf(0f, 0f, 0f) // 3D速度向量

                override fun onSensorChanged(event: SensorEvent?) {
                    if (event == null) return
                    if (lastEventTimeNanos > 0) {
                        // 计算时间间隔(转换为秒)
                        val dt = (event.timestamp - lastEventTimeNanos) * 1e-9f

                        // === 双重低通滤波算法 ===
                        // 用于分离真实运动加速度和重力/低频噪声
                        var lowPass1 = lowPass
                        var lowerPass1 = lowerPass
                        if (lowPass1 != null && lowerPass1 != null) {
                            // 第一级滤波：快速响应，去除高频噪声
                            val tf = 1f - 0.01f.pow(dt)  // 时间常数约100ms
                            lowPass1[0] += (event.values[0] - lowPass1[0]) * tf
                            lowPass1[1] += (event.values[1] - lowPass1[1]) * tf
                            lowPass1[2] += (event.values[2] - lowPass1[2]) * tf
                            
                            // 第二级滤波：慢速响应，提取重力基线
                            val tf2 = 1f - 0.02f.pow(dt) // 时间常数约50ms
                            lowerPass1[0] += (event.values[0] - lowerPass1[0]) * tf2
                            lowerPass1[1] += (event.values[1] - lowerPass1[1]) * tf2
                            lowerPass1[2] += (event.values[2] - lowerPass1[2]) * tf2
                        } else {
                            // 初始化滤波器
                            lowPass1 = event.values.toTypedArray().clone()
                            lowerPass1 = event.values.toTypedArray().clone()
                            lowPass = lowPass1
                            lowerPass = lowerPass1
                        }

                        // === 坐标轴映射 ===
                        // 根据屏幕方向映射传感器轴到显示轴
                        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                        val indexX = if (isPortrait) 0 else 1  // 横屏时X轴对应传感器Y轴
                        val indexY = if (isPortrait) 1 else 0  // 横屏时Y轴对应传感器X轴

                        // === 加速度计算 ===
                        // 通过双重滤波差值获得净运动加速度
                        val accelerationX = (event.values[indexX] - lowerPass1[indexX]) + (event.values[indexX] - lowPass1[indexX]) * 0.1f
                        val accelerationY = (event.values[indexY] - lowerPass1[indexY]) + (event.values[indexY] - lowPass1[indexY]) * 0.1f
                        val accelerationZ = (event.values[2] - lowerPass1[2]) + (event.values[2] - lowPass1[2]) * 0.1f

                        // === 物理积分：加速度 → 速度 → 位置 ===
                        velocity[0] -= accelerationX * dt  // X轴反向，符合视觉直觉
                        velocity[1] += accelerationY * dt  // Y轴正向
                        velocity[2] += accelerationZ * dt  // Z轴正向(远离/靠近屏幕)

                        // 保存上一帧位置，用于计算运动轨迹
                        lastPosition[0] = position[0]
                        lastPosition[1] = position[1]
                        lastPosition[2] = position[2]

                        // 更新当前位置
                        position[0] += velocity[0] * dt
                        position[1] += velocity[1] * dt
                        position[2] += velocity[2] * dt

                        // === 运动强度计算 ===
                        val accel2D = hypot(accelerationX, accelerationY)  // 2D平面加速度幅值
                        val speed2D = hypot(velocity[0], velocity[1])       // 2D平面速度幅值

                        // === 自适应摩擦力系统 ===
                        // 摩擦力随加速度动态调整：高加速度时摩擦小(保持灵敏)，低加速度时摩擦大(快速衰减)
                        val friction = 1 / (1 + dt * (8f + 16f / (1f + accel2D * 60f)))
                        velocity[0] *= friction
                        velocity[1] *= friction
                        velocity[2] *= friction

                        // === 视觉效果强度计算 ===
                        // 基于运动速度动态调整粒子系统的可见性
                        effectIntensity *= (1f - 0.2f.pow(72f * dt))  // 自然衰减(指数衰减)
                        // 速度驱动的强度增强：当速度超过阈值(0.09)时激活效果
                        effectIntensity = lerp(effectIntensity, 1f, 1f - (1f - sigmoid01((speed2D - 0.09f) * 60f)).pow(72f * dt))
                        currentTimeMillis = System.currentTimeMillis()
                    }
                    lastEventTimeNanos = event.timestamp
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            // 注册线性加速度传感器监听器，使用最高采样频率
            sensorManager?.registerListener(
                accelerationListener,
                sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                SensorManager.SENSOR_DELAY_FASTEST
            )
        }

        onDispose {
            // 清理传感器监听器
            if (accelerationListener != null) {
                sensorManager?.unregisterListener(accelerationListener)
            }
        }
    }

    // === 3D粒子系统渲染 ===
    Canvas(modifier = Modifier.fillMaxSize()) {
        // 根据预览模式确定最终效果强度
        val finalEffectIntensity = when (previewMode) {
            PreviewMode.NONE -> effectIntensity  // 使用实际计算的强度
            else -> 1f                           // 预览模式始终全强度显示
        }

        // === 启动动画计算 ===
        val startupEffectProgress = (currentTimeMillis - startTimeMillis).toFloat() / startEffectDurationMillis
        val startupEffectActive = !isPreview && startupEffectProgress in 0f..1f

        // === 粒子基础半径计算 ===
        // 基础大小4dp，随效果强度线性缩放
        val baseDotRadius = 4.dp.toPx() * finalEffectIntensity

        // 只有当粒子足够大或启动动画活跃时才进行渲染
        if (baseDotRadius > 0.15f || startupEffectActive) {
            val screenDepthPx = screenDepth.toPx()
            
            // === 外围衰减区域大小计算 ===
            val scaledPeripherySizePx = when (previewMode) {
                PreviewMode.SPEED -> 30.dp.toPx()  // 速度预览模式使用固定小尺寸
                else -> peripherySize.toPx() *
                        lerp(0.2f, 1f, overlayAreaSize) *                    // 用户设置的区域大小
                        lerp(0.4f, 1f, finalEffectIntensity).pow(2f)         // 效果强度的非线性缩放
            }

            // === 3D位置偏移计算 ===
            // 将物理位置转换为屏幕像素偏移
            val (offsetXPx, offsetYPx, offsetZPx) = when (previewMode) {
                PreviewMode.NONE -> Triple(
                    position[0] * oneMeter.toPx() * speedFactor,  // X轴偏移
                    position[1] * oneMeter.toPx() * speedFactor,  // Y轴偏移
                    position[2] * oneMeter.toPx() * speedFactor,  // Z轴偏移
                )

                PreviewMode.SIZE -> Triple(0f, 0f, 0f)           // 尺寸预览：无偏移
                PreviewMode.SPEED -> Triple(position[0], 0f, 0f) // 速度预览：只有X轴运动
            }

            // === 运动轨迹偏移计算 ===
            // 用于绘制粒子的运动拖尾效果
            val (trailDxPx, trailDyPx, trailDzPx) = when (previewMode) {
                PreviewMode.NONE -> Triple(
                    (position[0] - lastPosition[0]) * oneMeter.toPx(),  // X轴运动增量
                    (position[1] - lastPosition[1]) * oneMeter.toPx(),  // Y轴运动增量
                    (position[2] - lastPosition[2]) * oneMeter.toPx(),  // Z轴运动增量
                )

                else -> Triple(0f, 0f, 0f)  // 预览模式无轨迹效果
            }

            // === 3D网格参数设置 ===
            val gridSizeX = 60f.dp.toPx()              // X轴网格间距
            val gridSizeY = gridSizeX / hexRatio       // Y轴网格间距(六边形密排)
            val gridSizeZ = screenDepthPx * 0.8f       // Z轴网格间距

            // === 三重循环生成3D六边形网格 ===
            for (x in -2 until (size.width / gridSizeX + 2).toInt()) {
                for (y in -4 until (size.height / gridSizeY + 4).toInt()) {
                    for (z in -1 until (screenDepthPx / gridSizeZ + 2).toInt()) {
                        // === 六边形网格位置计算 ===
                        // 偶数行偏移0.5个网格创建六边形密排结构
                        val pixelX = (x + 0.5f + (y % 2) * 0.5f) * gridSizeX + offsetXPx % gridSizeX
                        val pixelY = (y + 0.5f + (z % 2) * 1.3333f) * gridSizeY + offsetYPx % (gridSizeY * 2)
                        val pixelZ = z * gridSizeZ + offsetZPx % (gridSizeZ * 2)
                        
                        // 计算运动轨迹终点位置
                        val pixelTrailX = pixelX + trailDxPx
                        val pixelTrailZ = pixelZ + trailDzPx
                        
                        // === 颜色方案渲染 ===
                        // 黑色粒子渲染(适用于黑白和纯黑方案)
                        if (overlayColor == OverlayColor.BLACK_AND_WHITE || overlayColor == OverlayColor.BLACK) {
                            val pixelTrailY = pixelY + trailDyPx
                            drawParticle(
                                pixelX, pixelY, pixelZ,
                                pixelTrailX, pixelTrailY, pixelTrailZ,
                                Color.Black,
                                baseDotRadius,
                                scaledPeripherySizePx,
                                startupEffectActive,
                                startupEffectProgress,
                            )
                        }
                        
                        // 白色粒子渲染(适用于黑白和纯白方案)
                        if (overlayColor == OverlayColor.BLACK_AND_WHITE || overlayColor == OverlayColor.WHITE) {
                            val whitePixelY = pixelY + gridSizeY * 0.6667f  // 白色粒子Y轴偏移
                            val whitePixelTrailY = whitePixelY + trailDyPx
                            drawParticle(
                                pixelX, whitePixelY, pixelZ,
                                pixelTrailX, whitePixelTrailY, pixelTrailZ,
                                Color.White,
                                baseDotRadius - 1f,  // 白色粒子稍小，避免完全遮盖黑色粒子
                                scaledPeripherySizePx,
                                startupEffectActive,
                                startupEffectProgress,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 绘制单个粒子
 * 实现3D粒子的透视投影、深度渐变、运动轨迹等效果
 */
private fun DrawScope.drawParticle(
    pixelX: Float,
    pixelY: Float,
    pixelZ: Float,
    pixelTrailX: Float,
    pixelTrailY: Float,
    pixelTrailZ: Float,
    color: Color,
    baseDotRadius: Float,
    scaledPeripherySizePx: Float,
    startupEffectActive: Boolean,
    startupEffectProgress: Float,
) {
    val eyeZPx = eyeZ.toPx()
    val screenDepthPx = screenDepth.toPx()
    
    // 计算粒子的最终渲染半径(包含各种效果修正)
    val dotRadius = 2f * dotRadius(
        pixelX, pixelY, pixelZ,
        baseDotRadius,
        size,
        screenDepthPx,
        scaledPeripherySizePx,
        startupEffectActive,
        startupEffectProgress
    )
    
    // 只渲染可见的粒子
    if (dotRadius > 0f) {
        // === 透视投影 ===
        // 将3D坐标投影到2D屏幕坐标
        val start = perspective(pixelX, pixelY, pixelZ, size)
        val end = perspective(pixelTrailX, pixelTrailY, pixelTrailZ, size)
        
        // === 绘制运动轨迹线条 ===
        drawLine(
            color = color,
            // 深度透明度：距离屏幕中心深度越远越透明
            alpha = 1f - min(abs((pixelZ - screenDepthPx * 0.5f) / (screenDepthPx * 0.5f)), 1f).pow(2),
            cap = StrokeCap.Round,  // 圆形端点
            start = start,
            end = end,
            // === 动态线条粗细计算 ===
            // 基础粗细 - 轨迹长度修正 × 透视缩放
            strokeWidth = max(1f, dotRadius - end.minus(start).getDistance() * 0.44f) * (eyeZPx / (pixelZ + eyeZPx))
        )
    }
}

/**
 * 透视投影函数
 * 将3D世界坐标转换为2D屏幕坐标
 */
private fun Density.perspective(x: Float, y: Float, z: Float, screenSize: Size): Offset {
    val eyeZPx = eyeZ.toPx()
    return Offset(
        // X轴透视投影：center + (point - center) * (eyeZ / (z + eyeZ))
        screenSize.width * 0.5f + (x - screenSize.width * 0.5f) * (eyeZPx / (z + eyeZPx)),
        // Y轴透视投影
        screenSize.height * 0.5f + (y - screenSize.height * 0.5f) * (eyeZPx / (z + eyeZPx)),
    )
}

/**
 * 计算粒子渲染半径
 * 综合考虑基础半径、边缘衰减、启动动画等因素
 */
private fun Density.dotRadius(
    x: Float, y: Float, z: Float,
    baseDotRadius: Float,
    screenSize: Size,
    screenDepthPx: Float,
    scaledPeripherySizePx: Float,
    startupEffectActive: Boolean,
    startupEffectProgress: Float
) = (
        max(
            0f,
            baseDotRadius * dotRadiusFactor(x, y, z, screenSize, screenDepthPx, scaledPeripherySizePx)
        ) + startUpEffectRadius(startupEffectActive, startupEffectProgress, y, screenSize))

/**
 * 计算边缘衰减因子
 * 在屏幕边缘附近的粒子逐渐变小，保护外围视觉舒适性
 */
private fun Density.dotRadiusFactor(x: Float, y: Float, z: Float, screenSize: Size, screenDepthPx: Float, peripherySize: Float): Float {
    return clamp(
        1f - min(1f, edgeDistance(x, y, z, screenSize) / peripherySize),
        0f, 1f
    )
}

/**
 * 计算粒子到屏幕边缘的最短距离
 * 用于边缘衰减效果计算
 */
private fun Density.edgeDistance(x: Float, y: Float, z: Float, screenSize: Size): Float {
    val (px, py) = perspective(x, y, z, screenSize)
    return max(0f, min(min(px, py), min(screenSize.width - px, screenSize.height - py)))
}

/**
 * 计算启动动画的额外半径
 * 实现从屏幕底部向上扩散的波浪效果
 */
private fun startUpEffectRadius(startupEffectActive: Boolean, startupEffectProgress: Float, y: Float, screenSize: Size): Float =
    if (startupEffectActive) {
        val span = lerp(0.1f, 0.7f, startupEffectProgress)  // 波浪宽度随时间增加
        12f * max(
            0f,
            // 波浪函数：中心在移动的波峰位置，距离波峰越远半径越小
            (span - abs((1f - y / screenSize.height) - lerp(-0.1f, 1.7f, startupEffectProgress.pow(2f)))) / span
        ).pow(2)  // 平方衰减创造平滑的波浪边缘
    } else
        0f

// === 数学工具函数 ===

/**
 * 加速度曲线函数
 * 在极小值附近提供平滑过渡，避免数值不稳定
 */
private fun accelerationCurve(x: Float) = x * sigmoid01((abs(x) - 2e-12f) * 1e4f)

/**
 * Sigmoid函数
 * 将任意实数映射到(0,1)区间，提供平滑的S形过渡
 */
private fun sigmoid01(x: Float) = 1f / (1f + floatE.pow(-x))

/**
 * 线性插值函数
 * 在两个值之间进行线性插值
 */
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t