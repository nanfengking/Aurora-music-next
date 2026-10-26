# Aurora Music Android — guide de l’aperçu

[简体中文](../../README.android.md) · [繁體中文](guide.zh-TW.md) · [English](guide.en.md) · [Français](guide.fr.md)

Version 1.5.0-preview.12, versionCode 18. Cibles : Xiaomi 14 Ultra et Xiaomi Pad 8 Pro. Cet aperçu nécessite encore des essais sur appareil pour certains fichiers M4A, serveurs WebDAV et usages prolongés écran éteint. Installez-le par-dessus l’aperçu précédent sans désinstaller. [Notes de version](../releases/1.5.0-preview.12.md).

Les préférences sont enregistrées dans l’ordre de validation. La langue et le thème natifs sont restaurés avant l’interface ; les connexions à froid et les widgets sont améliorés. La fermeture de l’interface termine ses appels en attente sans annuler les enregistrements acceptés ni la lecture native en arrière-plan.

<img src="../images/android/phone-listen.png" width="280" alt="Interface téléphone avec données fictives et interface chinoise">

![Disposition tablette avec données fictives](../images/android/tablet-listen.png)

Captures de navigateur avec appels natifs simulés, pas de captures Xiaomi. Choisissez Français dans Réglages → Langue.

## Compiler dans Android Studio

Installez Node.js 22, JDK 21, Android SDK Platform et Build Tools 36. À la racine contenant `package.json`, `mobile/`, `src/` et `android/` :

```sh
npm ci
npm run android:sync
```

Dans Android Studio, **Open → dossier android**. Choisissez JDK 21 pour Gradle, installez SDK 36 et attendez la synchronisation. Dans le terminal :

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Sous Windows, utilisez `.\gradlew.bat`. APK : `android/app/build/outputs/apk/debug/app-debug.apk`. Après une modification du TypeScript mobile/partagé, relancez `npm run android:sync` à la racine avant de compiler.

Autre possibilité : téléversez le code décompressé, y compris `.github/`, puis exécutez **Actions → Android debug APK**. Téléchargez et décompressez l’artefact `aurora-android-debug`. Un seul ZIP source ne déclenche pas la compilation. Ne publiez ni clés, bases privées, SDK/JDK ni caches.

## Écouter et voyager hors ligne

1. Réglages : saisissez l’URL HTTPS du dossier musical WebDAV et vos identifiants. Connectez-vous, puis indexez depuis Cloud. Les certificats autosignés sont refusés. Lecture et téléchargements acceptent cinq redirections HTTPS au maximum. Hors du dossier WebDAV d’origine, seules les cibles publiques sont permises ; Authorization/Cookie sont définitivement retirés, sans transmettre les identifiants WebDAV au CDN. HTTP, cibles privées et boucles sont bloqués. Les restrictions des dossiers et API de ressources ne changent pas.
2. Parcourez titres/albums/artistes/favoris, recherchez dans toute la bibliothèque et créez des playlists manuelles ou à règles. Les détails audio sont dans une boîte secondaire.
3. Avant le voyage, téléchargez en Wi-Fi, activez Hors ligne uniquement et testez sans réseau. Seuls les téléchargements complets sont hors ligne ; le cache de streaming n’est pas une garantie de fichier complet.
4. Réglages → Téléchargements : progression, pause/reprise/nouvel essai/annulation, par pages de 60. La pause conserve les fichiers partiels ; l’annulation ne nettoie que les fichiers locaux incomplets, pas la musique complète ou cloud. Reprise seulement avec ETag fort identique et plage vérifiée ; sinon redémarrage sûr. Aucun suivi périodique si la boîte est fermée ou l’application en arrière-plan. Wi-Fi concerne les nouvelles tâches. Le système planifie les essais, sans garantie d’immédiateté ; cinq erreurs consécutives sans progression nette arrêtent les essais automatiques. Les très gros transferts lents restent à valider sur appareil.
5. Dans HyperOS, autorisez l’arrière-plan, retirez les restrictions de batterie et, si nécessaire, verrouillez la tâche récente. Le service natif ne garantit pas de survivre à un arrêt forcé ou à toute fermeture système.
6. Appui long sur l’accueil → Widgets → Aurora : titre ou paroles, redimensionnables. Ajoutez-les manuellement si l’ajout direct n’est pas disponible. Les paroles changent par ligne lorsque l’écran est allumé.

## Personnalisation et lecture

<img src="../images/android/download-tasks.png" width="280" alt="Téléchargements avec progression fictive et appels natifs simulés">

Thèmes système/clair/sombre, couleurs du fond d’écran ou accent personnalisé, animations et visuels économes. Le mode économe désactive le grand fond flouté. Interface et widgets proposent quatre langues.

« Fond de pochette » peut désactiver séparément le calque flouté, sans masquer la pochette nette ni demander de nouvelle image. Les visuels économes et l’économie système restent prioritaires.

Taille des paroles : 18/24/30/36, en complément du réglage système. Les changements rapides sont enregistrés dans l’ordre. Désactiver « Mémoriser titre et position » efface le point de reprise, mais conserve file active, favoris, playlists et historique. Réactiver crée un nouveau point depuis la lecture actuelle, sans retrouver l’ancien.

Réglages affiche l’espace du cache de lecture et des pochettes. Vider le cache supprime les fragments inutilisés mais conserve les lectures actives ; changez de titre pour les libérer. La limite fixe de 128 Mio concerne le disque, pas la RAM. Les fichiers hors ligne ne sont pas recopiés dans ce cache. Le nettoyage conserve musique hors ligne, pochettes et données personnelles. Téléchargez les fichiers complets avant le voyage.

<img src="../images/android/storage-settings.png" width="280" alt="Stockage hors ligne et cache séparés, données fictives">

Touchez le titre en bas pour ouvrir le lecteur plein écran. Touchez une parole pour avancer à cet instant, ou Paroles pour revenir à la ligne courante. Autres actions contient détails, recherche et réglages ; Retour ferme une couche, annuler un choix conserve sa valeur. Le fond flouté exige Fond de pochette activé et Visuels économes désactivés ; l’économie système reste prioritaire, sans recherche d’image supplémentaire.

<img src="../images/android/phone-player.png" width="280" alt="Lecteur plein écran, titres fictifs et appels natifs simulés, pas une capture sur appareil">

Une nouvelle analyse peut déduire les informations de noms usuels comme `周杰伦-星晴.m4a` ou `Artist - Song.mp3`. Seuls les titres encore identiques au nom de fichier avec artiste vide sont corrigés. Tags, noms modifiés, favoris, paroles et téléchargements sont conservés, y compris les tags lus simultanément. Aucun album n’est inventé ; noms ambigus à plusieurs tirets ou anglais sans espaces inchangés. Il s’agit d’une déduction, pas de métadonnées audio vérifiées.

Ouvrez Autres actions → Réglages de lecture, ou Lecture et appareils audio dans Réglages. Choisissez répétition du titre/playlist, aléatoire sur toute la playlist, vitesse 0,5–2×, volume de l’application et minuterie, puis Enregistrer. La minuterie native met en pause à l’échéance mais ne survit pas à l’arrêt du processus. Ouvrir l’application ne lance pas automatiquement le son.

La normalisation par tags est désactivée par défaut. Activez-la puis Enregistrer : seuls les tags ReplayGain du titre et de crête peuvent atténuer le volume. Aucun boost, débruitage, réencodage ni analyse sonore ; sans tags, rien ne change, et le volume choisi reste la limite supérieure. Les tags MP3 TXXX, FLAC/Vorbis et les tags libres iTunes M4A sont pris en charge. Gain d’album, R128, gain d’en-tête Opus et Sound Check ne sont pas réappliqués. Les valeurs absentes, invalides ou hors limites sont ignorées, pas remplacées par 0 dB. Les tags sont visibles seulement dans les détails secondaires. Changer de titre réinitialise le facteur ; désactiver restaure le volume choisi. L’extraction sur fichiers réels et les transitions restent à vérifier sur appareil.

« Mettre le titre suivant en cache » est aussi désactivé par défaut : activez puis Enregistrer dans cette boîte. Après cinq secondes sans chargement courant, une tâche native tente le fichier suivant complet. Seuls les titres en ligne de taille connue, au plus 64 Mio, sont admissibles ; musique déjà hors ligne et téléchargements en attente/en cours sont ignorés. Le quota de fragments validés de 128 Mio est partagé avec la lecture. Aucun appel IA, copie hors ligne complète supplémentaire ou marquage « téléchargé ». Le cache peut être supprimé : téléchargez avant le voyage.

Fondu enchaîné : désactivé/1/2/3 secondes, puis Enregistrer. Un second lecteur natif n’existe qu’à l’approche de la fin ou au changement manuel. Après huit secondes sans préparation réussie, la transition reste normale. Répétition d’un titre, flux en direct, titres trop courts ou de durée inconnue ne se chevauchent pas automatiquement. Pause, déplacement, désactivation ou économie d’énergie terminent le fondu ; une sélection en préparation reste choisie sans démarrer. Les flux partagent focus et atténuation ; leurs gains linéaires demandés ne dépassent pas ensemble le volume choisi, sans garantie de mixage réel sans distorsion. Tampons bornés, mais décodeur supplémentaire temporaire, consommation accrue et données mobiles possibles ; ni décodage complet en RAM ni marquage hors ligne.

La règle Wi-Fi/réseau non facturé des téléchargements s’applique ; la désactiver peut consommer des données mobiles. Pause, changement de titre, chargement/mise en mémoire tampon courant, économie d’énergie système, changement de réseau ou désactivation annulent le transfert. Limite de 90 secondes par tentative, sans boucle d’échecs. Vider le cache suspend la prélecture pour le titre courant. Fragments de 2 Mio maximum, avec espace disque supplémentaire pour fragments non validés et index : ce n’est pas une limite de RAM. Les clés intègrent désormais taille/date de modification de la source ; les anciens fragments peuvent nécessiter une nouvelle lecture et restent évictables, sans changer les fichiers hors ligne complets. Réutilisation et annulation natives restent à tester sur appareil.

<img src="../images/android/playback-options.png" width="280" alt="Réglages secondaires de lecture, données fictives">

Les sorties visibles ne prouvent pas le chemin actif, le codec Bluetooth ou la fréquence réellement utilisée. Changez d’appareil dans le panneau média du système. Les métadonnées de la source ne garantissent pas la qualité de l’enregistrement ; aucun gain supplémentaire ni débruitage par défaut.

## Les titres sont visibles mais ne se lisent pas

Installez cette version par-dessus la précédente, sans désinstaller, effacer les données ou supprimer la bibliothèque. Essayez un MP3 puis un M4A. Touchez le titre en bas pour lire l’explication traduite et le code `AUDIO_…` / Media3. Appuyez une fois sur Lecture pour réessayer ; aucune interrogation continue en arrière-plan après échec.

- `AUDIO_HTTP_401` / `403` : téléchargement refusé. L’accès au dossier ne garantit pas les droits de téléchargement ; vérifiez le compte et le service WebDAV puis reconnectez-vous.
- `AUDIO_NETWORK` / `TIMEOUT` / `TLS` : réseau, délai dépassé ou certificat. La vérification des certificats reste active.
- `AUDIO_REDIRECT_…` / `NOT_AUDIO` / `RANGE` : redirection, page/erreur à la place de l’audio, ou plage d’octets invalide.
- `AUDIO_FORMAT` / `DECODER` : conteneur ou décodage de l’appareil, à distinguer d’une panne réseau.

Signalez uniquement code, format et appareil/version système. Filtrez Logcat sur `AuroraPlayback` ; ne partagez pas identifiants, liens signés ou réponses brutes. Les tests locaux n’utilisent pas votre serveur. Retestez lecture, déplacement, téléchargement et lecture sans réseau sur appareil.

## IA, paroles et confidentialité

Les recommandations locales ne nécessitent aucune API. Ajoutez URL, identifiant exact du modèle et clé, puis classez les services par priorité. Protocoles : Chat Completions, Responses, Anthropic et Gemini. Choisissez celui réellement pris en charge par le fournisseur, sans deviner l’identifiant du modèle.

La génération en langage naturel est locale par défaut. L’IA facultative reçoit consigne, résumé des préférences et métadonnées d’un nombre limité de candidats, pas l’audio, les URL WebDAV, l’historique brut ni les identifiants. Le repli peut contacter/facturer plusieurs services. Fermer la boîte ou passer en arrière-plan annule les requêtes restantes, pas les frais déjà engagés.

Les paroles suivent titre + artiste + album, puis titre + artiste, puis titre. À chaque niveau, vos API activées sont essayées par priorité avant LRCLIB. Réglages → Services de paroles et images accepte jusqu’à 10 API, avec type, priorité et clé Bearer facultative chiffrée. Ce sont des API JSON, pas des URL de modèles IA. Aucun test à l’enregistrement ; les clés enregistrées ne reviennent pas dans l’interface. Ressaisissez-les après changement d’URL.

Priorité aux pochettes intégrées, personnelles ou sources configurées. iTunes est désactivé par défaut et exige un accord séparé ; cela n’accorde aucun droit sur les images. Lisez [les conditions Apple](https://performance-partners.apple.com/search-api). Le fond par défaut est la pochette agrandie et floutée, sans requête supplémentaire ; une API de fond permet un choix manuel distinct.

Détails du titre → Rechercher, prévisualiser et choisir : modifiez les métadonnées, jusqu’à 8 résultats, puis cliquez pour charger un aperçu. Confirmez avant de remplacer la ressource ; les autres ressources, fichiers audio, playlists et historique restent inchangés. Les tags audio ne remplacent pas les pochettes choisies. Fermer ou passer en arrière-plan annule et nettoie les aperçus. Jetons valides 10 minutes : relancez la recherche après expiration. Réinitialiser retire seulement l’image locale choisie, pas la musique cloud.

<img src="../images/android/asset-preview.png" width="280" alt="Aperçu secondaire, données fictives originales et appels natifs simulés">
<img src="../images/android/asset-settings.png" width="280" alt="Priorités des API et iTunes désactivé par défaut, configuration fictive">

GET : `title`, `artist`, `album`, `kind`. Tableau `results`, champs `lyrics` ou `imageUrl`. HTTPS public uniquement, sans réseau privé, redirection ni paramètres dans l’URL configurée. Clé sur les images de même origine exacte uniquement, jamais sur un autre CDN. JSON : 2 Mio ; image : 8 Mio ; JPEG stocké avec côté maximal ≤768px. Aucun réencodage audio. Cache automatique total : 64 Mio / 400 images. Images choisies : quota séparé de 64 Mio / 400, sans éviction automatique ; remplacez ou réinitialisez si plein. Voir [le contrat API](../API_INTEGRATION.md).

Recherches avec accord : métadonnées et frais éventuels. Responses `store: false` ne garantit pas la non-conservation par une passerelle. Keystore protège les clés, pas tous les fichiers. Voir [Confidentialité](../../PRIVACY.md).

## Mise à jour et limites

Nouveau workflow manuel **Android signed APK**, avec contrôles de signature/manifeste. Vous configurez votre clé privée et son empreinte publique fixe ; aucune clé de publication n’a été créée ou téléversée pour vous. CI réel et conservation des données sur appareil restent à vérifier : [guide de signature](release-signing.md#français). Une nouvelle clé release ne remplace normalement pas une ancienne signature debug ; ne désinstallez pas d’abord.

Une mise à jour sans désinstallation exige le même identifiant d’application, le même certificat de signature et un versionCode approprié. Conservez la même clé privée de publication. Les signatures debug de différentes machines/CI peuvent varier. En cas de conflit, ne désinstallez pas et n’effacez pas les données automatiquement : retrouvez la clé initiale. L’export/import n’étant pas complet, une désinstallation peut perdre playlists, identifiants et musique hors ligne.

L’atténuation ReplayGain, le cache facultatif et le fondu natif restent à valider sur appareil. Builds à signature fixe configurés, mais clé de publication, CI réel/mises à jour et échange de données restent à terminer ; l’import d’images locales et de paroles annexes est un parcours futur supplémentaire. Android utilise Media3/les décodeurs système, pas FFmpeg Windows ; M4A/ISOM/MP42 ne garantissent pas la lecture AAC/ALAC.

Compilations, tests unitaires natifs et tests simulés interface/API ont réussi. Les tests d’intégration natifs sont seulement compilés. Lecture écran éteint, codecs, widgets, mémoire/batterie/température et grandes polices doivent être vérifiés sur les deux appareils. Voir [la liste complète](../ANDROID_REQUIREMENTS.md) et [les conditions de sécurité](../../SECURITY.md).
