package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.model.ImportResult
import com.example.pokeapi.model.Page
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.model.PokemonSummary
import com.example.pokeapi.repository.PokemonRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.slf4j.LoggerFactory

/**
 * Logique « cache-aside » : on lit d'abord dans PostgreSQL, et en cas d'absence
 * on interroge pokeapi.co puis on enregistre le résultat.
 */
class PokemonService(
    private val repository: PokemonRepository,
    private val pokeApi: PokeApiClient,
    importConcurrency: Int = 8,
) {
    private val log = LoggerFactory.getLogger(PokemonService::class.java)
    private val importSemaphore = Semaphore(importConcurrency.coerceAtLeast(1))

    suspend fun getPokemon(idOrName: String, refresh: Boolean = false): Pokemon? {
        val key = idOrName.trim().lowercase()
        if (key.isEmpty()) return null
        if (!refresh) {
            repository.findByIdOrName(key)?.let { return it }
        }
        val remote = pokeApi.getPokemon(key) ?: return null
        try {
            repository.save(remote)
        } catch (e: ExposedSQLException) {
            // Une requête concurrente a pu enregistrer le même Pokémon entre-temps.
            log.debug("Enregistrement concurrent de {} : {}", remote.name, e.message)
        }
        return repository.findByIdOrName(remote.id.toString())
    }

    suspend fun list(limit: Int, offset: Int, type: String?, name: String?): Page<PokemonSummary> =
        repository.list(limit, offset, type, name)

    /** Importe une plage de Pokémon depuis pokeapi.co dans la base. */
    suspend fun import(limit: Int, offset: Int): ImportResult = coroutineScope {
        val names = pokeApi.listPokemon(limit, offset).results.map { it.name }
        val cached = repository.existingNames(names)
        val toFetch = names.filterNot { it in cached }

        val outcomes = toFetch.map { name ->
            async {
                importSemaphore.withPermit {
                    runCatching {
                        val dto = pokeApi.getPokemon(name) ?: error("introuvable")
                        repository.save(dto)
                    }.onFailure { log.warn("Échec de l'import de {} : {}", name, it.message) }
                        .isSuccess to name
                }
            }
        }.awaitAll()

        ImportResult(
            requested = names.size,
            imported = outcomes.count { it.first },
            alreadyCached = cached.size,
            failed = outcomes.filterNot { it.first }.map { it.second },
        )
    }
}
