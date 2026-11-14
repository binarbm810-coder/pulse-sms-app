package com.example.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class CompressedPhotoResult(
  val webpBytes: ByteArray,
  val base64: String,
  val width: Int,
  val height: Int,
  val originalBytes: Long,
  val compressedBytes: Int,
  val qualityUsed: Int,
)

object WebpImageCompressor {
  const val TARGET_MAX_BYTES = 15_360 // 15 KB ideal target
  const val HARD_CEILING_BYTES = 24_576 // 24 KB strict upper ceiling

  suspend fun compressUriToWebp(
    context: Context,
    uri: Uri,
    targetMaxBytes: Int = TARGET_MAX_BYTES,
    hardCeilingBytes: Int = HARD_CEILING_BYTES,
  ): Result<CompressedPhotoResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        val resolver = context.contentResolver
        val originalSize =
          resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
          BitmapFactory.decodeStream(stream, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
          "Unable to read selected image dimensions"
        }

        val maxStartDim = 640
        var sampleSize = 1
        while (
          (bounds.outWidth / sampleSize) > maxStartDim * 2 ||
            (bounds.outHeight / sampleSize) > maxStartDim * 2
        ) {
          sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded =
          resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
          } ?: error("Failed to decode selected image")

        try {
          compressBitmapToWebp(
            source = decoded,
            originalBytes = originalSize,
            targetMaxBytes = targetMaxBytes,
            hardCeilingBytes = hardCeilingBytes,
          )
        } finally {
          decoded.recycle()
        }
      }
    }

  fun compressBitmapToWebp(
    source: Bitmap,
    originalBytes: Long = source.byteCount.toLong(),
    targetMaxBytes: Int = TARGET_MAX_BYTES,
    hardCeilingBytes: Int = HARD_CEILING_BYTES,
  ): CompressedPhotoResult {
    val format =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
      } else {
        @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
      }

    // Step through max edge dimensions if needed until bytes <= targetMaxBytes (or <= hardCeilingBytes)
    val dimCandidates = listOf(480, 384, 320, 256, 200)
    var bestBytes: ByteArray? = null
    var bestW = source.width
    var bestH = source.height
    var bestQ = 60

    for (maxDim in dimCandidates) {
      val scaled = scaleDownIfNeeded(source, maxDim)
      try {
        // Binary search WebP quality between 18 and 80 for crisp readability under 15KB
        var low = 18
        var high = 80
        var localBest: ByteArray? = null
        var localBestQ = 18

        while (low <= high) {
          val mid = (low + high) / 2
          val out = ByteArrayOutputStream()
          scaled.compress(format, mid, out)
          val bytes = out.toByteArray()

          if (bytes.size <= targetMaxBytes) {
            localBest = bytes
            localBestQ = mid
            low = mid + 1 // try higher quality while staying <= 15KB
          } else {
            high = mid - 1
          }
        }

        if (localBest != null) {
          bestBytes = localBest
          bestW = scaled.width
          bestH = scaled.height
          bestQ = localBestQ
          break
        }

        // If even quality=18 exceeded 15KB at this resolution, check if it's under hardCeilingBytes
        val minOut = ByteArrayOutputStream()
        scaled.compress(format, 18, minOut)
        val minBytes = minOut.toByteArray()
        if (bestBytes == null || minBytes.size < bestBytes.size) {
          bestBytes = minBytes
          bestW = scaled.width
          bestH = scaled.height
          bestQ = 18
        }
        if (minBytes.size <= hardCeilingBytes && maxDim <= 256) {
          break
        }
      } finally {
        if (scaled !== source) {
          scaled.recycle()
        }
      }
    }

    val finalBytes = requireNotNull(bestBytes) { "WebP compression failed" }
    require(finalBytes.size <= hardCeilingBytes) {
      "Compressed image (${finalBytes.size} B) exceeds 24 KB limit"
    }

    val b64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
    return CompressedPhotoResult(
      webpBytes = finalBytes,
      base64 = b64,
      width = bestW,
      height = bestH,
      originalBytes = originalBytes,
      compressedBytes = finalBytes.size,
      qualityUsed = bestQ,
    )
  }

  fun decodeWebpBytes(bytes: ByteArray): Bitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

  private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val maxCurrent = max(bitmap.width, bitmap.height)
    if (maxCurrent <= maxEdge) return bitmap
    val ratio = maxEdge.toFloat() / maxCurrent.toFloat()
    val targetW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
    val targetH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
  }
}
