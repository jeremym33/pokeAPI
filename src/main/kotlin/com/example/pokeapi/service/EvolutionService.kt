package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiChainLink
import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.PokeApiEvolutionChain
import com.example.pokeapi.client.PokeApiEvolutionDetail
import com.example.pokeapi.client.pokeApiJson
import com.example.pokeapi.client.resourceId
import com.example.pokeapi.model.EvolutionChain
import com.example.pokeapi.model.EvolutionNode
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.model.PokemonSummary
import com.example.pokeapi.model.Region
import com.example.pokeapi.repository.EvolutionRepository
import com.example.pokeapi.repository.PokemonRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * Arbre d'évolution d'un Pokémon, forme par forme : la chaîne PokéAPI (par espèce) est mise en cache en base,
 * puis dépliée en formes (par défaut et régionales) grâce aux formes de départ et d'arrivée de chaque condition.
 * Les membres de la famille absents de la base sont récupérés et les conditions décrites en français.
 */
class EvolutionService(
    private val pokemonService: PokemonService,
    private val pokemonRepository: PokemonRepository,
    private val evolutionRepository: EvolutionRepository,
    private val pokeApi: PokeApiClient,
) {
    private val log = LoggerFactory.getLogger(EvolutionService::class.java)
    private val fetchSemaphore = Semaphore(6)

    /** Une forme de la famille : clé = nom PokéAPI de la forme (ou de l'espèce pour la forme par défaut). */
    private data class FormNode(val key: String, val species: PokeApiChainLink, val order: Int, val summary: PokemonSummary?)

    private data class Edge(val from: String, val to: String, val details: List<PokeApiEvolutionDetail>)

    suspend fun evolutions(idOrName: String): EvolutionChain? {
        val pokemon = pokemonService.getPokemon(idOrName) ?: return null

        var chainId = pokemonRepository.evolutionChainId(pokemon.id)
        if (chainId == null) {
            // Pokémon importé avant la gestion des évolutions : on récupère à nouveau son espèce.
            pokemonService.getPokemon(pokemon.id.toString(), refresh = true)
            chainId = pokemonRepository.evolutionChainId(pokemon.id)
        }
        val chain = chainId?.let { loadChain(it) } ?: return EvolutionChain(null, listOf(singleNode(pokemon)))

        val links = allLinks(chain.chain)
        val defaults = loadMembers(links.mapNotNull { resourceId(it.species.url) })
        val forms = loadRegionalForms(links)
        val translator = loadTranslator(chain.chain)
        return EvolutionChain(chain.id, buildRoots(links, defaults, forms, translator))
    }

    private suspend fun loadChain(id: Int): PokeApiEvolutionChain? {
        evolutionRepository.findChainJson(id)?.let {
            return pokeApiJson.decodeFromString(PokeApiEvolutionChain.serializer(), it)
        }
        val chain = pokeApi.getEvolutionChain(id) ?: return null
        evolutionRepository.saveChainJson(id, pokeApiJson.encodeToString(PokeApiEvolutionChain.serializer(), chain))
        return chain
    }

    /** Résumés des formes par défaut de la famille ; celles absentes de la base sont récupérées sur PokéAPI. */
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

    /**
     * Formes régionales de la famille : celles citées par les conditions (récupérées si besoin)
     * et celles déjà en base (formes sans évolution, comme Artikodin de Galar).
     */
    private suspend fun loadRegionalForms(links: List<PokeApiChainLink>): Map<String, PokemonSummary> = coroutineScope {
        val speciesByName = links.associateBy { it.species.name }
        val referenced = links.flatMap { child ->
            currentDetails(child.evolutionDetails).flatMap { d ->
                listOfNotNull(d.evolvedPokemonForm?.name?.let { it to child.species.name }) +
                    listOfNotNull(d.requiredPokemonForm?.name?.let { name -> parentOf(links, child)?.let { name to it.species.name } })
            }
        }.filter { (form, species) -> speciesByName[species] != null && Region.ofRegionalForm(species, form) != null }
            .map { it.first }.distinct()

        val speciesIds = links.mapNotNull { resourceId(it.species.url) }
        val inDb = pokemonRepository.regionalForms(speciesIds).associateBy { it.name }
        referenced.filterNot { it in inDb }.map { name ->
            async {
                fetchSemaphore.withPermit {
                    runCatching { pokemonService.getPokemon(name) }
                        .onFailure { log.warn("Forme régionale {} indisponible : {}", name, it.message) }
                }
            }
        }.awaitAll()
        if (referenced.all { it in inDb }) inDb else pokemonRepository.regionalForms(speciesIds).associateBy { it.name }
    }

    private fun buildRoots(
        links: List<PokeApiChainLink>,
        defaults: Map<Int, PokemonSummary>,
        forms: Map<String, PokemonSummary>,
        t: Translator,
    ): List<EvolutionNode> {
        // Formes de chaque espèce : par défaut, puis régionales (dans l'ordre des ids)
        val nodes = linkedMapOf<String, FormNode>()
        val defaultKey = mutableMapOf<String, String>()
        links.forEachIndexed { index, link ->
            val speciesName = link.species.name
            val id = resourceId(link.species.url)
            defaultKey[speciesName] = speciesName
            nodes[speciesName] = FormNode(speciesName, link, index, id?.let { defaults[it] })
            forms.values.filter { it.speciesId == id }.sortedBy { it.id }.forEach { form ->
                nodes[form.name] = FormNode(form.name, link, index, form)
            }
        }

        /** Forme désignée par une condition ; une forme inconnue ou par défaut renvoie la forme par défaut. */
        fun resolve(species: String, formName: String?): String =
            formName?.takeIf { it in nodes && Region.ofRegionalForm(species, it) != null } ?: defaultKey.getValue(species)

        val edges = links.flatMap { child ->
            val parent = parentOf(links, child) ?: return@flatMap emptyList()
            currentDetails(child.evolutionDetails)
                .groupBy {
                    resolve(parent.species.name, it.requiredPokemonForm?.name) to
                        resolve(child.species.name, it.evolvedPokemonForm?.name)
                }
                .map { (fromTo, details) -> Edge(fromTo.first, fromTo.second, details) }
        }
        val outgoing = edges.groupBy { it.from }
        val withParent = edges.map { it.to }.toSet()

        fun toNode(key: String, conditions: List<String>, visited: Set<String>): EvolutionNode {
            val node = nodes.getValue(key)
            val children = outgoing[key].orEmpty()
                .filter { it.to !in visited }
                .sortedWith(compareBy({ nodes.getValue(it.to).order }, { nodes.getValue(it.to).summary?.id ?: 0 }))
            return EvolutionNode(
                speciesId = resourceId(node.species.species.url) ?: 0,
                name = key,
                pokemon = node.summary,
                isBaby = node.species.isBaby,
                conditions = conditions,
                evolvesTo = children.map { edge ->
                    toNode(edge.to, edge.details.map { describeEvolution(it, t) }.distinct(), visited + key)
                },
            )
        }

        return nodes.keys.filter { it !in withParent }.map { toNode(it, emptyList(), emptySet()) }
    }

    /** Charge (ou récupère puis met en cache) les noms français utilisés par les conditions de la chaîne. */
    private suspend fun loadTranslator(root: PokeApiChainLink): Translator = coroutineScope {
        val needed = slugsToTranslate(allLinks(root).flatMap { currentDetails(it.evolutionDetails) })
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

    private fun singleNode(p: Pokemon) = EvolutionNode(
        speciesId = p.speciesId,
        name = p.name,
        pokemon = PokemonSummary(
            p.id, p.speciesId, p.regionalForm, p.name, p.nameFr, p.generation, p.region, p.types, p.statTotal,
            p.spriteUrl, p.artworkUrl,
        ),
        isBaby = false,
        conditions = emptyList(),
        evolvesTo = emptyList(),
    )

    /** Espèces de la chaîne, dans l'ordre (parcours en profondeur). */
    private fun allLinks(link: PokeApiChainLink): List<PokeApiChainLink> =
        listOf(link) + link.evolvesTo.flatMap { allLinks(it) }

    private fun parentOf(links: List<PokeApiChainLink>, child: PokeApiChainLink): PokeApiChainLink? =
        links.firstOrNull { parent -> parent.evolvesTo.any { it.species.name == child.species.name } }
}
