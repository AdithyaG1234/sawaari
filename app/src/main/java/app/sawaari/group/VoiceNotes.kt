package app.sawaari.group

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records short, small voice notes (AAC, 16 kHz mono, about 3 KB a second) and plays them back. */
class VoiceNotes(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var startedAt = 0L
    private val file get() = File(context.cacheDir, "voice-note.m4a")

    val recording: Boolean get() = recorder != null

    /** Starts recording; false if the microphone couldn't be opened. */
    fun start(): Boolean {
        stopPlayback()
        return runCatching {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(24_000)
            r.setMaxDuration(MAX_MS.toInt())
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            startedAt = System.currentTimeMillis()
            true
        }.getOrElse { recorder?.release(); recorder = null; false }
    }

    /** Stops recording. Returns the audio and its length, or null if it was too short to keep. */
    fun stop(): Pair<ByteArray, Long>? {
        val r = recorder ?: return null
        recorder = null
        val ms = (System.currentTimeMillis() - startedAt).coerceAtMost(MAX_MS)
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || ms < MIN_MS) return null
        return runCatching { file.readBytes() to ms }.getOrNull()
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }

    /** Plays a voice note; [onDone] runs when it ends. */
    fun play(audio: ByteArray, onDone: () -> Unit) {
        stopPlayback()
        val f = File(context.cacheDir, "voice-play.m4a")
        runCatching {
            f.writeBytes(audio)
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { stopPlayback(); onDone() }
                prepare()
                start()
            }
        }.onFailure { stopPlayback(); onDone() }
    }

    fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun release() { cancel(); stopPlayback() }

    companion object {
        const val MAX_MS = 30_000L
        const val MIN_MS = 700L
    }
}
