package pf.reohuri

/** Un mot avec le début et la fin (en secondes) du passage d'où il vient. */
class Mot(val t: Double, val fin: Double, var texte: String)

/** Une phrase ou un paragraphe minuté. */
class Bloc(val debut: Double, var fin: Double, var texte: String)

/** Mise en forme du texte anglais : mots minutés -> phrases -> paragraphes. */
object Texte {

    private val FIN_PHRASE = Regex("""[.?!…]["'”’)\]]*$""")
    private val BRUIT = Regex("""\[[^\]]*\]|\([^)]*\)|>>+|♪+""")
    private val BORDS = Regex("""^[^\w]+|[^\w]+$""")
    private val QUEUE = Regex("""[^\w]+$""")
    private val ESPACES = Regex("""\s+""")
    private val TETE = Regex("""^\[(\d{1,2}):(\d{2}):(\d{2})\]\s*""")

    /** Retire les indications de sous-titres : [Music], (applause), >>. */
    fun nettoyerLigne(s: String): String =
        BRUIT.replace(s.replace("\n", " "), " ").replace(ESPACES, " ").trim()

    fun lignesVersMots(lignes: List<Ligne>): MutableList<Mot> {
        val mots = mutableListOf<Mot>()
        for (l in lignes) {
            for (m in nettoyerLigne(l.texte).split(" ")) {
                if (m.isNotEmpty()) mots += Mot(l.debut, maxOf(l.fin, l.debut), m)
            }
        }
        return mots
    }

    private fun finit(s: String) = FIN_PHRASE.containsMatchIn(s)

    /** Vrai si le texte porte déjà une ponctuation de phrase régulière. */
    fun estPonctue(mots: List<Mot>): Boolean {
        if (mots.isEmpty()) return true
        return mots.count { finit(it.texte) } * 40 >= mots.size
    }

    private fun nu(mot: String) = BORDS.replace(mot.lowercase(), "")

    /**
     * Ajoute ponctuation et majuscules avec un modèle local. Le modèle ne
     * change pas les mots : on garde la majuscule d'origine (noms propres
     * déjà reconnus) et on ne lui prend que la ponctuation.
     */
    fun ponctuer(
        mots: List<Mot>, modele: (String) -> String, taille: Int = 120,
        progres: ((Int) -> Unit)? = null
    ): MutableList<Mot> {
        val utiles = mots.filter { nu(it.texte).isNotEmpty() }
        val sortie = mutableListOf<Mot>()
        var finOuverte = false
        var i = 0
        while (i < utiles.size) {
            val lot = utiles.subList(i, minOf(i + taille, utiles.size))
            val rendu = modele(lot.joinToString(" ") { nu(it.texte) })
                .split(" ").filter { it.isNotEmpty() }
            val nouveaux = mutableListOf<Mot>()
            if (rendu.size == lot.size) {
                for (k in lot.indices) {
                    val src = lot[k]
                    val out = rendu[k]
                    val ponct = QUEUE.find(out)?.value ?: ""
                    var corps = out.substring(0, out.length - ponct.length)
                    val brut = BORDS.replace(src.texte, "")
                    if (brut != brut.lowercase() && brut.lowercase() == corps.lowercase()) corps = brut
                    val bas = corps.lowercase()
                    if (k == 0 && finOuverte && brut == brut.lowercase() && bas != "i" && !bas.startsWith("i'")) {
                        corps = corps.take(1).lowercase() + corps.drop(1)
                    }
                    nouveaux += Mot(src.t, src.fin, corps + ponct)
                }
            } else {
                for (k in rendu.indices) {
                    val src = lot[minOf(lot.size - 1, k * lot.size / maxOf(1, rendu.size))]
                    nouveaux += Mot(src.t, src.fin, rendu[k])
                }
            }
            if (nouveaux.isNotEmpty()) finOuverte = !finit(nouveaux.last().texte)
            sortie += nouveaux
            i += taille
            progres?.invoke(minOf(100, i * 100 / maxOf(1, utiles.size)))
        }
        if (sortie.isNotEmpty() && !finit(sortie.last().texte)) sortie.last().texte += "."
        return sortie
    }

    /** Découpe en phrases (ou en morceaux de maxCar si une phrase est très longue). */
    fun phrases(mots: List<Mot>, maxCar: Int = 420): MutableList<Bloc> {
        val blocs = mutableListOf<Bloc>()
        val courant = mutableListOf<Mot>()
        var longueur = 0
        fun vider() {
            if (courant.isNotEmpty()) {
                blocs += Bloc(courant.first().t, courant.last().fin, courant.joinToString(" ") { it.texte })
                courant.clear()
            }
            longueur = 0
        }
        for (m in mots) {
            courant += m
            longueur += m.texte.length + 1
            val virgule = m.texte.endsWith(",") || m.texte.endsWith(";") || m.texte.endsWith(":")
            if (finit(m.texte) || (longueur > maxCar && virgule) || longueur > maxCar * 2) vider()
        }
        vider()
        return blocs
    }

    /** Regroupe les phrases en paragraphes : coupe aux pauses, sinon à la longueur. */
    fun paragraphes(ph: List<Bloc>, mini: Int = 350, maxi: Int = 750, pause: Double = 1.2): MutableList<Bloc> {
        val paras = mutableListOf<Bloc>()
        val courant = mutableListOf<Bloc>()
        var longueur = 0
        for (i in ph.indices) {
            val p = ph[i]
            courant += p
            longueur += p.texte.length + 1
            val suivant = ph.getOrNull(i + 1)
            val silence = if (suivant != null) suivant.debut - p.fin else 0.0
            if (suivant == null || longueur >= maxi || (longueur >= mini && silence >= pause)) {
                paras += Bloc(courant.first().debut, courant.last().fin, courant.joinToString(" ") { it.texte })
                courant.clear()
                longueur = 0
            }
        }
        return paras
    }

    fun heure(sec: Double): String {
        val s = maxOf(0.0, sec).toInt()
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    fun formatTexte(paras: List<Bloc>, horodatage: Boolean): String =
        if (horodatage) paras.joinToString("\n\n") { "[${heure(it.debut)}] ${it.texte}" }
        else paras.joinToString("\n\n") { it.texte }

    /** Relit le texte (éventuellement corrigé à la main) en paragraphes minutés. */
    fun analyserTexte(texte: String): MutableList<Bloc> {
        val paras = mutableListOf<Bloc>()
        for (brut in texte.trim().split(Regex("""\n\s*\n"""))) {
            val ligne = brut.trim().replace(ESPACES, " ")
            if (ligne.isEmpty()) continue
            val m = TETE.find(ligne)
            if (m != null) {
                val t = m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
                paras += Bloc(t.toDouble(), t.toDouble(), ligne.substring(m.range.last + 1))
            } else if (paras.isNotEmpty()) {
                paras += Bloc(paras.last().debut, paras.last().debut, ligne)
            } else {
                paras += Bloc(0.0, 0.0, ligne)
            }
        }
        for (i in paras.indices) {
            val p = paras[i]
            val suivant = paras.getOrNull(i + 1)
            p.fin = if (suivant != null && suivant.debut > p.debut) suivant.debut
            else p.debut + maxOf(2.0, p.texte.length / 15.0)
        }
        return paras
    }

    private fun tempsSrt(sec: Double): String {
        val ms = Math.round(maxOf(0.0, sec) * 1000)
        return "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /** Sous-titres : chaque paragraphe est redécoupé en phrases, le temps réparti au prorata. */
    fun versSrt(paras: List<Bloc>, maxCar: Int = 160): String {
        val sb = StringBuilder()
        var n = 0
        for (p in paras) {
            val morceaux = phrases(p.texte.split(" ").filter { it.isNotEmpty() }.map { Mot(0.0, 0.0, it) }, maxCar)
            val total = maxOf(1, morceaux.sumOf { it.texte.length })
            var t = p.debut
            val duree = maxOf(1.0, p.fin - p.debut)
            for (b in morceaux) {
                val d = duree * b.texte.length / total
                n++
                sb.append(n).append('\n').append(tempsSrt(t)).append(" --> ").append(tempsSrt(t + d))
                    .append('\n').append(b.texte).append("\n\n")
                t += d
            }
        }
        return sb.toString()
    }

    /** Une correction par ligne : « mot entendu => mot juste ». */
    fun lireCorrections(texte: String): List<Pair<String, String>> {
        val regles = mutableListOf<Pair<String, String>>()
        for (ligne in texte.lines()) {
            if (!ligne.contains("=>") || ligne.trim().startsWith("#")) continue
            val faux = ligne.substringBefore("=>").trim()
            val juste = ligne.substringAfter("=>").trim()
            if (faux.isNotEmpty()) regles += faux to juste
        }
        return regles
    }

    fun appliquerCorrections(texte: String, regles: List<Pair<String, String>>): String {
        var t = texte
        for ((faux, juste) in regles) {
            val motif = Regex("(?<!\\w)" + Regex.escape(faux) + "(?!\\w)", RegexOption.IGNORE_CASE)
            t = motif.replace(t) { juste }
        }
        return t
    }

    class Resultat(val paras: List<Bloc>, val ponctuationAjoutee: Boolean)

    /** Chaîne complète : lignes reconnues -> paragraphes prêts à relire. */
    fun construire(
        lignes: List<Ligne>, modelePonct: ((String) -> String)?,
        regles: List<Pair<String, String>>, progres: ((Int) -> Unit)? = null
    ): Resultat {
        var mots: List<Mot> = lignesVersMots(lignes)
        var ajoutee = false
        if (!estPonctue(mots) && modelePonct != null) {
            mots = ponctuer(mots, modelePonct, progres = progres)
            ajoutee = true
        }
        val paras = paragraphes(phrases(mots))
        if (regles.isNotEmpty()) for (p in paras) p.texte = appliquerCorrections(p.texte, regles)
        return Resultat(paras, ajoutee)
    }
}

/** Un passage reconnu ou une ligne de sous-titre. */
class Ligne(val debut: Double, val fin: Double, val texte: String)
