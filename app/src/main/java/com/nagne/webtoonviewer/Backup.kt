package com.nagne.webtoonviewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 열람 기록 백업/복원.
 *
 * 앱을 지우고 다시 설치하면 앱 내부 저장소(열람 기록)가 삭제되므로,
 * 라이브러리 폴더 루트에 "webtoonviewer_backup.json" 파일로 기록을 함께 저장해 둔다.
 * 새로 설치한 뒤 같은 라이브러리 폴더를 선택하면 자동으로 복원된다.
 *
 * - 저장: 화면을 벗어날 때(onPause) 백그라운드에서 자동 저장
 * - 복원: 폴더 선택 시 / 열람 기록이 비어 있을 때 자동 병합 (기존 기록은 덮어쓰지 않음)
 */
object Backup {

    const val FILE_NAME = "webtoonviewer_backup.json"

    private val executor = Executors.newSingleThreadExecutor()

    /** 백업 대상 설정 키 (라이브러리 폴더 경로는 제외) */
    private val SETTING_KEYS = listOf("margin", "bg", "chapter_desc", "works_recent")

    private fun rootUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun canWrite(ctx: Context, tree: Uri) =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    private fun findBackup(ctx: Context, tree: Uri): Uri? =
        Library.listChildren(ctx, rootUri(tree)).firstOrNull { !it.isDir && it.name == FILE_NAME }?.uri

    // ------------------------------------------------------------ 저장

    /** 백그라운드 저장 (화면 전환을 막지 않음) */
    fun saveAsync(ctx: Context) {
        val app = ctx.applicationContext
        executor.execute { runCatching { save(app) } }
    }

    private fun save(ctx: Context) {
        val prefs = Prefs(ctx)
        val tree = prefs.treeUri ?: return
        if (!canWrite(ctx, tree)) return
        if (prefs.progress.all.isEmpty()) return // 빈 기록으로 기존 백업을 덮어쓰지 않음

        val json = export(prefs).toString()
        val target = findBackup(ctx, tree)
            ?: DocumentsContract.createDocument(ctx.contentResolver, rootUri(tree), "application/json", FILE_NAME)
            ?: return
        ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(json.toByteArray()) }
    }

    private fun export(prefs: Prefs): JSONObject {
        val progress = JSONObject()
        for ((k, v) in prefs.progress.all) {
            when (v) {
                is Boolean -> progress.put(k, JSONObject().put("t", "b").put("v", v))
                is String -> progress.put(k, JSONObject().put("t", "s").put("v", v))
                is Long -> progress.put(k, JSONObject().put("t", "l").put("v", v))
                is Int -> progress.put(k, JSONObject().put("t", "i").put("v", v))
            }
        }
        val settings = JSONObject()
        for (k in SETTING_KEYS) {
            when (val v = prefs.settings.all[k]) {
                is Boolean -> settings.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> settings.put(k, JSONObject().put("t", "i").put("v", v))
            }
        }
        return JSONObject()
            .put("version", 1)
            .put("savedAt", System.currentTimeMillis())
            .put("progress", progress)
            .put("settings", settings)
    }

    // ------------------------------------------------------------ 복원

    /**
     * 라이브러리 폴더의 백업 파일을 읽어 현재 기록에 병합한다.
     * 이미 있는 기록은 유지하고, 없는 항목만 추가한다 (최근 읽은 시각은 더 최신 값 사용).
     * @return 추가된 항목 수 (백업이 없으면 0)
     */
    fun restore(ctx: Context, tree: Uri): Int {
        val uri = findBackup(ctx, tree) ?: return 0
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return 0
        val root = JSONObject(text)
        val prefs = Prefs(ctx)
        var added = 0

        root.optJSONObject("progress")?.let { p ->
            val local = prefs.progress.all
            val ed = prefs.progress.edit()
            for (k in p.keys()) {
                val o = p.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> if (k !in local) { ed.putBoolean(k, o.getBoolean("v")); added++ }
                    "s" -> if (k !in local) { ed.putString(k, o.getString("v")); added++ }
                    "i" -> if (k !in local) { ed.putInt(k, o.getInt("v")); added++ }
                    "l" -> {
                        val v = o.getLong("v")
                        val cur = local[k] as? Long
                        if (cur == null || v > cur) { ed.putLong(k, v); if (cur == null) added++ }
                    }
                }
            }
            ed.apply()
        }

        root.optJSONObject("settings")?.let { s ->
            val local = prefs.settings.all
            val ed = prefs.settings.edit()
            for (k in s.keys()) {
                if (k in local) continue
                val o = s.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> ed.putBoolean(k, o.getBoolean("v"))
                    "i" -> ed.putInt(k, o.getInt("v"))
                }
            }
            ed.apply()
        }
        return added
    }
}
