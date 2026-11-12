package com.example.pokeapi.game

import kotlinx.serialization.Serializable

/** Attaque utilisable en combat (attaques offensives apprises par niveau). */
@Serializable
data class FighterMove(
    val name: String,
    val nameFr: String?,
    val type: String,
    /** physical ou special */
    val damageClass: String,
    val power: Int,
    /** null = ne rate jamais */
    val accuracy: Int?,
    val priority: Int = 0,
)

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
    val moves: List<FighterMove>,
    /** Multiplicateur de dégâts reçus par type attaquant. */
    val defenses: Map<String, Double>,
) {
    val displayName: String get() = nameFr ?: name.replaceFirstChar(Char::uppercase)
    val maxHp: Int get() = GameRules.stats(this).hp
    val fainted: Boolean get() = hp <= 0
}

@Serializable
enum class BattleKind { WILD, GYM }

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
) {
    val enemy: Fighter get() = enemies[enemyIndex]
}

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
)
