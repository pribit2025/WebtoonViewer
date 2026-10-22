package com.nagne.webtoonviewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.math.BigInteger

/** SAF(Storage Access Framework) 문서 항목 */
data class DocItem(
    val uri: Uri,
    val docId: String,
    val name: String,
    val isDir: Boolean,
)

/** 작품(= 라이브러리 루트 아래의 폴더) */
data class Work(
    val name: String,
    val uri: Uri,
    val chapters: List<Chapter>,
)

/** 화(= zip/cbz 파일 또는 이미지 폴더) */
data class Chapter(
    val name: String,
    val uri: Uri,
    val isDir: Boolean,
)

object Library {

    private val CHAPTER_EXT = setOf("zip", "cbz")
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isChapterFile(name: String) = ext(name) in CHAPTER_EXT
    fun isImageFile(name: String) = ext(name) in IMAGE_EXT

    /** 화 이름에서 확장자 제거 (표시용) */
    fun displayName(chapter: Chapter): String =
        if (chapter.isDir) chapter.name else chapter.name.substringBeforeLast('.')

    /**
     * DocumentsContract 를 직접 질의하여 자식 목록을 가져온다.
     * (DocumentFile.listFiles() 보다 훨씬 빠름)
     * @param parentUri 트리 기반 문서 URI (buildDocumentUriUsingTree 결과 또는 트리 URI 자체)
     */
    fun listChildren(ctx: Context, parentUri: Uri): List<DocItem> {
        val parentId = if (DocumentsContract.isDocumentUri(ctx, parentUri)) {
            DocumentsContract.getDocumentId(parentUri)
        } else {
            DocumentsContract.getTreeDocumentId(parentUri)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = ArrayList<DocItem>()
        ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (name.startsWith(".")) continue // 숨김 파일 제외
                val uri = DocumentsContract.buildDocumentUriUsingTree(parentUri, id)
                result.add(DocItem(uri, id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR))
            }
        }
        return result
    }

    /** 트리 루트의 표시 이름 */
    fun rootName(ctx: Context, treeUri: Uri): String {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        ctx.contentResolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "Library" }
        return "Library"
    }

    /** 한 작품 폴더 안의 화 목록 (자연 정렬, 오름차순) */
    fun listChapters(ctx: Context, workUri: Uri): List<Chapter> =
        listChildren(ctx, workUri)
            .filter { (!it.isDir && isChapterFile(it.name)) || it.isDir }
            .map { Chapter(it.name, it.uri, it.isDir) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }

    /**
     * 라이브러리 스캔.
     * - 루트 아래 각 폴더 = 작품
     * - 루트에 zip/cbz 가 바로 들어있으면 루트 자체도 하나의 작품으로 취급
     */
    fun scan(ctx: Context, treeUri: Uri): List<Work> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val children = listChildren(ctx, rootUri)
        val works = ArrayList<Work>()

        val rootChapters = children.filter { !it.isDir && isChapterFile(it.name) }
        if (rootChapters.isNotEmpty()) {
            works.add(
                Work(
                    rootName(ctx, treeUri), rootUri,
                    rootChapters.map { Chapter(it.name, it.uri, false) }
                        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                )
            )
        }
        for (dir in children.filter { it.isDir }) {
            val chapters = listChapters(ctx, dir.uri)
            if (chapters.isNotEmpty()) works.add(Work(dir.name, dir.uri, chapters))
        }
        return works
    }

    /** 숫자를 숫자로 비교하는 자연 정렬 (1화, 2화, 10화 순) */
    fun naturalCompare(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val x = ta[i]
            val y = tb[i]
            val xd = x[0] in '0'..'9'
            val yd = y[0] in '0'..'9'
            val cmp = if (xd && yd) {
                BigInteger(x).compareTo(BigInteger(y)).let { if (it == 0) x.length - y.length else it }
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return ta.size - tb.size
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var digit: Boolean? = null
        for (ch in s) {
            val d = ch in '0'..'9'
            if (digit != null && d != digit) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            sb.append(ch)
            digit = d
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
