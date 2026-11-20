"""Sous-titres anglais d'une vidéo YouTube (la vidéo n'est pas téléchargée)."""
from __future__ import annotations

import json
import re
import urllib.parse
import urllib.request

_MOTIFS = [
    re.compile(r"youtu\.be/([A-Za-z0-9_-]{11})"),
    re.compile(r"[?&]v=([A-Za-z0-9_-]{11})"),
    re.compile(r"youtube\.com/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})"),
]


def id_video(texte: str) -> str | None:
    for m in _MOTIFS:
        r = m.search(texte or "")
        if r:
            return r.group(1)
    return None


def titre(video: str) -> tuple[str, str]:
    """(titre, chaîne) par l'adresse publique oEmbed ; chaînes vides en cas d'échec."""
    try:
        url = "https://www.youtube.com/oembed?format=json&url=" + urllib.parse.quote(
            f"https://www.youtube.com/watch?v={video}", safe="")
        with urllib.request.urlopen(url, timeout=15) as r:
            d = json.load(r)
        return d.get("title", ""), d.get("author_name", "")
    except Exception:
        return "", ""


class ErreurYouTube(Exception):
    pass


def sous_titres(video: str) -> tuple[list[tuple[float, float, str]], bool]:
    """Renvoie (lignes [(début, fin, texte)], automatiques?).

    Préfère les sous-titres écrits par l'auteur aux sous-titres automatiques.
    """
    from youtube_transcript_api import YouTubeTranscriptApi
    from youtube_transcript_api import _errors as e

    langues = ["en", "en-US", "en-GB", "en-CA", "en-AU", "en-IN"]
    try:
        liste = YouTubeTranscriptApi().list(video)
        try:
            piste, auto = liste.find_manually_created_transcript(langues), False
        except e.NoTranscriptFound:
            piste, auto = liste.find_generated_transcript(langues), True
        morceaux = piste.fetch()
    except e.NoTranscriptFound:
        raise ErreurYouTube("Cette vidéo n'a pas de sous-titres anglais.")
    except e.TranscriptsDisabled:
        raise ErreurYouTube("Cette vidéo n'a pas de sous-titres.")
    except (e.IpBlocked, e.RequestBlocked):
        raise ErreurYouTube("YouTube bloque temporairement les demandes depuis cette connexion. Réessayez plus tard.")
    except e.PoTokenRequired:
        raise ErreurYouTube("YouTube refuse de donner les sous-titres de cette vidéo (jeton exigé).")
    except e.VideoUnavailable:
        raise ErreurYouTube("Vidéo introuvable ou privée.")
    except e.CouldNotRetrieveTranscript as ex:
        raise ErreurYouTube("Sous-titres indisponibles : " + type(ex).__name__)
    except ErreurYouTube:
        raise
    except Exception as ex:                      # pas de réseau, réponse inattendue…
        raise ErreurYouTube(f"Connexion à YouTube impossible ({type(ex).__name__}).")
    lignes = [(float(s.start), float(s.start) + float(s.duration), s.text) for s in morceaux]
    if not any(t.strip() for _, _, t in lignes):
        raise ErreurYouTube("YouTube a renvoyé des sous-titres vides.")
    return lignes, auto
