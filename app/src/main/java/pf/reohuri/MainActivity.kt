package pf.reohuri

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Reo Huri : une vidéo anglaise (fichier ou lien YouTube) devient un texte
 * anglais exact, à relire puis à enregistrer en Word, texte ou sous-titres.
 */
class MainActivity : Activity() {

    private lateinit var modeles: ModelManager
    private val prefs by lazy { getSharedPreferences("reglages", Context.MODE_PRIVATE) }

    private lateinit var edtUrl: EditText
    private lateinit var edtTexte: EditText
    private lateinit var txtFile: TextView
    private lateinit var txtModel: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtMaj: TextView
    private lateinit var btnStart: Button
    private lateinit var btnUpdate: ImageButton
    private lateinit var chkHoro: CheckBox
    private lateinit var progress: ProgressBar
    private lateinit var barMaj: ProgressBar
    private lateinit var dotUpdate: View

    private var fichier: Uri? = null
    private var titreDoc = "Transcription"
    private var sourceDoc = ""
    private var enCours = false
    private var transcripteur: Transcriber? = null
    private var majEnCours = false
    private var apkPret: java.io.File? = null

    // ---------- Démarrage ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        modeles = ModelManager(this)

        edtUrl = findViewById(R.id.edtUrl)
        edtTexte = findViewById(R.id.edtTexte)
        txtFile = findViewById(R.id.txtFile)
        txtModel = findViewById(R.id.txtModel)
        txtStatus = findViewById(R.id.txtStatus)
        txtMaj = findViewById(R.id.txtMaj)
        btnStart = findViewById(R.id.btnStart)
        btnUpdate = findViewById(R.id.btnUpdate)
        chkHoro = findViewById(R.id.chkHoro)
        progress = findViewById(R.id.progress)
        barMaj = findViewById(R.id.barMaj)
        dotUpdate = findViewById(R.id.dotUpdate)

        findViewById<TextView>(R.id.txtVersion).text =
            "Vidéo anglaise → texte anglais exact · v${BuildConfig.VERSION_NAME}"
        chkHoro.isChecked = prefs.getBoolean("horodatage", true)
        afficherMoteur()

        findViewById<Button>(R.id.btnPaste).setOnClickListener { coller() }
        findViewById<Button>(R.id.btnPick).setOnClickListener { choisirFichier() }
        txtModel.setOnClickListener { choisirMoteur() }
        btnStart.setOnClickListener { if (enCours) arreter() else lancer() }
        btnUpdate.setOnClickListener { mettreAJour() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { enregistrer() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { partager() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copier() }
        findViewById<Button>(R.id.btnFix).setOnClickListener { corrections() }

        recevoirPartage(intent)
        verifierMaj(manuel = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recevoirPartage(intent)
    }

    /** Vidéo ou lien « partagé » vers Reo Huri depuis une autre app. */
    private fun recevoirPartage(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val texte = i.getStringExtra(Intent.EXTRA_TEXT)
        if (texte != null && YouTube.idVideo(texte) != null) {
            poserLien(texte)
            return
        }
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) poserFichier(uri)
    }

    private fun dire(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun etat(message: String) { txtStatus.text = message }

    // ---------- Source ----------

    private fun coller() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (YouTube.idVideo(t) != null) poserLien(t) else dire("Copiez d'abord un lien YouTube")
    }

    private fun poserLien(texte: String) {
        val id = YouTube.idVideo(texte) ?: return
        edtUrl.setText("https://youtu.be/$id")
        fichier = null
        txtFile.text = "Lien YouTube prêt : l'app lira les sous-titres anglais de la vidéo."
    }

    private fun poserFichier(uri: Uri) {
        edtUrl.setText("")
        fichier = uri
        val nom = nomDe(uri) ?: "fichier"
        titreDoc = nom.substringBeforeLast('.')
        txtFile.text = "Fichier : $nom"
    }

    private fun nomDe(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun choisirFichier() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "Choisir une vidéo ou un fichier audio"), DEMANDE_FICHIER)
    }

    private fun afficherMoteur() {
        val m = modeles.moteur
        val suffixe = if (modeles.installe(m)) "installé" else "à télécharger"
        txtModel.text = "Moteur fichiers : ${m.label.substringBefore(" (")} · $suffixe ▾"
    }

    private fun choisirMoteur() {
        if (enCours) return
        val liste = Modele.MOTEURS
        val noms = liste.map { it.label + if (modeles.installe(it)) "  ✓" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Moteur de transcription des fichiers")
            .setSingleChoiceItems(noms, liste.indexOf(modeles.moteur)) { d, n ->
                modeles.moteur = liste[n]
                afficherMoteur()
                d.dismiss()
            }
            .show()
    }

    // ---------- Travail ----------

    private fun lancer() {
        val video = YouTube.idVideo(edtUrl.text.toString())
        val uri = fichier
        if (video == null && uri == null) {
            dire("Collez un lien YouTube ou choisissez un fichier")
            return
        }
        if (edtTexte.text.isNotBlank()) {
            AlertDialog.Builder(this)
                .setMessage("Le texte affiché sera remplacé. Continuer ?")
                .setPositiveButton("Continuer") { _, _ -> demarrer(video, uri) }
                .setNegativeButton("Annuler", null)
                .show()
        } else {
            demarrer(video, uri)
        }
    }

    private fun demarrer(video: String?, uri: Uri?) {
        prefs.edit().putBoolean("horodatage", chkHoro.isChecked).apply()
        enCours = true
        edtTexte.setText("")
        btnStart.text = "Arrêter"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val t = Transcriber(this, modeles)
        transcripteur = t
        thread(name = "reo-huri-travail") {
            val message = try {
                if (video != null) depuisYouTube(video) else depuisFichier(uri!!, t)
            } catch (e: Throwable) {
                val detail = e.message ?: e.javaClass.simpleName
                if (video != null) "$detail. Astuce : avec le fichier de la vidéo, « Fichier… » marche toujours."
                else "Erreur : $detail"
            }
            runOnUiThread {
                enCours = false
                transcripteur = null
                btnStart.text = "Obtenir le texte anglais"
                progress.visibility = View.INVISIBLE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                afficherMoteur()
                etat(message)
            }
        }
    }

    private fun arreter() {
        transcripteur?.annule = true
        etat("Arrêt en cours…")
    }

    private fun avancer(p: Int, message: String? = null) = runOnUiThread {
        progress.isIndeterminate = false
        progress.progress = p
        if (message != null) etat(message)
    }

    private fun attente(message: String) = runOnUiThread {
        progress.isIndeterminate = true
        etat(message)
    }

    private fun depuisYouTube(video: String): String {
        attente("Lecture des sous-titres YouTube…")
        val (titre, chaine) = YouTube.titre(video)
        titreDoc = titre.ifEmpty { "YouTube $video" }
        sourceDoc = "Source : https://youtu.be/$video" + if (chaine.isNotEmpty()) " ($chaine)" else ""
        val r = YouTube.sousTitres(video)
        return terminer(
            r.lignes,
            if (r.automatiques) "sous-titres automatiques de YouTube" else "sous-titres de l'auteur de la vidéo"
        )
    }

    private fun depuisFichier(uri: Uri, t: Transcriber): String {
        val m = modeles.moteur
        if (!modeles.installe(m)) {
            val texte = "Téléchargement du moteur (${m.mo} Mo, une seule fois)…"
            avancer(0, texte)
            modeles.telecharger(m) { avancer(it) }
        }
        modeles.telechargerVad()
        attente("Chargement du moteur…")
        sourceDoc = "Source : " + (nomDe(uri) ?: "fichier")
        val t0 = System.currentTimeMillis()
        val lignes = t.transcrire(
            uri, m,
            progres = { p ->
                val ecoule = (System.currentTimeMillis() - t0) / 1000
                val reste = if (p > 2) ecoule * (100 - p) / p else -1
                avancer(p, "Transcription… $p %" + if (reste >= 0) " · reste environ ${reste / 60} min ${reste % 60} s" else "")
            },
            surLigne = { ligne -> runOnUiThread { edtTexte.append("$ligne ") } }
        )
        if (lignes.isEmpty()) return if (t.annule) "Arrêté." else "Aucune parole détectée dans ce fichier."
        val nom = m.label.substringBefore(" (").substringAfter("— ")
        return terminer(lignes, "transcription automatique $nom" + if (t.annule) " (arrêtée avant la fin)" else "")
    }

    /** Ponctuation si besoin, phrases, paragraphes, corrections, puis affichage. */
    private fun terminer(lignes: List<Ligne>, origine: String): String {
        var provenance = origine
        val regles = Texte.lireCorrections(prefs.getString("corrections", "") ?: "")
        var ponct: Transcriber.Ponctuation? = null
        if (!Texte.estPonctue(Texte.lignesVersMots(lignes))) {
            try {
                if (!modeles.installe(Modele.PONCTUATION)) {
                    avancer(0, "Téléchargement du modèle de ponctuation (30 Mo, une seule fois)…")
                    modeles.telecharger(Modele.PONCTUATION) { avancer(it) }
                }
                ponct = Transcriber.Ponctuation(modeles)
                avancer(0, "Ajout de la ponctuation…")
            } catch (e: Throwable) {
                provenance += " ; ponctuation non ajoutée"
            }
        }
        val modele: ((String) -> String)? = ponct?.let { p -> { s: String -> p.ajouter(s) } }
        val r = try {
            Texte.construire(lignes, modele, regles) { avancer(it) }
        } finally {
            ponct?.fermer()
        }
        if (r.ponctuationAjoutee) provenance += " ; ponctuation et majuscules ajoutées par un modèle automatique"
        sourceDoc = "$sourceDoc — $provenance"
        val texte = Texte.formatTexte(r.paras, chkHoro.isChecked)
        val mots = r.paras.sumOf { it.texte.split(" ").size }
        runOnUiThread { edtTexte.setText(texte) }
        return "Terminé · $mots mots · $provenance. Relisez puis enregistrez."
    }

    // ---------- Texte ----------

    private fun paras(): List<Bloc> = Texte.analyserTexte(edtTexte.text.toString())

    private fun avecHorodatage() = edtTexte.text.startsWith("[")

    private fun nomFichier(): String =
        titreDoc.replace(Regex("""[\\/:*?"<>|]+"""), " ").trim().take(90).ifEmpty { "transcription" }

    private fun enregistrer() {
        if (paras().isEmpty()) { dire("Il n'y a pas encore de texte à enregistrer"); return }
        val choix = arrayOf("Document Word (.docx) — pour ton pack de traduction", "Texte (.txt)", "Sous-titres (.srt)")
        AlertDialog.Builder(this)
            .setTitle("Enregistrer sur le téléphone")
            .setItems(choix) { _, n ->
                val (ext, mime) = when (n) {
                    0 -> "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    1 -> "txt" to "text/plain"
                    else -> "srt" to "application/x-subrip"
                }
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, "${nomFichier()} (EN).$ext")
                }
                startActivityForResult(i, DEMANDE_ENREGISTRER + n)
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == DEMANDE_FICHIER) {
            poserFichier(uri)
            return
        }
        val p = paras()
        try {
            contentResolver.openOutputStream(uri)?.use { sortie ->
                when (requestCode - DEMANDE_ENREGISTRER) {
                    0 -> Exports.ecrireDocx(sortie, titreDoc, sourceDoc, p, avecHorodatage())
                    1 -> sortie.write(Exports.texteBrut(titreDoc, sourceDoc, p, avecHorodatage()).toByteArray(Charsets.UTF_8))
                    else -> sortie.write(Texte.versSrt(p).toByteArray(Charsets.UTF_8))
                }
            }
            dire("Fichier enregistré")
        } catch (e: Exception) {
            dire("Impossible d'enregistrer : ${e.message}")
        }
    }

    private fun partager() {
        val t = edtTexte.text.toString().trim()
        if (t.isEmpty()) { dire("Rien à partager pour l'instant"); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, titreDoc)
            putExtra(Intent.EXTRA_TEXT, t)
        }, "Partager le texte"))
    }

    private fun copier() {
        val t = edtTexte.text.toString().trim()
        if (t.isEmpty()) { dire("Rien à copier pour l'instant"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Reo Huri", t))
        dire("Texte copié")
    }

    /** Liste « mot entendu => mot juste », appliquée à chaque nouveau texte. */
    private fun corrections() {
        val zone = EditText(this).apply {
            setText(prefs.getString("corrections", ""))
            hint = "Parminder Bient => Parminder Biant"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            val marge = (16 * resources.displayMetrics.density).toInt()
            setPadding(marge, marge, marge, marge)
        }
        fun garder(appliquer: Boolean) {
            prefs.edit().putString("corrections", zone.text.toString().trim()).apply()
            if (appliquer && edtTexte.text.isNotBlank()) {
                val regles = Texte.lireCorrections(zone.text.toString())
                edtTexte.setText(Texte.appliquerCorrections(edtTexte.text.toString(), regles))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Corrections automatiques")
            .setMessage("Une correction par ligne :\nmot entendu => mot juste")
            .setView(zone)
            .setPositiveButton("Appliquer au texte") { _, _ -> garder(true) }
            .setNeutralButton("Enregistrer") { _, _ -> garder(false) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- Mise à jour (avec barre) ----------

    private var derniere: Release? = null

    private fun verifierMaj(manuel: Boolean) {
        if (manuel) txtMaj.text = "Recherche…"
        thread(name = "reo-huri-maj") {
            val r = Updater.derniere()
            runOnUiThread {
                derniere = r
                val nouvelle = r != null && Updater.plusRecente(r)
                dotUpdate.visibility = if (nouvelle) View.VISIBLE else View.GONE
                txtMaj.text = when {
                    nouvelle -> "Nouvelle version"
                    manuel && r == null -> "Vérification impossible"
                    manuel -> "À jour"
                    else -> ""
                }
                if (manuel && nouvelle) proposer(r!!)
            }
        }
    }

    private fun mettreAJour() {
        if (majEnCours) return
        val pret = apkPret
        if (pret != null) {                      // déjà téléchargé : il ne restait que l'autorisation
            if (!Updater.installer(this, pret)) dire("Autorisez l'installation, puis appuyez à nouveau sur le bouton")
            return
        }
        val r = derniere
        if (r != null && Updater.plusRecente(r)) proposer(r) else verifierMaj(manuel = true)
    }

    private fun proposer(r: Release) {
        AlertDialog.Builder(this)
            .setTitle("Nouvelle version ${r.tag}")
            .setMessage("Vous avez la v${BuildConfig.VERSION_NAME}. Télécharger et installer la nouvelle version ?")
            .setPositiveButton("Installer") { _, _ -> telechargerMaj(r) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun telechargerMaj(r: Release) {
        majEnCours = true
        barMaj.progress = 0
        barMaj.visibility = View.VISIBLE
        txtMaj.text = "0 %"
        thread(name = "reo-huri-maj") {
            try {
                val apk = Updater.telecharger(this, r) { p ->
                    runOnUiThread { barMaj.progress = p; txtMaj.text = "$p %" }
                }
                runOnUiThread {
                    majEnCours = false
                    apkPret = apk
                    barMaj.visibility = View.GONE
                    txtMaj.text = "Prête à installer"
                    if (!Updater.installer(this, apk)) {
                        dire("Autorisez l'installation, puis appuyez à nouveau sur le bouton de mise à jour")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    majEnCours = false
                    barMaj.visibility = View.GONE
                    txtMaj.text = "Échec"
                    dire("Téléchargement impossible : ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val DEMANDE_FICHIER = 10
        const val DEMANDE_ENREGISTRER = 20
    }
}
