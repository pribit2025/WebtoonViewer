package com.nagne.webtoonviewer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * 앱 설정 + 열람 기록 저장소.
 * 열람 기록 키: "작품명/화 파일명" (폴더를 다시 선택해도 유지되도록 이름 기반)
 */
class Prefs(ctx: Context) {

    private val settings: SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val progress: SharedPreferences =
        ctx.getSharedPreferences("progress", Context.MODE_PRIVATE)

    // ---------------- 설정 ----------------

    var treeUri: Uri?
        get() = settings.getString("tree_uri", null)?.let { Uri.parse(it) }
        set(v) = settings.edit().putString("tree_uri", v?.toString()).apply()

    /** 좌우 여백 (화면 폭 대비 한쪽 %, 0~40) */
    var marginPercent: Int
        get() = settings.getInt("margin", 0)
        set(v) = settings.edit().putInt("margin", v).apply()

    /** 0=검정, 1=회색, 2=흰색 */
    var background: Int
        get() = settings.getInt("bg", 0)
        set(v) = settings.edit().putInt("bg", v).apply()

    /** 화 목록 내림차순 표시 여부 */
    var chapterDesc: Boolean
        get() = settings.getBoolean("chapter_desc", false)
        set(v) = settings.edit().putBoolean("chapter_desc", v).apply()

    /** 작품 정렬: false=이름순, true=최근 읽은 순 */
    var worksByRecent: Boolean
        get() = settings.getBoolean("works_recent", true)
        set(v) = settings.edit().putBoolean("works_recent", v).apply()

    // ---------------- 열람 기록 ----------------

    fun key(work: String, chapter: String) = "$work/$chapter"

    fun isRead(key: String) = progress.getBoolean("r:$key", false)

    fun setRead(key: String, read: Boolean) {
        progress.edit().apply {
            if (read) putBoolean("r:$key", true) else {
                remove("r:$key")
                remove("p:$key")
            }
        }.apply()
    }

    /** 진행률(0~100). 기록 없으면 -1 */
    fun percent(key: String): Int {
        val s = progress.getString("p:$key", null) ?: return -1
        return s.split(',').getOrNull(2)?.toIntOrNull() ?: -1
    }

    /** 저장된 스크롤 위치: (아이템 인덱스, 아이템 내 비율 0~1) */
    fun position(key: String): Pair<Int, Float>? {
        val s = progress.getString("p:$key", null) ?: return null
        val parts = s.split(',')
        val idx = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val frac = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        return idx to frac
    }

    fun savePosition(key: String, index: Int, frac: Float, percent: Int) {
        progress.edit().putString("p:$key", "$index,$frac,$percent").apply()
    }

    /** 작품별 마지막으로 본 화 (화 파일명) */
    fun lastChapter(work: String): String? = progress.getString("l:$work", null)

    fun lastReadTime(work: String): Long = progress.getLong("t:$work", 0L)

    fun setLastChapter(work: String, chapter: String) {
        progress.edit()
            .putString("l:$work", chapter)
            .putLong("t:$work", System.currentTimeMillis())
            .apply()
    }
}
