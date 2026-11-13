package com.example.pokeapi.game

import kotlinx.serialization.Serializable

@Serializable
data class FighterView(
    val uid: String,
    val pokemonId: Int,
    val speciesId: Int,
    val name: String,
    val spriteUrl: String?,
    val artworkUrl: String?,
    val types: List<String>,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    /** Expérience dans le niveau actuel / nécessaire pour le suivant. */
    val xpInLevel: Int,
    val xpToNext: Int,
    val moves: List<FighterMove>,
    /** Attaques apprises non équipées. */
    val knownMoves: List<FighterMove>,
    val fainted: Boolean,
    /** burn, poison, paralysis, sleep, freeze */
    val status: String?,
    /** Niveaux de statistiques en combat (non nuls seulement). */
    val stages: Map<String, Int> = emptyMap(),
    /** Confus (en combat). */
    val confused: Boolean = false,
    /** Chromatique (« shiny ») ; les images sont alors celles de la version chromatique. */
    val shiny: Boolean = false,
)

/** Objet d'évolution du sac : quantité et Pokémon sur lesquels il a un effet. */
@Serializable
data class ItemView(val item: String, val name: String, val count: Int, val usableOn: List<String>)

/** État d'une espèce dans le Motisdex de la partie. */
@Serializable
enum class DexStatus { UNKNOWN, SEEN, CAUGHT }

/**
 * Entrée du Motisdex : une espèce de Kanto. Une espèce jamais rencontrée ne révèle rien (ni nom, ni image),
 * comme dans les jeux.
 */
@Serializable
data class DexEntry(
    /** Numéro du Pokédex national. */
    val number: Int,
    val status: DexStatus,
    val name: String? = null,
    val types: List<String> = emptyList(),
    val spriteUrl: String? = null,
    val artworkUrl: String? = null,
    /** Nombre de Pokémon de cette espèce dans l'équipe et la boîte. */
    val owned: Int = 0,
    /** Version chromatique vue en combat / rejointe. */
    val shinySeen: Boolean = false,
    val shinyCaught: Boolean = false,
    /** Images de la version chromatique (seulement si elle a été vue). */
    val shinySpriteUrl: String? = null,
    val shinyArtworkUrl: String? = null,
)

@Serializable
data class DexView(
    val total: Int,
    val seen: Int,
    val caught: Int,
    val entries: List<DexEntry>,
    /** Espèces dont la version chromatique a été vue (rejointes comprises) / a rejoint l'équipe. */
    val shinySeen: Int = 0,
    val shinyCaught: Int = 0,
)

@Serializable
data class GymView(val number: Int, val type: String?, val badge: Boolean)

@Serializable
data class StepView(
    val index: Int,
    /** kanto ou johto */
    val region: String = KANTO,
    val location: String,
    val nameFr: String?,
    val city: Boolean,
    val gym: GymView?,
    val wins: Int,
    /** Victoires nécessaires pour franchir l'étape (0 pour une ville). */
    val winsNeeded: Int,
    /** Espèces de la route déjà vues / à voir pour la franchir (0 / 0 pour une ville). */
    val speciesSeen: Int = 0,
    val speciesTotal: Int = 0,
    /** Espèces de la route (seulement pour l'étape actuelle) : nom et image si déjà vues. */
    val species: List<RouteSpecies> = emptyList(),
    /** Pokémon rares du lieu, après la victoire contre le Maître (coup de pouce seulement). */
    val rare: List<RouteSpecies> = emptyList(),
    /** Pokémon sauvages disponibles ici (avec les badges actuels). */
    val hasEncounters: Boolean,
    val cleared: Boolean,
    val unlocked: Boolean,
)

/** Badge d'arène : obtenu ou non, et ses effets. */
@Serializable
data class BadgeView(
    /** 1 à 8, dans l'ordre du parcours. */
    val number: Int,
    val name: String,
    /** Ville de l'arène. */
    val location: String?,
    /** Type de l'arène, connu une fois l'arène accessible. */
    val type: String?,
    val obtained: Boolean,
    val effects: List<String>,
)

@Serializable
data class BadgesView(val obtained: Int, val total: Int, val levelCap: Int, val badges: List<BadgeView>)

/** Défi de la Ligue (Plateau Indigo). */
@Serializable
data class LeagueView(
    /** 8 badges obtenus : le défi est ouvert. */
    val open: Boolean,
    /** Prochain adversaire du défi en cours (0 à 3 : Conseil, 4 : Maître). */
    val stage: Int,
    val opponents: List<LeagueOpponentView>,
    val championWins: Int,
)

@Serializable
data class LeagueOpponentView(val name: String, val type: String?, val defeated: Boolean)

@Serializable
data class DayCareView(
    val pokemon: List<FighterView>,
    /** Les 2 Pokémon peuvent avoir un œuf. */
    val compatible: Boolean,
    /** Avis de la pension sur le couple. */
    val message: String?,
    val eggWaiting: Boolean,
    /** Secondes avant le prochain œuf (couple compatible, pas d'œuf en attente). */
    val nextEggInSeconds: Int?,
)

@Serializable
data class EggView(val uid: String, val fightsLeft: Int, val fightsTotal: Int)

/** Pokémon à l'entraînement sur un lieu. */
@Serializable
data class TraineeView(
    val step: Int,
    val location: String?,
    val fighter: FighterView,
    val since: Long,
    val fights: Int,
    val xpGained: Int,
    val log: List<String>,
)

@Serializable
data class RouteSpecies(val number: Int, val seen: Boolean, val name: String? = null, val spriteUrl: String? = null)

@Serializable
data class BattleView(
    val kind: BattleKind,
    val title: String,
    val enemy: FighterView,
    /** Pokémon restants de l'adversaire (arène), adversaire actuel compris. */
    val enemyRemaining: Int,
    val active: FighterView,
    val log: List<String>,
    val mustSwitch: Boolean,
    val outcome: BattleOutcome?,
    val canFlee: Boolean,
    /** Pokémon vaincu qui veut rejoindre l'équipe (le joueur accepte ou non). */
    val recruit: FighterView?,
    /** La recrue est une espèce pas encore obtenue. */
    val recruitIsNew: Boolean,
    /** Combat terminé sur un lieu avec des Pokémon sauvages : « Pokémon suivant » possible (selon les réglages). */
    val canContinue: Boolean,
)

@Serializable
data class GameView(
    val started: Boolean,
    val starters: List<String> = GameRules.STARTERS,
    val currentStep: Int = 0,
    val unlockedStep: Int = 0,
    val badges: Int = 0,
    val levelCap: Int = GameRules.levelCap(0),
    val caught: Int = 0,
    val seen: Int = 0,
    val dexSize: Int = GameRules.DEX_SIZE,
    /** Johto ouvert (les 151 Pokémon de Kanto obtenus). */
    val johtoUnlocked: Boolean = false,
    val steps: List<StepView> = emptyList(),
    val team: List<FighterView> = emptyList(),
    val box: List<FighterView> = emptyList(),
    val battle: BattleView? = null,
    /** Objets d'évolution possédés, avec les Pokémon concernés. */
    val items: List<ItemView> = emptyList(),
    val lastEvent: String? = null,
    val settings: GameSettings = GameSettings(),
    /** Corrections faites par la vérification (POST /game/verify), vide sinon. */
    val corrections: List<String> = emptyList(),
    /** Succès débloqués par l'action qui renvoie cette vue (pour la notification), vide sinon. */
    val unlocked: List<UnlockedAchievement> = emptyList(),
    /** Pokémon à l'entraînement sur les lieux. */
    val training: List<TraineeView> = emptyList(),
    /** Secondes entre deux combats d'entraînement. */
    val trainingSeconds: Int = (GameRules.TRAINING_FIGHT_MS / 1000).toInt(),
    val dayCare: DayCareView? = null,
    /** Défi de la Ligue, quand on est au Plateau Indigo. */
    val league: LeagueView? = null,
    val eggs: List<EggView> = emptyList(),
)

@Serializable
data class UnlockedAchievement(val id: String, val name: String, val rewards: List<String>)
