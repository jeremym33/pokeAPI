package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.model.LEARN_METHOD_LABELS
import com.example.pokeapi.model.LearnedMove
import com.example.pokeapi.model.PokemonMoves
import com.example.pokeapi.model.VersionGroup
import com.example.pokeapi.model.VersionGroupInfo
import com.example.pokeapi.model.StoredLearnset
import com.example.pokeapi.model.latestVersionGroup
import com.example.pokeapi.model.learnMethodLabel
import com.example.pokeapi.repository.MoveInfo
import com.example.pokeapi.repository.MoveRepository
import com.example.pokeapi.repository.PokemonRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/** Attaques apprises par un Pokémon dans le jeu le plus récent ; le détail des attaques est mis en cache en base. */
class MoveService(
    private val pokemonService: PokemonService,
    private val pokemonRepository: PokemonRepository,
    private val moveRepository: MoveRepository,
    private val pokeApi: PokeApiClient,
    concurrency: Int = 8,
) {
    private val log = LoggerFactory.getLogger(MoveService::class.java)
    private val semaphore = Semaphore(concurrency.coerceAtLeast(1))

    suspend fun moves(idOrName: String): PokemonMoves? {
        val pokemon = pokemonService.getPokemon(idOrName) ?: return null

        val learnset = pokemonRepository.learnset(pokemon.id)
            ?: run {
                // Pokémon importé avant la gestion des attaques : on le récupère à nouveau.
                pokemonService.getPokemon(pokemon.id.toString(), refresh = true)
                pokemonRepository.learnset(pokemon.id)
            }
        val selected = learnset?.let { latestVersionGroup(it.versionGroups.keys) }
            ?: return PokemonMoves(pokemon.id, null, emptyList())
        val entries = learnset.versionGroups.getValue(selected)

        // Bases remplies avant ce changement : on ne garde que la version la plus récente.
        if (learnset.versionGroups.size > 1) {
            pokemonRepository.saveLearnset(pokemon.id, StoredLearnset(mapOf(selected to entries)))
        }
        val details = loadMoves(entries.map { it.m }.distinct())

        val methodOrder = LEARN_METHOD_LABELS.keys.toList()
        val moves = entries.map { e ->
            val info = details[e.m]
            LearnedMove(
                name = e.m,
                nameFr = info?.nameFr,
                type = info?.type,
                damageClass = info?.damageClass,
                power = info?.power,
                accuracy = info?.accuracy,
                pp = info?.pp,
                priority = info?.priority ?: 0,
                description = info?.descriptionFr,
                ailment = info?.ailment,
                ailmentChance = info?.ailmentChance ?: 0,
                method = e.k,
                methodLabel = learnMethodLabel(e.k),
                level = e.l.takeIf { e.k == "level-up" },
            )
        }.sortedWith(
            compareBy<LearnedMove>(
                { methodOrder.indexOf(it.method).let { i -> if (i < 0) methodOrder.size else i } },
                { it.level ?: 0 },
                { it.nameFr ?: it.name },
            )
        )

        return PokemonMoves(pokemon.id, VersionGroup(selected, VersionGroupInfo.label(selected)), moves)
    }

    /** Détails des attaques : depuis la base, sinon PokéAPI (puis mise en cache). */
    private suspend fun loadMoves(names: List<String>): Map<String, MoveInfo> = coroutineScope {
        // Les attaques mises en cache avant la gestion des statuts sont récupérées à nouveau.
        val cached = moveRepository.find(names).filterValues { it.metaLoaded }
        val fetched = names.filterNot { it in cached }.map { name ->
            async {
                semaphore.withPermit {
                    runCatching { pokeApi.getMove(name)?.let { moveRepository.save(it) } }
                        .onFailure { log.warn("Attaque '{}' indisponible : {}", name, it.message) }
                        .getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
        cached + fetched.associateBy { it.name }
    }
}
