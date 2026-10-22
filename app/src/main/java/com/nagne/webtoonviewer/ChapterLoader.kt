package com.nagne.webtoonviewer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 화 한 개를 표시용 페이지 목록으로 변환한다.
 * 1) zip/cbz 를 앱 캐시 폴더에 풀어둠 (다음 화는 미리 풀어 끊김 없이 이어보기)
 * 2) 각 이미지의 크기를 읽고, 세로로 아주 긴 이미지는 여러 조각(slice)으로 나눔
 *    → 대용량 웹툰 이미지도 메모리 부족 없이 표시
 */
object ChapterLoader {

    /** 한 조각의 최대 원본 높이(px) */
    private const val SLICE_HEIGHT = 2000

    /** 같은 화를 동시에 풀지 않도록 직렬화 */
    private val mutex = Mutex()

    data class Page(
        val file: File,
        val imgW: Int,
        val imgH: Int,
        val top: Int,
        val bottom: Int,
    ) {
        val sliceH get() = bottom - top
        val cacheKey get() = "${file.path}#$top"
    }

    private fun cacheRoot(ctx: Context) = File(ctx.cacheDir, "chapters").apply { mkdirs() }

    private fun dirFor(ctx: Context, uri: Uri): File {
        val md = MessageDigest.getInstance("MD5").digest(uri.toString().toByteArray())
        val hex = md.joinToString("") { "%02x".format(it) }
        return File(cacheRoot(ctx), hex)
    }

    /**
     * 캐시 정리: keep 에 포함된 화의 폴더만 남기고 삭제
     */
    suspend fun trimCache(ctx: Context, keep: Collection<Uri>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val keepNames = keep.map { dirFor(ctx, it).name }.toSet()
            cacheRoot(ctx).listFiles()?.forEach { if (it.name !in keepNames) it.deleteRecursively() }
        }
    }

    /** 화 이미지를 캐시에 준비하고 파일 목록(정렬됨)을 반환 */
    suspend fun extract(ctx: Context, uri: Uri, isDir: Boolean): List<File> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val dir = dirFor(ctx, uri)
                val done = File(dir, ".done")
                if (!done.exists()) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                    if (isDir) copyFolder(ctx, uri, dir) else unzip(ctx, uri, dir)
                    done.writeText("ok")
                }
                (dir.listFiles() ?: emptyArray())
                    .filter { Library.isImageFile(it.name) }
                    .sortedBy { it.name }
            }
        }

    /** 이미지 폴더 화: 이미지들을 순서대로 캐시에 복사 */
    private suspend fun copyFolder(ctx: Context, uri: Uri, dir: File) {
        val images = Library.listChildren(ctx, uri)
            .filter { !it.isDir && Library.isImageFile(it.name) }
            .sortedWith { a, b -> Library.naturalCompare(a.name, b.name) }
        images.forEachIndexed { i, item ->
            coroutineContext.ensureActive()
            val out = File(dir, "%05d.%s".format(i, Library.ext(item.name)))
            ctx.contentResolver.openInputStream(item.uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** zip/cbz 화: 임시 파일로 복사 후 압축 해제 (한글 파일명 CP949 대응) */
    private suspend fun unzip(ctx: Context, uri: Uri, dir: File) {
        val tmp = File(dir, "_source.zip")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("파일을 열 수 없습니다.")

        var lastError: Exception? = null
        for (cs in candidateCharsets()) {
            try {
                ZipFile(tmp, cs).use { zip ->
                    val entries = zip.entries().toList()
                        .filter { e ->
                            !e.isDirectory &&
                                Library.isImageFile(e.name) &&
                                !e.name.contains("__MACOSX") &&
                                !e.name.substringAfterLast('/').startsWith(".")
                        }
                        .sortedWith { a, b -> Library.naturalCompare(a.name, b.name) }
                    entries.forEachIndexed { i, e ->
                        coroutineContext.ensureActive()
                        val out = File(dir, "%05d.%s".format(i, Library.ext(e.name)))
                        zip.getInputStream(e).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                tmp.delete()
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                // 다음 문자셋으로 재시도하기 전, 일부 풀린 파일 제거
                dir.listFiles()?.forEach { if (it != tmp) it.delete() }
            }
        }
        tmp.delete()
        throw lastError ?: IllegalStateException("압축을 풀 수 없습니다.")
    }

    private fun candidateCharsets(): List<Charset> =
        listOf("UTF-8", "MS949", "x-windows-949", "EUC-KR")
            .filter { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
            .map { Charset.forName(it) }

    /** 이미지 파일 목록 → 표시용 페이지(조각) 목록 */
    suspend fun buildPages(files: List<File>): List<Page> = withContext(Dispatchers.IO) {
        val pages = ArrayList<Page>()
        val opts = BitmapFactory.Options()
        for (f in files) {
            coroutineContext.ensureActive()
            opts.inJustDecodeBounds = true
            opts.outWidth = 0
            opts.outHeight = 0
            BitmapFactory.decodeFile(f.path, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) continue // 손상/미지원 이미지는 건너뜀
            var top = 0
            while (top < h) {
                val bottom = minOf(h, top + SLICE_HEIGHT)
                // 마지막 조각이 너무 얇으면 앞 조각에 합침
                val finalBottom = if (h - bottom in 1..199) h else bottom
                pages.add(Page(f, w, h, top, finalBottom))
                top = finalBottom
            }
        }
        pages
    }
}
