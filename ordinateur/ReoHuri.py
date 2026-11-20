"""Point d'entrée de Reo Huri pour ordinateur."""
import sys


def autotest(rapport: str) -> int:
    """Vérifie que le programme construit contient tout ce qu'il faut (utilisé par GitHub Actions)."""
    try:
        import tkinter  # noqa: F401
        import numpy  # noqa: F401
        import sherpa_onnx
        import youtube_transcript_api  # noqa: F401
        from reohuri import modeles, texte, transcription
        import os
        ffmpeg = transcription._ffmpeg()
        assert os.path.exists(ffmpeg), "ffmpeg absent"
        if not modeles.installe(modeles.PONCTUATION):
            modeles.telecharger(modeles.PONCTUATION)
        paras, ajoutee = texte.construire(
            [(0.0, 4.0, "so today we are going to look at daniel chapter two"),
             (4.0, 8.0, "what does the king see he sees an image")], transcription.creer_ponctuation())
        assert ajoutee and paras, "ponctuation en échec"
        message = f"OK sherpa-onnx {sherpa_onnx.__version__} | {paras[0].texte}"
        code = 0
    except Exception as ex:  # noqa: BLE001
        message, code = f"ECHEC {type(ex).__name__}: {ex}", 1
    with open(rapport, "w", encoding="utf-8") as f:
        f.write(message + "\n")
    return code


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--autotest":
        sys.exit(autotest(sys.argv[2]))
    from reohuri.app import main
    main()
