package com.example.smartwall

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

// Helper object for Managing Saved Wallpapers & Queue
object WallpaperRepo {
    private const val PREFS_NAME = "wall_prefs"
    private const val KEY_AUTO_ENABLED = "auto_enabled"
    private const val KEY_QUEUE_INDEX = "queue_index"

    fun getWallpaperDir(context: Context): File {
        val dir = File(context.filesDir, "saved_wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Sorted Newest First (Most recently saved at index 0)
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

    fun decodeOptimizedBitmap(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxWidth || options.outHeight / sampleSize > maxHeight) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }

    // Sets ONLY Lock Screen or ONLY Home Screen
    fun applyWallpaper(context: Context, file: File, flag: Int): Boolean {
        return try {
            val metrics = context.resources.displayMetrics
            val bmp = decodeOptimizedBitmap(file, metrics.widthPixels * 2, metrics.heightPixels * 2)
                ?: return false
            val wm = WallpaperManager.getInstance(context)
            wm.setBitmap(bmp, null, true, flag)
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

    // Pick multiple images at once (100+ supported)
    private val pickWallpapersLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            saveSelectedImages(uris)
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Root Dark UI Layout
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

        // Auto-Change Lock Screen Card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(32, 24, 32, 24)
        }

        autoSwitch = SwitchMaterial(this).apply {
            text = "Auto-Change Lock Screen (Screen Off/On)"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = WallpaperRepo.isAutoEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                WallpaperRepo.setAutoEnabled(this@MainActivity, isChecked)
                if (isChecked) {
                    WallpaperRepo.setQueueIndex(this@MainActivity, 0)
                    startLockService()
                    Toast.makeText(
                        this@MainActivity,
                        "Enabled! Turn screen Off & On to see Lock Screen change.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    stopLockService()
                }
                refreshList()
            }
        }
        card.addView(autoSwitch)

        queueStatusText = TextView(this).apply {
            text = "Home Screen is strictly Manual-Only."
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 8, 0, 0)
        }
        card.addView(queueStatusText)
        root.addView(card)

        // Save Wallpapers Button
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
            setOnClickListener {
                pickWallpapersLauncher.launch("image/*")
            }
        }
        root.addView(saveBtn)

        // Grid of Saved Wallpapers (3 columns)
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        adapter = WallpaperAdapter { file, position ->
            showWallpaperOptionsDialog(file, position)
        }
        recycler.adapter = adapter
        root.addView(recycler)

        setContentView(root)

        if (WallpaperRepo.isAutoEnabled(this)) {
            startLockService()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun saveSelectedImages(uris: List<Uri>) {
        Toast.makeText(this, "Saving ${uris.size} wallpaper(s)...", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            val dir = WallpaperRepo.getWallpaperDir(this)
            var baseTime = System.currentTimeMillis()
            // Save so the selected items are stored with newest timestamps
            for (uri in uris) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        baseTime += 1L
                        val outFile = File(dir, "wall_$baseTime.jpg")
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Reset queue index to 0 so newly saved wallpaper shows FIRST on next Screen Off/On
            WallpaperRepo.setQueueIndex(this, 0)
            mainHandler.post {
                Toast.makeText(this, "Saved ${uris.size} wallpaper(s)!", Toast.LENGTH_SHORT).show()
                refreshList()
            }
        }
    }

    private fun showWallpaperOptionsDialog(file: File, position: Int) {
        val options = arrayOf(
            "Set on Home Screen (Manual Only)",
            "Set on Lock Screen Now",
            "Remove / Delete Wallpaper"
        )
        AlertDialog.Builder(this)
            .setTitle("Wallpaper #${position + 1}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(this, "Setting Home Screen...", Toast.LENGTH_SHORT).show()
                        ioExecutor.execute {
                            val ok = WallpaperRepo.applyWallpaper(
                                this,
                                file,
                                WallpaperManager.FLAG_SYSTEM
                            )
                            mainHandler.post {
                                Toast.makeText(
                                    this,
                                    if (ok) "Home Screen Wallpaper Updated!" else "Failed to set",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    1 -> {
                        Toast.makeText(this, "Setting Lock Screen...", Toast.LENGTH_SHORT).show()
                        ioExecutor.execute {
                            val ok = WallpaperRepo.applyWallpaper(
                                this,
                                file,
                                WallpaperManager.FLAG_LOCK
                            )
                            WallpaperRepo.setQueueIndex(this, position + 1)
                            mainHandler.post {
                                Toast.makeText(
                                    this,
                                    if (ok) "Lock Screen Updated!" else "Failed to set",
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshList()
                            }
                        }
                    }
                    2 -> {
                        file.delete()
                        Toast.makeText(this, "Wallpaper Removed", Toast.LENGTH_SHORT).show()
                        refreshList()
                    }
                }
            }
            .show()
    }

    private fun refreshList() {
        val files = WallpaperRepo.getSavedFiles(this)
        val nextIndex = if (files.isEmpty()) 0 else WallpaperRepo.getQueueIndex(this) % files.size
        countText.text = "Saved Wallpapers: ${files.size} (Order: Most Recently Saved First)"
        queueStatusText.text = if (files.isEmpty()) {
            "Save wallpapers to start queue. Home Screen stays manual."
        } else {
            "Next Lock Screen on Turn Off/On: Wallpaper #${nextIndex + 1}"
        }
        adapter.submitList(files, nextIndex)
    }

    private fun startLockService() {
        val intent = Intent(this, WallpaperLockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopLockService() {
        stopService(Intent(this, WallpaperLockService::class.java))
    }
}

// RecyclerView Adapter with Thumbnail Memory Cache for 100+ Images
class WallpaperAdapter(
    private val onClick: (File, Int) -> Unit
) : RecyclerView.Adapter<WallpaperAdapter.VH>() {

    private var items: List<File> = emptyList()
    private var nextQueueIndex: Int = 0
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(150)

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
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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
                        if (holder.img.tag == file.absolutePath) {
                            holder.img.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        holder.container.setOnClickListener {
            onClick(file, position)
        }
    }
}

// Foreground Service listening for Screen Off / On
class WallpaperLockService : Service() {

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // When screen turns OFF, immediately set the NEXT wallpaper on Lock Screen
            // so when the user turns the screen back ON, the new wallpaper is ready!
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                if (!WallpaperRepo.isAutoEnabled(context)) return
                bgExecutor.execute {
                    val files = WallpaperRepo.getSavedFiles(context)
                    if (files.isNotEmpty()) {
                        val currentIndex = WallpaperRepo.getQueueIndex(context) % files.size
                        val fileToApply = files[currentIndex]
                        WallpaperRepo.applyWallpaper(
                            context,
                            fileToApply,
                            WallpaperManager.FLAG_LOCK
                        )
                        // Advance turn-by-turn to next image
                        val nextIndex = (currentIndex + 1) % files.size
                        WallpaperRepo.setQueueIndex(context, nextIndex)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun startForegroundNotification() {
        val channelId = "smart_wall_lock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Lock Screen Wallpaper Auto-Changer",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Lock Screen Auto-Wallpaper Active")
            .setContentText("Changes Lock Screen turn-by-turn on Screen Off/On")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
