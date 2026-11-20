"""Dossier de données et réglages de l'utilisateur."""
from __future__ import annotations

import json
import os
import sys


def dossier_donnees() -> str:
    base = os.environ.get("LOCALAPPDATA") or os.path.join(os.path.expanduser("~"), ".local", "share")
    d = os.path.join(base, "ReoHuri")
    os.makedirs(d, exist_ok=True)
    return d


def dossier_app() -> str:
    """Dossier du programme installé (là où se trouve ReoHuri.exe)."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


_FICHIER = os.path.join(dossier_donnees(), "reglages.json")
DEFAUT = {"moteur": "parakeet", "horodatage": True, "corrections": "", "dossier": ""}


def lire() -> dict:
    try:
        with open(_FICHIER, encoding="utf-8") as f:
            return {**DEFAUT, **json.load(f)}
    except Exception:
        return dict(DEFAUT)


def ecrire(r: dict) -> None:
    with open(_FICHIER, "w", encoding="utf-8") as f:
        json.dump(r, f, ensure_ascii=False, indent=2)
