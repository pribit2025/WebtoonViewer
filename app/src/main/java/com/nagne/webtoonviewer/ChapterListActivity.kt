package com.nagne.webtoonviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 화 목록 화면 — 읽은 화는 회색, 읽는 중인 화는 진행률(파란색), 마지막 본 화는 좌측 막대 표시 */
class ChapterListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WORK_NAME = "work_name"
        const val EXTRA_WORK_URI = "work_uri"
    }

    private lateinit var prefs: Prefs
    private lateinit var workName: String
    private lateinit var workUri: Uri
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var btnContinue: ExtendedFloatingActionButton
    private val adapter = ChapterAdapter()
    private val folderAdapter = FolderAdapter()

    /** 시즌/부 등 서브폴더 (눌러서 들어가야 그 안의 화가 보임) */
    private var folders: List<DocItem> = emptyList()

    /** 오름차순 화 목록 (원본 순서) */
    private var chapters: List<Chapter> = emptyList()
    private var firstShow = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapters)
        prefs = Prefs(this)
        workName = intent.getStringExtra(EXTRA_WORK_NAME) ?: ""
        workUri = Uri.parse(intent.getStringExtra(EXTRA_WORK_URI) ?: "")

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = workName
        toolbar.setNavigationOnClickListener { finish() }

        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        btnContinue = findViewById(R.id.btn_continue)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = ConcatAdapter(folderAdapter, adapter)
        btnContinue.setOnClickListener { continueTarget()?.let { openReader(it) } }

        load()
    }

    override fun onResume() {
        super.onResume()
        if (chapters.isNotEmpty() || folders.isNotEmpty()) {
            folderAdapter.notifyDataSetChanged()
            refresh()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chapters, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_reverse -> {
                prefs.chapterDesc = !prefs.chapterDesc
                refresh()
                list.scrollToPosition(0)
            }
            R.id.action_unread_all -> {
                AlertDialog.Builder(this)
                    .setMessage("모든 화의 열람 기록을 지울까요?")
                    .setPositiveButton("지우기") { _, _ ->
                        chapters.forEach { prefs.setRead(key(it), false) }
                        refresh()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun key(c: Chapter) = prefs.key(workName, c.name)

    private fun load() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Library.listFolder(this@ChapterListActivity, workUri) }
            }
            progress.visibility = View.GONE
            result.onSuccess {
                chapters = it.chapters
                folders = it.subFolders
                folderAdapter.notifyDataSetChanged()
                refresh()
            }.onFailure {
                Toast.makeText(this@ChapterListActivity, "목록을 읽을 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 표시 갱신: 정렬 방향, 읽음 상태, 이어보기 버튼 */
    private fun refresh() {
        val shown = if (prefs.chapterDesc) chapters.reversed() else chapters
        adapter.submit(shown)

        val read = chapters.count { prefs.isRead(key(it)) }
        supportActionBar?.subtitle = buildString {
            append("$read / ${chapters.size}화 읽음")
            if (folders.isNotEmpty()) append("  ·  폴더 ${folders.size}개")
        }

        val target = continueTarget()
        if (target == null) {
            btnContinue.visibility = View.GONE
        } else {
            btnContinue.visibility = View.VISIBLE
            val label = Library.displayName(chapters[target])
            btnContinue.text = if (prefs.lastChapter(workName) == null) "첫 화 보기 · $label" else "이어보기 · $label"
        }

        // 처음 열 때 마지막으로 본 화 근처로 스크롤
        if (firstShow) {
            firstShow = false
            val last = prefs.lastChapter(workName)
            val idx = shown.indexOfFirst { it.name == last }.let { if (it >= 0) it + folders.size else it }
            if (idx > 2) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx - 2, 0)
        }
    }

    /**
     * 이어보기 대상 화 (오름차순 인덱스)
     * - 마지막 본 화를 다 안 읽었으면 그 화
     * - 다 읽었으면 그 다음 화 중 첫 안읽은 화
     * - 기록이 없으면 첫 안읽은 화
     */
    private fun continueTarget(): Int? {
        if (chapters.isEmpty()) return null
        val lastIdx = chapters.indexOfFirst { it.name == prefs.lastChapter(workName) }
        if (lastIdx >= 0) {
            if (!prefs.isRead(key(chapters[lastIdx]))) return lastIdx
            for (i in lastIdx + 1 until chapters.size) if (!prefs.isRead(key(chapters[i]))) return i
            return lastIdx
        }
        val firstUnread = chapters.indexOfFirst { !prefs.isRead(key(it)) }
        return if (firstUnread >= 0) firstUnread else 0
    }

    private fun openReader(ascIndex: Int) {
        val intent = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_WORK_NAME, workName)
            .putStringArrayListExtra(ReaderActivity.EXTRA_NAMES, ArrayList(chapters.map { it.name }))
            .putStringArrayListExtra(ReaderActivity.EXTRA_URIS, ArrayList(chapters.map { it.uri.toString() }))
            .putExtra(ReaderActivity.EXTRA_IS_DIR, chapters.map { it.isDir }.toBooleanArray())
            .putExtra(ReaderActivity.EXTRA_INDEX, ascIndex)
        startActivity(intent)
    }

    /** 길게 눌러 읽음 상태 변경 */
    private fun showChapterMenu(c: Chapter) {
        val ascIdx = chapters.indexOf(c)
        val isRead = prefs.isRead(key(c))
        val options = arrayOf(
            if (isRead) "안읽음으로 표시" else "읽음으로 표시",
            "이 화까지 모두 읽음 처리",
            "이 화부터 끝까지 안읽음 처리",
        )
        AlertDialog.Builder(this)
            .setTitle(Library.displayName(c))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> prefs.setRead(key(c), !isRead)
                    1 -> for (i in 0..ascIdx) prefs.setRead(key(chapters[i]), true)
                    2 -> for (i in ascIdx until chapters.size) prefs.setRead(key(chapters[i]), false)
                }
                refresh()
            }
            .show()
    }

    /** 서브폴더 열기: 같은 화면을 그 폴더 기준으로 다시 연다 */
    private fun openFolder(f: DocItem) {
        startActivity(
            Intent(this, ChapterListActivity::class.java)
                .putExtra(EXTRA_WORK_NAME, "$workName / ${f.name}")
                .putExtra(EXTRA_WORK_URI, f.uri.toString())
        )
    }

    // ---------------- 서브폴더 어댑터 (목록 맨 위) ----------------

    private inner class FolderAdapter : RecyclerView.Adapter<FolderAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val marker: View = v.findViewById(R.id.marker)
            val name: TextView = v.findViewById(R.id.name)
            val status: TextView = v.findViewById(R.id.status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

        override fun getItemCount() = folders.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = folders[position]
            val ctx = this@ChapterListActivity
            val sub = "$workName / ${f.name}"
            h.marker.visibility = View.INVISIBLE
            h.name.text = f.name
            h.name.setTextColor(ContextCompat.getColor(ctx, R.color.text))
            h.status.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            // 그 폴더에서 마지막으로 본 화가 있으면 표시
            h.status.text = if (prefs.lastChapter(sub) != null) "보는 중 ›" else "폴더 ›"
            h.itemView.setOnClickListener { openFolder(f) }
            h.itemView.setOnLongClickListener(null)
        }
    }

    // ---------------- 화 목록 어댑터 ----------------

    private inner class ChapterAdapter : RecyclerView.Adapter<ChapterAdapter.VH>() {
        private var items: List<Chapter> = emptyList()

        fun submit(newItems: List<Chapter>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val marker: View = v.findViewById(R.id.marker)
            val name: TextView = v.findViewById(R.id.name)
            val status: TextView = v.findViewById(R.id.status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            val k = key(c)
            val read = prefs.isRead(k)
            val pct = prefs.percent(k)
            val isLast = prefs.lastChapter(workName) == c.name
            val ctx = this@ChapterListActivity

            h.name.text = Library.displayName(c)
            h.marker.visibility = if (isLast) View.VISIBLE else View.INVISIBLE

            when {
                read -> {
                    // 이미 본 화: 회색
                    h.name.setTextColor(ContextCompat.getColor(ctx, R.color.text_read))
                    h.status.setTextColor(ContextCompat.getColor(ctx, R.color.text_read))
                    h.status.text = "읽음 ✓"
                }
                pct >= 0 -> {
                    // 보는 중: 파란색 + 진행률
                    h.name.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                    h.status.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                    h.status.text = "$pct%"
                }
                else -> {
                    h.name.setTextColor(ContextCompat.getColor(ctx, R.color.text))
                    h.status.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    h.status.text = if (c.isDir) "폴더" else ""
                }
            }
            h.itemView.setOnClickListener { openReader(chapters.indexOf(c)) }
            h.itemView.setOnLongClickListener { showChapterMenu(c); true }
        }
    }
}
