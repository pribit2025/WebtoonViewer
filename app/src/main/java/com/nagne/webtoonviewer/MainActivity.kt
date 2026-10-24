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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 라이브러리(작품 목록) 화면 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private val adapter = WorkAdapter()

    private var works: List<Work> = emptyList()
    private var scanJob: Job? = null

    /** 폴더 선택 (SAF) — 권한을 영구 보관하여 재실행 시 다시 고를 필요 없음 */
    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                // 읽기+쓰기 권한 (쓰기는 열람 기록 백업 파일 저장용)
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e2: SecurityException) {
                        Toast.makeText(this, "폴더 권한을 얻지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                prefs.treeUri = uri
                works = emptyList()
                restoreThenScan(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        list = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        emptyText = findViewById(R.id.empty_text)
        progress = findViewById(R.id.progress)
        findViewById<View>(R.id.btn_pick).setOnClickListener { pickFolder.launch(null) }

        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL).apply {
            ContextCompat.getDrawable(this@MainActivity, android.R.drawable.divider_horizontal_dark)
                ?.let { setDrawable(it) }
        })
        list.adapter = adapter

        scan()
    }

    override fun onResume() {
        super.onResume()
        // 리더에서 돌아왔을 때 진행 상태/정렬 갱신
        if (works.isNotEmpty()) showWorks()
    }

    override fun onPause() {
        super.onPause()
        Backup.saveAsync(this)
    }

    /** 라이브러리 폴더의 백업 파일에서 열람 기록을 복원한 뒤 스캔 */
    private fun restoreThenScan(tree: Uri) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { Backup.restore(this@MainActivity, tree) }.getOrDefault(0)
            }
            if (added > 0) {
                Toast.makeText(this@MainActivity, "이전 열람 기록을 복원했습니다.", Toast.LENGTH_SHORT).show()
            }
            scan()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_pick -> pickFolder.launch(null)
            R.id.action_refresh -> scan()
            R.id.action_sort -> {
                prefs.worksByRecent = !prefs.worksByRecent
                Toast.makeText(
                    this,
                    if (prefs.worksByRecent) "최근 읽은 순" else "이름순",
                    Toast.LENGTH_SHORT
                ).show()
                showWorks()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun hasPermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** 라이브러리 폴더 스캔 (백그라운드) */
    private fun scan() {
        val tree = prefs.treeUri
        if (tree == null || !hasPermission(tree)) {
            works = emptyList()
            emptyText.setText(R.string.empty_library)
            emptyView.visibility = View.VISIBLE
            adapter.submit(emptyList())
            return
        }
        scanJob?.cancel()
        emptyView.visibility = View.GONE
        scanJob = lifecycleScope.launch {
            // 1) 저장된 목록을 먼저 바로 표시 (실행 시 대기 없음)
            if (works.isEmpty()) {
                val cached = withContext(Dispatchers.IO) { Library.loadCache(this@MainActivity, tree) }
                if (!cached.isNullOrEmpty()) {
                    works = cached
                    showWorks()
                }
            }
            // 2) 백그라운드에서 폴더를 다시 읽어 바뀐 경우에만 갱신
            progress.visibility = if (works.isEmpty()) View.VISIBLE else View.GONE
            val result = withContext(Dispatchers.IO) {
                runCatching { Library.scan(this@MainActivity, tree) }
            }
            progress.visibility = View.GONE
            result.onSuccess { fresh ->
                withContext(Dispatchers.IO) { Library.saveCache(this@MainActivity, tree, fresh) }
                if (fresh != works) {
                    works = fresh
                    showWorks()
                }
            }.onFailure {
                if (works.isEmpty()) {
                    Toast.makeText(this@MainActivity, "폴더를 읽을 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show()
                    emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showWorks() {
        val sorted = if (prefs.worksByRecent) {
            works.sortedWith(
                compareByDescending<Work> { prefs.lastReadTime(it.name) }
                    .thenComparator { a, b -> Library.naturalCompareKeys(a.sortKey, b.sortKey) }
            )
        } else {
            works.sortedWith { a, b -> Library.naturalCompareKeys(a.sortKey, b.sortKey) }
        }
        if (sorted.isEmpty()) {
            emptyText.setText(R.string.no_works)
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
        adapter.submit(sorted)
    }

    private fun openWork(work: Work) {
        startActivity(
            Intent(this, ChapterListActivity::class.java)
                .putExtra(ChapterListActivity.EXTRA_WORK_NAME, work.name)
                .putExtra(ChapterListActivity.EXTRA_WORK_URI, work.uri.toString())
        )
    }

    // ---------------- 작품 목록 어댑터 ----------------

    private inner class WorkAdapter : RecyclerView.Adapter<WorkAdapter.VH>() {
        private var items: List<Work> = emptyList()

        fun submit(newItems: List<Work>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val bar: ProgressBar = v.findViewById(R.id.bar)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_work, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val w = items[position]
            val total = w.chapters.size
            val read = w.chapters.count { prefs.isRead(prefs.key(w.name, it.name)) }
            val last = prefs.lastChapter(w.name)
                ?.let { name -> w.chapters.firstOrNull { it.name == name } }
                ?.let { Library.displayName(it) }

            h.title.text = w.name
            h.subtitle.text = buildString {
                append("$read / ${total}화 읽음")
                if (w.subFolderCount > 0) append("  ·  폴더 ${w.subFolderCount}개")
                if (last != null) append("  ·  최근: $last")
            }
            // 완독한 작품은 흐리게 표시
            val done = total > 0 && read == total
            h.title.setTextColor(
                ContextCompat.getColor(this@MainActivity, if (done) R.color.text_read else R.color.text)
            )
            h.bar.progress = if (total == 0) 0 else read * 100 / total
            h.itemView.setOnClickListener { openWork(w) }
        }
    }
}
