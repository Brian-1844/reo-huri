package pf.reohuri

import android.app.ActivityManager
import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

/** Modèles téléchargés une seule fois, puis utilisés hors ligne. */
enum class Modele(
    val label: String,
    val dossier: String,
    val url: String,
    val mo: Int,
    val fichiers: List<String>,
    /** Préfixe des fichiers Whisper (vide pour les autres) */
    val prefixe: String = ""
) {
    PARAKEET(
        "Très précis — Parakeet (482 Mo, téléphone 6 Go de mémoire ou plus)",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        "$BASE/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", 482,
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    ),
    WHISPER_BASE(
        "Précis — Whisper base (199 Mo)",
        "sherpa-onnx-whisper-base.en",
        "$BASE/asr-models/sherpa-onnx-whisper-base.en.tar.bz2", 199,
        listOf("base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx", "base.en-tokens.txt"), "base.en"
    ),
    WHISPER_TINY(
        "Léger — Whisper tiny (113 Mo, téléphone modeste)",
        "sherpa-onnx-whisper-tiny.en",
        "$BASE/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2", 113,
        listOf("tiny.en-encoder.int8.onnx", "tiny.en-decoder.int8.onnx", "tiny.en-tokens.txt"), "tiny.en"
    ),
    PONCTUATION(
        "Ponctuation anglaise (30 Mo)",
        "sherpa-onnx-online-punct-en-2024-08-06",
        "$BASE/punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2", 30,
        listOf("model.int8.onnx", "bpe.vocab")
    );

    companion object {
        val MOTEURS = listOf(PARAKEET, WHISPER_BASE, WHISPER_TINY)
    }
}

class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)
    private val racine = File(context.filesDir, "models")

    /** Moteur choisi ; par défaut le plus précis que la mémoire du téléphone permet. */
    var moteur: Modele
        get() = Modele.MOTEURS.firstOrNull { it.name == prefs.getString("moteur", null) } ?: parDefaut()
        set(value) { prefs.edit().putString("moteur", value.name).apply() }

    private fun parDefaut(): Modele {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return if (info.totalMem >= 5_500_000_000L) Modele.PARAKEET else Modele.WHISPER_BASE
    }

    fun fichier(m: Modele, nom: String) = File(File(racine, m.dossier), nom)

    /** Détecteur de parole : découpe le son aux silences */
    val vad: File get() = File(racine, "silero_vad.onnx")

    fun installe(m: Modele): Boolean = m.fichiers.all { fichier(m, it).exists() }

    private fun ouvrir(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.connect()
        if (c.responseCode != 200) throw IllegalStateException("Téléchargement impossible (code ${c.responseCode})")
        return c
    }

    /** Flux qui compte les octets lus, pour afficher la progression. */
    private class Compteur(flux: InputStream, val total: Long, val cb: (Int) -> Unit) : FilterInputStream(flux) {
        private var lu = 0L
        private var dernier = -1
        private fun ajouter(n: Int) {
            if (n <= 0 || total <= 0) return
            lu += n
            val p = minOf(99L, lu * 100 / total).toInt()
            if (p != dernier) { dernier = p; cb(p) }
        }
        override fun read(): Int = super.read().also { if (it >= 0) ajouter(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { ajouter(it) }
    }

    /** Télécharge l'archive et n'en garde que les fichiers utiles. progres reçoit 0..100. */
    fun telecharger(m: Modele, progres: (Int) -> Unit) {
        racine.mkdirs()
        val tmp = File(racine, m.dossier + ".tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        val c = ouvrir(m.url)
        val flux = Compteur(BufferedInputStream(c.inputStream, 1 shl 16), c.contentLengthLong, progres)
        TarArchiveInputStream(BZip2CompressorInputStream(flux)).use { tar ->
            var e = tar.nextTarEntry
            while (e != null) {
                val nom = e.name.substringAfterLast('/')
                if (!e.isDirectory && nom in m.fichiers) {
                    FileOutputStream(File(tmp, nom)).use { tar.copyTo(it, 1 shl 16) }
                }
                e = tar.nextTarEntry
            }
        }
        if (!m.fichiers.all { File(tmp, it).exists() }) {
            tmp.deleteRecursively()
            throw IllegalStateException("Modèle incomplet, réessayez le téléchargement")
        }
        val cible = File(racine, m.dossier)
        cible.deleteRecursively()
        if (!tmp.renameTo(cible)) {
            tmp.copyRecursively(cible, overwrite = true)
            tmp.deleteRecursively()
        }
        progres(100)
    }

    fun telechargerVad() {
        if (vad.exists()) return
        racine.mkdirs()
        val tmp = File(racine, "silero_vad.tmp")
        ouvrir("$BASE/asr-models/silero_vad.onnx").inputStream.use { i -> FileOutputStream(tmp).use { i.copyTo(it) } }
        tmp.renameTo(vad)
    }
}
