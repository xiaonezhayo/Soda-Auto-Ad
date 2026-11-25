package com.soda.adhelper.core

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class OcrTextHit(
    val text: String,
    val bounds: Rect
)

class OcrRecognizer {
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun recognize(
        fullBitmap: Bitmap,
        topFraction: Float = 0.28f
    ): List<OcrTextHit> {
        val clampedFraction = topFraction.coerceIn(0.15f, 1.0f)
        val cropHeight = (fullBitmap.height * clampedFraction).toInt().coerceAtLeast(1)
        val targetBitmap = if (cropHeight < fullBitmap.height) {
            Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, cropHeight)
        } else {
            fullBitmap
        }

        return try {
            val image = InputImage.fromBitmap(targetBitmap, 0)
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val hits = mutableListOf<OcrTextHit>()
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                val box = line.boundingBox
                                val text = line.text.trim()
                                if (box != null && text.isNotEmpty()) {
                                    hits.add(OcrTextHit(text = text, bounds = Rect(box)))
                                }
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resume(hits)
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(emptyList())
                        }
                    }
            }
        } finally {
            if (targetBitmap !== fullBitmap && !targetBitmap.isRecycled) {
                targetBitmap.recycle()
            }
        }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
