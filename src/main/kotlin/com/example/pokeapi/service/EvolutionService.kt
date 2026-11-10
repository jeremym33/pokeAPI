package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiChainLink
import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.PokeApiEvolutionChain
import com.example.pokeapi.client.pokeApiJson
import com.example.pokeapi.client.resourceId
import com.example.pokeapi.model.EvolutionChain
import com.example.pokeapi.model.EvolutionNode
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.model.PokemonSummary
import com.example.pokeapi.repository.EvolutionRepository
import com.example.pokeapi.repository.PokemonRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * Arbre d'évolution d'un Pokémon : la chaîne PokéAPI est mise en cache en base, les membres de la famille
 * absents de la base sont récupérés, et les conditions sont décrites en français.
 */
class EvolutionService(
    private val pokemonService: PokemonService,
    private val pokemonRepository: PokemonRepository,
    private val evolutionRepository: EvolutionRepository,
    private val pokeApi: PokeApiClient,
) {
    private val log = LoggerFactory.getLogger(EvolutionService::class.java)
    private val fetchSemaphore = Semaphore(6)

    suspend fun evolutions(idOrName: String): EvolutionChain? {
        val pokemon = pokemonService.getPokemon(idOrName) ?: return null

        var chainId = pokemonRepository.evolutionChainId(pokemon.id)
        if (chainId == null) {
            // Pokémon importé avant la gestion des évolutions : on récupère à nouveau son espèce.
            pokemonService.getPokemon(pokemon.id.toString(), refresh = true)
            chainId = pokemonRepository.evolutionChainId(pokemon.id)
        }
        val chain = chainId?.let { loadChain(it) } ?: return EvolutionChain(null, singleNode(pokemon))

        val members = loadMembers(speciesIds(chain.chain))
        val translator = loadTranslator(chain.chain)
        return EvolutionChain(chain.id, toNode(chain.chain, members, translator))
    }

    private suspend fun loadChain(id: Int): PokeApiEvolutionChain? {
        evolutionRepository.findChainJson(id)?.let {
            return pokeApiJson.decodeFromString(PokeApiEvolutionChain.serializer(), it)
        }
        val chain = pokeApi.getEvolutionChain(id) ?: return null
        evolutionRepository.saveChainJson(id, pokeApiJson.encodeToString(PokeApiEvolutionChain.serializer(), chain))
        return chain
    }

    /** Résumés des membres de la famille ; ceux absents de la base sont récupérés sur PokéAPI. */
    private suspend fun loadMembers(ids: List<Int>): Map<Int, PokemonSummary> = coroutineScope {
        val known = pokemonRepository.summaries(ids).associateBy { it.id }
        val missing = ids.filterNot { it in known }
        missing.map { id ->
            async {
                fetchSemaphore.withPermit {
                    runCatching { pokemonService.getPokemon(id.toString()) }
                        .onFailure { log.warn("Membre d'évolution {} indisponible : {}", id, it.message) }
                }
            }
        }.awaitAll()
        if (missing.isEmpty()) known else pokemonRepository.summaries(ids).associateBy { it.id }
    }

    /** Charge (ou récupère puis met en cache) les noms français utilisés par les conditions de la chaîne. */
    private suspend fun loadTranslator(root: PokeApiChainLink): Translator = coroutineScope {
        val needed = slugsToTranslate(allDetails(root))
        val names = mutableMapOf<Pair<TranslationKind, String>, String?>()
        for ((kind, pairs) in needed.groupBy { it.first }) {
            val slugs = pairs.map { it.second }
            val cached = evolutionRepository.translations(kind.path, slugs)
            cached.forEach { (slug, fr) -> names[kind to slug] = fr }
            slugs.filterNot { it in cached }.map { slug ->
                async {
                    fetchSemaphore.withPermit {
                        val fr = runCatching {
                            pokeApi.getNames(kind.path, slug)?.names?.firstOrNull { it.language.name == "fr" }?.name
                        }.onFailure { log.warn("Traduction {} '{}' indisponible : {}", kind.path, slug, it.message) }
                        // Une erreur réseau n'est pas mise en cache, une absence de traduction oui.
                        fr.onSuccess { evolutionRepository.saveTranslation(kind.path, slug, it) }
                        slug to fr.getOrNull()
                    }
                }
            }.awaitAll().forEach { (slug, fr) -> names[kind to slug] = fr }
        }
        Translator { kind, slug -> names[kind to slug] ?: prettySlug(slug) }
    }

    private fun toNode(link: PokeApiChainLink, members: Map<Int, PokemonSummary>, t: Translator): EvolutionNode {
        val id = resourceId(link.species.url) ?: 0
        return EvolutionNode(
            speciesId = id,
            name = link.species.name,
            pokemon = members[id],
            isBaby = link.isBaby,
            conditions = describeEvolutions(link.evolutionDetails, t),
            evolvesTo = link.evolvesTo.map { toNode(it, members, t) },
        )
    }

    private fun singleNode(p: Pokemon) = EvolutionNode(
        speciesId = p.id,
        name = p.name,
        pokemon = PokemonSummary(
            p.id, p.speciesId, p.regionalForm, p.name, p.nameFr, p.generation, p.region, p.types, p.statTotal,
            p.spriteUrl, p.artworkUrl,
        ),
        isBaby = false,
        conditions = emptyList(),
        evolvesTo = emptyList(),
    )

    private fun speciesIds(link: PokeApiChainLink): List<Int> =
        listOfNotNull(resourceId(link.species.url)) + link.evolvesTo.flatMap { speciesIds(it) }

    private fun allDetails(link: PokeApiChainLink): List<com.example.pokeapi.client.PokeApiEvolutionDetail> =
        link.evolutionDetails.filter { it.isDefault }.ifEmpty { link.evolutionDetails } +
            link.evolvesTo.flatMap { allDetails(it) }
}
