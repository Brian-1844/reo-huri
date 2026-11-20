"""Reo Huri pour ordinateur : vidéo ou lien YouTube -> texte anglais à enregistrer."""
from __future__ import annotations

import os
import queue
import re
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from . import exports, maj, modeles, reglages, texte, version, youtube

LAGON, LAGON_FONCE, CORAIL, SABLE, ENCRE, GRIS = "#0E7C86", "#095760", "#F07B5A", "#FAF6EF", "#1E2A2E", "#6B7A7E"
POLICE = "Segoe UI" if sys.platform == "win32" else "DejaVu Sans"


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("Reo Huri")
        self.geometry("980x720")
        self.minsize(760, 560)
        self.configure(bg=SABLE)
        try:
            self.iconbitmap(os.path.join(os.path.dirname(os.path.abspath(__file__)), "reohuri.ico"))
        except Exception:
            pass
        self.reg = reglages.lire()
        self.file: queue.Queue = queue.Queue()
        self.travail: threading.Thread | None = None
        self.arret = threading.Event()
        self.fichier = ""
        self.titre_doc = "Transcription"
        self.source_doc = ""
        self.sortie_maj: maj.Sortie | None = None
        self._style()
        self._interface()
        self.after(80, self._pomper)
        self.after(600, lambda: self._verifier_maj(manuel=False))
        self.protocol("WM_DELETE_WINDOW", self._fermer)

    # ------------------------------------------------------------------ interface

    def _style(self) -> None:
        s = ttk.Style(self)
        try:
            s.theme_use("clam")
        except tk.TclError:
            pass
        s.configure(".", font=(POLICE, 10), background=SABLE, foreground=ENCRE)
        s.configure("TFrame", background=SABLE)
        s.configure("TLabel", background=SABLE, foreground=ENCRE)
        s.configure("Gris.TLabel", foreground=GRIS)
        s.configure("TCheckbutton", background=SABLE)
        s.configure("TButton", padding=(12, 7))
        s.configure("Action.TButton", font=(POLICE, 12, "bold"), padding=(16, 11),
                    background=CORAIL, foreground="white", borderwidth=0)
        s.map("Action.TButton", background=[("disabled", "#E6C3B8"), ("active", "#E0694A")])
        s.configure("Lagon.Horizontal.TProgressbar", troughcolor="#E3DCCF", background=LAGON,
                    bordercolor="#E3DCCF", lightcolor=LAGON, darkcolor=LAGON)
        s.configure("Maj.Horizontal.TProgressbar", troughcolor=LAGON_FONCE, background=CORAIL,
                    bordercolor=LAGON_FONCE, lightcolor=CORAIL, darkcolor=CORAIL)

    def _interface(self) -> None:
        # Bandeau : titre, version, mise à jour avec sa barre
        tete = tk.Frame(self, bg=LAGON)
        tete.pack(fill="x")
        self.tete = tete
        gauche = tk.Frame(tete, bg=LAGON)
        gauche.pack(side="left", padx=18, pady=10)
        tk.Label(gauche, text="Reo Huri", bg=LAGON, fg="white", font=(POLICE, 18, "bold")).pack(anchor="w")
        tk.Label(gauche, text=f"Vidéo anglaise → texte anglais exact · v{version.VERSION_NAME}",
                 bg=LAGON, fg="#D5EEF0", font=(POLICE, 9)).pack(anchor="w")
        droite = tk.Frame(tete, bg=LAGON)
        droite.pack(side="right", padx=14)
        self.btn_maj = tk.Button(droite, text="⟳  Mise à jour", command=lambda: self._verifier_maj(True),
                                 bg=LAGON_FONCE, fg="white", activebackground=CORAIL, activeforeground="white",
                                 relief="flat", bd=0, padx=14, pady=7, font=(POLICE, 10, "bold"), cursor="hand2")
        self.btn_maj.pack(anchor="e")
        self.txt_maj = tk.Label(droite, text="", bg=LAGON, fg="#D5EEF0", font=(POLICE, 8))
        self.txt_maj.pack(anchor="e")
        self.barre_maj = ttk.Progressbar(self, style="Maj.Horizontal.TProgressbar", maximum=100)

        corps = ttk.Frame(self, padding=(18, 14, 18, 12))
        corps.pack(fill="both", expand=True)

        # Source
        ttk.Label(corps, text="1 · Choisir la source", font=(POLICE, 11, "bold")).pack(anchor="w")
        ligne = ttk.Frame(corps)
        ligne.pack(fill="x", pady=(6, 0))
        self.url = tk.StringVar()
        champ = ttk.Entry(ligne, textvariable=self.url, font=(POLICE, 11))
        champ.pack(side="left", fill="x", expand=True, ipady=4)
        self.url.trace_add("write", lambda *_: self._source_changee())
        ttk.Button(ligne, text="Coller le lien", command=self._coller).pack(side="left", padx=(8, 0))
        ttk.Button(ligne, text="Choisir un fichier…", command=self._choisir).pack(side="left", padx=(8, 0))
        self.txt_source = ttk.Label(corps, text="Collez un lien YouTube, ou choisissez un fichier vidéo ou audio.",
                                    style="Gris.TLabel")
        self.txt_source.pack(anchor="w", pady=(4, 0))

        opts = ttk.Frame(corps)
        opts.pack(fill="x", pady=(8, 0))
        ttk.Label(opts, text="Moteur pour les fichiers :").pack(side="left")
        self.moteur = ttk.Combobox(opts, state="readonly", width=48, values=[m.nom for m in modeles.MOTEURS])
        cles = [m.cle for m in modeles.MOTEURS]
        self.moteur.current(cles.index(self.reg["moteur"]) if self.reg["moteur"] in cles else 0)
        self.moteur.pack(side="left", padx=(8, 16))
        self.horo = tk.BooleanVar(value=bool(self.reg["horodatage"]))
        ttk.Checkbutton(opts, text="Horodatages", variable=self.horo).pack(side="left")
        ttk.Button(opts, text="Corrections…", command=self._corrections).pack(side="right")

        # Action
        self.btn_go = ttk.Button(corps, text="Obtenir le texte anglais", style="Action.TButton", command=self._lancer)
        self.btn_go.pack(fill="x", pady=(14, 6))
        self.barre = ttk.Progressbar(corps, style="Lagon.Horizontal.TProgressbar", maximum=100)
        self.barre.pack(fill="x")
        self.etat = ttk.Label(corps, text="", style="Gris.TLabel")
        self.etat.pack(anchor="w", pady=(4, 8))

        # Texte
        ttk.Label(corps, text="2 · Relire, corriger, enregistrer", font=(POLICE, 11, "bold")).pack(anchor="w")
        bas = ttk.Frame(corps)
        bas.pack(side="bottom", fill="x", pady=(10, 0))
        ttk.Button(bas, text="Enregistrer en Word (.docx)", command=lambda: self._enregistrer("docx")).pack(side="left")
        ttk.Button(bas, text="Texte (.txt)", command=lambda: self._enregistrer("txt")).pack(side="left", padx=8)
        ttk.Button(bas, text="Sous-titres (.srt)", command=lambda: self._enregistrer("srt")).pack(side="left")
        ttk.Button(bas, text="Copier", command=self._copier).pack(side="left", padx=8)
        ttk.Label(bas, text="Le fichier Word est prêt pour ton pack de traduction.",
                  style="Gris.TLabel").pack(side="right")

        cadre = tk.Frame(corps, bg="#E3DCCF", bd=1)
        cadre.pack(fill="both", expand=True, pady=(6, 0))
        self.zone = tk.Text(cadre, wrap="word", undo=True, font=("Georgia", 12), bg="white", fg=ENCRE,
                            relief="flat", padx=14, pady=12, spacing3=6, insertbackground=ENCRE)
        barre_d = ttk.Scrollbar(cadre, command=self.zone.yview)
        self.zone.configure(yscrollcommand=barre_d.set)
        barre_d.pack(side="right", fill="y")
        self.zone.pack(fill="both", expand=True)

    # ------------------------------------------------------------------ outils

    def _dire(self, *message) -> None:
        """Appelé depuis le fil de travail : transmet un message à l'interface."""
        self.file.put(message)

    def _pomper(self) -> None:
        try:
            while True:
                m = self.file.get_nowait()
                getattr(self, "_sur_" + m[0])(*m[1:])
        except queue.Empty:
            pass
        self.after(80, self._pomper)

    def _sur_etat(self, t: str) -> None:
        self.etat.configure(text=t)

    def _sur_progres(self, p: int) -> None:
        self.barre.stop()
        self.barre.configure(mode="determinate", value=p)

    def _sur_attente(self) -> None:
        self.barre.configure(mode="indeterminate")
        self.barre.start(12)

    def _sur_apercu(self, t: str) -> None:
        self.zone.insert("end", t + " ")
        self.zone.see("end")

    def _sur_texte(self, t: str) -> None:
        self.zone.delete("1.0", "end")
        self.zone.insert("1.0", t)
        self.zone.edit_reset()

    def _sur_fini(self, message: str) -> None:
        self.barre.stop()
        self.barre.configure(mode="determinate", value=0)
        self.btn_go.configure(text="Obtenir le texte anglais")
        self.etat.configure(text=message)
        self.travail = None

    def _sur_maj_etat(self, t: str) -> None:
        self.txt_maj.configure(text=t)

    def _sur_maj_progres(self, p: int) -> None:
        if not self.barre_maj.winfo_ismapped():
            self.barre_maj.pack(fill="x", after=self.tete)
        self.barre_maj.configure(value=p)

    def _sur_maj_trouvee(self, s, manuel: bool) -> None:
        self.sortie_maj = s
        if s is None:
            self.txt_maj.configure(text="Vérification impossible" if manuel else "")
        elif maj.plus_recente(s):
            self.btn_maj.configure(text=f"●  Installer {s.nom}", bg=CORAIL, command=self._installer_maj)
            self.txt_maj.configure(text="Nouvelle version disponible")
            if manuel:
                self._installer_maj()
        else:
            self.txt_maj.configure(text="Vous avez la dernière version" if manuel else "")

    def _sur_maj_prete(self, archive: str) -> None:
        try:
            maj.installer(archive)
        except Exception as ex:
            messagebox.showerror("Mise à jour", f"Installation impossible : {ex}\nLa page de téléchargement va s'ouvrir.")
            maj.ouvrir_page()
            return
        self.destroy()

    def _sur_maj_echec(self, message: str) -> None:
        self.barre_maj.pack_forget()
        self.txt_maj.configure(text="Échec du téléchargement")
        self.btn_maj.configure(state="normal")
        messagebox.showerror("Mise à jour", message)

    # ------------------------------------------------------------------ source

    def _coller(self) -> None:
        try:
            t = self.clipboard_get()
        except tk.TclError:
            t = ""
        v = youtube.id_video(t)
        if v:
            self.url.set(f"https://youtu.be/{v}")
        else:
            messagebox.showinfo("Reo Huri", "Copiez d'abord un lien YouTube.")

    def _source_changee(self) -> None:
        if youtube.id_video(self.url.get()):
            self.fichier = ""
            self.txt_source.configure(text="Lien YouTube prêt : l'app lira les sous-titres anglais de la vidéo.")

    def _choisir(self) -> None:
        f = filedialog.askopenfilename(
            title="Choisir une vidéo ou un fichier audio", initialdir=self.reg.get("dossier") or None,
            filetypes=[("Vidéo et audio", "*.mp4 *.mkv *.mov *.avi *.webm *.m4v *.mp3 *.m4a *.wav *.ogg *.opus *.flac *.aac *.wma"),
                       ("Tous les fichiers", "*.*")])
        if f:
            self.url.set("")
            self.fichier = f
            self.reg["dossier"] = os.path.dirname(f)
            self.txt_source.configure(text="Fichier : " + os.path.basename(f))

    # ------------------------------------------------------------------ travail

    def _lancer(self) -> None:
        if self.travail is not None:
            self.arret.set()
            self.etat.configure(text="Arrêt en cours…")
            return
        video = youtube.id_video(self.url.get())
        if not video and not self.fichier:
            messagebox.showinfo("Reo Huri", "Collez un lien YouTube ou choisissez un fichier.")
            return
        if self.zone.get("1.0", "end").strip() and not messagebox.askyesno(
                "Reo Huri", "Le texte affiché sera remplacé. Continuer ?"):
            return
        self.reg["moteur"] = modeles.MOTEURS[self.moteur.current()].cle
        self.reg["horodatage"] = self.horo.get()
        reglages.ecrire(self.reg)
        self.arret.clear()
        self.zone.delete("1.0", "end")
        self.btn_go.configure(text="Arrêter")
        cible = self._fil_youtube if video else self._fil_fichier
        arg = video if video else self.fichier
        self.travail = threading.Thread(target=self._proteger, args=(cible, arg), daemon=True)
        self.travail.start()

    def _proteger(self, cible, arg) -> None:
        try:
            cible(arg)
        except youtube.ErreurYouTube as ex:
            self._dire("fini", f"{ex}  Astuce : avec le fichier de la vidéo, « Choisir un fichier » marche toujours.")
        except Exception as ex:
            self._dire("fini", f"Erreur : {ex}")

    def _ponctuation(self):
        """Charge le modèle de ponctuation (téléchargé une seule fois, 30 Mo)."""
        from . import transcription
        if not modeles.installe(modeles.PONCTUATION):
            self._dire("etat", "Téléchargement du modèle de ponctuation (30 Mo, une seule fois)…")
            modeles.telecharger(modeles.PONCTUATION, lambda p: self._dire("progres", p))
        return transcription.creer_ponctuation()

    def _terminer(self, lignes, provenance: str) -> None:
        regles = texte.lire_corrections(self.reg.get("corrections", ""))
        mots = texte.lignes_vers_mots(lignes)
        modele = None
        if not texte.est_ponctue(mots):
            try:
                modele = self._ponctuation()
                self._dire("etat", "Ajout de la ponctuation…")
            except Exception as ex:
                provenance += f" ; ponctuation non ajoutée ({ex})"
        paras, ajoutee = texte.construire(lignes, modele, regles, lambda p: self._dire("progres", p))
        if ajoutee:
            provenance += " ; ponctuation et majuscules ajoutées par un modèle automatique"
        self.source_doc = (self.source_doc + " — " + provenance).strip(" —")
        self._dire("texte", texte.format_texte(paras, self.horo.get()))
        mots_n = sum(len(p.texte.split()) for p in paras)
        self._dire("fini", f"Terminé · {mots_n} mots · {provenance}. Relisez puis enregistrez.")

    def _fil_youtube(self, video: str) -> None:
        self._dire("attente")
        self._dire("etat", "Lecture des sous-titres YouTube…")
        t, chaine = youtube.titre(video)
        self.titre_doc = t or f"YouTube {video}"
        self.source_doc = f"Source : https://youtu.be/{video}" + (f" ({chaine})" if chaine else "")
        lignes, auto = youtube.sous_titres(video)
        self._terminer(lignes, "sous-titres automatiques de YouTube" if auto else "sous-titres de l'auteur de la vidéo")

    def _fil_fichier(self, fichier: str) -> None:
        from . import transcription
        m = modeles.MOTEURS[self.moteur.current()]
        if not modeles.installe(m):
            self._dire("etat", f"Téléchargement du moteur ({m.mo} Mo, une seule fois)…")
            modeles.telecharger(m, lambda p: self._dire("progres", p))
        modeles.telecharger_vad()
        self._dire("attente")
        self._dire("etat", "Mesure du volume et chargement du moteur…")
        self.titre_doc = os.path.splitext(os.path.basename(fichier))[0]
        self.source_doc = "Source : " + os.path.basename(fichier)
        import time
        t0 = time.time()

        def progres(p: int) -> None:
            ecoule = time.time() - t0
            reste = int(ecoule * (100 - p) / p) if p > 2 else -1
            self._dire("progres", p)
            self._dire("etat", f"Transcription… {p} %" + (f" · reste environ {reste // 60} min {reste % 60} s" if reste >= 0 else ""))

        lignes = transcription.transcrire(fichier, m, progres, self.arret.is_set, lambda t: self._dire("apercu", t))
        if not lignes:
            self._dire("fini", "Arrêté." if self.arret.is_set() else "Aucune parole détectée dans ce fichier.")
            return
        self._terminer(lignes, "transcription automatique " + m.nom.split(" (")[0].split("— ")[-1]
                       + (" (arrêtée avant la fin)" if self.arret.is_set() else ""))

    # ------------------------------------------------------------------ texte

    def _paras(self):
        return texte.analyser_texte(self.zone.get("1.0", "end"))

    def _nom_fichier(self) -> str:
        return re.sub(r'[\\/:*?"<>|]+', " ", self.titre_doc).strip()[:90] or "transcription"

    def _enregistrer(self, ext: str) -> None:
        paras = self._paras()
        if not paras:
            messagebox.showinfo("Reo Huri", "Il n'y a pas encore de texte à enregistrer.")
            return
        types = {"docx": ("Document Word", "*.docx"), "txt": ("Texte", "*.txt"), "srt": ("Sous-titres", "*.srt")}
        chemin = filedialog.asksaveasfilename(defaultextension="." + ext, filetypes=[types[ext]],
                                              initialfile=f"{self._nom_fichier()} (EN).{ext}",
                                              initialdir=self.reg.get("dossier") or None)
        if not chemin:
            return
        avec = "[" in self.zone.get("1.0", "1.1")       # le texte porte-t-il des horodatages ?
        try:
            if ext == "docx":
                exports.ecrire_docx(chemin, self.titre_doc, self.source_doc, paras, avec)
            elif ext == "txt":
                exports.ecrire_txt(chemin, self.titre_doc, self.source_doc, paras, avec)
            else:
                exports.ecrire_srt(chemin, paras)
        except OSError as ex:
            messagebox.showerror("Reo Huri", f"Impossible d'enregistrer : {ex}")
            return
        self.reg["dossier"] = os.path.dirname(chemin)
        reglages.ecrire(self.reg)
        self.etat.configure(text="Enregistré : " + chemin)

    def _copier(self) -> None:
        t = self.zone.get("1.0", "end").strip()
        if t:
            self.clipboard_clear()
            self.clipboard_append(t)
            self.etat.configure(text="Texte copié.")

    def _corrections(self) -> None:
        f = tk.Toplevel(self)
        f.title("Corrections automatiques")
        f.geometry("560x420")
        f.configure(bg=SABLE)
        f.transient(self)
        ttk.Label(f, wraplength=520, justify="left", padding=(14, 12, 14, 6),
                  text="Une correction par ligne, sous la forme :  mot entendu => mot juste\n"
                       "Exemple :  Parminder Bient => Parminder Biant\n"
                       "Elles sont appliquées à chaque nouveau texte (noms propres, termes mal reconnus).").pack(anchor="w")
        z = tk.Text(f, font=("Consolas" if sys.platform == "win32" else "DejaVu Sans Mono", 11), padx=10, pady=8)
        z.insert("1.0", self.reg.get("corrections", ""))
        z.pack(fill="both", expand=True, padx=14)

        def garder(appliquer: bool) -> None:
            self.reg["corrections"] = z.get("1.0", "end").strip()
            reglages.ecrire(self.reg)
            if appliquer:
                actuel = self.zone.get("1.0", "end").rstrip("\n")
                self._sur_texte(texte.appliquer_corrections(actuel, texte.lire_corrections(self.reg["corrections"])))
            f.destroy()

        b = ttk.Frame(f, padding=14)
        b.pack(fill="x")
        ttk.Button(b, text="Enregistrer", command=lambda: garder(False)).pack(side="right")
        ttk.Button(b, text="Enregistrer et appliquer au texte", command=lambda: garder(True)).pack(side="right", padx=8)

    # ------------------------------------------------------------------ mise à jour

    def _verifier_maj(self, manuel: bool) -> None:
        if manuel:
            self.txt_maj.configure(text="Recherche…")
        threading.Thread(target=lambda: self._dire("maj_trouvee", maj.derniere(), manuel), daemon=True).start()

    def _installer_maj(self) -> None:
        s = self.sortie_maj
        if s is None:
            return
        if not maj.installable() or not s.url:
            maj.ouvrir_page()
            return
        if self.travail is not None and not messagebox.askyesno(
                "Mise à jour", "Une transcription est en cours et sera perdue. Mettre à jour quand même ?"):
            return
        self.btn_maj.configure(state="disabled")
        self.txt_maj.configure(text="Téléchargement… 0 %")

        def fil() -> None:
            try:
                def p(v: int) -> None:
                    self._dire("maj_progres", v)
                    self._dire("maj_etat", f"Téléchargement… {v} %")
                archive = maj.telecharger(s, p)
                self._dire("maj_etat", "Installation…")
                self._dire("maj_prete", archive)
            except Exception as ex:
                self._dire("maj_echec", f"Téléchargement impossible : {ex}")

        threading.Thread(target=fil, daemon=True).start()

    def _fermer(self) -> None:
        self.reg["horodatage"] = self.horo.get()
        try:
            reglages.ecrire(self.reg)
        except OSError:
            pass
        self.destroy()


def main() -> None:
    App().mainloop()
