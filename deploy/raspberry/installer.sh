#!/usr/bin/env bash
# Installe le projet sur un Raspberry Pi (Raspberry Pi OS 64 bits) ou tout Linux avec Docker :
#   1. Docker, s'il manque ;
#   2. le dépôt de l'application web (pokeApp), cloné à côté de pokeAPI ;
#   3. le fichier .env, avec un mot de passe de base de données généré ;
#   4. la construction et le démarrage (base, API, application web, Caddy) ;
#   5. une sauvegarde quotidienne de la base (crontab).
# Relançable sans risque : ce qui est déjà en place est gardé.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
API_REPO="$(cd "$HERE/../.." && pwd)"
cd "$HERE"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

arch="$(uname -m)"
if [[ "$arch" != "aarch64" && "$arch" != "x86_64" ]]; then
  echo "Attention : architecture $arch. Il faut un système 64 bits (Raspberry Pi OS 64 bits sur un Pi 4 ou 5)." >&2
  exit 1
fi

# 1. Docker
if ! command -v docker >/dev/null 2>&1; then
  say "Installation de Docker"
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER" || true
fi
DOCKER=(docker)
if ! docker info >/dev/null 2>&1; then DOCKER=(sudo docker); fi

# 2. Application web, à côté de pokeAPI, sur la même branche
if [[ -f .env ]]; then set -a; . ./.env; set +a; fi
POKEAPP_PATH="$(cd "$HERE" && realpath -m "${POKEAPP_DIR:-../../../pokeApp}")"
if [[ ! -d "$POKEAPP_PATH/.git" ]]; then
  branch="$(git -C "$API_REPO" rev-parse --abbrev-ref HEAD)"
  remote="$(git -C "$API_REPO" remote get-url origin | sed -E 's#pokeAPI(\.git)?$#pokeApp.git#')"
  say "Récupération de l'application web ($remote, branche $branch)"
  git clone -b "$branch" "$remote" "$POKEAPP_PATH" || git clone "$remote" "$POKEAPP_PATH"
fi

# 3. Réglages
if [[ ! -f .env ]]; then
  say "Création de .env (mot de passe de la base généré)"
  password="$(tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32)"
  sed -e "s#^POSTGRES_PASSWORD=.*#POSTGRES_PASSWORD=$password#" \
      -e "s#^BACKUP_DIR=.*#BACKUP_DIR=$HOME/sauvegardes-pokeapi#" .env.example > .env
  chmod 600 .env
fi

# 4. Construction et démarrage (la première fois : 10 à 30 minutes sur un Raspberry)
say "Construction et démarrage (patience la première fois)"
"${DOCKER[@]}" compose up -d --build

# 5. Sauvegarde quotidienne à 3 h 17
if command -v crontab >/dev/null 2>&1 && ! crontab -l 2>/dev/null | grep -q "deploy/raspberry/sauvegarder.sh"; then
  say "Sauvegarde quotidienne de la base (3 h 17)"
  (crontab -l 2>/dev/null; echo "17 3 * * * $HERE/sauvegarder.sh >> $HERE/sauvegardes.log 2>&1") | crontab -
fi

say "Vérification"
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${API_PORT:-8080}/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS "http://127.0.0.1:${API_PORT:-8080}/health" && echo || echo "L'API ne répond pas encore : voir « docker compose logs app »."

ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
set -a; . ./.env; set +a
if [[ "${SITE_ADDRESS:-:80}" == :* ]]; then
  port=""
  if [[ "${HTTP_PORT:-80}" != 80 ]]; then port=":$HTTP_PORT"; fi
  echo "Le projet est disponible sur http://${ip:-adresse-du-raspberry}$port"
else
  echo "Le projet est disponible sur https://$SITE_ADDRESS"
fi
echo "Au premier lancement, importez le Pokédex depuis l'écran « Mise à jour » de l'application."
