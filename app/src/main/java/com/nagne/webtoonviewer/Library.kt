package com.nagne.webtoonviewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

/**
 * 작품
 * chapters: 작품 폴더에 바로 들어있는 화 (서브폴더 안의 화는 포함하지 않음)
 * subFolderCount: 시즌/부 등 서브폴더 수 (화 목록 화면에서 폴더로 표시)
 */
data class Work(
    val name: String,
    val uri: Uri,
    val chapters: List<Chapter>,
    val subFolderCount: Int,
) {
    /** 정렬 키 (스캔 시 한 번만 계산) */
    val sortKey: String = Library.workSortKey(name)
}

/** 화(= zip/cbz 파일 또는 이미지 폴더) */
data class Chapter(
    val name: String,
    val uri: Uri,
    val isDir: Boolean,
)

/** 폴더 한 단계의 내용: 화 목록 + 서브폴더 목록 */
data class FolderContents(
    val chapters: List<Chapter>,
    val subFolders: List<DocItem>,
)

object Library {

    private val CHAPTER_EXT = setOf("zip", "cbz")
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")

    /** 분류 폴더를 따라 작품을 찾을 최대 깊이 */
    private const val WORK_DEPTH = 3

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

    private fun safeList(ctx: Context, uri: Uri): List<DocItem> =
        runCatching { listChildren(ctx, uri) }.getOrDefault(emptyList())

    /** 트리 루트의 표시 이름 */
    fun rootName(ctx: Context, treeUri: Uri): String {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        ctx.contentResolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "Library" }
        return "Library"
    }

    private fun sortChapters(list: List<Chapter>): List<Chapter> {
        val keys = list.associateWith { chapterSortKey(it.name) }
        return list.sortedWith { a, b -> naturalCompareKeys(keys.getValue(a), keys.getValue(b)) }
    }

    private fun sortDocs(list: List<DocItem>): List<DocItem> {
        val keys = list.associateWith { chapterSortKey(it.name) }
        return list.sortedWith { a, b -> naturalCompareKeys(keys.getValue(a), keys.getValue(b)) }
    }

    /**
     * 하위 폴더들이 "이미지 폴더형 화"인지 판단.
     * 속도를 위해 첫 번째 폴더 하나만 확인한다 (보통 모든 화 폴더가 같은 형태).
     */
    private fun subDirsAreImageChapters(ctx: Context, sortedDirs: List<DocItem>): Boolean {
        val first = sortedDirs.firstOrNull() ?: return false
        return safeList(ctx, first.uri).any { !it.isDir && isImageFile(it.name) }
    }

    // ------------------------------------------------------------ 폴더 한 단계 조회

    /**
     * 폴더 한 단계만 조회 (화 목록 화면용)
     * - 바로 아래의 zip/cbz → 화
     * - 하위 폴더가 이미지 폴더형 화이면 → 화, 아니면 → 서브폴더(들어가야 보임)
     */
    fun listFolder(ctx: Context, folderUri: Uri): FolderContents {
        val children = listChildren(ctx, folderUri)
        val zips = children.filter { !it.isDir && isChapterFile(it.name) }
            .map { Chapter(it.name, it.uri, false) }
        val dirs = sortDocs(children.filter { it.isDir })
        return if (dirs.isNotEmpty() && subDirsAreImageChapters(ctx, dirs)) {
            FolderContents(sortChapters(zips + dirs.map { Chapter(it.name, it.uri, true) }), emptyList())
        } else {
            FolderContents(sortChapters(zips), dirs)
        }
    }

    // ------------------------------------------------------------ 라이브러리 스캔

    /**
     * 라이브러리 스캔 (폴더당 조회 1~2회로 최소화)
     * - 루트에 zip/cbz 가 바로 있으면 루트 자체를 하나의 작품으로 취급
     * - 폴더에 zip/cbz 가 바로 있거나 하위 폴더가 이미지 폴더형 화이면 → 작품
     * - 화 없이 하위 폴더만 있으면 → 분류 폴더로 보고 하위 폴더를 각각 작품으로 표시 ("분류 / 작품")
     */
    fun scan(ctx: Context, treeUri: Uri): List<Work> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val children = listChildren(ctx, rootUri)
        val works = ArrayList<Work>()

        val rootZips = children.filter { !it.isDir && isChapterFile(it.name) }
        if (rootZips.isNotEmpty()) {
            works.add(
                Work(
                    rootName(ctx, treeUri), rootUri,
                    sortChapters(rootZips.map { Chapter(it.name, it.uri, false) }), 0
                )
            )
        }
        for (dir in children.filter { it.isDir }) scanFolder(ctx, dir, "", 0, works)
        return works
    }

    private fun scanFolder(ctx: Context, dir: DocItem, parentLabel: String, depth: Int, works: MutableList<Work>) {
        val children = safeList(ctx, dir.uri)
        val label = parentLabel + dir.name
        val zips = children.filter { !it.isDir && isChapterFile(it.name) }
            .map { Chapter(it.name, it.uri, false) }
        val dirs = sortDocs(children.filter { it.isDir })

        if (zips.isNotEmpty()) {
            // 작품 폴더 (서브폴더는 화 목록 화면에서 폴더로 표시)
            works.add(Work(label, dir.uri, sortChapters(zips), dirs.size))
            return
        }
        if (dirs.isEmpty()) return
        if (subDirsAreImageChapters(ctx, dirs)) {
            // 이미지 폴더형 화로 구성된 작품
            works.add(Work(label, dir.uri, sortChapters(dirs.map { Chapter(it.name, it.uri, true) }), 0))
            return
        }
        // 분류 폴더 → 하위 폴더를 각각 작품 후보로
        if (depth + 1 >= WORK_DEPTH) return
        for (sd in dirs) scanFolder(ctx, sd, "$label / ", depth + 1, works)
    }

    // ------------------------------------------------------------ 스캔 결과 캐시

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "library_cache.json")

    /** 스캔 결과 저장 (다음 실행 시 즉시 표시용) */
    fun saveCache(ctx: Context, treeUri: Uri, works: List<Work>) {
        runCatching {
            val arr = JSONArray()
            for (w in works) {
                val ch = JSONArray()
                for (c in w.chapters) {
                    ch.put(JSONObject().put("n", c.name).put("u", c.uri.toString()).put("d", c.isDir))
                }
                arr.put(
                    JSONObject().put("n", w.name).put("u", w.uri.toString())
                        .put("s", w.subFolderCount).put("c", ch)
                )
            }
            val root = JSONObject().put("tree", treeUri.toString()).put("works", arr)
            cacheFile(ctx).writeText(root.toString())
        }
    }

    /** 저장된 스캔 결과 (같은 라이브러리 폴더일 때만) */
    fun loadCache(ctx: Context, treeUri: Uri): List<Work>? = runCatching {
        val f = cacheFile(ctx)
        if (!f.exists()) return null
        val root = JSONObject(f.readText())
        if (root.optString("tree") != treeUri.toString()) return null
        val arr = root.getJSONArray("works")
        val works = ArrayList<Work>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ch = o.getJSONArray("c")
            val chapters = ArrayList<Chapter>(ch.length())
            for (j in 0 until ch.length()) {
                val c = ch.getJSONObject(j)
                chapters.add(Chapter(c.getString("n"), Uri.parse(c.getString("u")), c.getBoolean("d")))
            }
            works.add(Work(o.getString("n"), Uri.parse(o.getString("u")), chapters, o.optInt("s", 0)))
        }
        works
    }.getOrNull()

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
