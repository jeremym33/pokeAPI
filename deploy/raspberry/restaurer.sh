#!/usr/bin/env bash
# Restaure une sauvegarde (fichier .sql ou .sql.gz) : remplace tout le contenu de la base.
# Sert aussi à reprendre une partie existante : sauvegarde faite sur un autre ordinateur avec
#   pg_dump -U pokeapi -d pokeapi --clean --if-exists > partie.sql
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
file="${1:-}"
if [[ -z "$file" || ! -f "$file" ]]; then
  echo "Usage : $0 <sauvegarde.sql ou .sql.gz>" >&2
  exit 1
fi
file="$(realpath "$file")"
if [[ ! -s "$file" ]]; then
  echo "$file est vide : rien à restaurer." >&2
  exit 1
fi
cd "$HERE"
DOCKER=(docker)
if ! docker info >/dev/null 2>&1; then DOCKER=(sudo docker); fi

read -r -p "Remplacer tout le contenu de la base par $file ? (oui/non) " answer
[[ "$answer" == "oui" ]] || { echo "Annulé."; exit 1; }

echo "==> Sauvegarde de l'état actuel, au cas où"
"$HERE/sauvegarder.sh"

echo "==> Restauration (API arrêtée pendant l'opération)"
"${DOCKER[@]}" compose stop app
# Base vidée d'abord : accepte aussi une sauvegarde faite sans --clean
"${DOCKER[@]}" compose exec -T db psql -q -v ON_ERROR_STOP=1 -U pokeapi -d pokeapi -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
if [[ "$file" == *.gz ]]; then gunzip -c "$file"; else cat "$file"; fi \
  | "${DOCKER[@]}" compose exec -T db psql -q -v ON_ERROR_STOP=1 -U pokeapi -d pokeapi
"${DOCKER[@]}" compose start app
echo "Restauration terminée. Les parties sont mises à jour au démarrage de l'API."
