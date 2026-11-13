#!/usr/bin/env bash
# Sauvegarde la base (comptes, parties, Pokédex importé) dans BACKUP_DIR, compressée ; garde BACKUP_KEEP_DAYS jours.
# Restauration : ./restaurer.sh <fichier>
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
set -a; . ./.env; set +a
DOCKER=(docker)
if ! docker info >/dev/null 2>&1; then DOCKER=(sudo docker); fi

dir="${BACKUP_DIR:-$HOME/sauvegardes-pokeapi}"
mkdir -p "$dir"
file="$dir/pokeapi-$(date +%F-%H%M).sql.gz"
# --clean --if-exists : le fichier remplace le contenu de la base à la restauration
"${DOCKER[@]}" compose exec -T db pg_dump -U pokeapi -d pokeapi --clean --if-exists | gzip > "$file.tmp"
mv "$file.tmp" "$file"
find "$dir" -name 'pokeapi-*.sql.gz' -mtime +"${BACKUP_KEEP_DAYS:-30}" -delete
echo "Sauvegarde : $file ($(du -h "$file" | cut -f1))"
