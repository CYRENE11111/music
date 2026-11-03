package com.example.exp7

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 音乐播放服务：所有播放相关的操作（播放、暂停、停止、上一首、下一首、拖动进度）
 * 都在这里通过 MediaPlayer 完成。Activity 通过绑定服务拿到 [MusicBinder]，
 * 再调用本类的方法并读取歌曲名、播放进度等信息。
 */
class MusicService : Service() {

    /** 通过 Binder 把服务实例暴露给 Activity */
    inner class MusicBinder : Binder() {
        val service: MusicService get() = this@MusicService
    }

    private val binder = MusicBinder()

    private var mediaPlayer: MediaPlayer? = null
    private val playlist = mutableListOf<Song>()
    private var currentIndex = -1

    /** 是否已经进入前台服务（即已经开始播放并展示过通知） */
    private var foregroundStarted = false

    /** 当前歌曲，未播放任何歌曲时为 null */
    val currentSong: Song?
        get() = playlist.getOrNull(currentIndex)

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    /** 当前歌曲总时长（毫秒） */
    val duration: Int
        get() = mediaPlayer?.duration?.coerceAtLeast(0) ?: 0

    /** 当前播放位置（毫秒） */
    val position: Int
        get() = mediaPlayer?.currentPosition?.coerceAtLeast(0) ?: 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 即使 Activity 只是 bind，服务被系统重新拉起时也进入前台状态
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    /** Activity 查询到音乐列表后同步给服务 */
    fun setPlaylist(songs: List<Song>) {
        if (playlist == songs) return
        stopPlayback()
        playlist.clear()
        playlist.addAll(songs)
    }

    /** 播放列表中指定位置的歌曲 */
    fun playAt(index: Int) {
        val song = playlist.getOrNull(index) ?: return
        currentIndex = index
        releasePlayer()

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(applicationContext, song.uri)
            player.prepare()
            player.setOnCompletionListener { playNext() }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "无法播放歌曲：${song.title}", e)
            player.release()
            return
        }
        mediaPlayer = player
        updateNotification()
    }

    /** 播放/暂停切换：若当前没有 MediaPlayer，则从当前（或第一首）歌曲开始播放 */
    fun togglePlay() {
        val player = mediaPlayer
        if (player == null) {
            if (playlist.isNotEmpty()) {
                playAt(if (currentIndex in playlist.indices) currentIndex else 0)
            }
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            player.start()
        }
        updateNotification()
    }

    /** 暂停播放 */
    fun pause() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        updateNotification()
    }

    /** 停止播放并释放播放器，同时移除通知栏通知 */
    fun stopPlayback() {
        releasePlayer()
        currentIndex = -1
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    /** 下一首（最后一首之后回到第一首） */
    fun playNext() {
        if (playlist.isEmpty()) return
        val next = if (currentIndex + 1 < playlist.size) currentIndex + 1 else 0
        playAt(next)
    }

    /** 上一首（第一首之前跳到最后一首） */
    fun playPrevious() {
        if (playlist.isEmpty()) return
        val previous = if (currentIndex - 1 >= 0) currentIndex - 1 else playlist.size - 1
        playAt(previous)
    }

    /** 拖动 SeekBar 时调整播放进度 */
    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs.coerceAtLeast(0))
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // 播放器尚未进入有效状态时 stop() 会抛异常，忽略即可
            }
            it.release()
        }
        mediaPlayer = null
    }

    // ---------------- 通知栏 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /** 每首歌开始播放（以及暂停/停止）时更新通知栏内容 */
    private fun updateNotification() {
        // 还没播放过歌曲时不展示通知，避免一打开应用就出现空通知
        if (!foregroundStarted && mediaPlayer == null) return
        try {
            startForegroundCompat(buildNotification())
            foregroundStarted = true
        } catch (e: Exception) {
            // 例如应用处于后台时不允许启动前台服务，此时忽略，不影响播放
            Log.w(TAG, "更新通知失败", e)
        }
    }

    private fun buildNotification(): Notification {
        val song = currentSong
        val title = song?.title ?: getString(R.string.notification_idle_title)
        val text = when {
            song == null -> getString(R.string.notification_idle_text)
            isPlaying -> song.artist
            else -> getString(R.string.notification_paused, song.artist)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "MusicService"
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
