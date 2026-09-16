package com.bubuads.app

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class SplashActivity : Activity() {

    private var navigated = false
    private val handler = Handler(Looper.getMainLooper())

    private class FallingIcon(
        var x: Float,
        var y: Float,
        var speed: Float,
        var alpha: Float,
        var rotation: Float,
        var rotSpeed: Float,
        val symbol: String,
        val paint: Paint
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(SplashView(this))

        handler.postDelayed({
            if (!navigated) {
                navigated = true
                // Reenvia el deep-link (si lo hay) a MainActivity para abrir el video directo.
                val next = Intent(this, MainActivity::class.java).apply {
                    if (intent?.data != null) data = intent.data
                }
                startActivity(next)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }, 2500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    inner class SplashView(context: android.content.Context) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(12f, 0f, 3f, Color.parseColor("#AA000000"))
        }
        private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BBBBBB")
            textSize = 30f
            textAlign = Paint.Align.CENTER
        }

        private var logoBitmap: android.graphics.Bitmap? = null
        private var startTime = System.currentTimeMillis()

        private val fallingIcons = mutableListOf<FallingIcon>()
        private val symbols = listOf("▶", "♥", "💬", "🔔", "🎵", "⚡", "🔥", "📺")
        private val iconColors = listOf(
            Color.parseColor("#FF0000"),
            Color.parseColor("#FF4081"),
            Color.parseColor("#4FC3F7"),
            Color.parseColor("#FFD54F"),
            Color.parseColor("#69F0AE"),
            Color.parseColor("#E040FB"),
            Color.parseColor("#FF5252"),
            Color.parseColor("#FF9100")
        )

        init {
            try {
                val density = resources.displayMetrics.density
                val px = (300 * density).toInt()
                val resId = when {
                    density >= 3.0 -> R.drawable.ic_splash_logo_xxxhdpi
                    density >= 2.0 -> R.drawable.ic_splash_logo_xxhdpi
                    density >= 1.5 -> R.drawable.ic_splash_logo_xhdpi
                    density >= 1.0 -> R.drawable.ic_splash_logo_hdpi
                    else -> R.drawable.ic_splash_logo_mdpi
                }
                val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
                val bmp = android.graphics.BitmapFactory.decodeResource(resources, resId, opts)
                if (bmp != null) {
                    logoBitmap = android.graphics.Bitmap.createScaledBitmap(bmp, px, px, true)
                }
            } catch (_: Exception) {}

            for (i in 0 until 30) {
                val idx = i % symbols.size
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = iconColors[idx]
                    textSize = 32f + Random.nextFloat() * 18f
                    textAlign = Paint.Align.CENTER
                    alpha = 180 + Random.nextInt(75)
                }
                fallingIcons.add(
                    FallingIcon(
                        x = Random.nextFloat() * 1200f,
                        y = Random.nextFloat() * 2400f,
                        speed = 4f + Random.nextFloat() * 6f,
                        alpha = 0.5f + Random.nextFloat() * 0.5f,
                        rotation = Random.nextFloat() * 360f,
                        rotSpeed = -3f + Random.nextFloat() * 6f,
                        symbol = symbols[idx],
                        paint = paint
                    )
                )
            }

            startTime = System.currentTimeMillis()
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val elapsed = System.currentTimeMillis() - startTime

            // Background gradient
            bgPaint.shader = LinearGradient(
                0f, 0f, w * 0.3f, h,
                intArrayOf(
                    Color.parseColor("#1A0A2E"),
                    Color.parseColor("#16213E"),
                    Color.parseColor("#0F3460"),
                    Color.parseColor("#1A0A2E")
                ),
                floatArrayOf(0f, 0.3f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // Falling icons
            for (icon in fallingIcons) {
                icon.y += icon.speed
                icon.x += sin(elapsed / 800.0 + icon.x * 0.01).toFloat() * 0.8f
                icon.rotation += icon.rotSpeed
                if (icon.y > h + 60) {
                    icon.y = -60f
                    icon.x = Random.nextFloat() * w
                }
                canvas.save()
                canvas.rotate(icon.rotation, icon.x, icon.y)
                val savedAlpha = icon.paint.alpha
                icon.paint.alpha = (savedAlpha * icon.alpha).toInt()
                canvas.drawText(icon.symbol, icon.x, icon.y, icon.paint)
                icon.paint.alpha = savedAlpha
                canvas.restore()
            }

            // Logo
            logoBitmap?.let { bmp ->
                canvas.save()
                val cx = w / 2
                val cy = h * 0.35f
                val scale = (elapsed / 600f).coerceIn(0f, 1f)
                val rotation = (elapsed / 30f) % 360f
                canvas.rotate(rotation, cx, cy)
                canvas.scale(scale, scale, cx, cy)
                canvas.drawBitmap(bmp, cx - bmp.width / 2, cy - bmp.height / 2, logoPaint)
                canvas.restore()
            }

            // Text
            val textY = h * 0.35f + 180f
            val alpha = (elapsed / 400f * 255).toInt().coerceIn(0, 255)
            textPaint.alpha = alpha
            canvas.drawText("BubuADS", w / 2, textY, textPaint)
            subTextPaint.alpha = (alpha * 0.8f).toInt()
            canvas.drawText("YouTube sin anuncios", w / 2, textY + 50f, subTextPaint)

            // Loading
            if (elapsed > 800) {
                val dotCount = ((elapsed - 800) / 300 % 4).toInt()
                subTextPaint.alpha = (alpha * 0.5f).toInt()
                canvas.drawText("Cargando${".".repeat(dotCount)}", w / 2, h * 0.82f, subTextPaint)
            }

            // Keep animating
            if (elapsed < 3000) {
                postInvalidateOnAnimation()
            }
        }
    }
}
