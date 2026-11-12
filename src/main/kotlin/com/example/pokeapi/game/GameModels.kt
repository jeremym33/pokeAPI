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
    /** Statut (burn, poison, paralysis, sleep, freeze), conservé jusqu'au Centre Pokémon. */
    val status: String? = null,
    /** Tours de sommeil restants. */
    val sleepTurns: Int = 0,
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
    /** Pokémon sauvage vaincu qui veut rejoindre l'équipe : le joueur accepte ou non. */
    val recruit: Fighter? = null,
) {
    val enemy: Fighter get() = enemies[enemyIndex]
}

/** Réglages du joueur, sauvegardés avec la partie. */
@Serializable
data class GameSettings(
    /** En fin de combat sur une route, proposer d'enchaîner directement sur le Pokémon suivant. */
    val nextEncounterButton: Boolean = true,
    /** Ne proposer de rejoindre l'équipe qu'aux espèces pas encore obtenues. */
    val recruitOnlyNew: Boolean = false,
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
    /** Sac : objet -> quantité (pierres d'évolution, Câble Link), trouvés sur les Pokémon vaincus. */
    val items: Map<String, Int> = emptyMap(),
    /** Message de la dernière action hors combat (évolution par pierre…). */
    val lastEvent: String? = null,
    val settings: GameSettings = GameSettings(),
)
