# Ma ligne TaM

Petite app Android pour suivre **ses arrêts** du réseau TaM (Montpellier) :

- choix de la **ligne**, du **sens** puis de l'**arrêt** (tram, bus urbains et suburbains) ;
- **prochains passages en temps réel**, avec le retard éventuel ;
- **état du trafic** : perturbations sur la ligne, sur l'arrêt ou sur tout le réseau (grèves…), y compris celles annoncées pour les jours suivants ;
- **notifications automatiques** dès qu'une nouvelle perturbation touche un arrêt suivi (vérification toutes les 15 min environ).

## Installer

1. Depuis ton téléphone : https://github.com/StephaneInst/check-ma-ligne-tam/releases/latest/download/MaLigneTaM.apk
   (ou, pour les mises à jour automatiques, ajoute l'adresse du dépôt dans **Obtainium**).
2. Ouvre le fichier ; Android demande d'autoriser l'installation depuis le navigateur : accepte.
3. Au premier lancement, autorise les notifications.

Chaque modification poussée sur `main` recompile l'APK automatiquement (onglet **Actions**). La signature est fixe : les nouvelles versions s'installent par-dessus l'ancienne.

## Utilisation

- **+ Ajouter un arrêt** : ligne → sens → arrêt (un champ de recherche filtre les listes).
- **Appui long** sur une carte pour la retirer.
- Menu ⋮ : activer/désactiver les notifications, mettre à jour la liste des lignes, ouvrir tam-voyages.com.

## Données

Open data de Montpellier Méditerranée Métropole / TaM, licence ODbL :
`https://data.montpellier3m.fr/GTFS/{Urbain|Suburbain}/` → `GTFS.zip` (lignes, arrêts), `Alert.pb` (perturbations), `TripUpdate.pb` (temps réel).
Si ces adresses changent, elles se modifient dans `app/src/main/java/fr/perso/tamligne/Gtfs.kt` (`enum class Network`).

## Pour économiser la batterie

Certains téléphones (Xiaomi, Huawei, Samsung…) bloquent les tâches de fond. Si les notifications n'arrivent pas, mets l'app en « Aucune restriction » dans les réglages de batterie.
