"""Mise en forme du texte anglais : mots minutés -> phrases -> paragraphes.

Aucune dépendance : ce module est testé seul.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Iterable


@dataclass
class Mot:
    t: float      # début (secondes) du passage d'où vient le mot
    fin: float    # fin de ce passage
    texte: str


@dataclass
class Bloc:
    debut: float
    fin: float
    texte: str


FIN_PHRASE = re.compile(r"""[.?!…]["'”’)\]]*$""")
BRUIT = re.compile(r"\[[^\]]*\]|\([^)]*\)|>>+|♪+")


def nettoyer_ligne(s: str) -> str:
    """Retire les indications de sous-titres : [Music], (applause), >>."""
    s = BRUIT.sub(" ", s.replace("\n", " "))
    return re.sub(r"\s+", " ", s).strip()


def lignes_vers_mots(lignes: Iterable[tuple[float, float, str]]) -> list[Mot]:
    mots: list[Mot] = []
    for debut, fin, texte in lignes:
        for m in nettoyer_ligne(texte).split():
            mots.append(Mot(debut, max(fin, debut), m))
    return mots


def est_ponctue(mots: list[Mot]) -> bool:
    """Vrai si le texte porte déjà une ponctuation de phrase régulière."""
    if not mots:
        return True
    fins = sum(1 for m in mots if FIN_PHRASE.search(m.texte))
    return fins * 40 >= len(mots)          # au moins une fin de phrase pour 40 mots


def _nu(mot: str) -> str:
    """Mot en minuscules, sans ponctuation autour (apostrophes internes gardées)."""
    return re.sub(r"^[^\w]+|[^\w]+$", "", mot.lower())


def ponctuer(mots: list[Mot], modele: Callable[[str], str], taille: int = 120,
             progres: Callable[[int], None] | None = None) -> list[Mot]:
    """Ajoute ponctuation et majuscules avec un modèle local.

    Le modèle ne change pas les mots : on garde donc la majuscule d'origine
    (noms propres déjà reconnus) et on ne lui prend que la ponctuation.
    """
    utiles = [m for m in mots if _nu(m.texte)]
    sortie: list[Mot] = []
    fin_ouverte = False                     # le lot précédent finit-il au milieu d'une phrase ?
    for i in range(0, len(utiles), taille):
        lot = utiles[i:i + taille]
        nus = [_nu(m.texte) for m in lot]
        rendu = modele(" ".join(nus)).split()
        if len(rendu) == len(lot):
            nouveaux = []
            for k, (src, out) in enumerate(zip(lot, rendu)):
                queue = re.search(r"[^\w]+$", out)
                ponct = queue.group(0) if queue else ""
                corps = out[:len(out) - len(ponct)] if ponct else out
                brut = re.sub(r"^[^\w]+|[^\w]+$", "", src.texte)
                if brut != brut.lower() and brut.lower() == corps.lower():
                    corps = brut            # majuscule d'origine conservée
                if k == 0 and fin_ouverte and brut == brut.lower() and corps.lower() != "i" \
                        and not corps.lower().startswith("i'"):
                    corps = corps[:1].lower() + corps[1:]
                nouveaux.append(Mot(src.t, src.fin, corps + ponct))
        else:                               # cas rare : on garde le rendu du modèle tel quel
            nouveaux = []
            for k, out in enumerate(rendu):
                src = lot[min(len(lot) - 1, k * len(lot) // max(1, len(rendu)))]
                nouveaux.append(Mot(src.t, src.fin, out))
        if nouveaux:
            fin_ouverte = not FIN_PHRASE.search(nouveaux[-1].texte)
        sortie.extend(nouveaux)
        if progres:
            progres(min(100, (i + taille) * 100 // max(1, len(utiles))))
    if sortie and not FIN_PHRASE.search(sortie[-1].texte):
        sortie[-1].texte += "."
    return sortie


def phrases(mots: list[Mot], max_car: int = 420) -> list[Bloc]:
    """Découpe en phrases (ou en morceaux de max_car si une phrase est très longue)."""
    blocs: list[Bloc] = []
    courant: list[Mot] = []

    def vider():
        if courant:
            blocs.append(Bloc(courant[0].t, courant[-1].fin, " ".join(m.texte for m in courant)))
            courant.clear()

    longueur = 0
    for m in mots:
        courant.append(m)
        longueur += len(m.texte) + 1
        if FIN_PHRASE.search(m.texte) or (longueur > max_car and m.texte.endswith((",", ";", ":"))) \
                or longueur > max_car * 2:
            vider()
            longueur = 0
    vider()
    return blocs


def paragraphes(ph: list[Bloc], mini: int = 350, maxi: int = 750, pause: float = 1.2) -> list[Bloc]:
    """Regroupe les phrases en paragraphes : coupe aux pauses, sinon à la longueur."""
    paras: list[Bloc] = []
    courant: list[Bloc] = []
    longueur = 0
    for i, p in enumerate(ph):
        courant.append(p)
        longueur += len(p.texte) + 1
        suivant = ph[i + 1] if i + 1 < len(ph) else None
        silence = (suivant.debut - p.fin) if suivant else 0.0
        if suivant is None or longueur >= maxi or (longueur >= mini and silence >= pause):
            paras.append(Bloc(courant[0].debut, courant[-1].fin, " ".join(b.texte for b in courant)))
            courant, longueur = [], 0
    return paras


def heure(sec: float) -> str:
    s = int(max(0, sec))
    return f"{s // 3600:02d}:{s // 60 % 60:02d}:{s % 60:02d}"


def format_texte(paras: list[Bloc], horodatage: bool = True) -> str:
    if horodatage:
        return "\n\n".join(f"[{heure(p.debut)}] {p.texte}" for p in paras)
    return "\n\n".join(p.texte for p in paras)


_TETE = re.compile(r"^\[(\d{1,2}):(\d{2}):(\d{2})\]\s*")


def analyser_texte(texte: str) -> list[Bloc]:
    """Relit le texte (éventuellement corrigé à la main) en paragraphes minutés."""
    paras: list[Bloc] = []
    for brut in re.split(r"\n\s*\n", texte.strip()):
        ligne = " ".join(brut.split())
        if not ligne:
            continue
        m = _TETE.match(ligne)
        if m:
            t = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
            paras.append(Bloc(float(t), float(t), ligne[m.end():]))
        elif paras:
            paras.append(Bloc(paras[-1].debut, paras[-1].debut, ligne))
        else:
            paras.append(Bloc(0.0, 0.0, ligne))
    for i, p in enumerate(paras):           # la fin d'un paragraphe = le début du suivant
        if i + 1 < len(paras) and paras[i + 1].debut > p.debut:
            p.fin = paras[i + 1].debut
        else:
            p.fin = p.debut + max(2.0, len(p.texte) / 15.0)
    return paras


def _temps_srt(sec: float) -> str:
    ms = int(round(max(0.0, sec) * 1000))
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"


def vers_srt(paras: list[Bloc], max_car: int = 160) -> str:
    """Sous-titres : chaque paragraphe est redécoupé en phrases, le temps réparti au prorata."""
    sortie: list[str] = []
    n = 0
    for p in paras:
        mots = [Mot(0, 0, w) for w in p.texte.split()]
        morceaux = phrases(mots, max_car=max_car)
        total = sum(len(b.texte) for b in morceaux) or 1
        t = p.debut
        duree = max(1.0, p.fin - p.debut)
        for b in morceaux:
            d = duree * len(b.texte) / total
            n += 1
            sortie.append(f"{n}\n{_temps_srt(t)} --> {_temps_srt(t + d)}\n{b.texte}\n")
            t += d
    return "\n".join(sortie)


def lire_corrections(texte: str) -> list[tuple[str, str]]:
    """Une correction par ligne : « mot entendu => mot juste »."""
    regles = []
    for ligne in texte.splitlines():
        if "=>" in ligne and not ligne.strip().startswith("#"):
            faux, juste = ligne.split("=>", 1)
            if faux.strip():
                regles.append((faux.strip(), juste.strip()))
    return regles


def appliquer_corrections(texte: str, regles: list[tuple[str, str]]) -> str:
    for faux, juste in regles:
        motif = r"(?<!\w)" + re.escape(faux) + r"(?!\w)"
        texte = re.sub(motif, lambda _m, j=juste: j, texte, flags=re.IGNORECASE)
    return texte


def construire(lignes: list[tuple[float, float, str]], modele_ponct: Callable[[str], str] | None,
               regles: list[tuple[str, str]] | None = None,
               progres: Callable[[int], None] | None = None) -> tuple[list[Bloc], bool]:
    """Chaîne complète. Renvoie (paragraphes, ponctuation_ajoutée)."""
    mots = lignes_vers_mots(lignes)
    ajoutee = False
    if not est_ponctue(mots) and modele_ponct is not None:
        mots = ponctuer(mots, modele_ponct, progres=progres)
        ajoutee = True
    paras = paragraphes(phrases(mots))
    if regles:
        for p in paras:
            p.texte = appliquer_corrections(p.texte, regles)
    return paras, ajoutee
