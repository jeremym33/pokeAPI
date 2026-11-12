# PokéAPI · Ktor + PostgreSQL

API REST en Kotlin/[Ktor](https://ktor.io) qui s'appuie sur [PokéAPI](https://pokeapi.co/) et met les données en cache dans **PostgreSQL**.
Elle permet aussi de composer des **équipes** de 6 Pokémon maximum et de jouer à un **jeu d'aventure**, propres à chaque **compte utilisateur**.

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
| `CORS_ALLOWED_HOSTS` | `*` (toutes origines ; en production : `pokedex.example.com,localhost:8081`) |

Le schéma est créé automatiquement au démarrage ; les colonnes ajoutées par une nouvelle version sont créées sur une base existante. Après une mise à jour, relancez `POST /pokemon/import` : les Pokémon importés avec une ancienne version sont complétés (région, formes régionales…).

## Swagger

Une interface Swagger UI permet de tester tous les endpoints depuis le navigateur :
**http://localhost:8080/swagger** (la racine `/` y redirige).

La spécification OpenAPI se trouve dans `src/main/resources/openapi/documentation.yaml` ; elle est maintenue à la main, pensez à la mettre à jour quand vous ajoutez une route.

## Endpoints

### Comptes

L'aventure et les équipes demandent un compte ; le Pokédex, les régions et la mise à jour restent publics.

| Méthode | Route | Description |
|---|---|---|
| `POST` | `/auth/register` | Crée un compte : `{"username": "sacha", "password": "pikachu123"}` (nom de 3 à 20 caractères, unique sans tenir compte de la casse ; mot de passe d'au moins 8 caractères). Renvoie un jeton de session |
| `POST` | `/auth/login` | Se connecte, renvoie un jeton de session (valable 30 jours) |
| `POST` | `/auth/logout` | Révoque le jeton |
| `GET` | `/auth/me` | Compte connecté |

Le jeton s'envoie dans l'en-tête `Authorization: Bearer <jeton>` (bouton « Authorize » dans Swagger). Les mots de passe sont hachés (PBKDF2-SHA256, sel aléatoire) et seul le hachage SHA-256 des jetons est enregistré. Le **premier compte créé** reprend la partie et les équipes qui existaient avant l'ajout des comptes.

### Pokémon

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/pokemon?type=fire,flying&region=kanto&name=sala&sort=statTotal&order=desc` | Pokédex : liste paginée des Pokémon **en base** (filtres : 1 ou 2 types, région, génération, nom FR/EN ; tri : `id`, `name`, `nameFr`, `height`, `weight`, `baseExperience`, `statTotal`) |
| `POST` | `/pokedex/update?mode=missing` | Met à jour tout le Pokédex en arrière-plan (`missing` : ajoute ce qui manque, `all` : récupère tout à nouveau) |
| `GET` | `/pokedex/update` | Avancement de la mise à jour (traités / total, importés, formes régionales, échecs) |
| `DELETE` | `/pokedex/update` | Arrête la mise à jour en cours |
| `GET` | `/regions/{name}/locations` | Lieux d'une région (villes, routes, grottes…) avec leur nom français |
| `GET` | `/locations/{name}` | Pokémon rencontrés dans un lieu, zone par zone et jeu par jeu (méthode, niveaux, chance) |
| `GET`, `POST`… | `/game/…` | Jeu d'aventure dans Kanto, une partie par compte : nouvelle partie, déplacements, combats, arènes, Motisdex (`/game/dex`) et succès (`/game/achievements`), qui sont la seule source de pierres d'évolution et de Câble Link (voir Swagger, section Aventure) |
| `GET` | `/regions` | Les 9 régions avec le nombre de Pokémon en base |
| `GET` | `/types` | Types présents en base avec leur effectif |
| `GET` | `/pokemon/{idOrName}` | Détail d'un Pokémon (récupéré sur PokéAPI si absent), avec ses talents détaillés en français. `?refresh=true` force la mise à jour |
| `GET` | `/pokemon/{idOrName}/evolutions` | Arbre d'évolution de la famille, formes régionales comprises (Raichu d'Alola…), avec les conditions en français (« Niveau 16 », « Utiliser Pierre Foudre »…). Mis en cache en base |
| `GET` | `/pokemon/{idOrName}/moves` | Attaques apprises dans le jeu le plus récent (Écarlate / Violet pour la plupart) : nom FR, type, catégorie, puissance, précision, PP, méthode et niveau |
| `GET` | `/abilities/{name}` | Talent détaillé (nom FR, texte du jeu, effet) et liste des Pokémon qui le possèdent |
| `GET` | `/pokemon/{idOrName}/type-defenses` | Faiblesses (×2, ×4), résistances (×½, ×¼) et immunités de type, avec les talents qui les modifient |
| `POST` | `/pokemon/import?limit=151&offset=0` | Importe une plage de Pokémon depuis PokéAPI (max 2000 par appel), avec leurs formes régionales (Raichu d'Alola…) |

### Équipes

Équipes du compte connecté (jeton requis) ; l'équipe d'un autre compte répond 404.

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

Les erreurs sont renvoyées au format `{"error": "..."}` : `400` requête invalide, `401` connexion requise ou identifiants incorrects, `404` ressource introuvable, `409` nom d'utilisateur déjà pris, `502` PokéAPI indisponible.

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
- `app_user` (comptes, mot de passe haché), `user_session` (hachage des jetons de session, expiration)
- `team` (avec son compte propriétaire), `team_member` (position 1 à 6)
- `region_cache`, `location`, `version_info` (lieux des régions, rencontres, noms des jeux)
- `game_save` (partie du jeu d'aventure de chaque compte, JSON)
- `type_relation` (dégâts reçus par type, pour le calcul des faiblesses)
- `ability` (détail des talents et Pokémon qui les possèdent, partagé)
- `pokemon_learnset` (attaques apprises dans le jeu le plus récent, JSON), `move` (détail des attaques, partagé)
- `evolution_chain` (chaînes d'évolution PokéAPI en JSON), `translation` (noms français des objets, capacités, lieux)

## Tests

```bash
./gradlew test
```

Les tests utilisent une base H2 en mémoire (mode PostgreSQL) et un `MockEngine` à la place de pokeapi.co : aucun accès réseau ni base externe nécessaire.

## Structure

```
src/main/kotlin/com/example/pokeapi/
├── Application.kt          # module Ktor + injection des dépendances
├── auth/                   # comptes, mots de passe, sessions
├── client/                 # client HTTP PokéAPI + DTOs
├── db/                     # connexion Hikari/Exposed + tables
├── model/                  # modèles exposés par l'API
├── repository/             # accès PostgreSQL
├── service/                # logique métier (cache, import, équipes)
├── routes/                 # routes HTTP
└── plugins/                # sérialisation, logs, gestion des erreurs
```
