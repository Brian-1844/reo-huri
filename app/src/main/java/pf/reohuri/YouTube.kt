package pf.reohuri

import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lit les sous-titres anglais déjà présents sur une vidéo YouTube (la vidéo
 * n'est pas téléchargée). YouTube n'offre pas d'accès officiel : si cette
 * méthode cesse de fonctionner, il faudra mettre l'app à jour.
 * Fonctions bloquantes : à appeler hors du fil principal.
 */
object YouTube {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val MOTIFS = listOf(
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})""")
    )

    /** @return l'identifiant de la vidéo, ou null si le texte n'est pas un lien YouTube */
    fun idVideo(texte: String): String? {
        for (m in MOTIFS) m.find(texte)?.let { return it.groupValues[1] }
        return null
    }

    class Resultat(val lignes: List<Ligne>, val automatiques: Boolean)

    /** (titre, chaîne) par l'adresse publique oEmbed ; chaînes vides en cas d'échec. */
    fun titre(video: String): Pair<String, String> = try {
        val u = "https://www.youtube.com/oembed?format=json&url=" +
            URLEncoder.encode("https://www.youtube.com/watch?v=$video", "UTF-8")
        val o = JSONObject(lire(u, null))
        o.optString("title") to o.optString("author_name")
    } catch (e: Exception) {
        "" to ""
    }

    fun sousTitres(video: String): Resultat {
        // 1. Page de la vidéo -> clé interne
        var cookie: String? = null
        var html = lire("https://www.youtube.com/watch?v=$video", cookie)
        if (html.contains("action=\"https://consent.youtube.com/s\"")) {
            val v = Regex("""name="v" value="(.*?)"""").find(html)?.groupValues?.get(1)
                ?: throw IllegalStateException("YouTube demande un consentement, impossible de continuer")
            cookie = "CONSENT=YES+$v"
            html = lire("https://www.youtube.com/watch?v=$video", cookie)
        }
        val cle = Regex(""""INNERTUBE_API_KEY":\s*"([a-zA-Z0-9_-]+)"""").find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("Page YouTube illisible (lien incorrect ou accès bloqué)")

        // 2. Liste des sous-titres
        val corps = JSONObject()
            .put("context", JSONObject().put("client",
                JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.38")))
            .put("videoId", video)
        val data = JSONObject(envoyer("https://www.youtube.com/youtubei/v1/player?key=$cle", corps.toString(), cookie))

        val etat = data.optJSONObject("playabilityStatus")
        val st = etat?.optString("status").orEmpty()
        if (st.isNotEmpty() && st != "OK") {
            val raison = etat?.optString("reason").orEmpty()
            throw IllegalStateException("Vidéo non disponible : " + raison.ifEmpty { st })
        }
        val pistes = data.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
        if (pistes == null || pistes.length() == 0) throw IllegalStateException("Cette vidéo n'a pas de sous-titres")

        // 3. Choix : anglais écrit par l'auteur, sinon anglais automatique
        var auteur: JSONObject? = null
        var auto: JSONObject? = null
        for (i in 0 until pistes.length()) {
            val t = pistes.getJSONObject(i)
            if (!t.optString("languageCode").startsWith("en")) continue
            if (t.optString("kind") == "asr") { if (auto == null) auto = t }
            else if (auteur == null) auteur = t
        }
        val piste = auteur ?: auto ?: throw IllegalStateException("Cette vidéo n'a pas de sous-titres anglais")

        // 4. Lecture du texte minuté
        val xml = lire(piste.getString("baseUrl").replace("&fmt=srv3", ""), cookie)
        val lignes = analyser(xml)
        if (lignes.isEmpty()) {
            throw IllegalStateException("YouTube a renvoyé des sous-titres vides pour cette vidéo")
        }
        return Resultat(lignes, auteur == null)
    }

    /** Lit les deux formats connus : <text start dur> (secondes) et <p t d> (millisecondes). */
    fun analyser(xml: String): List<Ligne> {
        val out = mutableListOf<Ligne>()
        val reText = Regex("""<text\b([^>]*)>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
        val reP = Regex("""<p\b([^>]*)>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        fun attr(attrs: String, nom: String): Double? =
            Regex("\\b$nom=\"([0-9.]+)\"").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()

        for (m in reText.findAll(xml)) {
            val debut = attr(m.groupValues[1], "start") ?: continue
            val duree = attr(m.groupValues[1], "dur") ?: 0.0
            val texte = propre(m.groupValues[2])
            if (texte.isNotEmpty()) out += Ligne(debut, debut + duree, texte)
        }
        if (out.isEmpty()) {
            for (m in reP.findAll(xml)) {
                val debut = (attr(m.groupValues[1], "t") ?: continue) / 1000.0
                val duree = (attr(m.groupValues[1], "d") ?: 0.0) / 1000.0
                val texte = propre(m.groupValues[2])
                if (texte.isNotEmpty()) out += Ligne(debut, debut + duree, texte)
            }
        }
        return out
    }

    private fun propre(s: String): String {
        // Deux passes : le texte est encodé deux fois (&amp;#39; -> &#39; -> ')
        var t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
        t = Html.fromHtml(t, Html.FROM_HTML_MODE_LEGACY).toString()
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun lire(url: String, cookie: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (cookie != null) c.setRequestProperty("Cookie", cookie)
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        if (c.responseCode == 429) throw IllegalStateException("YouTube bloque temporairement les demandes, réessayez plus tard")
        if (c.responseCode != 200) throw IllegalStateException("YouTube a répondu ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun envoyer(url: String, json: String, cookie: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        if (cookie != null) c.setRequestProperty("Cookie", cookie)
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        if (c.responseCode != 200) throw IllegalStateException("YouTube a répondu ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
