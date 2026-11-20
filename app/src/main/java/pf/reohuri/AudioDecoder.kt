package pf.reohuri

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Extrait la piste audio d'une vidéo (ou d'un fichier audio) et la convertit
 * en PCM 16 bits mono 16 kHz, morceau par morceau (peu de mémoire utilisée).
 */
class AudioDecoder(private val context: Context, private val uri: Uri) {

    var durationUs: Long = 0L
        private set

    /** @param onPcm reçoit des échantillons 16 kHz mono
     *  @param onProgress reçoit la position en microsecondes */
    fun decode(
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
        onPcm: (ShortArray, Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("Cette vidéo ne contient pas de piste audio.")
        }
        extractor.selectTrack(trackIndex)
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            durationUs = format.getLong(MediaFormat.KEY_DURATION)
        }

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        var resampler = Resampler(sampleRate)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (isCancelled()) break

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(sampleRate)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())

                            val mono: FloatArray
                            if (isFloat) {
                                val fb = buf.asFloatBuffer()
                                val total = fb.remaining()
                                val frames = total / channels
                                mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += fb.get(f * channels + c)
                                    mono[f] = s / channels * 32767f
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                val total = sb.remaining()
                                val frames = total / channels
                                mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get(f * channels + c)
                                    mono[f] = s / channels
                                }
                            }
                            resampler.process(mono, mono.size, onPcm)
                            onProgress(info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
    }

    /** Rééchantillonnage linéaire continu vers 16 000 Hz. */
    private class Resampler(inRate: Int) {
        private val step = inRate / 16000.0
        private var t = 0.0
        private var prev = 0f

        fun process(inp: FloatArray, n: Int, out: (ShortArray, Int) -> Unit) {
            if (n <= 0) return
            val res = ShortArray((n / step).toInt() + 4)
            var k = 0
            while (t < n - 1 && k < res.size) {
                val i = floor(t).toInt()
                val frac = (t - i).toFloat()
                val a = if (i < 0) prev else inp[i]
                val b = inp[i + 1]
                var v = a + (b - a) * frac
                if (v > 32767f) v = 32767f
                if (v < -32768f) v = -32768f
                res[k++] = v.toInt().toShort()
                t += step
            }
            t -= n
            prev = inp[n - 1]
            if (k > 0) out(res, k)
        }
    }
}
