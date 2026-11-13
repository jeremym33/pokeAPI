package com.example.pokeapi.game

import kotlinx.serialization.Serializable

/** Ce qui compte pour les succès : progression de la partie. */
data class AchievementContext(
    /** Combats gagnés (Pokémon sauvages et arènes). */
    val victories: Int,
    val badges: Int,
    /** Espèces de Kanto rejointes / vues (rejointes comprises). */
    val caught: Int,
    val seen: Int,
    /** Espèces de Kanto rejointes, par type. */
    val caughtByType: Map<String, Int>,
    /** Victoires contre le Maître de la Ligue. */
    val championWins: Int = 0,
)

/**
 * Succès : un objectif de la partie, qui donne des objets d'évolution (seule façon d'en obtenir).
 * [rewards] : identifiants des objets de [GameRules.ITEMS].
 */
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val target: Int,
    val rewards: List<String>,
    val progress: (AchievementContext) -> Int,
)

@Serializable
data class AchievementView(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val progress: Int,
    val target: Int,
    val done: Boolean,
    /** Noms français des objets reçus. */
    val rewards: List<String>,
)

@Serializable
data class AchievementsView(val done: Int, val total: Int, val achievements: List<AchievementView>)

object Achievements {
    private const val FIRE = "fire-stone"
    private const val WATER = "water-stone"
    private const val THUNDER = "thunder-stone"
    private const val LEAF = "leaf-stone"
    private const val MOON = "moon-stone"
    private const val LINK = "link-cable"

    private fun victories(id: String, name: String, target: Int, vararg rewards: String) = Achievement(
        id, name, "Gagner $target combat${if (target > 1) "s" else ""}.", "Combats", target, rewards.toList(),
    ) { it.victories }

    private fun badges(id: String, name: String, target: Int, vararg rewards: String) = Achievement(
        id, name, "Obtenir $target badge${if (target > 1) "s" else ""} d'arène.", "Arènes", target, rewards.toList(),
    ) { it.badges }

    private fun caught(id: String, name: String, target: Int, vararg rewards: String) = Achievement(
        id, name, "Faire rejoindre $target espèces différentes de Kanto.", "Motisdex", target, rewards.toList(),
    ) { it.caught }

    private fun seen(id: String, name: String, target: Int, vararg rewards: String) = Achievement(
        id, name, "Voir $target espèces différentes de Kanto.", "Motisdex", target, rewards.toList(),
    ) { it.seen }

    /** Pokémon d'un ou plusieurs types rejoints : la pierre qui fait évoluer ce genre de Pokémon. */
    private fun types(id: String, name: String, label: String, types: Set<String>, target: Int, vararg rewards: String) = Achievement(
        id, name, "Faire rejoindre $target espèces de type $label.", "Types", target, rewards.toList(),
    ) { ctx -> types.sumOf { ctx.caughtByType[it] ?: 0 } }

    val ALL: List<Achievement> = listOf(
        victories("wins-1", "Premier combat", 1, LEAF),
        victories("wins-10", "Combattant", 10, WATER),
        victories("wins-50", "Vétéran", 50, THUNDER),
        victories("wins-100", "Infatigable", 100, LINK, FIRE),

        badges("badges-1", "Premier badge", 1, MOON),
        badges("badges-2", "Deux badges", 2, FIRE),
        badges("badges-4", "Mi-parcours", 4, LINK),
        badges("badges-6", "Six badges", 6, WATER, THUNDER),
        badges("badges-8", "Tous les badges", 8, FIRE, WATER, THUNDER, LEAF, MOON, LINK),

        caught("caught-10", "Petite équipe", 10, MOON),
        caught("caught-30", "Collectionneur", 30, LINK),
        caught("caught-60", "Grand collectionneur", 60, WATER, LEAF),
        caught("caught-100", "Expert du Motisdex", 100, LINK, FIRE, THUNDER),
        seen("seen-25", "Observateur", 25, THUNDER),
        seen("seen-75", "Explorateur", 75, LEAF, MOON),

        Achievement("league", "Maître de la Ligue", "Battre le Conseil de la Ligue puis son Maître.", "Ligue", 1, listOf(LINK, MOON, LEAF)) { it.championWins },

        types("type-fire", "Dresseur Feu", "Feu", setOf("fire"), 2, FIRE),
        types("type-water", "Dresseur Eau", "Eau", setOf("water"), 3, WATER),
        types("type-electric", "Dresseur Électrik", "Électrik", setOf("electric"), 2, THUNDER),
        types("type-grass", "Dresseur Plante", "Plante", setOf("grass"), 3, LEAF),
        types("type-moon", "Sous la lune", "Poison ou Fée", setOf("poison", "fairy"), 3, MOON),
        types("type-link", "Esprits forts", "Psy, Combat, Roche ou Spectre", setOf("psychic", "fighting", "rock", "ghost"), 3, LINK),
    )

    /** Succès atteints mais pas encore débloqués dans [done]. */
    fun newlyCompleted(ctx: AchievementContext, done: Set<String>): List<Achievement> =
        ALL.filter { it.id !in done && it.progress(ctx) >= it.target }

    fun rewardNames(a: Achievement): List<String> = a.rewards.map { r -> GameRules.ITEMS.single { it.item == r }.nameFr }

    fun view(ctx: AchievementContext, done: Set<String>): AchievementsView {
        val list = ALL.map { a ->
            AchievementView(
                id = a.id, name = a.name, description = a.description, category = a.category,
                progress = a.progress(ctx).coerceAtMost(a.target), target = a.target, done = a.id in done,
                rewards = rewardNames(a),
            )
        }
        return AchievementsView(done = list.count { it.done }, total = list.size, achievements = list)
    }
}
