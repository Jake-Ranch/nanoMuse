package io.github.nanomuse.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * nanoMuse: built-in offline ASR.
 *
 * Runs sherpa-onnx with the SenseVoice int8 model entirely on-device, so
 * voice input works with no ASR API configured: only the chat model's key is
 * needed. The model and tokens ship in assets/nanomuse/asr/ (put there by CI)
 * and are copied to internal storage on first use, then opened by absolute
 * path, the same way sherpa-onnx's own Android demo does it.
 */
object LocalAsr {

    @Volatile
    private var ready = false

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private var initStarted = false

    /** True once the model is unpacked and the recognizer is loaded. */
    val isReady: Boolean get() = ready

    /** Human-readable state, shown in the hotword notification. */
    @Volatile
    var state: String = "idle"
        private set

    /** Non-null when initialization failed; the reason, in one line. */
    @Volatile
    var lastError: String? = null
        private set

    /** Idempotent entry point; safe to call from any thread. */
    fun ensureInit(context: Context) = init(context)

    @Synchronized
    fun init(context: Context) {
        if (initStarted) return
        initStarted = true
        Thread {
            try {
                state = "copying"
                val dir = File(context.filesDir, "nanomuse_asr").apply { mkdirs() }
                val model = File(dir, "model.int8.onnx")
                val tokens = File(dir, "tokens.txt")
                if (!model.exists() || model.length() < 1_000_000L) {
                    context.assets.open("nanomuse/asr/model.int8.onnx").use { ins ->
                        model.outputStream().use { out -> ins.copyTo(out) }
                    }
                }
                if (!tokens.exists()) {
                    context.assets.open("nanomuse/asr/tokens.txt").use { ins ->
                        tokens.outputStream().use { out -> ins.copyTo(out) }
                    }
                }
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = "zh",
                            useInverseTextNormalization = true,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                state = "loading"
                recognizer = OfflineRecognizer(config = config)
                ready = true
                state = "ready"
            } catch (t: Throwable) {
                android.util.Log.e("LocalAsr", "init failed", t)
                lastError = t.message ?: t.javaClass.simpleName
                state = "failed"
                ready = false
            }
        }.start()
    }

    /** Transcribe one 16 kHz mono PCM16 WAV (44-byte header). Blocking; call off the main thread. */
    suspend fun transcribe(wav: ByteArray): String? = withContext(Dispatchers.Default) {
        val rec = recognizer ?: return@withContext null
        if (wav.size <= 44) return@withContext null
        val n = (wav.size - 44) / 2
        val samples = FloatArray(n)
        for (i in 0 until n) {
            val lo = wav[44 + i * 2].toInt() and 0xFF
            val hi = wav[44 + i * 2 + 1].toInt()
            samples[i] = (((hi shl 8) or lo).toShort().toFloat()) / 32768f
        }
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate = 16000)
            rec.decode(stream)
            rec.getResult(stream).text?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            android.util.Log.e("LocalAsr", "transcribe failed", t)
            null
        } finally {
            runCatching { stream.release() }
        }
    }
}
