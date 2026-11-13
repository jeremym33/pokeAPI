#!/usr/bin/env bash
# Met à jour le projet : sauvegarde de la base, récupération des deux dépôts, reconstruction et redémarrage.
# Les parties existantes sont mises à jour automatiquement au démarrage de l'API (compte rendu affiché à la fin).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
set -a; . ./.env; set +a
DOCKER=(docker)
if ! docker info >/dev/null 2>&1; then DOCKER=(sudo docker); fi

echo "==> Sauvegarde avant mise à jour"
"$HERE/sauvegarder.sh"

echo "==> Récupération des dépôts"
git -C "$HERE/../.." pull --ff-only
git -C "$(realpath -m "${POKEAPP_DIR:-../../../pokeApp}")" pull --ff-only

echo "==> Reconstruction et redémarrage"
"${DOCKER[@]}" compose up -d --build
"${DOCKER[@]}" image prune -f >/dev/null

echo "==> Mise à jour des parties"
for _ in $(seq 1 60); do
  if "${DOCKER[@]}" compose logs app 2>/dev/null | grep -q "Mise à jour des parties"; then break; fi
  sleep 2
done
"${DOCKER[@]}" compose logs --since 10m app | grep "Mise à jour des parties" || echo "(aucune partie enregistrée, ou API encore en démarrage)"
