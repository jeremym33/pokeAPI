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
)

@Serializable
data class DexView(val total: Int, val seen: Int, val caught: Int, val entries: List<DexEntry>)

@Serializable
data class GymView(val number: Int, val type: String?, val badge: Boolean)

@Serializable
data class StepView(
    val index: Int,
    val location: String,
    val nameFr: String?,
    val city: Boolean,
    val gym: GymView?,
    val wins: Int,
    /** Victoires nécessaires pour franchir l'étape (0 pour une ville). */
    val winsNeeded: Int,
    /** Pokémon sauvages disponibles ici (avec les badges actuels). */
    val hasEncounters: Boolean,
    val cleared: Boolean,
    val unlocked: Boolean,
)

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
)

@Serializable
data class UnlockedAchievement(val id: String, val name: String, val rewards: List<String>)
