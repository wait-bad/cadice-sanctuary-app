package com.sanctuary.cadice

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * 🖼️ 全屏图片查看器（支持双指缩放与单指拖拽）
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var ivPhoto: ImageView
    private val matrix = Matrix()
    private var currentScale = 1f
    private var minScale = 1f
    private var maxScale = 4f
    private var bmpWidth = 0f
    private var bmpHeight = 0f
    private val lastTouch = PointF()
    private var isDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        ivPhoto = findViewById(R.id.iv_fullscreen_photo)
        findViewById<MaterialButton>(R.id.btn_close_viewer).setOnClickListener { finish() }

        val serverUrl = intent.getStringExtra("server_url") ?: ""
        val filePath = intent.getStringExtra("file_path") ?: ""
        val localUri = intent.getStringExtra("local_uri") ?: ""

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val targetScale = (currentScale * factor).coerceIn(minScale, maxScale * minScale)
                val appliedFactor = targetScale / currentScale
                currentScale = targetScale
                matrix.postScale(appliedFactor, appliedFactor, detector.focusX, detector.focusY)
                ivPhoto.imageMatrix = matrix
                return true
            }
        })

        ivPhoto.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouch.set(event.x, event.y)
                    isDragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                        val dx = event.x - lastTouch.x
                        val dy = event.y - lastTouch.y
                        matrix.postTranslate(dx, dy)
                        ivPhoto.imageMatrix = matrix
                        lastTouch.set(event.x, event.y)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
            }
            true
        }

        lifecycleScope.launch {
            val bmp = SanctuaryCore.fetchOrLoadChatImageBitmap(
                context = applicationContext,
                serverUrl = serverUrl,
                filePath = filePath,
                localFileUri = localUri,
                cacheKey = "full_${filePath}_$localUri",
                maxDim = 2048
            )
            if (bmp != null) {
                displayCenteredBitmap(bmp)
            }
        }
    }

    private fun displayCenteredBitmap(bmp: Bitmap) {
        ivPhoto.setImageBitmap(bmp)
        bmpWidth = bmp.width.toFloat()
        bmpHeight = bmp.height.toFloat()
        ivPhoto.post {
            val vw = ivPhoto.width.toFloat()
            val vh = ivPhoto.height.toFloat()
            if (vw > 0f && vh > 0f && bmpWidth > 0f && bmpHeight > 0f) {
                minScale = minOf(vw / bmpWidth, vh / bmpHeight)
                currentScale = minScale
                val dx = (vw - bmpWidth * minScale) / 2f
                val dy = (vh - bmpHeight * minScale) / 2f
                matrix.reset()
                matrix.postScale(minScale, minScale)
                matrix.postTranslate(dx, dy)
                ivPhoto.imageMatrix = matrix
            }
        }
    }
}
