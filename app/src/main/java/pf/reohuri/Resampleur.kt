package pf.reohuri

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Convertit un son mono vers 16 000 Hz, morceau par morceau.
 *
 * Avant de réduire la cadence, un filtre passe-bas retire les aigus au-dessus
 * de 7 kHz : sans lui, ces aigus se replient dans la voix et gênent la
 * reconnaissance. Le gain sert à remonter un enregistrement trop faible.
 */
class Resampleur(inRate: Int, private val gain: Float = 1f) {

    private val pas = inRate / 16000.0
    private var t = 0.0
    private var precedent = 0f

    private val filtre: FloatArray? = if (inRate > 16000) passeBas(inRate) else null
    private val memoire = FloatArray(filtre?.size ?: 0)   // derniers échantillons reçus (tampon circulaire)
    private var tete = 0

    private fun passeBas(rate: Int): FloatArray {
        val n = 2 * (rate * 2 / 1000) + 1                 // environ 4 ms de son, toujours impair
        val fc = 7000.0 / rate
        val h = FloatArray(n)
        var somme = 0.0
        for (i in 0 until n) {
            val k = i - (n - 1) / 2.0
            val sinc = if (k == 0.0) 2 * fc else sin(2 * PI * fc * k) / (PI * k)
            val fenetre = 0.54 - 0.46 * cos(2 * PI * i / (n - 1))
            h[i] = (sinc * fenetre).toFloat()
            somme += h[i]
        }
        for (i in 0 until n) h[i] = (h[i] / somme).toFloat()
        return h
    }

    private fun filtrer(x: Float): Float {
        val h = filtre ?: return x
        memoire[tete] = x
        var acc = 0f
        var j = tete
        for (c in h) {
            acc += c * memoire[j]
            j = if (j == 0) h.size - 1 else j - 1
        }
        tete = if (tete + 1 == h.size) 0 else tete + 1
        return acc
    }

    /** @param entree échantillons mono à l'échelle 16 bits (-32768..32767) */
    fun traiter(entree: FloatArray, n: Int, sortie: (ShortArray, Int) -> Unit) {
        if (n <= 0) return
        for (i in 0 until n) entree[i] = filtrer(entree[i]) * gain
        val res = ShortArray((n / pas).toInt() + 4)
        var k = 0
        while (t < n - 1 && k < res.size) {
            val i = floor(t).toInt()
            val frac = (t - i).toFloat()
            val a = if (i < 0) precedent else entree[i]
            val b = entree[i + 1]
            var v = a + (b - a) * frac
            if (v > 32767f) v = 32767f
            if (v < -32768f) v = -32768f
            res[k++] = v.toInt().toShort()
            t += pas
        }
        t -= n
        precedent = entree[n - 1]
        if (k > 0) sortie(res, k)
    }
}
