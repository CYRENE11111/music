package com.example.exp7

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.exp7.MusicService.MusicBinder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var listView: ListView

    /** ContentResolver 查询到的音乐列表 */
    private val songs = mutableListOf<Song>()
    private val adapter = SongAdapter()

    @Volatile
    private var musicService: MusicService? = null
    private var bindRequested = false

    /** 用户正在拖动 SeekBar 时，进度线程不要覆盖用户操作 */
    private var userSeeking = false

    /** 上一次在列表中高亮的歌曲，避免频繁刷新列表 */
    private var highlightedSongId: Long? = null

    /** 当前播放按钮显示的是否为“暂停”图标 */
    private var showingPauseIcon = false

    private var progressExecutor: ScheduledExecutorService? = null

    /** 读取音乐文件权限：Android 13（API 33）起改为 READ_MEDIA_AUDIO */
    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 通知权限被拒绝不影响查询和播放，只检查音频权限
        if (hasAudioPermission()) {
            loadSongs()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicBinder
            musicService = binder.service
            // 把查询到的歌曲列表交给服务，并同步界面状态
            musicService?.setPlaylist(songs)
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        setupListeners()

        listView.adapter = adapter
        listView.emptyView = findViewById(R.id.tvEmpty)
        requestPermissionsIfNeeded()
        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        if (!bindRequested) {
            bindRequested = bindService(
                Intent(this, MusicService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
        startProgressUpdater()
    }

    override fun onStop() {
        if (bindRequested) {
            unbindService(connection)
            bindRequested = false
        }
        musicService = null
        stopProgressUpdater()
        super.onStop()
    }

    private fun bindViews() {
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        listView = findViewById(R.id.lvSongs)
    }

    private fun setupListeners() {
        listView.setOnItemClickListener { _, _, position, _ ->
            musicService?.playAt(position)
            refreshUi()
        }

        btnPlayPause.setOnClickListener {
            musicService?.togglePlay()
            refreshUi()
        }
        findViewById<ImageButton>(R.id.btnPrevious).setOnClickListener {
            musicService?.playPrevious()
            refreshUi()
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            musicService?.playNext()
            refreshUi()
        }
        findViewById<ImageButton>(R.id.btnStop).setOnClickListener {
            musicService?.stopPlayback()
            refreshUi()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                musicService?.seekTo(seekBar.progress)
                userSeeking = false
            }
        })
    }

    // ---------------- 权限与音乐列表 ----------------

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(audioPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            loadSongs()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** 使用 ContentResolver 从 MediaStore 查询音乐文件列表 */
    private fun loadSongs() {
        songs.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                songs.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleColumn) ?: getString(R.string.unknown_title),
                        artist = cursor.getString(artistColumn) ?: getString(R.string.unknown_artist),
                        duration = cursor.getLong(durationColumn),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        )
                    )
                )
            }
        }

        adapter.notifyDataSetChanged()
        musicService?.setPlaylist(songs)

        if (songs.isEmpty()) {
            Toast.makeText(this, R.string.no_music_found, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- 进度刷新（后台线程 + 主线程更新 UI） ----------------

    private fun startProgressUpdater() {
        if (progressExecutor != null) return
        progressExecutor = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) refreshUi()
                }
            }, 0, PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopProgressUpdater() {
        progressExecutor?.shutdownNow()
        progressExecutor = null
    }

    private fun updateProgress(position: Int, duration: Int, playing: Boolean) {
        // 拖动过程中不要覆盖用户正在调整的进度
        if (userSeeking) return
        if (seekBar.max != duration) {
            seekBar.max = duration
        }
        seekBar.progress = position
        val current = formatTime(position.toLong())
        if (tvCurrentTime.text.toString() != current) {
            tvCurrentTime.text = current
        }
        val total = formatTime(duration.toLong())
        if (tvTotalTime.text.toString() != total) {
            tvTotalTime.text = total
        }
        setPlayPauseIcon(playing)
    }

    private fun setPlayPauseIcon(playing: Boolean) {
        if (showingPauseIcon == playing) return
        showingPauseIcon = playing
        btnPlayPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    /** 根据服务当前状态刷新歌曲信息与控制按钮 */
    private fun refreshUi() {
        val service = musicService
        val song = service?.currentSong
        if (song != null) {
            if (tvSongTitle.text.toString() != song.title) {
                tvSongTitle.text = song.title
            }
            if (tvArtist.text.toString() != song.artist) {
                tvArtist.text = song.artist
            }
            if (!userSeeking) {
                updateProgress(service.position, service.duration, service.isPlaying)
            }
        } else {
            tvSongTitle.text = getString(R.string.no_song_playing)
            tvArtist.text = getString(R.string.select_song_hint)
            seekBar.max = 0
            seekBar.progress = 0
            tvCurrentTime.text = formatTime(0)
            tvTotalTime.text = formatTime(0)
            setPlayPauseIcon(false)
        }
        // 当前歌曲变化时刷新列表的高亮状态
        if (highlightedSongId != song?.id) {
            highlightedSongId = song?.id
            adapter.notifyDataSetChanged()
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /** 歌曲列表适配器 */
    private inner class SongAdapter : BaseAdapter() {
        override fun getCount(): Int = songs.size

        override fun getItem(position: Int): Song = songs[position]

        override fun getItemId(position: Int): Long = songs[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_song, parent, false)
            val song = songs[position]
            val selected = musicService?.currentSong?.id == song.id
            view.findViewById<TextView>(R.id.tvItemTitle).text = song.title
            view.findViewById<TextView>(R.id.tvItemArtist).text =
                getString(R.string.song_artist_and_duration, song.artist, formatTime(song.duration))
            view.setBackgroundColor(
                if (selected) ContextCompat.getColor(this@MainActivity, R.color.song_selected)
                else 0
            )
            return view
        }
    }

    companion object {
        /** 进度刷新间隔：300ms */
        private const val PROGRESS_INTERVAL_MS = 300L
    }
}
