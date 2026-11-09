# PokéAPI · Ktor + PostgreSQL

API REST en Kotlin/[Ktor](https://ktor.io) qui s'appuie sur [PokéAPI](https://pokeapi.co/) et met les données en cache dans **PostgreSQL**.
Elle permet aussi de composer des **équipes** de 6 Pokémon maximum.

## Fonctionnement

```
client ──► Ktor ──► PostgreSQL (cache)
                └─► pokeapi.co   (si le Pokémon n'est pas encore en base)
```

- `GET /pokemon/{idOrName}` lit d'abord en base ; sinon il appelle `https://pokeapi.co/api/v2/pokemon/{idOrName}`, enregistre le résultat (Pokémon, types, stats, talents) et le renvoie.
- `POST /pokemon/import` importe une plage de Pokémon en masse (appels parallèles limités).
- Les équipes référencent les Pokémon en base ; un Pokémon inconnu est récupéré automatiquement depuis PokéAPI.

## Stack

| Rôle | Techno |
|---|---|
| Serveur HTTP | Ktor 3 (Netty), kotlinx.serialization |
| Client PokéAPI | Ktor Client (CIO) avec retry + timeouts |
| Base de données | PostgreSQL 16, Exposed (ORM), HikariCP |
| Tests | Ktor test host, MockEngine, H2 (mode PostgreSQL) |
| Build | Gradle 8 (wrapper), JDK 21 |

## Démarrage rapide

Tout en Docker (PostgreSQL + application) :

```bash
docker compose up --build
```

Ou bien PostgreSQL en Docker et l'application en local :

```bash
docker compose up -d db
```

```bash
./gradlew run
```

L'API écoute sur `http://localhost:8080`. Le PostgreSQL du compose est exposé sur le port **5433** de la machine (pour ne pas entrer en conflit avec un PostgreSQL déjà installé sur 5432).

Pour changer les identifiants ou les ports, copier `.env.example` en `.env` et l'adapter : la base et l'application utilisent les mêmes valeurs.

### Dépannage : `authentification par mot de passe échouée pour l'utilisateur « pokeapi »`

Les variables `POSTGRES_*` ne sont appliquées **qu'à la première initialisation** du volume. Si le volume a été créé avec un autre mot de passe, il faut le recréer (⚠️ supprime les données de la base) :

```bash
docker compose down -v
```

```bash
docker compose up --build
```

Si l'application est lancée avec `./gradlew run`, vérifier qu'elle pointe bien sur le PostgreSQL du compose (`localhost:5433`) et non sur un PostgreSQL local.

## Configuration

Variables d'environnement (valeurs par défaut dans `src/main/resources/application.yaml`) :

| Variable | Défaut |
|---|---|
| `PORT` | `8080` |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/pokeapi` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `pokeapi` / `pokeapi` |
| `DATABASE_POOL_SIZE` | `10` |
| `POKEAPI_BASE_URL` | `https://pokeapi.co/api/v2` |
| `POKEAPI_IMPORT_CONCURRENCY` | `8` |

Le schéma est créé automatiquement au démarrage (`SchemaUtils.create`).

## Endpoints

### Pokémon

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/pokemon?limit=20&offset=0&type=fire&name=char` | Liste paginée des Pokémon **en base**, filtres optionnels par type et par nom |
| `GET` | `/pokemon/{idOrName}` | Détail d'un Pokémon (récupéré sur PokéAPI si absent). `?refresh=true` force la mise à jour |
| `POST` | `/pokemon/import?limit=151&offset=0` | Importe une plage de Pokémon depuis PokéAPI (max 2000 par appel) |

### Équipes

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/teams` | Liste des équipes |
| `POST` | `/teams` | Crée une équipe : `{"name": "Kanto", "pokemon": ["pikachu", "1"]}` |
| `GET` | `/teams/{id}` | Détail d'une équipe |
| `PATCH` | `/teams/{id}` | Renomme : `{"name": "Nouveau nom"}` |
| `DELETE` | `/teams/{id}` | Supprime l'équipe |
| `POST` | `/teams/{id}/pokemon/{idOrName}` | Ajoute un Pokémon (6 max) |
| `DELETE` | `/teams/{id}/members/{position}` | Retire le Pokémon à la position donnée (1 à 6) |

### Divers

`GET /health` vérifie que l'application et la base répondent.

Les erreurs sont renvoyées au format `{"error": "..."}` : `400` requête invalide, `404` ressource introuvable, `502` PokéAPI indisponible.

## Exemples

```bash
curl -X POST "http://localhost:8080/pokemon/import?limit=151"
```

```bash
curl "http://localhost:8080/pokemon/pikachu"
```

```bash
curl "http://localhost:8080/pokemon?type=fire&limit=10"
```

```bash
curl -X POST http://localhost:8080/teams -H 'Content-Type: application/json' -d '{"name":"Starters","pokemon":["bulbasaur","charmander","squirtle"]}'
```

## Schéma de la base

- `pokemon` (id PokéAPI, nom, taille, poids, expérience, sprites, date de récupération)
- `pokemon_type`, `pokemon_stat`, `pokemon_ability` (liés à `pokemon`, suppression en cascade)
- `team`, `team_member` (position 1 à 6)

## Tests

```bash
./gradlew test
```

Les tests utilisent une base H2 en mémoire (mode PostgreSQL) et un `MockEngine` à la place de pokeapi.co : aucun accès réseau ni base externe nécessaire.

## Structure

```
src/main/kotlin/com/example/pokeapi/
├── Application.kt          # module Ktor + injection des dépendances
├── client/                 # client HTTP PokéAPI + DTOs
├── db/                     # connexion Hikari/Exposed + tables
├── model/                  # modèles exposés par l'API
├── repository/             # accès PostgreSQL
├── service/                # logique métier (cache, import, équipes)
├── routes/                 # routes HTTP
└── plugins/                # sérialisation, logs, gestion des erreurs
```
