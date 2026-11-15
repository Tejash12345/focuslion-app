package com.focuslion.focuslion_app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel

/**
 * Leo's offline ears: ONE microphone loop that listens for "Hey Leo" and then
 * hears the command — without losing words.
 *
 * The old approach stopped the wake recognizer and started a second one after
 * "Hey Leo"; that took a few hundred ms, so in "hey leo scroll down" the word
 * "scroll" was lost and Leo only heard "down". Here recent audio is kept in
 * memory and replayed into a command recognizer that is created up front, and
 * the hardware buffer holds 3 s so nothing overflows while it catches up.
 *
 * All Vosk calls happen on this loop's own thread; callbacks go to the main thread.
 */
class VoiceLoop(
    private val model: Model,
    private val onWake: () -> Unit,
    /** text (offline, may be rough), the command's audio (for cloud speech-to-text), lowest word confidence */
    private val onCommand: (String, ShortArray?, Double) -> Unit,
    private val onEnd: () -> Unit,
    /** true while a video / music is playing — the wake word must be extra clear then */
    private val isNoisy: () -> Boolean = { false },
    /** follow-up command heard WITHOUT "Hey Leo"; free = any command (quiet room only) */
    private val onFollow: (String, Boolean) -> Unit = { _, _ -> },
    /** Voice Lock: speaker model (null = off) and "is this result the owner's voice?" */
    private val spk: SpeakerModel? = null,
    private val owner: (String?) -> Boolean = { true },
    /** a wake word / command in someone else's voice was ignored */
    private val onStranger: () -> Unit = { },
) {
    /** after a command, listen for follow-ups (no wake word) until this time */
    @Volatile private var followUntil = 0L

    /** Keep accepting short follow-up commands for `ms` (0 = stop following). */
    /** Leo is speaking an answer: don't take his own words as follow-up commands */
    @Volatile private var muteUntil = 0L
    fun muteFollow(ms: Long) { muteUntil = if (ms <= 0) 0L else System.currentTimeMillis() + ms }

    /** why the loop ended ("" = asked to stop): "mic" = another app has the microphone */
    @Volatile var endReason = ""

    fun followFor(ms: Long) { followUntil = if (ms <= 0) 0L else System.currentTimeMillis() + ms }

    companion object {
        /** only one loop may own the microphone (and use the model) at a time */
        val mic = Any()

        private val WAKE = Regex("\\b(hey|hi|okay|ok|hello) leo\\b")
        /** just "Leo" (no "hey") — also wakes him */
        private val BARE = Regex("^leo\\b")
        private val WAKE_WORD = Regex("\\b(leo|lio|leon|leos)\\b")
        /** a free-speech follow-up must START with one of these (a real command) */
        private val VERBS = setOf("open", "call", "send", "message", "text", "play", "search", "scroll", "go",
            "next", "pause", "resume", "stop", "skip", "volume", "turn", "set", "take", "swipe", "tap", "type",
            "like", "close", "read", "start", "show", "whatsapp", "remind", "add", "make", "what's", "whats", "what",
            "tell", "find", "increase", "decrease", "mute", "unmute", "slowly", "keep",
            "save", "share", "follow", "double", "reply", "answer", "post", "comment", "auto", "watch", "selfie", "torch",
            "flashlight", "swipe", "previous", "back", "skip", "heart", "love", "switch", "lock", "dial", "note", "translate", "explain", "define", "navigate", "directions")
        private val NOISE = setOf("", "leo", "hey", "hey leo", "the", "a", "huh", "uh", "um", "hi", "hello")

        /** Text after the LAST wake word; text with no wake word is kept as is. */
        fun afterWake(text: String): String {
            val t = text.trim().lowercase()
            val m = WAKE_WORD.findAll(t).lastOrNull() ?: return t
            return t.substring(m.range.last + 1).trim(' ', ',', '.')
        }

        /** "the hey leo scroll down" / "hey lio scroll down" -> "scroll down" */
        fun stripWake(text: String): String {
            val t = text.trim().lowercase()
            val words = t.split(Regex("\\s+"))
            val head = words.take(5).joinToString(" ")
            val last = WAKE_WORD.findAll(head).lastOrNull() ?: return t
            return t.substring(last.range.last + 1).trim().trimStart(',', '.', ' ')
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "leo-voice").apply { isDaemon = true; start() }
    }

    /** Asks the loop to stop; it releases the microphone within ~0.2 s. Never blocks the caller. */
    fun stop() {
        running = false
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() = synchronized(mic) {
        val rate = 16000
        val chunk = 3200 // 0.2 s per read
        var rec: AudioRecord? = null
        val effects = ArrayList<android.media.audiofx.AudioEffect>()
        var wakeRec: Recognizer? = null
        var cmdRec: Recognizer? = null
        var followRec: Recognizer? = null
        var freeRec: Recognizer? = null
        try {
            if (!running) return@synchronized
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // 6 s of hardware buffer: no overflow while the command recognizer catches up
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate * 2 * 6))
            if (rec.state != AudioRecord.STATE_INITIALIZED) { endReason = "mic"; return@synchronized }
            // the phone's own noise suppression + automatic volume: less background
            // noise (fan, traffic, TV) and a louder, clearer voice (where supported)
            val sid = rec.audioSessionId
            val fx = ArrayList<String>()
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(sid)?.let { it.enabled = true; effects += it; fx += "noise suppression" } } catch (_: Exception) { }
            try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(sid)?.let { it.enabled = true; effects += it; fx += "AGC" } } catch (_: Exception) { }
            try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(sid)?.let { it.enabled = true; effects += it; fx += "echo cancel" } } catch (_: Exception) { }
            if (fx.isNotEmpty()) LeoBubbleService.ev("hardware mic effects: " + fx.joinToString(", "))
            // grammar = only the wake phrases: tiny, accurate, low battery
            wakeRec = Recognizer(model, rate.toFloat(), "[\"hey leo\", \"hi leo\", \"okay leo\", \"hello leo\", \"ok leo\", \"leo\", \"[unk]\"]")
            wakeRec.setWords(true) // per-word confidence: video/music noise rarely scores high
            // created up front so nothing is lost while it would be built later
            cmdRec = Recognizer(model, rate.toFloat())
            cmdRec.setWords(true)
            cmdRec.setPartialWords(true)
            // follow-up mode knows ONLY multi-word screen commands — a video's speech
            // can't become "call mom", and each word must be heard with high confidence
            followRec = Recognizer(model, rate.toFloat(), "[\"scroll down\", \"scroll up\", \"scroll down slowly\", \"slowly scroll\", \"slowly scroll down\", \"scroll a little\", \"scroll down a little\", \"scroll up a little\", \"scroll a lot\", \"keep scrolling\", \"stop scrolling\", \"scroll to the top\", \"scroll to the bottom\", \"go to the top\", \"go up\", \"go down\", \"next video\", \"next reel\", \"next song\", \"previous video\", \"go back\", \"go home\", \"pause video\", \"pause the video\", \"pause it\", \"skip ad\", \"skip the ad\", \"volume up\", \"volume down\", \"turn it up\", \"turn it down\", \"swipe left\", \"swipe right\", \"take a screenshot\", \"recent apps\", \"stop listening\", \"like this reel\", \"like this post\", \"double tap\", \"save reel\", \"share it\", \"share this\", \"open reels\", \"open instagram reels\", \"open shorts\", \"youtube shorts\", \"auto play reels\", \"keep playing reels\", \"previous reel\", \"swipe up\", \"swipe down\", \"take a selfie\", \"take a photo\", \"torch on\", \"torch off\", \"flashlight on\", \"flashlight off\", \"next post\", \"skip this\", \"pause reel\", \"play reel\", \"close this\", \"open whatsapp\", \"open youtube\", \"open instagram\", \"open chrome\", \"open gmail\", \"open maps\", \"open camera\", \"open settings\", \"open spotify\", \"open photos\", \"open gallery\", \"open messages\", \"open phone\", \"open contacts\", \"open calculator\", \"open clock\", \"open calendar\", \"open facebook\", \"open telegram\", \"open snapchat\", \"open play store\", \"open focuslion\", \"open files\", \"open music\", \"[unk]\"]")
            followRec.setWords(true)
            // free-speech follow-ups ("open whatsapp", "call mom") — only used when no
            // video/music/Leo's voice is playing, and every word must be clear
            freeRec = Recognizer(model, rate.toFloat())
            freeRec.setWords(true)
            if (spk != null) { // voice prints for every result we act on
                cmdRec.setSpeakerModel(spk)
                followRec.setSpeakerModel(spk)
                freeRec.setSpeakerModel(spk)
            }
            rec.startRecording()
            val ring = ArrayDeque<ShortArray>() // recent audio (~9 s), volume-boosted for the offline recognizer
            val rawRing = ArrayDeque<ShortArray>() // the same audio as recorded (for Whisper: no boost distortion)
            val buf = ShortArray(chunk)
            var capturing = false
            var capStart = 0L
            var lastVoiceMs = 0L
            var spoke = false
            var wasFollowing = false
            var prefix = "" // command words already heard together with the wake word

            var heardSound = false
            var wakeFed = 0L // samples given to wakeRec since its last reset
            var cut = 0.0 // command = words starting after this time (cmdRec's clock)
            val cmdAudio = ArrayList<ShortArray>() // what was said after "leo" (for cloud speech-to-text)
            var cmdSamples = 0
            var cmdConf = 1.0 // lowest confidence of the command's words (offline recognizer)
            fun addAudio(p: ShortArray) { if (cmdSamples < 16000 * 12) { cmdAudio += p; cmdSamples += p.size } }
            var floor = 200.0 // background noise level
            var speech = 1000.0 // typical speech level
            fun resetWake() { wakeRec?.reset(); wakeFed = 0 }

            fun finish(cmd: String) {
                capturing = false
                prefix = ""
                lastVoiceMs = 0L
                spoke = false
                cmdRec?.reset()
                resetWake()
                followRec?.reset()
                freeRec?.reset()
                ring.clear(); rawRing.clear()
                val audio = if (cmdSamples > 0) ShortArray(cmdSamples).also { out ->
                    var o = 0
                    for (p in cmdAudio) { p.copyInto(out, o); o += p.size }
                } else null
                val conf = cmdConf
                cmdAudio.clear(); cmdSamples = 0; cmdConf = 1.0
                main.post { onCommand(if (cmd in NOISE) "" else cmd, audio, conf) }
            }

            var silentChunks = 0
            while (running) {
                val n = rec.read(buf, 0, chunk)
                if (n < 0) { endReason = "mic"; break } // mic taken (call / other app) or dead: end, the service restarts us
                if (n == 0) { Thread.sleep(20); continue }
                val raw = buf.copyOf(n)
                val piece = buf.copyOf(n)
                var currentRms = 0.0
                // auto-gain: some phones (e.g. Motorola) give a very quiet mic — lift
                // speech to the level the speech model expects
                run {
                    var sum = 0.0
                    for (x in piece) sum += x.toDouble() * x
                    currentRms = Math.sqrt(sum / n)
                    val rms = currentRms
                    floor = if (rms < floor) floor * 0.8 + rms * 0.2 else floor * 0.995 + rms * 0.005
                    if (rms > floor * 2.5 && rms > 150) speech = speech * 0.9 + rms * 0.1
                    val gain = (3000.0 / maxOf(speech, 300.0)).coerceIn(1.0, 6.0)
                    if (gain > 1.05) for (i in 0 until n) piece[i] = (piece[i] * gain).coerceIn(-32768.0, 32767.0).toInt().toShort()
                }
                // Android hands a background app pure silence when it blocks the mic
                // (service restarted while FocusLion was closed): detect it and report
                if (!heardSound) {
                    if (piece.any { it.toInt() != 0 }) heardSound = true
                    else if (++silentChunks >= 20) { endReason = "silent"; break } // ~4 s of exact zeros
                }
                ring.addLast(piece)
                rawRing.addLast(raw)
                while (ring.size > 45) ring.removeFirst() // ~9 s: long commands keep their start
                while (rawRing.size > 45) rawRing.removeFirst()

                // ---- follow-up commands (no "Hey Leo") for a while after a command ----
                val now0 = System.currentTimeMillis()
                val following = !capturing && now0 < followUntil && now0 >= muteUntil
                if (following && !wasFollowing) { followRec?.reset(); freeRec?.reset() } // no leftover audio
                wasFollowing = following
                if (following) {
                    val noisy = isNoisy()
                    var hit: String? = null
                    var hitRes: String? = null
                    var free = false
                    val f = followRec
                    if (f != null && f.acceptWaveForm(piece, n)) {
                        val res = f.result
                        val said = text(res, "text")
                        if (said.isNotEmpty() && !said.contains("[unk]") &&
                            (!noisy || said.split(" ").size >= 2) &&
                            confidentAll(res, if (noisy) 0.95 else 0.85)) { hit = said; hitRes = res }
                    }
                    val fr = freeRec
                    if (fr != null) {
                        if (noisy) fr.reset() // video / Leo talking: free speech is off
                        else if (fr.acceptWaveForm(piece, n)) {
                            val res = fr.result
                            val said = afterWake(text(res, "text"))
                            val words = said.split(" ").filter { it.isNotEmpty() }
                            if (hit == null && words.size in 2..14 && words[0] in VERBS && confidentAll(res, 0.8)) {
                                hit = said; hitRes = res; free = true
                            }
                        }
                    }
                    if (hit != null && spk != null && !owner(hitRes)) {
                        f?.reset(); fr?.reset()
                        ring.clear(); rawRing.clear()
                        main.post { onStranger() }
                        continue
                    }
                    if (hit != null) {
                        f?.reset(); fr?.reset()
                        resetWake()
                        ring.clear(); rawRing.clear() // never replay an executed follow-up again
                        val said = hit; val isFree = free
                        main.post { onFollow(said, isFree) }
                        continue
                    }
                }

                if (!capturing) {
                    val w = wakeRec ?: break
                    wakeFed += n
                    // only a FINAL result with confident words counts — partial guesses
                    // and low-confidence matches (YouTube speech, songs, TV) are ignored
                    if (!w.acceptWaveForm(piece, n)) continue
                    val res = w.result
                    val wt = text(res, "text")
                    if (wt.isNotEmpty() && wt != "[unk]") Log.i("Leo", "wake grammar: " + wt + " " + confs(res))
                    val noisyNow = isNoisy()
                    // plain "Leo" works too — but while a video/song plays (and Voice Lock is
                    // off) only "Hey Leo" counts, so a video saying "Leo" can't wake him
                    val wakeOk = WAKE.containsMatchIn(wt) || (BARE.containsMatchIn(wt) && (!noisyNow || spk != null))
                    if (!wakeOk || !confidentWake(res, if (noisyNow) 0.93 else if (WAKE.containsMatchIn(wt)) 0.75 else 0.8)) continue
                    // where "leo" ended, in the time of the audio we keep (the ring)
                    val ringSamples = ring.sumOf { it.size }
                    val leoEnd = wakeWordEnd(res)
                    val ringStart = (wakeFed - ringSamples) / 16000.0
                    cut = if (leoEnd >= 0) maxOf(0.0, leoEnd - ringStart) else 0.0
                    resetWake()
                    // replay the kept audio into the full recognizer: the words said AFTER
                    // "leo" are the command (never the video's words before it)
                    val r0 = cmdRec ?: break
                    r0.reset()
                    cmdConf = 1.0; cmdAudio.clear(); cmdSamples = 0
                    val words = ArrayList<String>()
                    var lastRes: String? = null
                    for (p in ring.toList()) {
                        if (r0.acceptWaveForm(p, p.size)) { val x = r0.result; lastRes = better(lastRes, x); words += wordsAfter(x, cut); cmdConf = minOf(cmdConf, confAfter(x, cut)) }
                    }
                    prefix = ""
                    var closed = false
                    if (spk != null) {
                        // Voice Lock needs a finished piece of speech for the voice print
                        val x = r0.finalResult
                        lastRes = better(lastRes, x)
                        words += wordsAfter(x, cut)
                        cmdConf = minOf(cmdConf, confAfter(x, cut))
                        closed = true
                    }
                    if (spk != null && !owner(lastRes)) { // not the owner's voice
                        r0.reset(); ring.clear(); rawRing.clear(); prefix = ""
                        main.post { onStranger() }
                        continue
                    }
                    val after = words.joinToString(" ").trim()
                    // the cloud recognizer gets everything from the START of "hey leo"
                    // (one-breath commands: the end of "leo" can be placed too late)
                    cmdAudio.clear(); cmdSamples = 0
                    run {
                        val wakeStart = wakeWordStart(res)
                        var skip = if (wakeStart >= 0) (maxOf(0.0, wakeStart - ringStart - 0.4) * 16000).toInt()
                            else maxOf(0, ringSamples - 64000)
                        for (p in rawRing) {
                            if (skip >= p.size) { skip -= p.size; continue }
                            addAudio(if (skip > 0) p.copyOfRange(skip, p.size) else p)
                            skip = 0
                        }
                    }
                    capturing = true
                    capStart = System.currentTimeMillis()
                    lastVoiceMs = capStart
                    main.post { onWake() }
                    // the wake grammar only answers after a pause: if words came after
                    // "leo", the command is complete
                    val stillTalking = !closed && hasPartialWords(r0.partialResult)
                    if (after.isNotEmpty() && after !in NOISE && !stillTalking) { finish(after); continue }
                    prefix = if (after in NOISE) "" else after
                    spoke = prefix.isNotEmpty() || stillTalking
                    continue
                }

                val r = cmdRec ?: break
                val now = System.currentTimeMillis()
                addAudio(raw)
                val voiceDetected = (currentRms > floor * 1.8 && currentRms > 200) || hasPartialWords(r.partialResult)
                if (voiceDetected) {
                    spoke = true
                    lastVoiceMs = now
                }
                if (r.acceptWaveForm(piece, n)) {
                    val res1 = r.result
                    cmdConf = minOf(cmdConf, confAfter(res1, cut))
                    val cmd = (prefix + " " + wordsAfter(res1, cut).joinToString(" ")).trim()
                    if (cmd !in NOISE) { finish(cmd); continue }
                }
                // nothing said after "Hey Leo" for 5 seconds -> Leo opens to listen himself
                if (!spoke && now - capStart > 5000) { finish(prefix); continue }
                // user spoke and then paused for 1.3s, or reached 8.5s limit -> finish command
                if (spoke && ((now - lastVoiceMs > 1300 && now - capStart > 1800) || now - capStart > 8500)) {
                    val fr = r.finalResult
                    cmdConf = minOf(cmdConf, confAfter(fr, cut))
                    val w = wordsAfter(fr, cut)
                    val recognized = if (w.isNotEmpty()) w.joinToString(" ") else text(fr, "text")
                    finish((prefix + " " + recognized).trim())
                    continue
                }
            }
        } catch (e: Exception) {
            endReason = "error: ${e.message ?: e.javaClass.simpleName}"
            // mic busy / model problem: reported through onEnd, the service restarts us
        } finally {
            for (e in effects) try { e.release() } catch (_: Exception) { }
            try { rec?.stop() } catch (_: Exception) { }
            try { rec?.release() } catch (_: Exception) { }
            try { wakeRec?.close() } catch (_: Exception) { }
            try { cmdRec?.close() } catch (_: Exception) { }
            try { followRec?.close() } catch (_: Exception) { }
            try { freeRec?.close() } catch (_: Exception) { }
            running = false
            main.post { onEnd() }
        }
    }

    /** Of two results, the one with the longer voice print (more speech to compare). */
    private fun better(a: String?, b: String?): String? {
        fun frames(j: String?) = try { JSONObject(j ?: "{}").optInt("spk_frames", 0) } catch (_: Exception) { 0 }
        return if (frames(b) > frames(a)) b else a ?: b
    }

    /** End time (s) of the last wake word ("leo") in a grammar result, -1 if unknown. */
    /** Start time (s) of the wake phrase ("hey"/"ok"/"leo"…) in a grammar result, -1 if unknown. */
    private fun wakeWordStart(json: String?): Double = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        var start = -1.0
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("word") in setOf("hey", "hi", "ok", "okay", "hello", "leo")) { start = o.optDouble("start", -1.0); break }
        }
        start
    } catch (_: Exception) { -1.0 }

    private fun wakeWordEnd(json: String?): Double = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        var end = -1.0
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("word") == "leo") end = o.optDouble("end", -1.0)
        }
        end
    } catch (_: Exception) { -1.0 }

    /** Words of a final result that START after `cut` seconds (wake words removed). */
    private fun wordsAfter(json: String?, cut: Double): List<String> = timed(json, "result", cut)

    private fun hasPartialWords(json: String?): Boolean = try {
        val o = JSONObject(json ?: "{}")
        val p = o.optString("partial", "").trim().lowercase()
        if (p.isNotEmpty()) {
            val words = p.split(Regex("\\s+")).filter { it.isNotEmpty() && !WAKE_WORD.matches(it) && it !in NOISE }
            words.isNotEmpty()
        } else {
            val arr = o.optJSONArray("partial_result")
            arr != null && arr.length() > 0
        }
    } catch (_: Exception) { false }

    private fun wordsAfterPartial(json: String?, cut: Double): List<String> = timed(json, "partial_result", cut)

    /** Lowest confidence among the words after `cut` (1.0 if none). */
    private fun confAfter(json: String?, cut: Double): Double = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        var m = 1.0
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val w = o.optString("word")
            if (o.optDouble("start", 0.0) >= cut - 0.05 && !WAKE_WORD.matches(w) && w != "hey") m = minOf(m, o.optDouble("conf", 1.0))
        }
        m
    } catch (_: Exception) { 1.0 }

    private fun timed(json: String?, key: String, cut: Double): List<String> = try {
        val arr = JSONObject(json ?: "{}").optJSONArray(key)
        val out = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val w = o.optString("word")
            if (o.optDouble("start", 0.0) >= cut - 0.05 && w.isNotEmpty() && !WAKE_WORD.matches(w) && w != "hey") out += w
        }
        out
    } catch (_: Exception) { emptyList() }

    private fun confs(json: String?): String = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        if (arr == null) "" else (0 until arr.length()).joinToString(",") { i ->
            val o = arr.getJSONObject(i); o.optString("word") + "=" + "%.2f".format(o.optDouble("conf", 0.0))
        }
    } catch (_: Exception) { "" }

    /** Was EVERY word of the result heard with at least `min` confidence? */
    private fun confidentAll(json: String?, min: Double): Boolean = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        arr != null && arr.length() > 0 && (0 until arr.length()).all { arr.getJSONObject(it).optDouble("conf", 0.0) >= min }
    } catch (_: Exception) { false }

    /** Was "leo" (and the word before it) recognised with at least `min` confidence? */
    private fun confidentWake(json: String?, min: Double): Boolean = try {
        val arr = JSONObject(json ?: "{}").optJSONArray("result")
        if (arr == null) true // no word details from the model: fall back to the text match
        else {
            var ok = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("word") == "leo" && o.optDouble("conf", 0.0) >= min) {
                    val prev = if (i > 0) arr.getJSONObject(i - 1).optDouble("conf", 0.0) else 1.0
                    if (prev >= min - 0.1) ok = true
                }
            }
            ok
        }
    } catch (_: Exception) { false }

    private fun text(json: String?, key: String): String =
        try { JSONObject(json ?: "{}").optString(key).trim().lowercase() } catch (_: Exception) { "" }
}
