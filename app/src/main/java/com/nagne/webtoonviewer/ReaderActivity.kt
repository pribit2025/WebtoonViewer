package com.nagne.webtoonviewer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.Future
import kotlin.math.roundToInt

/**
 * 웹툰 리더.
 * - 화 안의 모든 이미지를 끊김 없이 세로 연속 스크롤
 * - 좌우 여백 슬라이더로 이미지 폭(크기) 조절
 * - 읽던 위치 자동 저장/복원, 끝까지 보면 '읽음' 처리
 * - 다음 화 미리 압축해제 → 바로 이어보기
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WORK_NAME = "work_name"
        const val EXTRA_NAMES = "names"
        const val EXTRA_URIS = "uris"
        const val EXTRA_IS_DIR = "is_dir"
        const val EXTRA_INDEX = "index"

        private const val TYPE_PAGE = 0
        private const val TYPE_FOOTER = 1
    }

    private lateinit var prefs: Prefs
    private lateinit var decoder: PageDecoder

    // 뷰
    private lateinit var root: View
    private lateinit var rv: RecyclerView
    private lateinit var lm: PreloadLayoutManager
    private lateinit var loading: View
    private lateinit var loadingSpinner: View
    private lateinit var loadingText: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var workTitle: TextView
    private lateinit var chapterTitle: TextView
    private lateinit var seekPos: SeekBar
    private lateinit var posText: TextView
    private lateinit var seekMargin: SeekBar
    private lateinit var marginText: TextView
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton

    // 데이터
    private lateinit var workName: String
    private lateinit var names: List<String>
    private lateinit var uris: List<Uri>
    private lateinit var isDirs: BooleanArray
    private var index = 0
    private var pages: List<ChapterLoader.Page> = emptyList()
    private val adapter = PageAdapter()

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var overlayVisible = false
    private var userSeeking = false
    private var lastWidth = 0

    private val currentKey get() = prefs.key(workName, names[index])

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        prefs = Prefs(this)
        decoder = PageDecoder()

        workName = intent.getStringExtra(EXTRA_WORK_NAME) ?: ""
        names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        uris = (intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()).map { Uri.parse(it) }
        isDirs = intent.getBooleanArrayExtra(EXTRA_IS_DIR) ?: BooleanArray(names.size)
        index = savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)
        if (names.isEmpty() || names.size != uris.size) {
            finish()
            return
        }
        index = index.coerceIn(0, names.size - 1)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        bindViews()
        setupInsets()
        setupList()
        setupControls()
        applyBackground(prefs.background)
        setSystemBarsVisible(false)

        rv.doOnLayout {
            lastWidth = rv.width
            applyMargin(effectiveMargin(), keepAnchor = false)
            openChapter(index)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::decoder.isInitialized) decoder.shutdown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 폴드 접기/펼치기, 회전: 여백 적용 여부와 이미지 높이를 다시 계산
        updateMarginUi()
        rv.post { applyMargin(effectiveMargin(), keepAnchor = true) }
    }

    /**
     * 펼친 화면 여부. 갤럭시 폴드의 펼친 화면(최소 폭 600dp 이상)에서만 여백을 적용하고,
     * 접은 화면(커버 화면)은 항상 폭에 맞춰 표시한다.
     */
    private fun isWideScreen() = resources.configuration.smallestScreenWidthDp >= 600

    private fun effectiveMargin() = if (isWideScreen()) prefs.marginPercent else 0

    /** 여백 슬라이더 상태 표시: 접은 화면에서는 비활성 + 안내 */
    private fun updateMarginUi() {
        val wide = isWideScreen()
        seekMargin.isEnabled = wide
        seekMargin.alpha = if (wide) 1f else 0.4f
        marginText.text = if (wide) "${prefs.marginPercent}%" else "폭맞춤"
    }

    // ---------------------------------------------------------------- 초기화

    private fun bindViews() {
        root = findViewById(R.id.root)
        rv = findViewById(R.id.pages)
        loading = findViewById(R.id.loading)
        loadingSpinner = findViewById(R.id.loading_spinner)
        loadingText = findViewById(R.id.loading_text)
        topBar = findViewById(R.id.top_bar)
        bottomBar = findViewById(R.id.bottom_bar)
        workTitle = findViewById(R.id.work_title)
        chapterTitle = findViewById(R.id.chapter_title)
        seekPos = findViewById(R.id.seek_pos)
        posText = findViewById(R.id.pos_text)
        seekMargin = findViewById(R.id.seek_margin)
        marginText = findViewById(R.id.margin_text)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next_ch)
    }

    /** 상태바/내비게이션바/카메라 컷아웃 영역만큼 오버레이에 여백 적용 */
    private fun setupInsets() {
        val baseH = (16 * resources.displayMetrics.density).toInt()
        val baseV = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(bars.left, bars.top, bars.right, 0)
            bottomBar.setPadding(baseH + bars.left, baseV / 2, baseH + bars.right, baseV + bars.bottom)
            insets
        }
    }

    private fun setupList() {
        lm = PreloadLayoutManager(this)
        rv.layoutManager = lm
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(4)

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePosUi()
                checkRead()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && overlayVisible) showOverlay(false)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) savePosition()
            }
        })

        // 화면 폭이 바뀌면(회전 등) 높이 재계산
        rv.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val w = right - left
            if (lastWidth != 0 && w != oldRight - oldLeft && w > 0) {
                lastWidth = w
                rv.post { applyMargin(effectiveMargin(), keepAnchor = true) }
            }
        }

        // 탭 처리: 가운데 탭 = 메뉴 토글, (옵션) 위/아래 탭 = 한 화면 스크롤
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null && rv.getChildViewHolder(child) is FooterVH) return false
                if (overlayVisible) {
                    showOverlay(false)
                    return true
                }
                if (prefs.tapPaging) {
                    val h = rv.height
                    when {
                        e.y < h * 0.3f -> { scrollScreen(-1); return true }
                        e.y > h * 0.7f -> { scrollScreen(1); return true }
                    }
                }
                showOverlay(true)
                return true
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun setupControls() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnPrev.setOnClickListener { if (index > 0) openChapter(index - 1) }
        btnNext.setOnClickListener { if (index < names.size - 1) goNext() }

        // 진행 위치 스크러버
        seekPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && pages.isNotEmpty()) lm.scrollToPositionWithOffset(progress, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                savePosition()
            }
        })

        // 좌우 여백
        seekMargin.progress = prefs.marginPercent
        updateMarginUi()
        seekMargin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || !isWideScreen()) return
                marginText.text = "$progress%"
                prefs.marginPercent = progress
                applyMargin(progress, keepAnchor = true)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // 배경색
        val group = findViewById<MaterialButtonToggleGroup>(R.id.bg_group)
        val ids = intArrayOf(R.id.bg_black, R.id.bg_gray, R.id.bg_white)
        group.check(ids[prefs.background.coerceIn(0, 2)])
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val bg = ids.indexOf(checkedId)
            if (bg >= 0) {
                prefs.background = bg
                applyBackground(bg)
            }
        }

        // 옵션
        findViewById<MaterialSwitch>(R.id.sw_volume).apply {
            isChecked = prefs.volumeKeys
            setOnCheckedChangeListener { _, v -> prefs.volumeKeys = v }
        }
        findViewById<MaterialSwitch>(R.id.sw_tap).apply {
            isChecked = prefs.tapPaging
            setOnCheckedChangeListener { _, v -> prefs.tapPaging = v }
        }
    }

    // ---------------------------------------------------------------- 화 열기

    private fun goNext() {
        // 다음 화로 넘어가면 현재 화는 읽음 처리
        if (pages.isNotEmpty()) prefs.setRead(currentKey, true)
        openChapter(index + 1)
    }

    private fun openChapter(i: Int) {
        if (pages.isNotEmpty()) savePosition()
        loadJob?.cancel()
        prefetchJob?.cancel()

        index = i
        workTitle.text = workName
        chapterTitle.text = displayName(i)
        btnPrev.isEnabled = i > 0
        btnNext.isEnabled = i < names.size - 1
        prefs.setLastChapter(workName, names[i])

        pages = emptyList()
        adapter.notifyDataSetChanged()
        decoder.clear()
        loading.visibility = View.VISIBLE
        loadingSpinner.visibility = View.VISIBLE
        loadingText.setText(R.string.loading)

        loadJob = lifecycleScope.launch {
            try {
                val files = ChapterLoader.extract(this@ReaderActivity, uris[i], isDirs[i])
                val built = ChapterLoader.buildPages(files)
                if (built.isEmpty()) {
                    loadingSpinner.visibility = View.GONE
                    loadingText.text = "표시할 이미지가 없습니다."
                    return@launch
                }
                pages = built
                loading.visibility = View.GONE
                adapter.notifyDataSetChanged()
                restorePosition()
                rv.post { updatePosUi() }

                // 앞뒤 화만 캐시에 남기고, 다음 화는 미리 풀어둠
                val keep = listOfNotNull(uris.getOrNull(i - 1), uris[i], uris.getOrNull(i + 1))
                ChapterLoader.trimCache(this@ReaderActivity, keep)
                if (i + 1 < names.size) {
                    prefetchJob = lifecycleScope.launch {
                        try {
                            ChapterLoader.extract(this@ReaderActivity, uris[i + 1], isDirs[i + 1])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (ignored: Exception) {
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadingSpinner.visibility = View.GONE
                loadingText.text = "열 수 없습니다.\n${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun displayName(i: Int) =
        if (isDirs[i]) names[i] else names[i].substringBeforeLast('.')

    // ---------------------------------------------------------------- 위치 저장/복원

    private fun restorePosition() {
        val key = currentKey
        val saved = prefs.position(key)
        // 이미 다 본 화를 다시 열면 처음부터
        if (saved == null || prefs.isRead(key)) {
            lm.scrollToPositionWithOffset(0, 0)
            return
        }
        val idx = saved.first.coerceIn(0, pages.size - 1)
        val h = itemHeight(pages[idx])
        lm.scrollToPositionWithOffset(idx, -(saved.second * h).roundToInt())
    }

    private fun savePosition() {
        if (pages.isEmpty() || !::lm.isInitialized) return
        val first = lm.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        val v = lm.findViewByPosition(first)
        val frac = if (v != null && v.height > 0) (-v.top).toFloat() / v.height else 0f
        prefs.savePosition(currentKey, first.coerceAtMost(pages.size - 1), frac.coerceIn(0f, 1f), percent())
    }

    private fun percent(): Int {
        if (pages.isEmpty()) return 0
        val last = lm.findLastVisibleItemPosition()
        if (last == RecyclerView.NO_POSITION) return 0
        if (last >= pages.size) return 100
        return ((last + 1) * 100 / pages.size).coerceIn(0, 100)
    }

    /** 마지막(끝 표시)까지 보이면 읽음 처리 */
    private fun checkRead() {
        if (pages.isEmpty()) return
        if (lm.findLastVisibleItemPosition() == adapter.itemCount - 1 && !prefs.isRead(currentKey)) {
            prefs.setRead(currentKey, true)
        }
    }

    private fun updatePosUi() {
        if (pages.isEmpty()) {
            posText.text = ""
            return
        }
        seekPos.max = (pages.size - 1).coerceAtLeast(1)
        if (!userSeeking) {
            val first = lm.findFirstVisibleItemPosition()
            if (first != RecyclerView.NO_POSITION) seekPos.progress = first.coerceAtMost(pages.size - 1)
        }
        posText.text = "${percent()}%"
    }

    // ---------------------------------------------------------------- 표시 조절

    /** 이미지 표시 폭 (화면 폭 - 좌우 여백) */
    private fun contentWidth() = (rv.width - rv.paddingLeft - rv.paddingRight).coerceAtLeast(1)

    /** 디코딩 기준 폭: 여백과 무관하게 화면 폭 기준 (슬라이더 조절 시 재디코딩 방지) */
    private fun decodeWidth() = rv.width.coerceAtLeast(1)

    private fun itemHeight(p: ChapterLoader.Page): Int =
        (p.sliceH.toLong() * contentWidth() / p.imgW).toInt().coerceAtLeast(1)

    /**
     * 좌우 여백 적용. keepAnchor=true 이면 보고 있던 위치를 유지.
     */
    private fun applyMargin(percent: Int, keepAnchor: Boolean) {
        var anchorPos = RecyclerView.NO_POSITION
        var anchorFrac = 0f
        if (keepAnchor && pages.isNotEmpty()) {
            anchorPos = lm.findFirstVisibleItemPosition()
            val v = if (anchorPos != RecyclerView.NO_POSITION) lm.findViewByPosition(anchorPos) else null
            if (v != null && v.height > 0) anchorFrac = (-v.top).toFloat() / v.height
        }
        val px = rv.width * percent / 100
        rv.setPadding(px, 0, px, 0)
        if (pages.isEmpty()) return
        adapter.notifyDataSetChanged()
        if (anchorPos != RecyclerView.NO_POSITION) {
            val h = if (anchorPos < pages.size) itemHeight(pages[anchorPos]) else 0
            lm.scrollToPositionWithOffset(anchorPos, -(anchorFrac * h).roundToInt())
        }
    }

    private fun applyBackground(bg: Int) {
        val color = when (bg) {
            1 -> Color.rgb(0x3A, 0x3A, 0x3C)
            2 -> Color.WHITE
            else -> Color.BLACK
        }
        root.setBackgroundColor(color)
        rv.setBackgroundColor(color)
    }

    private fun scrollScreen(dir: Int) {
        rv.smoothScrollBy(0, dir * (rv.height * 0.85f).toInt())
    }

    // ---------------------------------------------------------------- 오버레이/시스템바

    private fun showOverlay(show: Boolean) {
        overlayVisible = show
        topBar.visibility = if (show) View.VISIBLE else View.GONE
        bottomBar.visibility = if (show) View.VISIBLE else View.GONE
        setSystemBarsVisible(show)
        if (show) updatePosUi()
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) c.show(WindowInsetsCompat.Type.systemBars())
        else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- 볼륨키 스크롤

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (prefs.volumeKeys && pages.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { scrollScreen(1); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { scrollScreen(-1); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (prefs.volumeKeys && pages.isNotEmpty() &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- 어댑터

    private class PageVH(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var future: Future<*>? = null
        var key: String? = null
    }

    private class FooterVH(v: View) : RecyclerView.ViewHolder(v) {
        val endText: TextView = v.findViewById(R.id.end_text)
        val btn: MaterialButton = v.findViewById(R.id.btn_next)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = if (pages.isEmpty()) 0 else pages.size + 1

        override fun getItemViewType(position: Int) =
            if (position < pages.size) TYPE_PAGE else TYPE_FOOTER

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_PAGE) {
                PageVH(inf.inflate(R.layout.item_page, parent, false) as ImageView)
            } else {
                FooterVH(inf.inflate(R.layout.item_footer, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is PageVH -> bindPage(holder, pages[position])
                is FooterVH -> bindFooter(holder)
            }
        }

        private fun bindPage(h: PageVH, p: ChapterLoader.Page) {
            // 이미지 로드 전에도 정확한 높이를 잡아 스크롤 위치가 튀지 않게 함
            val lp = h.image.layoutParams
            lp.height = itemHeight(p)
            h.image.layoutParams = lp

            h.future?.cancel(true)
            h.key = p.cacheKey
            val tw = decodeWidth()
            val cached = decoder.cached(p, tw)
            if (cached != null) {
                h.image.setImageBitmap(cached)
                return
            }
            h.image.setImageDrawable(null)
            val key = p.cacheKey
            h.future = decoder.request(p, tw) { bmp ->
                if (h.key == key && bmp != null) h.image.setImageBitmap(bmp)
            }
        }

        private fun bindFooter(h: FooterVH) {
            h.endText.text = "${displayName(index)} 끝"
            if (index < names.size - 1) {
                h.btn.text = "다음 화 · ${displayName(index + 1)}"
                h.btn.setOnClickListener { goNext() }
            } else {
                h.btn.text = "마지막 화입니다 · 목록으로"
                h.btn.setOnClickListener { finish() }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is PageVH) {
                holder.future?.cancel(true)
                holder.future = null
                holder.key = null
                holder.image.setImageDrawable(null)
            }
        }
    }
}

/** 화면 1장 분량을 위/아래로 미리 배치하여 스크롤 시 이미지가 미리 로드되도록 함 */
class PreloadLayoutManager(ctx: Context) : LinearLayoutManager(ctx) {
    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        val extra = height
        extraLayoutSpace[0] = extra
        extraLayoutSpace[1] = extra
    }
}
