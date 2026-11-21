package com.example.multikamera

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Nagrywa jeden film MP4: obraz rysowany przez Canvas (podział ekranu) -> H.264,
 * dźwięk z mikrofonu -> AAC.
 */
class SplitScreenRecorder(
    private val output: File,
    val width: Int = 1280,
    val height: Int = 720,
    withAudio: Boolean,
) {
    private companion object {
        const val VIDEO = 0
        const val AUDIO = 1
        const val SAMPLE_RATE = 44100
        const val STOP_TIMEOUT_MS = 3000L
    }

    private class Pending(val kind: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var audio: MediaCodec? = null
    private var mic: AudioRecord? = null
    private lateinit var surface: Surface

    private val drawLock = Any()
    private val muxLock = Any()
    private val tracks = intArrayOf(-1, -1)
    private val lastPts = longArrayOf(-1, -1)
    private val pending = ArrayList<Pending>()
    private var muxerStarted = false
    private var samplesWritten = 0
    private val expectAudio: Boolean

    @Volatile private var running = true
    @Volatile private var stopRequestedAt = 0L
    private val startUs = System.nanoTime() / 1000
    private val workers = ArrayList<Thread>()

    init {
        expectAudio = withAudio && prepareAudio()
    }

    @SuppressLint("MissingPermission") // uprawnienie sprawdza wywołujący
    private fun prepareAudio(): Boolean = try {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            false
        } else {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mic = rec
            audio = codec
            true
        }
    } catch (e: Exception) {
        false
    }

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = video.createInputSurface()
        video.start()
        workers += thread(name = "video-encoder") { drainUntilEos(video, VIDEO) }

        val codec = audio
        val rec = mic
        if (codec != null && rec != null) {
            codec.start()
            rec.startRecording()
            workers += thread(name = "audio-encoder") { audioLoop(codec, rec) }
        }
    }

    /** Rysuje jedną klatkę filmu. Wołane z wątku renderującego. */
    fun drawFrame(draw: (Canvas) -> Unit) {
        synchronized(drawLock) {
            if (!running) return
            val canvas = surface.lockHardwareCanvas()
            try {
                draw(canvas)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun audioLoop(codec: MediaCodec, rec: AudioRecord) {
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        val base = System.nanoTime() / 1000
        var eosQueued = false
        while (true) {
            if (!eosQueued) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buf = codec.getInputBuffer(index)!!
                    buf.clear()
                    val pts = base + samples * 1_000_000 / SAMPLE_RATE
                    if (!running) {
                        codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    } else {
                        val n = rec.read(buf, minOf(buf.capacity(), 8192))
                        val size = maxOf(n, 0)
                        codec.queueInputBuffer(index, 0, size, pts, 0)
                        samples += size / 2
                    }
                }
            }
            if (drainOnce(codec, AUDIO, info, if (eosQueued) 10_000 else 0)) break
            if (timedOut()) break
        }
    }

    private fun drainUntilEos(codec: MediaCodec, kind: Int) {
        val info = MediaCodec.BufferInfo()
        while (!drainOnce(codec, kind, info, 10_000)) {
            if (timedOut()) break
        }
    }

    private fun timedOut(): Boolean =
        stopRequestedAt != 0L && SystemClock.elapsedRealtime() - stopRequestedAt > STOP_TIMEOUT_MS

    /** Odbiera dostępne dane z kodeka. Zwraca true po końcu strumienia. */
    private fun drainOnce(codec: MediaCodec, kind: Int, info: MediaCodec.BufferInfo, timeoutUs: Long): Boolean {
        var timeout = timeoutUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            timeout = 0
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(kind, codec.outputFormat)
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buf != null && !isConfig && info.size > 0) write(kind, buf, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun addTrack(kind: Int, format: MediaFormat) {
        synchronized(muxLock) {
            if (muxerStarted || tracks[kind] >= 0) return
            tracks[kind] = muxer.addTrack(format)
            if (tracks[VIDEO] >= 0 && (!expectAudio || tracks[AUDIO] >= 0)) {
                muxer.start()
                muxerStarted = true
                for (p in pending) writeToMuxer(p.kind, p.data, p.info)
                pending.clear()
            }
        }
    }

    private fun write(kind: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val copyInfo = MediaCodec.BufferInfo()
        copyInfo.set(0, info.size, info.presentationTimeUs - startUs, info.flags)
        if (copyInfo.presentationTimeUs < 0) return
        synchronized(muxLock) {
            if (muxerStarted) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                writeToMuxer(kind, buf.slice(), copyInfo)
            } else {
                val copy = ByteBuffer.allocateDirect(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                copy.put(buf)
                copy.flip()
                pending += Pending(kind, copy, copyInfo)
            }
        }
    }

    private fun writeToMuxer(kind: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.presentationTimeUs <= lastPts[kind]) return // znaczniki czasu muszą rosnąć
        lastPts[kind] = info.presentationTimeUs
        muxer.writeSampleData(tracks[kind], data, info)
        if (kind == VIDEO) samplesWritten++
    }

    /** Kończy nagrywanie (blokuje do kilku sekund, wołać poza wątkiem UI). Zwraca true, gdy plik jest poprawny. */
    fun stop(): Boolean {
        synchronized(drawLock) { running = false }
        stopRequestedAt = SystemClock.elapsedRealtime()
        try { video.signalEndOfInputStream() } catch (_: Exception) {}
        workers.forEach { it.join(STOP_TIMEOUT_MS + 1000) }
        var ok: Boolean
        synchronized(muxLock) {
            ok = muxerStarted && samplesWritten > 0
            try { if (muxerStarted) muxer.stop() } catch (_: Exception) { ok = false }
            try { muxer.release() } catch (_: Exception) {}
        }
        try { video.stop() } catch (_: Exception) {}
        video.release()
        try { surface.release() } catch (_: Exception) {}
        audio?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        mic?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        if (!ok) output.delete()
        return ok
    }
}
