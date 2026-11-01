package com.example.smartwall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object WallpaperRepo {
    private const val PREFS_NAME = "wall_prefs"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_QUEUE_INDEX = "queue_index"

    @Volatile
    private var preloadedBytes: ByteArray? = null
    @Volatile
    private var preloadedFilePath: String? = null
    private val isApplying = AtomicBoolean(false)

    fun getWallpaperDir(context: Context): File {
        val dir = File(context.filesDir, "saved_wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSavedFiles(context: Context): List<File> {
        val files = getWallpaperDir(context).listFiles() ?: return emptyList()
        return files.filter { it.isFile }.sortedByDescending {
            it.name.substringAfter("wall_").substringBefore(".").toLongOrNull() ?: 0L
        }
    }

    fun isAutoEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ENABLED, false)
    }

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
    }

    fun getQueueIndex(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_QUEUE_INDEX, 0)
    }

    fun setQueueIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_QUEUE_INDEX, index).apply()
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(context.packageName)
    }

    fun decodeOptimizedBitmap(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxWidth * 2 || options.outHeight / sampleSize > maxHeight * 2) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    fun cropAndCompressToScreenSize(context: Context, rawBitmap: Bitmap): ByteArray {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val cropped = ThumbnailUtils.extractThumbnail(
            rawBitmap,
            screenW,
            screenH,
            ThumbnailUtils.OPTIONS_RECYCLE_INPUT
        )

        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 100, out)
        return out.toByteArray()
    }

    fun preloadNextInRam(context: Context) {
        try {
            val files = getSavedFiles(context)
            if (files.isEmpty()) {
                preloadedBytes = null
                preloadedFilePath = null
                return
            }
            val nextIdx = getQueueIndex(context) % files.size
            val targetFile = files[nextIdx]

            if (preloadedFilePath == targetFile.absolutePath && preloadedBytes != null) return

            val metrics = context.resources.displayMetrics
            val bmp = decodeOptimizedBitmap(targetFile, metrics.widthPixels, metrics.heightPixels)
            if (bmp != null) {
                val exactBytes = cropAndCompressToScreenSize(context, bmp)
                preloadedBytes = exactBytes
                preloadedFilePath = targetFile.absolutePath
            } else {
                preloadedBytes = targetFile.readBytes()
                preloadedFilePath = targetFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun applyNextWallpapersFast(context: Context) {
        if (!isApplying.compareAndSet(false, true)) return
        try {
            val files = getSavedFiles(context)
            if (files.isEmpty()) return

            val currentIndex = getQueueIndex(context) % files.size
            val fileToApply = files[currentIndex]

            val bytes = if (preloadedFilePath == fileToApply.absolutePath && preloadedBytes != null) {
                preloadedBytes!!
            } else {
                val metrics = context.resources.displayMetrics
                val bmp = decodeOptimizedBitmap(fileToApply, metrics.widthPixels, metrics.heightPixels)
                if (bmp != null) cropAndCompressToScreenSize(context, bmp) else fileToApply.readBytes()
            }

            val wm = WallpaperManager.getInstance(context)
            ByteArrayInputStream(bytes).use { stream ->
                wm.setStream(stream, null, false, WallpaperManager.FLAG_LOCK)
            }
            ByteArrayInputStream(bytes).use { stream ->
                wm.setStream(stream, null, false, WallpaperManager.FLAG_SYSTEM)
            }

            val nextIndex = (currentIndex + 1) % files.size
            setQueueIndex(context, nextIndex)
            preloadNextInRam(context)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isApplying.set(false)
        }
    }

    fun applyWallpaperManual(context: Context, file: File, flag: Int): Boolean {
        return try {
            val metrics = context.resources.displayMetrics
            val bmp = decodeOptimizedBitmap(file, metrics.widthPixels, metrics.heightPixels)
                ?: return false
            val bytes = cropAndCompressToScreenSize(context, bmp)
            val wm = WallpaperManager.getInstance(context)
            ByteArrayInputStream(bytes).use { stream ->
                wm.setStream(stream, null, false, flag)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var countText: TextView
    private lateinit var queueStatusText: TextView
    private lateinit var autoSwitch: SwitchMaterial
    private lateinit var adapter: WallpaperAdapter
    private val ioExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickWallpapersLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) saveSelectedImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(32, 48, 32, 24)
        }

        val title = TextView(this).apply {
            text = "Smart Wallpaper Manager"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        countText = TextView(this).apply {
            text = "Saved Wallpapers: 0 (Newest First)"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 8, 0, 20)
        }
        root.addView(countText)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(32, 24, 32, 24)
        }

        autoSwitch = SwitchMaterial(this).apply {
            text = "Auto-Change Home & Lock Screen"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = WallpaperRepo.isAutoEnabled(this@MainActivity) &&
                    WallpaperRepo.isAccessibilityServiceEnabled(this@MainActivity)

            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (!WallpaperRepo.isAccessibilityServiceEnabled(this@MainActivity)) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Enable System Service")
                            .setMessage("Bina '1 active app' aur bina notification ke chalane ke liye Accessibility Settings -> Installed Apps -> SmartWall Auto Changer ko ON karein.")
                            .setPositiveButton("Open Settings") { _, _ ->
                                WallpaperRepo.setAutoEnabled(this@MainActivity, true)
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                            .setNegativeButton("Cancel") { _, _ ->
                                this.isChecked = false
                            }
                            .show()
                    } else {
                        WallpaperRepo.setAutoEnabled(this@MainActivity, true)
                        WallpaperRepo.setQueueIndex(this@MainActivity, 0)
                        ioExecutor.execute { WallpaperRepo.preloadNextInRam(this@MainActivity) }
                        Toast.makeText(this@MainActivity, "Auto Home & Lock Enabled!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    WallpaperRepo.setAutoEnabled(this@MainActivity, false)
                }
                refreshList()
            }
        }
        card.addView(autoSwitch)

        queueStatusText = TextView(this).apply {
            text = "Both Home and Lock will auto-change."
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 0)
        }
        card.addView(queueStatusText)
        root.addView(card)

        val saveBtn = MaterialButton(this).apply {
            text = "+ Save Wallpapers (Select Multiple)"
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 28, 0, 28)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 28, 0, 24) }
            layoutParams = params
            setOnClickListener { pickWallpapersLauncher.launch("image/*") }
        }
        root.addView(saveBtn)

        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = WallpaperAdapter { file, position -> showWallpaperOptionsDialog(file, position) }
        recycler.adapter = adapter
        root.addView(recycler)

        setContentView(root)
        ioExecutor.execute { WallpaperRepo.preloadNextInRam(this) }
    }

    override fun onResume() {
        super.onResume()
        val isServiceReady = WallpaperRepo.isAutoEnabled(this) &&
                WallpaperRepo.isAccessibilityServiceEnabled(this)
        autoSwitch.isChecked = isServiceReady
        refreshList()
    }

    private fun saveSelectedImages(uris: List<Uri>) {
        Toast.makeText(this, "Optimizing 9:16 & Saving...", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            val dir = WallpaperRepo.getWallpaperDir(this)
            var baseTime = System.currentTimeMillis()

            for (uri in uris) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val rawBytes = input.readBytes()
                        val rawBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        baseTime += 1L
                        val outFile = File(dir, "wall_$baseTime.jpg")
                        if (rawBmp != null) {
                            val optimizedBytes = WallpaperRepo.cropAndCompressToScreenSize(this, rawBmp)
                            FileOutputStream(outFile).use { it.write(optimizedBytes) }
                        } else {
                            FileOutputStream(outFile).use { it.write(rawBytes) }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            WallpaperRepo.setQueueIndex(this, 0)
            WallpaperRepo.preloadNextInRam(this)
            mainHandler.post {
                Toast.makeText(this, "Saved successfully!", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }
    }

    private fun showWallpaperOptionsDialog(file: File, position: Int) {
        val options = arrayOf(
            "Set on Home Screen Only",
            "Set on Lock Screen Only",
            "Set on Both Screens Now",
            "Remove / Delete Wallpaper"
        )
        AlertDialog.Builder(this)
            .setTitle("Wallpaper #${position + 1}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyManual(file, WallpaperManager.FLAG_SYSTEM, "Home Screen Updated!")
                    1 -> applyManual(file, WallpaperManager.FLAG_LOCK, "Lock Screen Updated!")
                    2 -> {
                        ioExecutor.execute {
                            WallpaperRepo.applyWallpaperManual(this, file, WallpaperManager.FLAG_SYSTEM)
                            WallpaperRepo.applyWallpaperManual(this, file, WallpaperManager.FLAG_LOCK)
                            mainHandler.post { Toast.makeText(this, "Both Screens Updated!", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    3 -> {
                        file.delete()
                        ioExecutor.execute { WallpaperRepo.preloadNextInRam(this) }
                        Toast.makeText(this, "Wallpaper Removed", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                }
            }
            .show()
    }

    private fun applyManual(file: File, flag: Int, msg: String) {
        Toast.makeText(this, "Applying...", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            val ok = WallpaperRepo.applyWallpaperManual(this, file, flag)
            mainHandler.post {
                Toast.makeText(this, if (ok) msg else "Failed to set", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshList() {
        val files = WallpaperRepo.getSavedFiles(this)
        val nextIndex = if (files.isEmpty()) 0 else WallpaperRepo.getQueueIndex(this) % files.size
        countText.text = "Saved Wallpapers: ${files.size} (Newest First)"
        queueStatusText.text = if (files.isEmpty()) {
            "Save wallpapers to start auto-rotation."
        } else {
            "Next Auto-Change (Home & Lock): Wallpaper #${nextIndex + 1}"
        }
        adapter.submitList(files, nextIndex)
    }
}

class WallpaperAdapter(
    private val onClick: (File, Int) -> Unit
) : RecyclerView.Adapter<WallpaperAdapter.VH>() {

    private var items: List<File> = emptyList()
    private var nextQueueIndex: Int = 0
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(30)

    fun submitList(newItems: List<File>, nextIndex: Int) {
        items = newItems
        nextQueueIndex = nextIndex
        notifyDataSetChanged()
    }

    class VH(val container: FrameLayout, val img: ImageView, val badge: TextView) :
        RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val size = parent.resources.displayMetrics.widthPixels / 3 - 24
        val frame = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(size, (size * 1.5).toInt()).apply {
                setMargins(8, 8, 8, 8)
            }
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        val imageView = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val badgeView = TextView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
            setPadding(12, 6, 12, 6)
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC0F172A"))
        }
        frame.addView(imageView)
        frame.addView(badgeView)
        return VH(frame, imageView, badgeView)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = items[position]
        holder.img.tag = file.absolutePath
        holder.img.setImageBitmap(null)

        if (position == nextQueueIndex) {
            holder.badge.text = "#${position + 1} • NEXT"
            holder.badge.setBackgroundColor(Color.parseColor("#E62563EB"))
        } else {
            holder.badge.text = "#${position + 1}"
            holder.badge.setBackgroundColor(Color.parseColor("#B30F172A"))
        }

        val cached = cache.get(file.absolutePath)
        if (cached != null) {
            holder.img.setImageBitmap(cached)
        } else {
            executor.execute {
                val bmp = WallpaperRepo.decodeOptimizedBitmap(file, 300, 500)
                if (bmp != null) {
                    cache.put(file.absolutePath, bmp)
                    mainHandler.post {
                        if (holder.img.tag == file.absolutePath) holder.img.setImageBitmap(bmp)
                    }
                }
            }
        }

        holder.container.setOnClickListener { onClick(file, position) }
    }
}

class WallpaperLockService : AccessibilityService() {

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (!WallpaperRepo.isAutoEnabled(context)) return
                val pendingResult = goAsync()
                bgExecutor.execute {
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                        WallpaperRepo.applyNextWallpapersFast(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        bgExecutor.execute { WallpaperRepo.preloadNextInRam(this) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
