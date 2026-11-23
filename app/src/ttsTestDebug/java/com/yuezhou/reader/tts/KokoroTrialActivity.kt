package com.yuezhou.reader.tts

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 阶段 B 最小试音入口，仅存在于 ttsTestDebug 变体。
 *
 * 模型通过 adb 推送到应用专属外部目录：
 *   Android/data/com.yuezhou.reader.tts.test/files/tts-models/<model-id>/
 *
 * 自动运行：
 *   adb shell am start -n com.yuezhou.reader.tts.test/com.yuezhou.reader.tts.KokoroTrialActivity \
 *     --ez autorun true --es model int8
 */
class KokoroTrialActivity : Activity() {

    private val tag = "KokoroTrial"
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private var engine: OfflineTts? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(status)
        root.addView(button("运行 int8") { runTrial("int8") })
        root.addView(button("运行 fp32") { runTrial("fp32") })
        root.addView(button("释放引擎") {
            executor.execute {
                runCatching { engine?.release() }
                engine = null
                log("RELEASED")
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })

        if (intent.getBooleanExtra("autorun", false)) {
            val model = intent.getStringExtra("model") ?: "int8"
            root.post { runTrial(model) }
        }
    }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun runTrial(model: String) {
        val speeds = intent.getStringExtra("speeds")
            ?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() }
            ?: listOf(0.8f, 1.0f, 1.2f, 1.5f)
        val text = intent.getStringExtra("text") ?: "静读测试，今天天气很好，我们开始阅读吧。"
        val sid = intent.getIntExtra("sid", 3)
        executor.execute {
            try {
                trial(model, speeds, text, sid)
            } catch (t: Throwable) {
                log("FAIL ${t.javaClass.simpleName}: ${t.message}")
                Log.e(tag, "trial failed", t)
            }
        }
    }

    private fun trial(modelName: String, speeds: List<Float>, text: String, sid: Int) {
        val dirName = when (modelName) {
            "int8" -> "kokoro-int8-multi-lang-v1_1"
            else -> "kokoro-multi-lang-v1_1"
        }
        val dir = File(filesDir, "tts-models/$dirName")
        log("MODEL $modelName dir=${dir.absolutePath}")
        log(
            "DIR exists=${dir.exists()} isDir=${dir.isDirectory} canRead=${dir.canRead()} " +
                "parentExists=${dir.parentFile?.exists()} list=${dir.list()?.take(5)}",
        )
        check(dir.isDirectory) { "模型目录不存在，请先 adb push" }
        val modelFile = listOf("model.onnx", "model.int8.onnx")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }
            ?: error("缺少 model.onnx / model.int8.onnx")
        for (name in listOf("voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt")) {
            check(File(dir, name).isFile) { "缺少 $name" }
        }
        check(File(dir, "espeak-ng-data").isDirectory) { "缺少 espeak-ng-data/" }

        val pssBefore = pssKb()
        val initStart = SystemClock.elapsedRealtime()
        log("INIT_BEGIN pss=${pssBefore}KB")
        val tts = OfflineTts(
            null,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = modelFile.absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
                            .joinToString(",") { File(dir, it).absolutePath },
                        lang = "en",
                        dictDir = File(dir, "dict").absolutePath,
                        lengthScale = 1.0f,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f,
            ),
        )
        val initMs = SystemClock.elapsedRealtime() - initStart
        runCatching { engine?.release() }
        engine = tts
        val pssAfterInit = pssKb()
        log(
            "INIT_OK initMs=$initMs sampleRate=${tts.sampleRate()} numSpeakers=${tts.numSpeakers()} " +
                "pss=${pssAfterInit}KB delta=${pssAfterInit - pssBefore}KB",
        )
        log("TEXT sid=$sid text=$text")

        for (speed in speeds) {
            val genStart = SystemClock.elapsedRealtimeNanos()
            var audio = tts.generate(text, sid, speed)
            var health = health(audio.samples, audio.sampleRate)
            if (!health.ok) {
                log("HEALTH_RETRY speed=$speed ${health.summary()}")
                audio = tts.generate(text, sid, speed)
                health = health(audio.samples, audio.sampleRate)
            }
            val synthMs = (SystemClock.elapsedRealtimeNanos() - genStart) / 1_000_000
            if (audio.samples.isEmpty()) {
                log("FAIL speed=$speed empty audio")
                return
            }
            val audioMs = audio.samples.size * 1000L / audio.sampleRate
            val rtf = synthMs.toDouble() / audioMs
            val wav = File(filesDir, "trials/$modelName-speed$speed.wav")
            wav.parentFile?.mkdirs()
            val saved = audio.save(wav.absolutePath)
            log(
                "GEN speed=$speed synthMs=$synthMs audioMs=$audioMs rtf=${"%.3f".format(rtf)} " +
                    "health=${health.summary()} wavSaved=$saved pss=${pssKb()}KB",
            )
            val play = play(audio.samples, audio.sampleRate)
            log("PLAY speed=$speed setupMs=${play.setupMs} playedMs=${play.playedMs} completed=${play.completed}")
        }

        log(
            "SUMMARY model=$modelName initMs=$initMs pssBefore=${pssBefore}KB " +
                "pssAfterInit=${pssAfterInit}KB pssEnd=${pssKb()}KB " +
                "sampleRate=${tts.sampleRate()} numSpeakers=${tts.numSpeakers()}",
        )
        log("DONE model=$modelName")
    }

    private data class Health(
        val ok: Boolean,
        val nan: Int,
        val inf: Int,
        val peak: Float,
        val rms: Float,
        val clipMs: Float,
        val reason: String,
    ) {
        fun summary(): String =
            "ok=$ok nan=$nan inf=$inf peak=${"%.3f".format(peak)} rms=${"%.4f".format(rms)} " +
                "clipMs=${"%.0f".format(clipMs)}${if (reason.isEmpty()) "" else " reason=$reason"}"
    }

    private fun health(samples: FloatArray, sampleRate: Int): Health {
        if (samples.isEmpty()) return Health(false, 0, 0, 0f, 0f, 0f, "empty")
        var nan = 0
        var inf = 0
        var peak = 0f
        var sum = 0.0
        var run = 0
        var maxRun = 0
        for (s in samples) {
            if (s.isNaN()) {
                nan++
                continue
            }
            if (s.isInfinite()) {
                inf++
                continue
            }
            val a = abs(s)
            if (a > peak) peak = a
            sum += s.toDouble() * s
            if (a >= 0.999f) {
                run++
                if (run > maxRun) maxRun = run
            } else {
                run = 0
            }
        }
        val clipMs = maxRun * 1000f / sampleRate
        val rms = sqrt(sum / samples.size).toFloat()
        val reason = when {
            nan > 0 -> "nan"
            inf > 0 -> "inf"
            clipMs >= 50f -> "clipped"
            rms < 1e-4f -> "silent"
            else -> ""
        }
        return Health(reason.isEmpty(), nan, inf, peak, rms, clipMs, reason)
    }

    private data class PlayResult(val setupMs: Long, val playedMs: Long, val completed: Boolean)

    private fun play(samples: FloatArray, sampleRate: Int): PlayResult {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBytes, 4096 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            val start = SystemClock.elapsedRealtime()
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val n = track.write(samples, offset, min(4096, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                check(n >= 0) { "AudioTrack.write=$n" }
                offset += n
            }
            val setupMs = SystemClock.elapsedRealtime() - start
            val frames = samples.size
            val deadline = SystemClock.elapsedRealtime() + frames * 1000L / sampleRate + 5_000
            var completed = false
            var lastHead = 0
            var stall = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                val head = track.playbackHeadPosition
                if (head >= frames) {
                    completed = true
                    break
                }
                if (head == lastHead) stall++ else { lastHead = head; stall = 0 }
                if (stall > 60) break
                Thread.sleep(50)
            }
            return PlayResult(setupMs, SystemClock.elapsedRealtime() - start, completed)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun pssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }

    private fun log(line: String) {
        Log.i(tag, line)
        runOnUiThread { status.append("$line\n") }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.execute { runCatching { engine?.release() } }
        executor.shutdown()
    }
}
