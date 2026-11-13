package com.example.pokeapi.game

/** Version actuelle des sauvegardes (voir [upgradeSave]). */
const val SAVE_VERSION = 1

/**
 * Met à jour une sauvegarde d'une version précédente, sans rien perdre de la progression :
 * - la Ligue unique d'avant les régions devient la Ligue de Kanto ;
 * - la pension unique devient la pension de Kanto ;
 * - chaque Pokémon reçoit sa région d'origine, déduite de son espèce (Kanto : n° 1 à 151, Johto : 152 à 251).
 * Sans effet sur une sauvegarde déjà à jour.
 */
fun upgradeSave(s: GameState): GameState {
    if (s.saveVersion >= SAVE_VERSION) return s
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
        saveVersion = SAVE_VERSION,
    )
}
