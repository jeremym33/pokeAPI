# Déploiement sur Raspberry Pi

Tout le projet sur un Raspberry Pi (ou tout Linux 64 bits), avec Docker :

| Service | Rôle |
|---|---|
| `db` | PostgreSQL 16 : comptes, parties, Pokédex importé (volume `pokeapi-pi-pgdata`) |
| `app` | API Ktor (ce dépôt) |
| `web` | Application web (dépôt `pokeApp`) servie par **Caddy**, qui relaie aussi l'API |

Une seule adresse : l'application sur `/`, l'API sur `/api`. Pas de CORS à régler, et le HTTPS est automatique
avec un nom de domaine.

## Prérequis

- Raspberry Pi 4 ou 5, **2 Go de RAM au moins** (4 Go conseillés pour la première construction).
- **Raspberry Pi OS 64 bits** (Java 21 et PostgreSQL 16 n'existent pas en 32 bits pour Docker).
- Environ 4 Go d'espace libre.

## Installation

```bash
sudo apt install -y git
git clone -b claude/bold-lovelace-rdlawh https://github.com/jeremym33/pokeAPI.git ~/pokeAPI
~/pokeAPI/deploy/raspberry/installer.sh
```

`installer.sh` :

1. installe Docker s'il manque ;
2. clone l'application web (`pokeApp`, même branche) à côté : `~/pokeApp` ;
3. crée `.env` avec un mot de passe de base de données aléatoire ;
4. construit et démarre tout (**10 à 30 minutes la première fois** sur un Raspberry) ;
5. programme une sauvegarde quotidienne de la base à 3 h 17.

Il se relance sans risque. Si Docker vient d'être installé, déconnectez-vous puis reconnectez-vous ensuite pour
utiliser `docker` sans `sudo`.

Le projet est alors disponible sur `http://adresse-du-raspberry` (affichée à la fin ; sinon `hostname -I`).
Au premier lancement, importez le Pokédex depuis l'écran **Mise à jour** de l'application.

## Reprendre votre partie actuelle

Sur l'ordinateur où tourne la version actuelle (base du `docker-compose.yml` de développement, port 5433) :

```bash
docker compose exec -T db pg_dump -U pokeapi -d pokeapi > partie.sql
```

Copiez `partie.sql` sur le Raspberry (`scp partie.sql pi@adresse-du-raspberry:`), puis :

```bash
~/pokeAPI/deploy/raspberry/restaurer.sh ~/partie.sql
```

La base du Raspberry est remplacée (après une sauvegarde de son état) ; au redémarrage, l'API met les parties à
jour (compte rendu : `docker compose logs app | grep "Mise à jour des parties"`).

## Au quotidien

Les commandes se lancent depuis `~/pokeAPI/deploy/raspberry`.

| Pour… | Commande |
|---|---|
| Mettre à jour (sauvegarde, `git pull` des deux dépôts, reconstruction) | `./mettre-a-jour.sh` |
| Sauvegarder maintenant | `./sauvegarder.sh` |
| Restaurer une sauvegarde | `./restaurer.sh ~/sauvegardes-pokeapi/pokeapi-AAAA-MM-JJ-HHMM.sql.gz` |
| Voir l'état des services | `docker compose ps` |
| Lire les journaux de l'API | `docker compose logs -f app` |
| Arrêter / redémarrer | `docker compose stop` / `docker compose up -d` |

Les services redémarrent seuls avec le Raspberry. Les sauvegardes vont dans `~/sauvegardes-pokeapi`
(30 jours gardés : `BACKUP_KEEP_DAYS` dans `.env`) ; pensez à en copier de temps en temps ailleurs.

## Accès depuis l'extérieur

- **Pour vous seul (le plus simple et le plus sûr)** : [Tailscale](https://tailscale.com) sur le Raspberry et sur
  vos appareils, sans rien ouvrir sur la box :
  ```bash
  curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
  ```
  Le projet est alors sur `http://nom-du-raspberry` depuis vos appareils connectés.
- **Public, avec un nom de domaine** (DuckDNS gratuit, ou le vôtre) : dans `.env`, `SITE_ADDRESS=pokedex.mondomaine.fr`,
  redirigez les ports 80 et 443 de la box vers le Raspberry, puis `docker compose up -d`. Caddy obtient et renouvelle
  le certificat HTTPS tout seul.

## Réglages (`.env`)

| Variable | Rôle |
|---|---|
| `POSTGRES_PASSWORD` | Mot de passe de la base (généré par `installer.sh`) |
| `SITE_ADDRESS` | `:80` sur le réseau local, ou un nom de domaine pour le HTTPS |
| `HTTP_PORT`, `HTTPS_PORT` | Ports publiés (80 et 443) |
| `API_PORT` | Port de l'API, accessible seulement depuis le Raspberry (`curl http://127.0.0.1:8080/health`) |
| `POKEAPP_DIR` | Dépôt de l'application web, relatif à ce dossier |
| `JAVA_OPTIONS` | Mémoire de Java (`-XX:MaxRAMPercentage=40` par défaut) |
| `BACKUP_DIR`, `BACKUP_KEEP_DAYS` | Dossier et durée de conservation des sauvegardes |

Après un changement : `docker compose up -d`.

## En cas de souci

- **La construction s'arrête faute de mémoire** (Raspberry de 2 Go) : ajoutez de l'espace d'échange,
  `sudo dphys-swapfile swapoff && sudo sed -i 's/^CONF_SWAPSIZE=.*/CONF_SWAPSIZE=2048/' /etc/dphys-swapfile && sudo dphys-swapfile setup && sudo dphys-swapfile swapon`,
  puis relancez `./installer.sh`.
- **La page s'affiche mais rien ne charge** : `docker compose logs app` (l'API démarre en une minute environ).
- **Swagger** (documentation de l'API) : depuis votre ordinateur, `ssh -L 8080:127.0.0.1:8080 pi@adresse-du-raspberry`,
  puis `http://localhost:8080/swagger`.
