# Reo Huri

Une vidéo en anglais (fichier ou lien YouTube) devient un **texte anglais aussi exact que possible**, à relire, corriger et enregistrer. La traduction en français se fait ensuite à part, avec ton pack de protocole : le fichier Word produit est au format que `pipeline.py` attend (un horodatage seul sur sa ligne, puis le paragraphe).

Deux versions, construites ensemble par GitHub :

| Version | Fichier dans « Releases » | Dossier du code |
|---|---|---|
| Téléphone Android | `ReoHuri.apk` | `app/` |
| Ordinateur Windows | `ReoHuri-Windows.zip` | `ordinateur/` |

Aucune clé API, aucun compte : tout le travail se fait sur l'appareil.

## Ce que font les deux versions

- **Lien YouTube** : lit les sous-titres anglais de la vidéo (ceux de l'auteur en priorité, sinon les automatiques). La vidéo n'est pas téléchargée. Si les sous-titres n'ont pas de ponctuation, un petit modèle local l'ajoute, avec les majuscules ; les mots ne sont pas changés.
- **Fichier vidéo ou audio** : reconnaissance vocale hors ligne. Trois moteurs : Parakeet (le plus précis, 482 Mo, environ 1 Go de mémoire), Whisper base (199 Mo), Whisper tiny (113 Mo).
- **Relecture** : le texte est modifiable avant l'enregistrement. Les paragraphes commencent par `[hh:mm:ss]` (case « Horodatages »).
- **Corrections** : une liste `mot entendu => mot juste`, appliquée à chaque nouveau texte (noms propres, termes mal reconnus).
- **Enregistrement** : Word (.docx), texte (.txt), sous-titres (.srt). La ligne « Source » du document dit d'où vient le texte et si la ponctuation a été ajoutée automatiquement.
- **Mise à jour** : bouton en haut à droite, avec une barre de progression. Un point orange (téléphone) ou un bouton orange (ordinateur) signale une nouvelle version.

Pour la meilleure exactitude : utiliser le **fichier** de la vidéo avec le moteur Parakeet. Les sous-titres automatiques de YouTube sont moins exacts qu'une transcription Parakeet.

## Construire sur GitHub

1. Créer un dépôt **public** (par exemple `Brian-1844/reo-huri`). Public, pour que le bouton de mise à jour puisse lire les versions.
2. Envoyer tout le contenu de ce dossier, y compris `.github/` et `app/keystore/`.
3. Chaque envoi sur la branche `main` lance « Construire Reo Huri » (onglet Actions) puis publie les deux fichiers dans **Releases**.
4. Téléphone : télécharger `ReoHuri.apk` et l'installer. Ordinateur : télécharger `ReoHuri-Windows.zip`, le décompresser dans un dossier à toi (Documents, Bureau…), lancer `ReoHuri.exe`.

Les versions suivantes s'installent avec le bouton de mise à jour. L'app cherche ses mises à jour dans le dépôt qui l'a construite : aucun nom de dépôt à modifier.

## Premier usage (Internet nécessaire une fois par modèle)

- Moteur choisi : téléchargé à la première transcription d'un fichier.
- Modèle de ponctuation (30 Mo) : téléchargé la première fois que des sous-titres sans ponctuation arrivent.

Ensuite les fichiers se traitent hors ligne. Le lien YouTube demande toujours Internet.

## À savoir

- La clé de signature `app/keystore/release.jks` doit rester la même : elle permet d'installer chaque nouvelle version Android par-dessus l'ancienne.
- Sur Windows, garde le dossier `ReoHuri` hors de « Program Files », sinon la mise à jour ne peut pas remplacer les fichiers. Au premier lancement, Windows peut afficher « Windows a protégé votre ordinateur » : choisir « Informations complémentaires », puis « Exécuter quand même ».
- YouTube n'offre pas d'accès officiel aux sous-titres : si le lien cesse de marcher, il faudra mettre l'app à jour. Le fichier de la vidéo marche toujours.
- Le workflow vérifie le logiciel Windows construit (`--autotest`) avant de le publier.
