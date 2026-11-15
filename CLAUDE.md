# Contexte du projet — API PokéAPI (Ktor) et jeu d'aventure

Fichier de contexte pour les assistants (Claude Code et autres). Le projet est en **français** : code commenté en
français, messages du jeu en français, commits en français. Réponses à l'utilisateur en français.

## Les deux dépôts

| Dépôt | Rôle |
|---|---|
| `pokeAPI` (celui-ci) | API Ktor + PostgreSQL : Pokédex importé depuis pokeapi.co, comptes, équipes, **jeu d'aventure** |
| `pokeApp` | Application Expo (web et mobile) : Pokédex, régions, lieux, et l'aventure (voir son `CLAUDE.md`) |

Ils se clonent côte à côte (`~/pokeAPI`, `~/pokeApp`). Branche de travail actuelle : `claude/bold-lovelace-rdlawh`
(pas de pull request sans demande explicite).

## Stack et commandes

Kotlin 2.1, Ktor 3.1, Exposed, kotlinx.serialization, PostgreSQL 16 (H2 + MockEngine dans les tests), Java 21.

```bash
./gradlew test            # tous les tests (ApplicationTest : API et aventure ; GameRulesTest : règles pures)
./gradlew buildFatJar     # build/libs/pokeapi-all.jar
docker compose up --build # développement : base (port 5433) + API (8080)
```

Configuration : `src/main/resources/application.yaml` (variables `DATABASE_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `POKEAPI_BASE_URL`, `CORS_ALLOWED_HOSTS`, `PORT`). Swagger : `/swagger`.
**La documentation OpenAPI (`src/main/resources/openapi/documentation.yaml`) est tenue à la main** : la mettre à
jour avec chaque route ou champ ajouté.

## Organisation du code

- `routes/` : `Routes.kt` (Pokédex, régions, lieux, équipes, mise à jour), `AuthRoutes.kt`, `GameRoutes.kt` (`/game/...`).
- `service/`, `repository/`, `client/` (appels pokeapi.co), `db/Tables.kt` (dont `game_save` : une partie JSON par utilisateur).
- `game/` : le jeu d'aventure.
  - `GameRules.kt` : constantes et règles pures (parcours `STEPS`, niveaux, XP, dégâts, chances, Ligue, Pokémon rares…).
  - `GameModels.kt` : état sauvegardé (`GameState`, `Fighter`, `Battle`, `DayCare`, `LeagueProgress`, `Egg`…).
  - `GameViews.kt` : ce que l'API renvoie (`GameView`, `FighterView`, `StepView`, `LeagueView`…).
  - `GameService.kt` : toutes les actions (gros fichier : chercher par section `// ---------- … ----------`).
  - `Achievements.kt` : succès (récompenses : pierres d'évolution, Câble Link).
  - `SaveUpgrade.kt` : mise à jour des anciennes sauvegardes (`SAVE_VERSION`).

### Mécanique de `GameService`

- Toute action passe par `act(userId) { s -> … }` (verrou, chargement migré, `syncTraining`/`syncDayCare`, puis
  `unlockAchievements`, `rememberCleared`, `rememberShiny`, `unlockJohto`, sauvegarde, vue).
- `loadMigrated` : `upgradeSave` (version de sauvegarde), `migrateRoutes`, `migrateMoves`. **Toute évolution du
  format de `GameState` doit rester compatible** : champs nouveaux avec valeur par défaut, et migration dans
  `upgradeSave` (incrémenter `SAVE_VERSION`) si des données existantes doivent changer. Au démarrage,
  `upgradeAll()` met à jour toutes les parties et écrit « Mise à jour des parties : … » dans le journal.
- Erreurs joueur : `BadRequestException` avec un message français (affiché tel quel par l'application).
- Gardes : `noBattle(s)`, `noLeagueChallenge(s)` (un défi de Ligue en cours bloque toute autre action ; sortie par
  `POST /game/league/abandon`).

## Règles du jeu (résumé)

- **Pas de contenu original recréé** : pas de noms ni d'équipes de champions, du Conseil des 4 ou de Maîtres des jeux
  officiels. Tout est généré depuis les données (types les plus fréquents des routes, statistiques…).
- **Parcours** (`GameRules.STEPS`) : villes, routes et lieux de Kanto (rencontres de Vert Feuille), puis Johto
  (Cristal, niveaux +45) jusqu'au Plateau Indigo de Johto. Chaque lieu n'apparaît qu'une fois par région. Les
  numéros d'étapes sont sauvegardés : ajouter des étapes **à la fin** ; retirer ou déplacer une étape exige une
  migration des numéros (`SaveUpgrade.kt`, voir `remapStepV2`). Une route est franchie avec 10 victoires et toutes
  ses espèces vues (herbes et Canne : `routeSpecies`). La vue d'un lieu (`StepView.species`, `RouteSpecies`) indique pour
  chaque espèce si elle est vue (`seen`) et rejointe (`caught`) ; après la Ligue de la région, elle ajoute les espèces de
  la Super Canne, de la Méga Canne et du Surf (`GameRules.shownSpecies`, champ `method`), visées aussi par le coup de
  pouce, sans changer la condition pour franchir le lieu.
- **Jadielle** : une seule fois, au début ; son arène (`gymOpensAfter`) est la 8e de Kanto et n'ouvre qu'avec les 7
  autres badges, sans bloquer la route ; la Route 23 (`requiresBadgeOf`) attend son badge. L'ordre des arènes suit
  `GameRules.gymOrder` / `gymSteps()`, pas l'ordre des étapes.
- **Arènes** : 8 par région, type tiré des Pokémon rencontrés avant ; badges = limite de niveau (`LEVEL_CAPS`).
- **Ligues** : une par région au Plateau Indigo (`leagues[région]`) : 4 membres du Conseil puis le Maître, à la
  suite. Kanto : niv. 52 à 65 ; Johto : niv. 78 à 100. Légendaires exclus des équipes.
- **Pokémon rares** : après le Maître d'une région, les espèces introuvables de cette région apparaissent par le
  coup de pouce ; légendaires à lieu fixe (`RARE_HOMES`). Mewtwo seul dans la Grotte Azurée.
- **Coup de pouce** invisible : au hasard entre le 5e et le 15e combat sur un lieu (`GameRules.boostChance`,
  moyenne ≈ 10) ; vise une espèce pas encore vue, sinon vue mais pas rejointe (chaque espèce visée à chance égale),
  ou un Pokémon rare après la Ligue ; 10 % de chromatique (sinon 1/4096).
- **Évolutions** : `GameRules.evolutionConditionMet` (niveau, Attaque/Défense, bonheur de jour ou de nuit) à la fin
  des combats ; objets (`GameRules.ITEMS`, dont Pierre Soleil gagnée par les succès de Johto). Bonheur
  (`Fighter.happiness`) : +1 par adversaire vaincu en ayant combattu, maximum `HAPPINESS_EVOLUTION` (20). Les 251
  Pokémon sont obtenables : garder `docs/obtenir-les-pokemon.md` à jour si une règle change.
- **Chromatiques** (`shiny`) : gardés à l'évolution (toutes les évolutions passent par `evolve`) ; `shinySeen` /
  `shinyCaught` pour le Motisdex. Réglage `recruitOnlyNew` : un chromatique reste proposé tant que ce chromatique
  n'est pas obtenu, même si l'espèce l'est.
- **Régions** : Johto s'ouvre avec les 151 Pokémon de Kanto obtenus (œuf de starter offert). Chaque Pokémon a une
  région d'origine (`origin`) : seuls ceux de la région en cours combattent, plus **un compagnon** choisi au passage
  de région. Une pension par région, accessible de partout (sauf défi de Ligue) ; zones d'entraînement limitées aux
  Pokémon de leur région. Pas d'évolution vers une espèce d'une région non débloquée.
- **Attaques qui blessent le lanceur** : contrecoup proportionnel aux dégâts via `drain` < 0 (données PokéAPI) ;
  les autres effets ne sont pas dans les données et sont listés dans `GameRules` : `SELF_KO_MOVES` (Destruction,
  Explosion : K.O.), `HALF_HP_COST_MOVES` (Métalaser), `CRASH_MOVES` (Pied Voltige raté). Mêmes listes dans
  `pokeApp/src/components/game/move-effects.ts` pour l'affichage.
- **Attaques de statut à effets propres** (`GameService.specialMove`) : Cognobidon, Malédiction (Spectre : malédiction
  `cursed`), Clonage (`substituteHp` : le clone encaisse les dégâts et bloque les effets sur la cible), Balance,
  Souvenir, Vœu Soin / Danse Lune (`wish` : remplaçant soigné), Tout ou Rien. Ces états de combat disparaissent avec
  `leftBattle()`. Les adversaires les utilisent quand elles servent (`GameRules.specialScore`, dans `moveScore`).
- **Pension** : 2 Pokémon compatibles (groupe d'œufs ou Métamorph) → un œuf toutes les 5 min ; l'espèce de l'œuf est
  fixée à son apparition (`eggSpecies`) ; le bébé éclot avec `HAPPINESS_HATCHED` (10) points de bonheur.
- **Combat** : l'IA des adversaires est `GameRules.chooseMove` / `moveScore` (meilleure attaque utile, 1 fois sur 5
  au hasard parmi les utiles). Destruction et Explosion seulement sous 25 % des PV.

Guide joueur : `docs/obtenir-les-pokemon.md` (façons d'obtenir les Pokémon, Pokémon rares, limites). À tenir à jour
avec les règles d'obtention (rencontres, évolutions, œufs, rares).

## Tests

- Toute fonctionnalité du jeu a un test dans `ApplicationTest` (données PokéAPI simulées par `MockEngine`, état de
  partie modifié via `editSave { … }`). Ne jamais désactiver un test pour passer au vert.
- Lancer `./gradlew test` avant chaque commit ; tous les tests doivent passer.

## Déploiement

`deploy/raspberry/` : Docker Compose (base, API, application web construite depuis `pokeApp`, Caddy qui relaie
l'API sur `/api`), scripts `installer.sh`, `mettre-a-jour.sh`, `sauvegarder.sh`, `restaurer.sh`. Fonctionne aussi
sur un PC (Docker Desktop sous Windows : créer `.env` à partir de `.env.example`). Voir `deploy/raspberry/README.md`.

## Données de l'utilisateur

La vraie partie de l'utilisateur est sur sa propre machine. Ne jamais supposer qu'une base de test reflète sa partie ;
toute modification du format de sauvegarde doit passer par une migration sans perte.
