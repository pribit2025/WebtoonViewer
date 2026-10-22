package com.nagne.webtoonviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 페이지(조각) 비트맵 디코더.
 * - 백그라운드 스레드에서 디코딩, 메모리 LRU 캐시 사용
 * - 긴 이미지는 BitmapRegionDecoder 로 필요한 영역만 디코딩
 */
class PageDecoder {

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private val bitmapCache: LruCache<String, Bitmap> = run {
        val max = Runtime.getRuntime().maxMemory()
        val size = minOf(max / 4, 256L * 1024 * 1024).toInt()
        object : LruCache<String, Bitmap>(size) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }

    /** 파일별 영역 디코더 캐시 (같은 이미지의 여러 조각에 재사용) */
    private val regionDecoders = object : LruCache<String, BitmapRegionDecoder>(6) {}

    fun cached(page: ChapterLoader.Page, targetW: Int): Bitmap? = bitmapCache.get(key(page, targetW))

    private fun key(page: ChapterLoader.Page, targetW: Int) = "${page.cacheKey}@$targetW"

    /** 비동기 디코딩. 완료 시 메인 스레드에서 callback 호출 */
    fun request(page: ChapterLoader.Page, targetW: Int, callback: (Bitmap?) -> Unit): Future<*> {
        return executor.submit(Runnable {
            val k = key(page, targetW)
            var bmp = bitmapCache.get(k)
            if (bmp == null) {
                bmp = try {
                    decode(page, targetW)
                } catch (e: OutOfMemoryError) {
                    bitmapCache.evictAll()
                    null
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) bitmapCache.put(k, bmp)
            }
            val result = bmp
            if (!Thread.currentThread().isInterrupted) main.post { callback(result) }
        })
    }

    private fun sampleSize(imgW: Int, targetW: Int): Int {
        var s = 1
        if (targetW <= 0) return 1
        while (imgW / (s * 2) >= targetW) s *= 2
        return s
    }

    private fun decode(page: ChapterLoader.Page, targetW: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(page.imgW, targetW)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // 조각이 이미지 전체면 일반 디코딩
        if (page.top == 0 && page.bottom == page.imgH) {
            return BitmapFactory.decodeFile(page.file.path, opts)
        }
        // 영역 디코딩 (JPEG/PNG/WebP/HEIF 지원)
        val region = try {
            regionDecoder(page.file.path)
        } catch (e: Exception) {
            null
        }
        if (region != null) {
            val bmp = try {
                region.decodeRegion(Rect(0, page.top, page.imgW, page.bottom), opts)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) return bmp
        }
        // 폴백: 전체 디코딩 후 잘라내기 (GIF/BMP 등)
        val full = BitmapFactory.decodeFile(page.file.path, opts) ?: return null
        val s = opts.inSampleSize
        val t = (page.top / s).coerceIn(0, full.height - 1)
        val b = (page.bottom / s).coerceIn(t + 1, full.height)
        val cropped = Bitmap.createBitmap(full, 0, t, full.width, b - t)
        if (cropped !== full) full.recycle()
        return cropped
    }

    private fun regionDecoder(path: String): BitmapRegionDecoder? {
        synchronized(regionDecoders) {
            regionDecoders.get(path)?.let { return it }
        }
        val dec: BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(path, false)
        }
        if (dec != null) synchronized(regionDecoders) { regionDecoders.put(path, dec) }
        return dec
    }

    /** 화 전환 시 메모리 정리 */
    fun clear() {
        bitmapCache.evictAll()
        synchronized(regionDecoders) { regionDecoders.evictAll() }
    }

    fun shutdown() {
        executor.shutdownNow()
        clear()
    }
}
