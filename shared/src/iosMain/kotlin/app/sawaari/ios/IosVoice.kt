@file:OptIn(ExperimentalForeignApi::class)

package app.sawaari.ios

import app.sawaari.util.nowMillis
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.darwin.NSObject

/**
 * Records short voice notes (AAC in .m4a, 16 kHz mono, 24 kbit/s, the same as Android) and plays them back.
 * Notes cross Firebase as Base64 text, so iPhone and Android riders can hear each other.
 */
class VoiceNotes {
    private var recorder: AVAudioRecorder? = null
    private var player: AVAudioPlayer? = null
    private var playerDelegate: NSObject? = null
    private var startedAt = 0L
    private val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "voice-note.m4a")

    val recording: Boolean get() = recorder != null

    /** Asks for the microphone the first time; [onResult] gets whether it's allowed. */
    fun askPermission(onResult: (Boolean) -> Unit) {
        @Suppress("DEPRECATION")
        AVAudioSession.sharedInstance().requestRecordPermission { ok -> onResult(ok) }
    }

    /** Starts recording; false if the microphone couldn't be opened. */
    fun start(): Boolean {
        stopPlayback()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionDuckOthers, null)
        session.setActive(true, null)
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
            AVSampleRateKey to 16_000.0,
            AVNumberOfChannelsKey to 1,
            AVEncoderBitRateKey to 24_000,
        )
        val r = AVAudioRecorder(uRL = url, settings = settings, error = null)
        if (!r.recordForDuration(MAX_MS / 1000.0)) return false
        recorder = r
        startedAt = nowMillis()
        return true
    }

    /** Stops recording. Returns the audio in Base64 and its length, or null if it was too short to keep. */
    fun stop(): Pair<String, Long>? {
        val r = recorder ?: return null
        recorder = null
        val ms = (nowMillis() - startedAt).coerceAtMost(MAX_MS)
        r.stop()
        restoreSession()
        if (ms < MIN_MS) return null
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        return data.base64EncodedStringWithOptions(0u) to ms
    }

    fun cancel() {
        recorder?.stop()
        recorder = null
        restoreSession()
    }

    /** Plays a voice note from its Base64 text; [onDone] runs when it ends. */
    fun play(base64Audio: String, onDone: () -> Unit) {
        stopPlayback()
        val data = NSData.create(base64EncodedString = base64Audio, options = 0u) ?: return onDone()
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionCategoryOptionDuckOthers, null)
        val p = AVAudioPlayer(data = data, error = null)
        val d = object : NSObject(), AVAudioPlayerDelegateProtocol {
            override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
                stopPlayback()
                onDone()
            }
        }
        playerDelegate = d
        p.delegate = d
        if (!p.play()) { stopPlayback(); onDone(); return }
        player = p
    }

    fun stopPlayback() {
        player?.stop()
        player = null
        playerDelegate = null
    }

    fun release() { cancel(); stopPlayback() }

    private fun restoreSession() {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionCategoryOptionDuckOthers, null)
    }

    companion object {
        const val MAX_MS = 30_000L
        const val MIN_MS = 700L
    }
}
