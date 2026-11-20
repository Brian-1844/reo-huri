package pf.reohuri

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class Release(val versionCode: Int, val tag: String, val apkUrl: String)

/** Mise à jour depuis les « Releases » GitHub du dépôt. Fonctions réseau bloquantes. */
object Updater {

    private val api get() = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

    /** Dernière version publiée, ou null si la vérification est impossible. */
    fun derniere(): Release? = try {
        val c = URL(api).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        if (c.responseCode != 200) null else {
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = tag.substringAfterLast('.').filter { it.isDigit() }.toIntOrNull()
            val assets = json.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            if (code == null || url == null) null else Release(code, tag, url)
        }
    } catch (e: Exception) {
        null
    }

    fun plusRecente(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    fun telecharger(activity: Activity, r: Release, progres: (Int) -> Unit): File {
        val dossier = File(activity.cacheDir, "maj").apply { mkdirs() }
        val fichier = File(dossier, "ReoHuri.apk")
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        val total = c.contentLengthLong
        c.inputStream.use { entree ->
            FileOutputStream(fichier).use { sortie ->
                val tampon = ByteArray(64 * 1024)
                var lu = 0L
                var dernier = -1
                while (true) {
                    val n = entree.read(tampon)
                    if (n < 0) break
                    sortie.write(tampon, 0, n)
                    lu += n
                    if (total > 0) {
                        val p = (lu * 100 / total).toInt()
                        if (p != dernier) { dernier = p; progres(p) }
                    }
                }
            }
        }
        return fichier
    }

    /** @return false si l'autorisation « sources inconnues » doit d'abord être donnée */
    fun installer(activity: Activity, apk: File): Boolean {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }
}
