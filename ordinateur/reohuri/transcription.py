"""Reconnaissance vocale hors ligne d'un fichier audio ou vidéo (sherpa-onnx)."""
from __future__ import annotations

import os
import re
import subprocess
from typing import Callable

from . import modeles

_SANS_FENETRE = getattr(subprocess, "CREATE_NO_WINDOW", 0)
_BRUIT = re.compile(r"^[\[(].*[\])]$")


def _ffmpeg() -> str:
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def duree(fichier: str) -> float:
    """Durée en secondes (0 si inconnue)."""
    try:
        r = subprocess.run([_ffmpeg(), "-hide_banner", "-i", fichier], capture_output=True,
                           text=True, errors="replace", creationflags=_SANS_FENETRE)
        m = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", r.stderr)
        return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else 0.0
    except Exception:
        return 0.0


def gain_db(fichier: str) -> float:
    """Décibels à ajouter : 0 pour un volume normal, jusqu'à 40 pour un son très faible.

    Un enregistrement trop faible fait manquer des paroles au détecteur de
    parole ; on mesure donc le volume moyen (passage rapide) pour le remonter.
    """
    try:
        r = subprocess.run([_ffmpeg(), "-hide_banner", "-nostdin", "-i", fichier, "-vn",
                            "-af", "volumedetect", "-f", "null", "-"], capture_output=True,
                           text=True, errors="replace", creationflags=_SANS_FENETRE)
        m = re.search(r"mean_volume:\s*(-?[\d.]+) dB", r.stderr)
        c = re.search(r"max_volume:\s*(-?[\d.]+) dB", r.stderr)
        moyen = float(m.group(1)) if m else -20.0
        crete = float(c.group(1)) if c else 0.0
    except Exception:
        return 0.0
    return calcul_gain(moyen, crete)


def calcul_gain(moyen: float, crete: float) -> float:
    """Vise un volume moyen de -20 dB, sans écrêter les pointes de plus de 6 dB, 40 dB au plus."""
    if moyen >= -26.0:
        return 0.0
    return max(0.0, min(40.0, -20.0 - moyen, 6.0 - crete))


def creer_ponctuation() -> Callable[[str], str]:
    import sherpa_onnx
    m = modeles.PONCTUATION
    cfg = sherpa_onnx.OnlinePunctuationConfig(model_config=sherpa_onnx.OnlinePunctuationModelConfig(
        cnn_bilstm=modeles.chemin(m, "model.int8.onnx"), bpe_vocab=modeles.chemin(m, "bpe.vocab")))
    p = sherpa_onnx.OnlinePunctuation(cfg)
    return p.add_punctuation_with_case


def _reconnaisseur(m: modeles.Modele, fils: int):
    import sherpa_onnx
    c = lambda f: modeles.chemin(m, f)
    if m.cle == "parakeet":
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=c("encoder.int8.onnx"), decoder=c("decoder.int8.onnx"), joiner=c("joiner.int8.onnx"),
            tokens=c("tokens.txt"), model_type="nemo_transducer", num_threads=fils)
    prefixe = m.fichiers[0].split("-encoder")[0]
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=c(f"{prefixe}-encoder.int8.onnx"), decoder=c(f"{prefixe}-decoder.int8.onnx"),
        tokens=c(f"{prefixe}-tokens.txt"), language="en", task="transcribe", num_threads=fils)


def transcrire(fichier: str, m: modeles.Modele,
               progres: Callable[[int], None] | None = None,
               annule: Callable[[], bool] = lambda: False,
               sur_ligne: Callable[[str], None] | None = None) -> list[tuple[float, float, str]]:
    """Renvoie les passages reconnus [(début, fin, texte)]."""
    import numpy as np
    import sherpa_onnx

    fils = max(1, min(4, os.cpu_count() or 2))
    rec = _reconnaisseur(m, fils)
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = modeles.vad()
    cfg.silero_vad.threshold = 0.5
    cfg.silero_vad.min_silence_duration = 0.45
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = 22.0      # Whisper ne lit que des passages de moins de 30 s
    cfg.sample_rate = 16000
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)

    total = duree(fichier)
    gain = gain_db(fichier)
    filtre = ["-af", f"volume={gain:.1f}dB"] if gain > 0 else []
    proc = subprocess.Popen(
        [_ffmpeg(), "-nostdin", "-loglevel", "error", "-i", fichier, "-vn", *filtre,
         "-ac", "1", "-ar", "16000", "-f", "s16le", "-"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=_SANS_FENETRE)
    lignes: list[tuple[float, float, str]] = []
    lus = 0

    def vider():
        while not vad.empty():
            seg = vad.front
            echantillons = np.array(seg.samples, dtype=np.float32)
            debut = seg.start / 16000.0
            vad.pop()
            flux = rec.create_stream()
            flux.accept_waveform(16000, echantillons)
            rec.decode_stream(flux)
            texte = flux.result.text.strip()
            if texte and not _BRUIT.match(texte):
                lignes.append((debut, debut + len(echantillons) / 16000.0, texte))
                if sur_ligne:
                    sur_ligne(texte)

    try:
        fenetre = 512
        reste = np.zeros(0, dtype=np.float32)
        while not annule():
            brut = proc.stdout.read(16000 * 2 * 5)          # 5 secondes
            if not brut:
                break
            pcm = np.frombuffer(brut[:len(brut) // 2 * 2], dtype=np.int16).astype(np.float32) / 32768.0
            lus += len(pcm)
            reste = np.concatenate([reste, pcm])
            n = len(reste) // fenetre * fenetre
            for i in range(0, n, fenetre):
                vad.accept_waveform(reste[i:i + fenetre])
            reste = reste[n:]
            vider()
            if progres and total > 0:
                progres(min(99, int(lus / 16000.0 * 100 / total)))
        if not annule():
            vad.flush()
            vider()
    finally:
        proc.kill()
        erreur = (proc.stderr.read() or b"").decode("utf-8", "replace").strip()
        proc.wait()
    if lus == 0:
        raise RuntimeError("Impossible de lire le son de ce fichier." + (f" ({erreur[:200]})" if erreur else ""))
    if progres:
        progres(100)
    return lignes
