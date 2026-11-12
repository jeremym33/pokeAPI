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
    val fainted: Boolean,
    /** burn, poison, paralysis, sleep, freeze */
    val status: String?,
)

/** Pierre d'évolution : prix en boutique, quantité dans le sac et Pokémon sur lesquels elle a un effet. */
@Serializable
data class ItemView(val item: String, val name: String, val price: Int, val count: Int, val usableOn: List<String>)

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
    val money: Int = 0,
    /** Pierres d'évolution (prix, quantité dans le sac, Pokémon concernés). */
    val items: List<ItemView> = emptyList(),
    /** Boutique disponible (dans les villes). */
    val shopOpen: Boolean = false,
    val lastEvent: String? = null,
)
