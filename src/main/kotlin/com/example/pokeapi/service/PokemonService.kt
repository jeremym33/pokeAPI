package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.PokeApiPokemon
import com.example.pokeapi.client.PokeApiSpecies
import com.example.pokeapi.model.ImportResult
import com.example.pokeapi.model.Page
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.model.PokemonQuery
import com.example.pokeapi.model.PokemonSummary
import com.example.pokeapi.model.Region
import com.example.pokeapi.model.RegionCount
import com.example.pokeapi.model.TypeCount
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
            saveWithForms(remote)
        } catch (e: ExposedSQLException) {
            // Une requête concurrente a pu enregistrer le même Pokémon entre-temps.
            log.debug("Enregistrement concurrent de {} : {}", remote.name, e.message)
        }
        return repository.findByIdOrName(remote.id.toString())
    }

    /**
     * Enregistre un Pokémon. Une forme par défaut entraîne l'import de ses formes régionales
     * (Raichu → Raichu d'Alola) ; une forme régionale demandée directement est enregistrée comme telle.
     * Renvoie le nombre de formes régionales enregistrées en plus.
     */
    private suspend fun saveWithForms(dto: PokeApiPokemon): Int {
        val species = fetchSpecies(dto)
        if (!dto.isDefault) {
            saveForm(dto, species)
            return 0
        }
        repository.save(dto, species)
        if (species == null) return 0

        var saved = 0
        species.varieties
            .filter { !it.isDefault && Region.ofRegionalForm(species.name, it.pokemon.name) != null }
            .forEach { variety ->
                runCatching { pokeApi.getPokemon(variety.pokemon.name)?.let { saveForm(it, species); saved++ } }
                    .onFailure { log.warn("Forme régionale {} indisponible : {}", variety.pokemon.name, it.message) }
            }
        repository.markFormsChecked(dto.id)
        return saved
    }

    private suspend fun saveForm(dto: PokeApiPokemon, species: PokeApiSpecies?) {
        val region = species?.let { Region.ofRegionalForm(it.name, dto.name) }
        if (region == null) {
            // Autre forme (méga, Gigamax…) : enregistrée pour sa fiche, mais absente du Pokédex.
            repository.save(dto, species)
            return
        }
        val nameFr = runCatching {
            dto.forms.firstOrNull()?.let { pokeApi.getPokemonForm(it.name) }
                ?.names?.firstOrNull { it.language.name == "fr" }?.name
        }.getOrNull() ?: species.names.firstOrNull { it.language.name == "fr" }?.name?.let { "$it ${regionSuffixFr(region)}" }
        repository.save(dto, species, region, nameFr)
    }

    private fun regionSuffixFr(region: Region): String = when (region) {
        Region.ALOLA -> "d'Alola"
        Region.GALAR -> "de Galar"
        Region.HISUI -> "de Hisui"
        Region.PALDEA -> "de Paldéa"
        else -> "de ${region.apiName.replaceFirstChar(Char::uppercase)}"
    }

    suspend fun list(query: PokemonQuery): Page<PokemonSummary> = repository.list(query)

    suspend fun regions(): List<RegionCount> = repository.regions()

    suspend fun types(): List<TypeCount> = repository.types()

    /** L'espèce apporte la génération (donc la région) et le nom français ; son absence n'est pas bloquante. */
    private suspend fun fetchSpecies(pokemon: PokeApiPokemon): PokeApiSpecies? {
        val speciesName = pokemon.species?.name ?: pokemon.name
        return runCatching { pokeApi.getSpecies(speciesName) }
            .onFailure { log.warn("Espèce indisponible pour {} : {}", pokemon.name, it.message) }
            .getOrNull()
    }

    /** Importe une plage de Pokémon depuis pokeapi.co dans la base. */
    suspend fun import(limit: Int, offset: Int): ImportResult = coroutineScope {
        val names = pokeApi.listPokemon(limit, offset).results.map { it.name }
        val cached = repository.completeNames(names)
        val toFetch = names.filterNot { it in cached }

        val outcomes = toFetch.map { name ->
            async {
                importSemaphore.withPermit {
                    runCatching {
                        val dto = pokeApi.getPokemon(name) ?: error("introuvable")
                        saveWithForms(dto)
                    }.onFailure { log.warn("Échec de l'import de {} : {}", name, it.message) }
                        .let { result -> Triple(result.isSuccess, name, result.getOrDefault(0)) }
                }
            }
        }.awaitAll()

        ImportResult(
            requested = names.size,
            imported = outcomes.count { it.first },
            alreadyCached = cached.size,
            failed = outcomes.filterNot { it.first }.map { it.second },
            regionalForms = outcomes.sumOf { it.third },
        )
    }
}
