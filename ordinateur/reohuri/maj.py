"""Mise à jour depuis les « Releases » GitHub du dépôt."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import urllib.request
import webbrowser
import zipfile
from dataclasses import dataclass
from typing import Callable

from . import version
from .reglages import dossier_app, dossier_donnees

API = f"https://api.github.com/repos/{version.DEPOT}/releases/latest"
PAGE = f"https://github.com/{version.DEPOT}/releases/latest"


@dataclass
class Sortie:
    code: int
    nom: str
    url: str


def derniere() -> Sortie | None:
    """Dernière version publiée, ou None si la vérification est impossible."""
    try:
        req = urllib.request.Request(API, headers={"Accept": "application/vnd.github+json",
                                                   "User-Agent": "ReoHuri"})
        with urllib.request.urlopen(req, timeout=15) as r:
            d = json.load(r)
        nom = d.get("tag_name", "")
        code = int("".join(c for c in nom.rsplit(".", 1)[-1] if c.isdigit()) or 0)
        for a in d.get("assets", []):
            if a.get("name", "").lower().endswith("windows.zip"):
                return Sortie(code, nom, a["browser_download_url"])
        return Sortie(code, nom, "")
    except Exception:
        return None


def plus_recente(s: Sortie) -> bool:
    return s.code > version.VERSION_CODE


def telecharger(s: Sortie, progres: Callable[[int], None]) -> str:
    d = os.path.join(dossier_donnees(), "maj")
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    cible = os.path.join(d, "ReoHuri-Windows.zip")
    req = urllib.request.Request(s.url, headers={"User-Agent": "ReoHuri"})
    with urllib.request.urlopen(req, timeout=60) as r, open(cible, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        lu = 0
        while True:
            bloc = r.read(1 << 16)
            if not bloc:
                break
            f.write(bloc)
            lu += len(bloc)
            if total:
                progres(min(100, lu * 100 // total))
    return cible


def installable() -> bool:
    return sys.platform == "win32" and getattr(sys, "frozen", False)


def installer(archive: str) -> None:
    """Décompresse la nouvelle version, puis lance un petit script qui remplace
    les fichiers une fois le programme fermé et le relance. Le programme doit
    se fermer juste après cet appel."""
    d = os.path.dirname(archive)
    neuf = os.path.join(d, "neuf")
    with zipfile.ZipFile(archive) as z:
        z.extractall(neuf)
    interne = os.path.join(neuf, "ReoHuri")
    source = interne if os.path.isdir(interne) else neuf
    app = dossier_app()
    script = os.path.join(d, "maj.bat")
    with open(script, "w", encoding="mbcs", errors="replace") as f:
        f.write("@echo off\r\n"
                "ping -n 4 127.0.0.1 >nul\r\n"
                f'robocopy "{source}" "{app}" /E /R:10 /W:1 /NFL /NDL /NJH /NJS >nul\r\n'
                f'start "" "{os.path.join(app, "ReoHuri.exe")}"\r\n')
    subprocess.Popen(["cmd", "/c", script], cwd=d,
                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
                     | getattr(subprocess, "DETACHED_PROCESS", 0))


def ouvrir_page() -> None:
    webbrowser.open(PAGE)
