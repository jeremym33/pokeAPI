package com.example.pokeapi.game

import kotlinx.serialization.Serializable

/** Attaque utilisable en combat (offensive, ou de statut comme Cage Éclair). */
@Serializable
data class FighterMove(
    val name: String,
    val nameFr: String?,
    val type: String,
    /** physical, special ou status */
    val damageClass: String,
    /** 0 pour une attaque de statut */
    val power: Int,
    /** null = ne rate jamais */
    val accuracy: Int?,
    val priority: Int = 0,
    /** Statut infligé (burn, poison, paralysis, sleep, freeze), null si aucun. */
    val ailment: String? = null,
    /** Chance d'infliger le statut (%) ; une attaque de statut l'inflige toujours si elle touche. */
    val ailmentChance: Int = 0,
    /** Niveau auquel l'attaque est apprise (null : attaque de secours). */
    val level: Int? = null,
    /** % des dégâts infligés récupérés en PV (Vole-Vie : 50) ; négatif = contrecoup (Damoclès : -33). */
    val drain: Int = 0,
    /** % des PV max récupérés (Soin : 50). */
    val healing: Int = 0,
    /** Nombre de coups (Combo-Griffe : 2 à 5, Double Pied : 2), null pour un seul coup. */
    val minHits: Int? = null,
    val maxHits: Int? = null,
    /** Statistiques modifiées (attack -> +2) : sur le lanceur si [statSelf], sinon sur la cible, avec [statChance] %. */
    val statChanges: Map<String, Int> = emptyMap(),
    val statSelf: Boolean = false,
    val statChance: Int = 100,
    /** Chance d'apeurer la cible (%) ; à 100 % (Bluff), l'attaque ne marche qu'au premier tour du lanceur au combat. */
    val flinchChance: Int = 0,
) {
    /** Attaque de statut qui soigne le lanceur (Soin, Synthèse, Repos…). */
    val heals: Boolean get() = damageClass == "status" && (healing > 0 || name == REST)

    companion object {
        /** Repos : soigne entièrement et guérit le statut, mais endort le lanceur 2 tours. */
        const val REST = "rest"
    }
}

/** Pokémon de l'équipe, de la boîte ou adversaire. */
@Serializable
data class Fighter(
    val uid: String,
    val pokemonId: Int,
    val speciesId: Int,
    val name: String,
    val nameFr: String?,
    val spriteUrl: String?,
    val artworkUrl: String?,
    val types: List<String>,
    val level: Int,
    /** Expérience totale (niveau n à partir de n³). */
    val xp: Int,
    val hp: Int,
    /** Statistiques de base (hp, attack, defense, special-attack, special-defense, speed). */
    val base: Map<String, Int>,
    val baseExperience: Int,
    val captureRate: Int,
    /** Attaques équipées (4 au plus), utilisées en combat. */
    val moves: List<FighterMove>,
    /** Toutes les attaques apprises, équipées ou non (le joueur choisit lesquelles équiper). */
    val knownMoves: List<FighterMove> = emptyList(),
    /** Multiplicateur de dégâts reçus par type attaquant. */
    val defenses: Map<String, Double>,
    /** Statut (burn, poison, paralysis, sleep, freeze), conservé jusqu'au Centre Pokémon. La confusion est à part. */
    val status: String? = null,
    /** Tours de sommeil restants. */
    val sleepTurns: Int = 0,
    /** Niveaux de statistiques en combat (-6 à +6 : attack, defense, special-attack, special-defense, speed, accuracy, evasion). */
    val stages: Map<String, Int> = emptyMap(),
    /** Confusion : tours restants (0 = pas confus). Disparaît en quittant le combat. */
    val confusedTurns: Int = 0,
    /** Apeuré ce tour-ci : ne pourra pas attaquer (effacé en fin de tour). */
    val flinched: Boolean = false,
    /** Tours passés au combat depuis l'entrée (Bluff ne marche qu'au premier). */
    val battleTurns: Int = 0,
    /** Pokémon chromatique (« shiny ») : couleurs rares, conservées en évoluant. */
    val shiny: Boolean = false,
) {
    val displayName: String get() = nameFr ?: name.replaceFirstChar(Char::uppercase)
    val maxHp: Int get() = GameRules.stats(this).hp
    val fainted: Boolean get() = hp <= 0
    /** Effets qui disparaissent en quittant le combat : niveaux de statistiques, confusion, peur. */
    fun leftBattle(): Fighter = copy(stages = emptyMap(), confusedTurns = 0, flinched = false, battleTurns = 0)

    /** Attaques équipées et connues, sans doublon. */
    val allMoves: List<FighterMove> get() = (moves + knownMoves).distinctBy { it.name }
}

@Serializable
enum class BattleKind { WILD, GYM, LEAGUE }

@Serializable
enum class BattleOutcome { WON, LOST, FLED }

@Serializable
data class Battle(
    val kind: BattleKind,
    val step: Int,
    val enemies: List<Fighter>,
    val enemyIndex: Int = 0,
    val activeUid: String,
    /** Pokémon de l'équipe qui ont combattu l'adversaire actuel (ils se partagent l'expérience). */
    val participants: Set<String> = emptySet(),
    val log: List<String> = emptyList(),
    /** Le Pokémon actif est K.O. : le joueur doit en envoyer un autre. */
    val mustSwitch: Boolean = false,
    val outcome: BattleOutcome? = null,
    /** Pokémon sauvage vaincu qui veut rejoindre l'équipe : le joueur accepte ou non. */
    val recruit: Fighter? = null,
    /** Nom de l'adversaire (Ligue). */
    val title: String? = null,
) {
    val enemy: Fighter get() = enemies[enemyIndex]
}

/**
 * Pokémon laissé à l'entraînement sur un lieu : il y combat les Pokémon sauvages (un combat simulé toutes les
 * 10 secondes) et gagne leur expérience.
 */
@Serializable
data class Trainee(
    val fighter: Fighter,
    /** Début de l'entraînement (ms depuis l'epoch). */
    val since: Long,
    /** Instant jusqu'où les combats ont été comptés. */
    val lastSync: Long,
    val fights: Int = 0,
    val xpGained: Int = 0,
    /** Derniers évènements (montées de niveau, attaques, évolutions). */
    val log: List<String> = emptyList(),
)

/** Pension (dans les villes) : jusqu'à 2 Pokémon ; un couple compatible produit des œufs. */
@Serializable
data class DayCare(
    val pokemon: List<Fighter> = emptyList(),
    /** Instant où le prochain œuf sera prêt (couple compatible), null sinon. */
    val nextEggAt: Long? = null,
    /** Un œuf attend d'être pris. */
    val eggWaiting: Boolean = false,
)

/** Œuf transporté : il éclot après quelques combats. L'espèce reste cachée jusqu'à l'éclosion. */
@Serializable
data class Egg(
    val uid: String,
    val speciesId: Int,
    val fightsLeft: Int,
    val fightsTotal: Int,
)

/** Réglages du joueur, sauvegardés avec la partie. */
@Serializable
data class GameSettings(
    /** En fin de combat sur une route, proposer d'enchaîner directement sur le Pokémon suivant. */
    val nextEncounterButton: Boolean = true,
    /** Ne proposer de rejoindre l'équipe qu'aux espèces pas encore obtenues. */
    val recruitOnlyNew: Boolean = false,
    /** Avec 4 attaques équipées, une nouvelle attaque remplace la plus ancienne ; sinon elle rejoint les attaques connues. */
    val autoReplaceMoves: Boolean = true,
    /** Ancien réglage du coup de pouce, qui est désormais invisible et toujours actif (conservé pour les sauvegardes). */
    val unseenBoost: Boolean = false,
)

/** Sauvegarde de la partie. */
@Serializable
data class GameState(
    val starter: String,
    val stepIndex: Int = 0,
    /** Victoires par étape (routes). */
    val wins: Map<Int, Int> = emptyMap(),
    /** Étapes dont l'arène a été battue. */
    val badges: Set<Int> = emptySet(),
    val team: List<Fighter>,
    val box: List<Fighter> = emptyList(),
    /** Espèces obtenues (numéros du Pokédex national). */
    val caught: Set<Int> = emptySet(),
    val seen: Set<Int> = emptySet(),
    /** Type de chaque arène, choisi d'après les Pokémon rencontrés avant elle. */
    val gymTypes: Map<Int, String> = emptyMap(),
    /** Dernière ville visitée : retour au Centre Pokémon après une défaite. */
    val lastCenter: Int = 0,
    val battle: Battle? = null,
    val uidCounter: Int = 0,
    /** Sac : objet -> quantité (pierres d'évolution, Câble Link), reçus en récompense des succès. */
    val items: Map<String, Int> = emptyMap(),
    /** Message de la dernière action hors combat (évolution par pierre…). */
    val lastEvent: String? = null,
    val settings: GameSettings = GameSettings(),
    /** Combats gagnés (sauvages et arènes). Pour une partie plus ancienne, voir GameService.victoryCount. */
    val victories: Int = 0,
    /** Succès débloqués (identifiants de [Achievements.ALL]). */
    val achievements: Set<String> = emptySet(),
    /** Routes franchies : acquises une fois pour toutes (la règle peut évoluer, la progression reste). */
    val clearedSteps: Set<Int> = emptySet(),
    /** Version de la règle de passage des routes (1 : 10 victoires et toutes les espèces vues). */
    val routeRules: Int = 0,
    /** Pokémon à l'entraînement, par étape (un au plus par lieu). */
    val training: Map<Int, Trainee> = emptyMap(),
    /** Combats sauvages par étape depuis le dernier coup de pouce (il revient à [GameRules.BOOST_EVERY]). */
    val boostCounters: Map<Int, Int> = emptyMap(),
    /** Espèces dont la version chromatique a été vue en combat / a rejoint l'équipe (Motisdex). */
    val shinySeen: Set<Int> = emptySet(),
    val shinyCaught: Set<Int> = emptySet(),
    val dayCare: DayCare = DayCare(),
    /** Ligue : prochain adversaire (0 à 3 : Conseil, 4 : Maître) pendant un défi en cours, 0 sinon. */
    val leagueStage: Int = 0,
    /** Types des 4 membres du Conseil, choisis au premier défi. */
    val leagueTypes: List<String> = emptyList(),
    /** Victoires contre le Maître : dès la première, les Pokémon rares apparaissent (coup de pouce). */
    val championWins: Int = 0,
    val eggs: List<Egg> = emptyList(),
    /** Version des attaques enregistrées dans la partie (2 : drain, soin, coups multiples ; 3 : statistiques ; 4 : peur). */
    val movesVersion: Int = 0,
)
