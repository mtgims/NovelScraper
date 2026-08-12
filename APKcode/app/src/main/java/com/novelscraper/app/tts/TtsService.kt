package com.novelscraper.app.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat.MediaStyle
import com.novelscraper.app.MainActivity
import com.novelscraper.app.R
import com.novelscraper.app.data.ChapterRead
import com.novelscraper.app.data.ProgressUpdate
import com.novelscraper.app.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Foreground service that narrates chapters with the device's native
 * TextToSpeech engine, so playback continues with the screen off / app
 * backgrounded (which the web app's Web Speech engine cannot do). A
 * MediaSession drives the lock-screen / media-notification transport and routes
 * headset buttons. State is mirrored to [TtsController] for the reader UI.
 */
class TtsService : LifecycleService() {

    private lateinit var tts: TextToSpeech
    private var ready = false
    private var pending: (() -> Unit)? = null

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // current chapter
    private var bookId = 0
    private var position = 0
    private var bookTitle = ""
    private var chapterTitle = ""
    private var sentences: List<String> = emptyList()
    private var cumChars = IntArray(0)   // chars before sentence i (for time estimate)
    private var totalChars = 0
    private var index = 0
    private var hasNext = false
    private var hasPrev = false
    private var playing = false

    private fun rate() = com.novelscraper.app.data.ReaderPrefs.ttsRate.value.coerceAtLeast(0.1f)
    private fun elapsedSec(): Int {
        val before = if (index in cumChars.indices) cumChars[index] else totalChars
        return (before * SEC_PER_CHAR / rate()).toInt()
    }
    private fun totalSec(): Int = (totalChars * SEC_PER_CHAR / rate()).toInt()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        session = MediaSessionCompat(this, "NovelScraperTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = skip(+1)
                override fun onSkipToPrevious() = skip(-1)
            })
            isActive = true
        }
        tts = TextToSpeech(this) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.setOnUtteranceProgressListener(progressListener)
                pending?.invoke()
                pending = null
            }
        }
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> {
                bookId = intent.getIntExtra(EXTRA_BOOK_ID, 0)
                position = intent.getIntExtra(EXTRA_POSITION, 1)
                bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: "NovelScraper"
                val start = intent.getIntExtra(EXTRA_INDEX, 0)
                startForegroundLoading()
                runWhenReady { loadAndSpeak(position, start) }
            }
            ACTION_TOGGLE -> if (playing) pause() else resume()
            ACTION_NEXT -> skip(+1)
            ACTION_PREV -> skip(-1)
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> {
                val i = intent.getIntExtra(EXTRA_INDEX, index)
                if (sentences.isNotEmpty()) runWhenReady { requestFocus(); speakFrom(i) }
            }
            ACTION_SETRATE -> if (playing) runWhenReady { speakFrom(index) } // re-apply rate/voice
        }
        return START_NOT_STICKY
    }

    // --- playback -------------------------------------------------------

    private fun runWhenReady(block: () -> Unit) {
        if (ready) block() else pending = block
    }

    private fun loadAndSpeak(pos: Int, startIndex: Int = 0) {
        lifecycleScope.launch {
            val chapter: ChapterRead = try {
                Net.api.chapter(bookId, pos)
            } catch (e: Exception) {
                stopPlayback(); return@launch
            }
            position = pos
            chapterTitle = chapter.title
            hasNext = chapter.has_next
            hasPrev = chapter.has_prev
            sentences = toSentences(chapter.content)
            cumChars = IntArray(sentences.size)
            var acc = 0
            for (i in sentences.indices) { cumChars[i] = acc; acc += sentences[i].length }
            totalChars = acc
            index = 0
            // Listening marks the chapter read + moves the resume point.
            launch(Dispatchers.IO) {
                try { Net.api.putProgress(bookId, ProgressUpdate(last_position = pos, mark_read = pos)) }
                catch (_: Exception) {}
            }
            if (sentences.isEmpty()) { skip(+1); return@launch }
            requestFocus()
            speakFrom(startIndex)
        }
    }

    private fun speakFrom(from: Int) {
        index = from.coerceIn(0, sentences.size)
        if (index >= sentences.size) { onChapterFinished(); return }
        playing = true
        tts.setSpeechRate(com.novelscraper.app.data.ReaderPrefs.ttsRate.value)
        val voiceName = com.novelscraper.app.data.ReaderPrefs.ttsVoice.value
        if (voiceName.isNotBlank()) {
            runCatching { tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it } }
        }
        var q = TextToSpeech.QUEUE_FLUSH
        for (i in index until sentences.size) {
            tts.speak(sentences[i], q, null, i.toString())
            q = TextToSpeech.QUEUE_ADD
        }
        publish(true)
    }

    private fun resume() {
        if (playing || sentences.isEmpty()) return
        runWhenReady { requestFocus(); speakFrom(index) }
    }

    private fun pause() {
        if (!playing) return
        playing = false
        if (::tts.isInitialized) tts.stop()
        publish(true)
    }

    private fun skip(delta: Int) {
        val target = position + delta
        if (delta > 0 && !hasNext) return
        if (delta < 0 && !hasPrev) return
        if (target < 1) return
        if (::tts.isInitialized) tts.stop()
        startForegroundLoading()
        runWhenReady { loadAndSpeak(target) }
    }

    private fun onChapterFinished() {
        if (hasNext) skip(+1) else stopPlayback()
    }

    private fun stopPlayback() {
        playing = false
        if (::tts.isInitialized) tts.stop()
        abandonFocus()
        TtsController.clear()
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // Track position for pause/resume + push live progress (index / time) to
            // the reader pill, without rebuilding the notification each sentence.
            utteranceId?.toIntOrNull()?.let { index = it }
            publish(false)
        }
        override fun onDone(utteranceId: String?) {
            val i = utteranceId?.toIntOrNull() ?: return
            if (i >= sentences.size - 1 && playing) onChapterFinished()
        }
        @Deprecated("deprecated in API level 21")
        override fun onError(utteranceId: String?) {}
    }

    // --- audio focus ----------------------------------------------------

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    AudioManager.AUDIOFOCUS_GAIN -> resume()
                }
            }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // --- notification / session -----------------------------------------

    private fun startForegroundLoading() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    /** Update the UI [TtsController] state (always) and, when [updateNotification]
     *  is set, the media session + notification (only needed on play/pause/chapter
     *  changes, not on every sentence). */
    private fun publish(updateNotification: Boolean) {
        if (updateNotification) {
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP,
                    )
                    .setState(
                        if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f,
                    )
                    .build(),
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        }
        TtsController.update(
            TtsController.State(
                active = true, playing = playing,
                bookId = bookId, position = position, chapterTitle = chapterTitle,
                sentenceIndex = index, sentenceCount = sentences.size,
                elapsedSec = elapsedSec(), totalSec = totalSec(),
            ),
        )
    }

    private fun buildNotification(): Notification {
        val contentPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIcon = if (playing) android.R.drawable.ic_media_pause
                         else android.R.drawable.ic_media_play
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(chapterTitle.ifBlank { "Narrating…" })
            .setContentText(bookTitle.ifBlank { "NovelScraper" })
            .setContentIntent(contentPI)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setDeleteIntent(action(ACTION_STOP))
            .addAction(android.R.drawable.ic_media_previous, "Prev", action(ACTION_PREV))
            .addAction(toggleIcon, if (playing) "Pause" else "Play", action(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP))
            .setStyle(
                MediaStyle().setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun action(a: String): PendingIntent =
        PendingIntent.getService(
            this, a.hashCode(),
            Intent(this, TtsService::class.java).setAction(a),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Narration", NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(noisyReceiver)
        abandonFocus()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        if (::session.isInitialized) session.release()
        TtsController.clear()
    }

    private fun toSentences(html: String): List<String> {
        // Shared splitter so reader highlight/tap indices match what we speak.
        val plain = com.novelscraper.app.data.Sentences.plain(html)
        return com.novelscraper.app.data.Sentences.ranges(plain)
            .map { plain.substring(it.first, it.last + 1) }
    }

    companion object {
        const val ACTION_PLAY = "com.novelscraper.app.tts.PLAY"
        const val ACTION_TOGGLE = "com.novelscraper.app.tts.TOGGLE"
        const val ACTION_NEXT = "com.novelscraper.app.tts.NEXT"
        const val ACTION_PREV = "com.novelscraper.app.tts.PREV"
        const val ACTION_STOP = "com.novelscraper.app.tts.STOP"
        const val ACTION_SEEK = "com.novelscraper.app.tts.SEEK"
        const val ACTION_SETRATE = "com.novelscraper.app.tts.SETRATE"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_POSITION = "position"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL_ID = "tts"
        private const val NOTIF_ID = 42
        private const val SEC_PER_CHAR = 0.06f
    }
}
