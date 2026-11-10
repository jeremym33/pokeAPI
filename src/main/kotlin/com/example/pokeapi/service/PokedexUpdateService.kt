package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.model.PokedexUpdateStatus
import com.example.pokeapi.model.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant

private const val MAX_FAILED_NAMES = 50

/** Taille du Pokédex national si PokéAPI ne répond pas pour la compter. */
private const val DEFAULT_DEX_SIZE = 1025

/**
 * Mise à jour du Pokédex complet en arrière-plan (une seule à la fois), dont l'avancement est consultable
 * pendant qu'elle tourne.
 */
class PokedexUpdateService(
    private val scope: CoroutineScope,
    private val pokemonService: PokemonService,
    private val pokeApi: PokeApiClient,
    concurrency: Int = 8,
) {
    private val log = LoggerFactory.getLogger(PokedexUpdateService::class.java)
    private val semaphore = Semaphore(concurrency.coerceAtLeast(1))
    private val lock = Mutex()

    @Volatile
    private var status = PokedexUpdateStatus()
    private var job: Job? = null

    fun status(): PokedexUpdateStatus = status

    /**
     * Lance la mise à jour. [refreshAll] = true récupère à nouveau tous les Pokémon depuis PokéAPI,
     * sinon seuls les Pokémon absents ou incomplets sont importés.
     * Renvoie null si une mise à jour est déjà en cours.
     */
    suspend fun start(refreshAll: Boolean): PokedexUpdateStatus? = lock.withLock {
        if (status.state == UpdateState.RUNNING) return null
        status = PokedexUpdateStatus(
            state = UpdateState.RUNNING,
            mode = if (refreshAll) "all" else "missing",
            startedAt = Instant.now().toString(),
        )
        job = scope.launch { run(refreshAll) }
        status
    }

    /** Arrête la mise à jour en cours ; renvoie false s'il n'y en a pas. */
    suspend fun cancel(): Boolean {
        val running = lock.withLock { job?.takeIf { status.state == UpdateState.RUNNING } } ?: return false
        running.cancel()
        return true
    }

    private suspend fun run(refreshAll: Boolean) {
        try {
            val total = runCatching { pokeApi.speciesCount() }.getOrDefault(DEFAULT_DEX_SIZE)
            // Les formes par défaut des espèces sont en tête de liste, dans l'ordre du Pokédex national.
            val names = pokeApi.listPokemon(total, 0).results.map { it.name }
            val upToDate = if (refreshAll) emptySet() else pokemonService.completeNames(names)
            update { it.copy(total = names.size, processed = upToDate.size, alreadyUpToDate = upToDate.size) }

            coroutineScope {
                names.filterNot { it in upToDate }.map { name ->
                    async {
                        semaphore.withPermit {
                            val forms = pokemonService.importOne(name)
                            update {
                                if (forms == null) {
                                    it.copy(
                                        processed = it.processed + 1,
                                        failedCount = it.failedCount + 1,
                                        failed = (it.failed + name).take(MAX_FAILED_NAMES),
                                    )
                                } else {
                                    it.copy(processed = it.processed + 1, imported = it.imported + 1, regionalForms = it.regionalForms + forms)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            finish(UpdateState.COMPLETED)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(UpdateState.CANCELLED) }
            throw e
        } catch (e: Exception) {
            log.error("Échec de la mise à jour du Pokédex", e)
            finish(UpdateState.FAILED, e.message ?: "Erreur inconnue")
        }
    }

    private suspend fun update(change: (PokedexUpdateStatus) -> PokedexUpdateStatus) = lock.withLock {
        status = change(status)
    }

    private suspend fun finish(state: UpdateState, error: String? = null) = update {
        it.copy(state = state, finishedAt = Instant.now().toString(), error = error)
    }
}
