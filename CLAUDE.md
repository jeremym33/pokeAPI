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

- `routes/` : `Routes.kt` (Pokédex, régions, lieux, équipes, mise à jour), `AuthRoutes.kt` (`/auth/...` : inscription,
  connexion, déconnexion, `me`, suppression du compte), `GameRoutes.kt` (`/game/...`).
- `auth/AuthService.kt` : comptes (mots de passe PBKDF2, sessions par jeton haché), `confirmPassword`, `deleteAccount`.
- `service/`, `repository/`, `client/` (appels pokeapi.co), `db/Tables.kt` (dont `game_save` : une partie JSON par utilisateur).
- `docs/obtenir-les-pokemon.md` : guide joueur (obtention des 386 Pokémon).
- `docs/idees.md` : idées proposées mais pas encore faites (retirer une idée une fois réalisée).
- `game/` : le jeu d'aventure (Kanto, Johto, Hoenn).
  - `GameRules.kt` : constantes et règles pures (parcours `STEPS`, niveaux, XP, dégâts, chances, Ligue, Pokémon rares…).
  - `GameModels.kt` : état sauvegardé (`GameState`, `Fighter`, `Battle`, `DayCare`, `LeagueProgress`, `Egg`,
    `TeamPreset`, `HistoryEntry`, `DailyChallenges`, `PlayStats`…).
  - `GameViews.kt` : ce que l'API renvoie (`GameView`, `FighterView`, `StepView`, `LeagueView`…).
  - `GameService.kt` : toutes les actions (gros fichier : chercher par section `// ---------- … ----------`).
  - `Achievements.kt` : succès (récompenses : pierres d'évolution, Câble Link ; succès de Johto et de Hoenn par
    espèces de la région rejointes : `johtoCaught`, `hoennCaught`).
  - `SaveUpgrade.kt` : mise à jour des anciennes sauvegardes (`SAVE_VERSION` 3 : v1 Ligues et pensions par région,
    origines ; v2 une seule Jadielle, numéros d'étapes décalés ; v3 starter de région, plus de compagnon).

### Mécanique de `GameService`

- Toute action passe par `act(userId) { s -> … }` (verrou, chargement migré, `syncTraining`/`syncDayCare`,
  `withDaily` (défis du jour), puis l'action, `unlockAchievements`, `rememberCleared`, `rememberShiny`, `unlockRegions`,
  `chronicle` (journal, progrès des défis, statistiques, en comparant l'état avant et après), sauvegarde, vue). Les
  lectures (`GET /game`, `/journal`, `/dex`…) ne passent pas par `act` : rien n'y est modifié.
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
  (Cristal) jusqu'au Plateau Indigo de Johto, puis Hoenn (Émeraude) de Bourg-en-Vol à Éternara (Ligue), suivi du
  Pilier Céleste, de la Voie du Désert et de la Grotte Atelier. Niveaux d'origine : chaque région repart de zéro
  (`REGION_LEVEL_OFFSET` vide). Régions : `GameRules.REGIONS`, `REGION_DEX`, `REGION_VERSIONS`, `REGION_NAMES`,
  `regionIds`, `regionsUpTo`. Chaque lieu n'apparaît qu'une fois par région. Les
  numéros d'étapes sont sauvegardés : ajouter des étapes **à la fin** ; retirer ou déplacer une étape exige une
  migration des numéros (`SaveUpgrade.kt`, voir `remapStepV2`). Une route est franchie avec 10 victoires et toutes
  ses espèces vues (herbes et Canne : `routeSpecies`). La vue d'un lieu (`StepView.species`, `RouteSpecies`) indique pour
  chaque espèce si elle est vue (`seen`) et rejointe (`caught`) ; après la Ligue de la région, elle ajoute les espèces de
  la Super Canne, de la Méga Canne et du Surf (`GameRules.shownSpecies`, champ `method`), visées aussi par le coup de
  pouce, sans changer la condition pour franchir le lieu.
- **Jadielle** : une seule fois, au début ; son arène (`gymOpensAfter`) est la 8e de Kanto et n'ouvre qu'avec les 7
  autres badges, sans bloquer la route ; la Route 23 (`requiresBadgeOf`) attend son badge. De même **Clémenti-Ville**
  (Hoenn) : 5e arène (`gymOpensAfter = "mirage-tower"`), ouverte avec les 4 badges qui la précèdent (`gymOpen`). L'ordre des arènes suit
  `GameRules.gymOrder` / `gymSteps()`, pas l'ordre des étapes.
- **Arènes** : 8 par région, type tiré des Pokémon rencontrés avant (dans la région, sans répéter un type de la région),
  même progression dans chaque région ; les badges
  **de la région en cours** (`regionBadges`) fixent la limite de niveau (`LEVEL_CAPS`) et les rencontres (cannes, surf).
- **Ligues** : une par région (`leagues[région]`), au Plateau Indigo pour Kanto et Johto, à Éternara pour Hoenn :
  4 membres du Conseil puis le Maître, à la suite. Toutes : niv. 38 à 55 (meilleur Pokémon
  de chaque adversaire : `REGION_LEAGUE_TOP_LEVELS`). Légendaires exclus des équipes.
- **Pokémon rares** : après le Maître d'une région, les espèces introuvables de cette région apparaissent par le
  coup de pouce ; légendaires à lieu fixe (`RARE_HOMES`). Mewtwo seul dans la Grotte Azurée, Rayquaza au Pilier
  Céleste (`SOLO_RARES`).
- **Coup de pouce** invisible : au hasard entre le 5e et le 15e combat sur un lieu (`GameRules.boostChance`,
  moyenne ≈ 10) ; vise une espèce pas encore vue, sinon vue mais pas rejointe (chaque espèce visée à chance égale),
  ou un Pokémon rare après la Ligue ; 10 % de chromatique (sinon 1/4096).
- **Évolutions** : `GameRules.evolutionConditionMet` (niveau, Attaque/Défense, bonheur de jour ou de nuit) à la fin
  des combats ; plusieurs évolutions possibles au niveau : une au hasard (Chenipotte) ; Munja apparaît en plus de
  Ninjask (`isShedCondition`, `withShed`) ; un objet à plusieurs évolutions (Coquiperl) vise d'abord une espèce pas
  encore rejointe ; objets (`GameRules.ITEMS`, dont Pierre Soleil gagnée par les succès de Johto ; succès de Hoenn). Bonheur
  (`Fighter.happiness`) : +1 par adversaire vaincu en ayant combattu, maximum `HAPPINESS_EVOLUTION` (20). Les 386
  Pokémon sont obtenables : garder `docs/obtenir-les-pokemon.md` à jour si une règle change.
- **Chromatiques** (`shiny`) : gardés à l'évolution (toutes les évolutions passent par `evolve`) ; `shinySeen` /
  `shinyCaught` pour le Motisdex. Réglage `recruitOnlyNew` : un chromatique reste proposé tant que ce chromatique
  n'est pas obtenu, même si l'espèce l'est ; une espèce aussi, tant qu'aucun exemplaire né dans la région en cours n'est
  possédé (`ownsBornIn`, `BattleView.recruitNewInRegion`).
- **Régions** : Johto s'ouvre avec les 151 Pokémon de Kanto obtenus, Hoenn avec les 251 premiers (`unlockRegions`,
  `GameState.johtoUnlocked` / `hoennUnlocked`, `regionUnlocked`, vue : `GameView.unlockedRegions`). Chaque Pokémon a une région d'origine
  (`origin`) : seuls ceux de la région en cours combattent (plus de compagnon). À la première arrivée dans une région,
  on y choisit un **starter de la région** (`REGION_STARTERS`, niveau `REGION_STARTER_LEVEL` : 5 ;
  `TravelRequest.starter`, ou `POST /game/region-starter` sur place ; `GameState.regionStarters` ; vue :
  `GameView.starterChoices`). Une pension par région, accessible de partout (sauf défi de Ligue) ; zones
  d'entraînement limitées aux Pokémon de leur région. Pas d'évolution vers une espèce d'une région non débloquée.
- **Attaques qui blessent le lanceur** : contrecoup proportionnel aux dégâts via `drain` < 0 (données PokéAPI) ;
  les autres effets ne sont pas dans les données et sont listés dans `GameRules` : `SELF_KO_MOVES` (Destruction,
  Explosion : K.O.), `HALF_HP_COST_MOVES` (Métalaser), `CRASH_MOVES` (Pied Voltige raté). Mêmes listes dans
  `pokeApp/src/components/game/move-effects.ts` pour l'affichage.
- **Attaques de statut à effets propres** (`GameService.specialMove`) : Cognobidon, Malédiction (Spectre : malédiction
  `cursed`), Clonage (`substituteHp` : le clone encaisse les dégâts et bloque les effets sur la cible), Balance,
  Souvenir, Vœu Soin / Danse Lune (`wish` : remplaçant soigné), Tout ou Rien. Ces états de combat disparaissent avec
  `leftBattle()`. Les adversaires les utilisent quand elles servent (`GameRules.specialScore`, dans `moveScore`).
- **Comptes** : recommencer la partie (`DELETE /game`) et supprimer le compte (`DELETE /auth/account`, avec la partie
  et les équipes) demandent le mot de passe du compte (`{"password": …}`, `AuthService.confirmPassword` : 400 si
  incorrect, pas 401, la session restant valide).
- **Pension** : une par région ouverte, gérable depuis n'importe où : `?region=` sur `/game/daycare/deposit`,
  `/withdraw`, `/egg` (par défaut la région actuelle) ; vue : `GameView.dayCares` (et `dayCare`, la région actuelle) ;
  2 Pokémon compatibles (groupe d'œufs ou Métamorph) → un œuf toutes les 5 min ; l'espèce de l'œuf est
  fixée à son apparition (`eggSpecies`) ; le bébé éclot avec `HAPPINESS_HATCHED` (10) points de bonheur.
- **Équipes enregistrées** (`GameState.teamPresets`, 6 au plus ; `POST /game/team-presets`, `/team-presets/apply`,
  `DELETE /team-presets/{name}`) ; **soin rapide** (`POST /game/heal-nearest` : ville accessible la plus proche).
- **Défis du jour** (`withDaily`, `GameState.daily`) : 3 par jour (date du serveur), tirés pour la région actuelle
  (gagner N combats, battre N Pokémon d'un type, accueillir N Pokémon), progrès suivis dans `chronicle`, objet
  d'évolution en récompense ; vue `GameView.daily`. **Journal** (`chronicle`, après chaque action dans `act`) :
  `GameState.history` (recrues, éclosions, évolutions, badges, Ligues, régions, défis ; 300 au plus) et
  `GameState.stats` (temps de jeu hors pauses de plus de 5 min, recrues, évolutions, éclosions, défaites,
  `fighterWins` pour le Pokémon préféré) ; `GET /game/journal`. `GameView.night` : nuit à l'heure du serveur.
- **Fiches** : `GET /game/dex/{number}` (`dexSpecies` : où obtenir une espèce vue ou rejointe : starter, lieux
  sauvages avec méthode et niveaux, évolution, œuf, Pokémon rare) et `GET /game/pokemon/{uid}` (`fighterDetails` :
  statistiques, évolutions possibles avec leur condition et leur progression).
- **Combat** : `BattleView.moveEffectiveness` donne l'efficacité de chaque attaque équipée sur l'adversaire (défenses
  de l'adversaire, null pour une attaque de statut), affichée par l'application. L'IA des adversaires est `GameRules.chooseMove` / `moveScore` (meilleure attaque utile, 1 fois sur 5
  au hasard parmi les utiles). Destruction et Explosion seulement sous 25 % des PV.

Guide joueur : `docs/obtenir-les-pokemon.md` (façons d'obtenir les Pokémon, coup de pouce, Pokémon rares, liste des
espèces des lieux, simplifications par rapport aux jeux). À tenir à jour avec les règles d'obtention (rencontres,
évolutions, œufs, rares, coup de pouce).

## Ajouter une région

Étapes ajoutées à la fin de `STEPS` ; `REGIONS`, `REGION_DEX`, `REGION_VERSIONS`, `REGION_NAMES`, `REGION_STARTERS`,
`REGION_STARTER_LEVEL`, `REGION_LEAGUE_TOP_LEVELS`, `LEGENDARIES`, `RARE_HOMES` ; un champ `<région>Unlocked` dans
`GameState` et dans `regionUnlocked` / `unlockRegions` ; succès de la région ; conditions d'évolution nouvelles dans
`evolutionConditionMet` ; côté application, `src/constants/regions.ts` (nom, symbole, `lastDex`, couleur). Vérifier
sur les données réelles que toutes les espèces sont obtenables, puis mettre à jour le guide joueur.

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
