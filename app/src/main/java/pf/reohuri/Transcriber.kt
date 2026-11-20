package pf.reohuri

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Reconnaissance vocale hors ligne (sherpa-onnx). Fonctions bloquantes : à appeler hors du fil principal. */
class Transcriber(private val context: Context, private val modeles: ModelManager) {

    @Volatile var annule = false

    private fun reconnaisseur(m: Modele): OfflineRecognizer {
        val fils = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        fun c(nom: String) = modeles.fichier(m, nom).absolutePath
        val config = if (m == Modele.PARAKEET) {
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = c("encoder.int8.onnx"),
                    decoder = c("decoder.int8.onnx"),
                    joiner = c("joiner.int8.onnx")
                ),
                tokens = c("tokens.txt"),
                numThreads = fils,
                modelType = "nemo_transducer"
            )
        } else {
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = c("${m.prefixe}-encoder.int8.onnx"),
                    decoder = c("${m.prefixe}-decoder.int8.onnx"),
                    language = "en",
                    task = "transcribe"
                ),
                tokens = c("${m.prefixe}-tokens.txt"),
                numThreads = fils
            )
        }
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = config))
    }

    /** Fichier vidéo ou audio -> passages reconnus. progres reçoit 0..100. */
    fun transcrire(
        uri: Uri, m: Modele, progres: (Int) -> Unit, surLigne: (String) -> Unit,
        surEtape: (String) -> Unit = {}
    ): List<Ligne> {
        // 1er passage, rapide : un enregistrement trop faible fait manquer des
        // paroles au détecteur ; on mesure donc le volume pour le remonter.
        surEtape("Mesure du volume…")
        val mesure = AudioDecoder(context, uri)
        var vu = -1
        val volume = mesure.volume({ annule }) { us ->
            val d = mesure.durationUs
            if (d > 0) {
                val p = (us * 100 / d).toInt().coerceIn(0, 99)
                if (p != vu) { vu = p; progres(p) }
            }
        }
        if (annule) return emptyList()
        val gain = gainPour(volume)

        surEtape("Chargement du moteur…")
        val rec = reconnaisseur(m)
        // Le détecteur de parole découpe le son aux silences
        // (Whisper ne lit que des passages de moins de 30 secondes).
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modeles.vad.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 22f
                ),
                sampleRate = 16000,
                numThreads = 1
            )
        )
        val lignes = mutableListOf<Ligne>()
        val bruit = Regex("""^[\[(].*[\])]$""")   // ex. [BLANK_AUDIO], (music)

        fun vider() {
            while (!vad.empty() && !annule) {
                val sp = vad.front()
                vad.pop()
                val flux = rec.createStream()
                flux.acceptWaveform(sp.samples, 16000)
                rec.decode(flux)
                val texte = rec.getResult(flux).text.trim()
                flux.release()
                if (texte.isNotEmpty() && !bruit.matches(texte)) {
                    lignes += Ligne(sp.start / 16000.0, (sp.start + sp.samples.size) / 16000.0, texte)
                    surLigne(texte)
                }
            }
        }

        try {
            val decodeur = AudioDecoder(context, uri)
            var dernier = -1
            decodeur.decode(
                isCancelled = { annule },
                onProgress = { us ->
                    val d = decodeur.durationUs
                    if (d > 0) {
                        val p = (us * 100 / d).toInt().coerceIn(0, 99)
                        if (p != dernier) { dernier = p; progres(p) }
                    }
                },
                gain = gain
            ) { pcm, n ->
                vad.acceptWaveform(FloatArray(n) { pcm[it] / 32768f })
                vider()
            }
            if (!annule) { vad.flush(); vider() }
        } finally {
            vad.release()
            rec.release()
        }
        return lignes
    }

    companion object {
        /** Gain à appliquer : rien pour un volume normal, jusqu'à ×100 pour un son très faible. */
        fun gainPour(v: AudioDecoder.Volume): Float {
            if (v.moyen >= -26.0) return 1f
            // Vise un volume moyen de -20 dB, sans écrêter les pointes de plus de 6 dB, 40 dB au plus
            val db = maxOf(0.0, minOf(40.0, -20.0 - v.moyen, 6.0 - v.crete))
            return Math.pow(10.0, db / 20.0).toFloat()
        }
    }

    /** Modèle local qui ajoute ponctuation et majuscules. Appeler fermer() après usage. */
    class Ponctuation(modeles: ModelManager) {
        private val p = OnlinePunctuation(
            config = OnlinePunctuationConfig(
                model = OnlinePunctuationModelConfig(
                    cnnBilstm = modeles.fichier(Modele.PONCTUATION, "model.int8.onnx").absolutePath,
                    bpeVocab = modeles.fichier(Modele.PONCTUATION, "bpe.vocab").absolutePath
                )
            )
        )
        fun ajouter(texte: String): String = p.addPunctuation(texte)
        fun fermer() = p.release()
    }
}
