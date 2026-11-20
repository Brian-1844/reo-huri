"""Téléchargement des modèles de reconnaissance (une seule fois, puis hors ligne)."""
from __future__ import annotations

import os
import shutil
import tarfile
import urllib.request
from dataclasses import dataclass
from typing import Callable

from .reglages import dossier_donnees

_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"


@dataclass(frozen=True)
class Modele:
    cle: str
    nom: str
    archive: str            # nom du dossier dans l'archive
    url: str
    mo: int
    fichiers: tuple[str, ...]


PARAKEET = Modele("parakeet", "Très précis — Parakeet (482 Mo, 1 Go de mémoire)",
                  "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
                  f"{_BASE}/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", 482,
                  ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"))
WHISPER_BASE = Modele("whisper_base", "Précis — Whisper base (199 Mo)",
                      "sherpa-onnx-whisper-base.en",
                      f"{_BASE}/asr-models/sherpa-onnx-whisper-base.en.tar.bz2", 199,
                      ("base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx", "base.en-tokens.txt"))
WHISPER_TINY = Modele("whisper_tiny", "Léger — Whisper tiny (113 Mo, ordinateur lent)",
                      "sherpa-onnx-whisper-tiny.en",
                      f"{_BASE}/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2", 113,
                      ("tiny.en-encoder.int8.onnx", "tiny.en-decoder.int8.onnx", "tiny.en-tokens.txt"))
PONCTUATION = Modele("ponctuation", "Ponctuation anglaise (30 Mo)",
                     "sherpa-onnx-online-punct-en-2024-08-06",
                     f"{_BASE}/punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2", 30,
                     ("model.int8.onnx", "bpe.vocab"))
MOTEURS = [PARAKEET, WHISPER_BASE, WHISPER_TINY]
VAD_URL = f"{_BASE}/asr-models/silero_vad.onnx"


def racine() -> str:
    d = os.path.join(dossier_donnees(), "modeles")
    os.makedirs(d, exist_ok=True)
    return d


def dossier(m: Modele) -> str:
    return os.path.join(racine(), m.archive)


def chemin(m: Modele, fichier: str) -> str:
    return os.path.join(dossier(m), fichier)


def vad() -> str:
    return os.path.join(racine(), "silero_vad.onnx")


def installe(m: Modele) -> bool:
    return all(os.path.exists(chemin(m, f)) for f in m.fichiers)


class _Compteur:
    """Enveloppe un flux pour suivre l'avancement du téléchargement."""

    def __init__(self, flux, total: int, progres: Callable[[int], None] | None):
        self.flux, self.total, self.progres, self.lu, self.dernier = flux, total, progres, 0, -1

    def read(self, n: int = -1) -> bytes:
        d = self.flux.read(n)
        self.lu += len(d)
        if self.progres and self.total > 0:
            p = min(99, self.lu * 100 // self.total)
            if p != self.dernier:
                self.dernier = p
                self.progres(p)
        return d


def telecharger(m: Modele, progres: Callable[[int], None] | None = None) -> None:
    """Télécharge l'archive et n'en garde que les fichiers utiles (décompression au fil de l'eau)."""
    tmp = dossier(m) + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    with urllib.request.urlopen(m.url, timeout=60) as r:
        flux = _Compteur(r, int(r.headers.get("Content-Length") or 0), progres)
        with tarfile.open(fileobj=flux, mode="r|bz2") as tar:
            for membre in tar:
                nom = os.path.basename(membre.name)
                if membre.isfile() and nom in m.fichiers:
                    src = tar.extractfile(membre)
                    with open(os.path.join(tmp, nom), "wb") as dst:
                        shutil.copyfileobj(src, dst, 1 << 20)
    manquants = [f for f in m.fichiers if not os.path.exists(os.path.join(tmp, f))]
    if manquants:
        shutil.rmtree(tmp, ignore_errors=True)
        raise RuntimeError("Modèle incomplet, réessayez le téléchargement.")
    shutil.rmtree(dossier(m), ignore_errors=True)
    os.replace(tmp, dossier(m))
    if progres:
        progres(100)


def telecharger_vad() -> None:
    if os.path.exists(vad()):
        return
    tmp = vad() + ".tmp"
    with urllib.request.urlopen(VAD_URL, timeout=60) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    os.replace(tmp, vad())
