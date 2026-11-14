package com.example.pokeapi.game

/** Version actuelle des sauvegardes (voir [upgradeSave]). */
const val SAVE_VERSION = 2

/**
 * Version 2 : Jadielle n'apparaît plus qu'une fois dans le parcours. L'ancienne étape 43 (Jadielle avec son arène,
 * après la Route 21) est fusionnée avec l'étape 2 (Jadielle au début) ; les étapes suivantes reculent d'une place.
 */
private const val OLD_VIRIDIAN_GYM = 43
private const val VIRIDIAN = 2

/** Nouveau numéro d'une étape d'une sauvegarde de version 1 ou moins. */
internal fun remapStepV2(step: Int): Int = when {
    step == OLD_VIRIDIAN_GYM -> VIRIDIAN
    step > OLD_VIRIDIAN_GYM -> step - 1
    else -> step
}

/**
 * Met à jour une sauvegarde d'une version précédente, sans rien perdre de la progression :
 * - version 1 : la Ligue unique d'avant les régions devient la Ligue de Kanto ; la pension unique devient la
 *   pension de Kanto ; chaque Pokémon reçoit sa région d'origine, déduite de son espèce ;
 * - version 2 : les numéros d'étapes suivent le parcours avec une seule Jadielle ([remapStepV2]).
 * Sans effet sur une sauvegarde déjà à jour.
 */
fun upgradeSave(s: GameState): GameState {
    if (s.saveVersion >= SAVE_VERSION) return s
    var u = s
    if (u.saveVersion < 1) u = upgradeV1(u)
    if (u.saveVersion < 2) u = upgradeV2(u)
    return u.copy(saveVersion = SAVE_VERSION)
}

private fun upgradeV1(s: GameState): GameState {
    var u = s
    if (KANTO !in u.leagues && (u.leagueStage != 0 || u.leagueTypes.isNotEmpty() || u.championWins != 0)) {
        u = u.copy(leagues = u.leagues + (KANTO to LeagueProgress(u.leagueStage, u.leagueTypes, u.championWins)))
    }
    u = u.copy(leagueStage = 0, leagueTypes = emptyList(), championWins = 0)
    if (KANTO !in u.dayCares && u.dayCare != DayCare()) u = u.copy(dayCares = u.dayCares + (KANTO to u.dayCare))
    u = u.copy(dayCare = DayCare())
    fun Fighter.withOrigin() = if (origin != null) this else copy(origin = GameRules.speciesRegion(speciesId))
    return u.copy(
        team = u.team.map { it.withOrigin() },
        box = u.box.map { it.withOrigin() },
        training = u.training.mapValues { (_, t) -> t.copy(fighter = t.fighter.withOrigin()) },
        dayCares = u.dayCares.mapValues { (_, d) -> d.copy(pokemon = d.pokemon.map { it.withOrigin() }) },
        saveVersion = 1,
    )
}

private fun upgradeV2(s: GameState): GameState {
    val r = ::remapStepV2
    fun <V> Map<Int, V>.remapped(merge: (V, V) -> V): Map<Int, V> =
        entries.fold(mutableMapOf<Int, V>()) { acc, (k, v) -> acc.also { m -> m[r(k)] = m[r(k)]?.let { merge(it, v) } ?: v } }
    return s.copy(
        stepIndex = r(s.stepIndex),
        lastCenter = r(s.lastCenter),
        badges = s.badges.map(r).toSet(),
        clearedSteps = s.clearedSteps.map(r).toSet(),
        wins = s.wins.remapped(Int::plus),
        boostCounters = s.boostCounters.remapped { a, b -> maxOf(a, b) },
        // L'arène de Jadielle (ancienne étape 43) garde son type ; l'étape 2 n'en avait pas
        gymTypes = s.gymTypes.remapped { a, _ -> a },
        // Entraînement : seulement sur des lieux (jamais sur une ville), donc pas de collision
        training = s.training.remapped { a, _ -> a },
        battle = s.battle?.let { it.copy(step = r(it.step)) },
        saveVersion = 2,
    )
}
