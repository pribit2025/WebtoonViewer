package com.nagne.webtoonviewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.math.BigInteger
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** SAF(Storage Access Framework) 문서 항목 */
data class DocItem(
    val uri: Uri,
    val docId: String,
    val name: String,
    val isDir: Boolean,
)

/** 작품 */
data class Work(
    val name: String,
    val uri: Uri,
    val chapters: List<Chapter>,
)

/**
 * 화(= zip/cbz 파일 또는 이미지 폴더)
 * name: 작품 폴더 기준 상대 경로 (예: "001화.zip", "1부/001화.zip")
 */
data class Chapter(
    val name: String,
    val uri: Uri,
    val isDir: Boolean,
)

object Library {

    private val CHAPTER_EXT = setOf("zip", "cbz")
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

    /** 작품 폴더 안에서 화를 찾을 하위 폴더 최대 깊이 (시즌/부 폴더 대응) */
    private const val CHAPTER_DEPTH = 3

    /** 분류 폴더를 따라 작품을 찾을 최대 깊이 */
    private const val WORK_DEPTH = 4

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isChapterFile(name: String) = ext(name) in CHAPTER_EXT
    fun isImageFile(name: String) = ext(name) in IMAGE_EXT

    /** 화 이름 표시용: 확장자 제거 */
    fun displayName(chapter: Chapter): String =
        if (chapter.isDir) chapter.name else chapter.name.substringBeforeLast('.')

    // ------------------------------------------------------------ SAF 조회

    /**
     * DocumentsContract 를 직접 질의하여 자식 목록을 가져온다.
     * @param parentUri 트리 기반 문서 URI
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

    // ------------------------------------------------------------ 화 목록

    /**
     * 작품 폴더 안의 화 목록 (자연 정렬, 오름차순)
     * - 바로 아래의 zip/cbz
     * - 이미지가 바로 들어있는 하위 폴더 (이미지 폴더형 화)
     * - 시즌/부 하위 폴더 안의 zip/cbz (이름: "1부/001화.zip")
     */
    fun listChapters(ctx: Context, workUri: Uri): List<Chapter> {
        val out = ArrayList<Chapter>()
        collectChapters(ctx, workUri, "", 0, out, listChildren(ctx, workUri))
        val keys = out.associateWith { chapterSortKey(it.name) }
        return out.sortedWith { a, b -> naturalCompareKeys(keys.getValue(a), keys.getValue(b)) }
    }

    private fun collectChapters(
        ctx: Context,
        dirUri: Uri,
        prefix: String,
        depth: Int,
        out: MutableList<Chapter>,
        children: List<DocItem>,
    ) {
        for (c in children) {
            if (!c.isDir && isChapterFile(c.name)) out.add(Chapter(prefix + c.name, c.uri, false))
        }
        for (d in children.filter { it.isDir }) {
            val sub = runCatching { listChildren(ctx, d.uri) }.getOrDefault(emptyList())
            if (sub.any { !it.isDir && isImageFile(it.name) }) {
                // 이미지가 바로 들어있는 폴더 = 이미지 폴더형 화
                out.add(Chapter(prefix + d.name, d.uri, true))
            } else if (depth + 1 < CHAPTER_DEPTH) {
                // 시즌/부 폴더 → 안쪽까지 탐색
                collectChapters(ctx, d.uri, prefix + d.name + "/", depth + 1, out, sub)
            }
        }
    }

    // ------------------------------------------------------------ 라이브러리 스캔

    /**
     * 라이브러리 스캔 (자동 판단)
     * - 루트에 zip/cbz 가 바로 있으면 루트 자체를 하나의 작품으로 취급
     * - 폴더에 zip/cbz 또는 이미지 폴더형 화가 바로 있으면 → 그 폴더가 작품
     *   (작품 안의 시즌/부 폴더는 같은 작품에 합쳐짐)
     * - 화 없이 하위 폴더만 있으면 → 분류 폴더로 보고 하위 폴더를 각각 작품으로 표시
     *   (작품 이름: "분류 / 작품")
     */
    fun scan(ctx: Context, treeUri: Uri): List<Work> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val children = listChildren(ctx, rootUri)
        val works = ArrayList<Work>()

        val rootZips = children.filter { !it.isDir && isChapterFile(it.name) }
        if (rootZips.isNotEmpty()) {
            val chapters = rootZips.map { Chapter(it.name, it.uri, false) }
            val keys = chapters.associateWith { chapterSortKey(it.name) }
            works.add(
                Work(
                    rootName(ctx, treeUri), rootUri,
                    chapters.sortedWith { a, b -> naturalCompareKeys(keys.getValue(a), keys.getValue(b)) }
                )
            )
        }
        for (dir in children.filter { it.isDir }) {
            scanFolder(ctx, dir, "", 0, works)
        }
        return works
    }

    private fun scanFolder(ctx: Context, dir: DocItem, parentLabel: String, depth: Int, works: MutableList<Work>) {
        val children = runCatching { listChildren(ctx, dir.uri) }.getOrDefault(emptyList())
        val label = parentLabel + dir.name

        val hasDirectZip = children.any { !it.isDir && isChapterFile(it.name) }
        val subDirs = children.filter { it.isDir }

        // 하위 폴더 각각의 내용을 한 번만 조회해 재사용
        val subContents: Map<DocItem, List<DocItem>> = if (hasDirectZip) emptyMap() else
            subDirs.associateWith { runCatching { listChildren(ctx, it.uri) }.getOrDefault(emptyList()) }
        val hasImageFolder = subContents.values.any { sub -> sub.any { !it.isDir && isImageFile(it.name) } }

        if (hasDirectZip || hasImageFolder) {
            // 작품 폴더
            val chapters = listChapters(ctx, dir.uri)
            if (chapters.isNotEmpty()) works.add(Work(label, dir.uri, chapters))
            return
        }
        // 분류 폴더 → 하위 폴더를 각각 작품 후보로
        if (depth + 1 >= WORK_DEPTH) return
        for (sd in subDirs) scanFolder(ctx, sd, "$label / ", depth + 1, works)
    }

    // ------------------------------------------------------------ 정렬

    private val collator: Collator = Collator.getInstance(Locale.KOREAN).apply {
        strength = Collator.SECONDARY // 대소문자 무시
    }

    /** 숨김/제어 문자 (제로폭 공백, 방향 제어, BOM, soft hyphen 등) */
    private val INVISIBLE = Regex("[\\u0000-\\u001F\\u007F\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u206F\\uFEFF]")

    /** 앞에 붙은 괄호 머리말: [완결], (19), 【연재】, 〔단편〕, 「외전」, <신작> 등 */
    private val LEADING_TAG = Regex(
        "^[\\[(【〔「『<〈《{［（][^\\])】〕」』>〉》}］）]*[\\])】〕」』>〉》}］）]\\s*"
    )

    /** 이름 맨 앞의 기호(★, #, _, -, ., ~ 등) */
    private val LEADING_SYMBOLS = Regex("^[^\\p{L}\\p{N}]+")

    /**
     * 공통 정규화: 유니코드 호환 정규화(NFKC → 맥 자모분리 한글 결합, 전각 숫자/문자 → 반각),
     * 숨김 문자 제거, 연속 공백 정리
     */
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace(INVISIBLE, "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 작품 정렬 키: 정규화 + 괄호 머리말/앞 기호 무시 */
    fun workSortKey(name: String): String {
        var s = normalize(name)
        val original = s
        while (true) {
            val stripped = s.replaceFirst(LEADING_TAG, "")
            if (stripped == s) break
            s = stripped
        }
        s = s.replaceFirst(LEADING_SYMBOLS, "").trim()
        return s.ifEmpty { original }
    }

    /** 화 정렬 키: 정규화만 (괄호 안 숫자가 회차일 수 있으므로 머리말은 유지) */
    fun chapterSortKey(name: String): String = normalize(name)

    /** 작품 이름 비교 (정렬 키 기준) */
    fun compareWorkNames(a: String, b: String): Int =
        naturalCompareKeys(workSortKey(a), workSortKey(b))

    /** 일반 자연 정렬 (파일명 등) */
    fun naturalCompare(a: String, b: String): Int =
        naturalCompareKeys(chapterSortKey(a), chapterSortKey(b))

    /**
     * 자연 정렬: 숫자는 숫자 크기로, 문자는 한국어 사전순(Collator)으로 비교
     * (1화, 2화, 10화 순 / 가나다 순 / 영문 대소문자 무시)
     */
    fun naturalCompareKeys(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val x = ta[i]
            val y = tb[i]
            val xd = x[0] in '0'..'9'
            val yd = y[0] in '0'..'9'
            val cmp = when {
                xd && yd -> BigInteger(x).compareTo(BigInteger(y)).let { if (it == 0) x.length - y.length else it }
                xd -> -1 // 숫자가 문자보다 앞
                yd -> 1
                else -> collator.compare(x, y)
            }
            if (cmp != 0) return cmp
        }
        if (ta.size != tb.size) return ta.size - tb.size
        return a.compareTo(b)
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
