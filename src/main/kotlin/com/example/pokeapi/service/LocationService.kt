package com.example.pokeapi.service

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.PokeApiLocationArea
import com.example.pokeapi.client.pokeApiJson
import com.example.pokeapi.client.resourceId
import com.example.pokeapi.db.LocationTable
import com.example.pokeapi.db.RegionCacheTable
import com.example.pokeapi.db.VersionTable
import com.example.pokeapi.model.AreaEncounters
import com.example.pokeapi.model.Encounter
import com.example.pokeapi.model.EncounterMethod
import com.example.pokeapi.model.GameVersion
import com.example.pokeapi.model.LocationCategory
import com.example.pokeapi.model.LocationDetail
import com.example.pokeapi.model.LocationSummary
import com.example.pokeapi.model.Region
import com.example.pokeapi.model.RegionLocations
import com.example.pokeapi.model.VersionGroupInfo
import com.example.pokeapi.repository.EvolutionRepository
import com.example.pokeapi.repository.PokemonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.Instant

/* Format de stockage des rencontres d'un lieu (sans traductions, ajoutées à la lecture). */
@Serializable
data class StoredArea(val name: String, val label: String?, val encounters: List<StoredEncounter>)

@Serializable
data class StoredEncounter(val version: String, val pokemonId: Int, val pokemon: String, val methods: List<StoredMethod>)

@Serializable
data class StoredMethod(val method: String, val minLevel: Int, val maxLevel: Int, val chance: Int)

private val NATURE_WORDS = listOf(
    "cave", "cavern", "tunnel", "mt-", "mount", "forest", "woods", "desert", "lake", "falls", "meadow", "hill",
    "trail", "path", "canyon", "marsh", "swamp", "island", "bay", "sea", "beach", "ocean", "river", "pond",
    "garden", "field", "valley", "glacier", "grotto", "chamber", "volcano", "jungle", "spring", "wetland",
)
private val CITY_WORDS = listOf("city", "town", "village")

/** Catégorie d'un lieu d'après son nom PokéAPI (« kanto-route-1 », « pallet-town », « mt-moon »). */
fun categorize(name: String): Pair<LocationCategory, Int?> {
    val parts = name.split('-')
    val routeIndex = parts.indexOf("route")
    if (routeIndex >= 0) {
        return LocationCategory.ROUTE to parts.drop(routeIndex + 1).firstNotNullOfOrNull { it.toIntOrNull() }
    }
    return when {
        CITY_WORDS.any { it in parts } -> LocationCategory.CITY to null
        // Nom en un seul mot : ville (Galar, Paldea… n'ont pas de suffixe « -city »)
        parts.size == 1 -> LocationCategory.CITY to null
        NATURE_WORDS.any { w -> if (w.endsWith("-")) name.startsWith(w) || "-$w" in name else w in parts } ->
            LocationCategory.NATURE to null
        else -> LocationCategory.OTHER to null
    }
}

/**
 * Régions et lieux : liste des lieux d'une région (avec noms français) et Pokémon rencontrés dans un lieu,
 * jeu par jeu. Tout est mis en cache en base à la première consultation.
 */
class LocationService(
    private val database: Database,
    private val pokemonRepository: PokemonRepository,
    private val translations: EvolutionRepository,
    private val pokeApi: PokeApiClient,
) {
    private val log = LoggerFactory.getLogger(LocationService::class.java)
    private val semaphore = Semaphore(8)
    private val storedAreas = ListSerializer(StoredArea.serializer())

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    /** Lieux d'une région, ou null si la région n'existe pas (ou n'est pas une région du Pokédex). */
    suspend fun regionLocations(regionName: String): RegionLocations? {
        val region = Region.ofName(regionName) ?: return null
        if (!isRegionCached(region.apiName)) fetchRegion(region.apiName) ?: return null

        return dbQuery {
            val nameFr = RegionCacheTable.selectAll().where { RegionCacheTable.name eq region.apiName }
                .single()[RegionCacheTable.nameFr]
            val locations = LocationTable.selectAll().where { LocationTable.region eq region.apiName }
                .orderBy(LocationTable.position)
                .map {
                    val name = it[LocationTable.name]
                    val (category, number) = categorize(name)
                    LocationSummary(name, it[LocationTable.nameFr], category, number, it[LocationTable.areas].isNotBlank())
                }
                // Routes dans l'ordre de leur numéro, autres lieux dans l'ordre PokéAPI
                .sortedWith(compareBy({ it.category.ordinal }, { if (it.category == LocationCategory.ROUTE) it.routeNumber ?: Int.MAX_VALUE else 0 }))
            RegionLocations(region.apiName, nameFr, disambiguate(locations))
        }
    }

    /**
     * Lieux de même nom français (trois « Ekaeka », deux « Route Victoire ») : on précise ce qui les distingue
     * dans leur nom PokéAPI (« Beachfront », « 2 »).
     */
    private fun disambiguate(locations: List<LocationSummary>): List<LocationSummary> {
        val groups = locations.groupBy { it.nameFr ?: it.name }.filterValues { it.size > 1 }
        if (groups.isEmpty()) return locations
        val details = groups.values.flatMap { same ->
            val tokens = same.map { it.name.split('-').filter { t -> t.isNotBlank() } }
            val common = tokens.reduce { acc, t -> acc.intersect(t.toSet()).toList() }.toSet()
            same.zip(tokens).map { (loc, t) -> loc.name to t.filterNot { it in common }.joinToString(" ").replaceFirstChar(Char::uppercase) }
        }.toMap()
        return locations.map { loc -> details[loc.name]?.takeIf { it.isNotBlank() }?.let { loc.copy(detail = it) } ?: loc }
    }

    private suspend fun isRegionCached(name: String): Boolean = dbQuery {
        val expected = RegionCacheTable.selectAll().where { RegionCacheTable.name eq name }
            .singleOrNull()?.get(RegionCacheTable.locationCount) ?: return@dbQuery false
        LocationTable.selectAll().where { LocationTable.region eq name }.count() >= expected
    }

    /** Récupère la région et chacun de ses lieux (nom français, zones), puis les met en cache. */
    private suspend fun fetchRegion(name: String): Unit? = coroutineScope {
        val region = pokeApi.getRegion(name) ?: return@coroutineScope null
        val locations = region.locations.mapIndexed { index, loc ->
            async {
                semaphore.withPermit {
                    runCatching { pokeApi.getLocation(loc.name) }
                        .onFailure { log.warn("Lieu {} indisponible : {}", loc.name, it.message) }
                        .getOrNull()
                        ?.let { Triple(index, loc.name, it) }
                }
            }
        }.awaitAll().filterNotNull()

        dbQuery {
            for ((index, locName, loc) in locations) {
                LocationTable.upsert {
                    it[LocationTable.name] = locName
                    it[LocationTable.region] = name
                    it[position] = index
                    it[nameFr] = loc.names.firstOrNull { n -> n.language.name == "fr" }?.name
                    it[areas] = loc.areas.joinToString(",") { a -> a.name }
                    it[fetchedAt] = Instant.now()
                }
            }
            RegionCacheTable.upsert {
                it[RegionCacheTable.name] = name
                it[nameFr] = region.names.firstOrNull { n -> n.language.name == "fr" }?.name
                // Les lieux en échec seront redemandés à la prochaine consultation.
                it[locationCount] = region.locations.size
                it[fetchedAt] = Instant.now()
            }
        }
        Unit
    }

    /** Pokémon rencontrés dans un lieu, zone par zone et jeu par jeu ; null si le lieu est inconnu. */
    suspend fun location(name: String): LocationDetail? {
        val row = dbQuery {
            LocationTable.selectAll().where { LocationTable.name eq name }.singleOrNull()
        } ?: run {
            // Lieu pas encore en cache : on le cherche directement sur PokéAPI.
            val loc = pokeApi.getLocation(name) ?: return null
            val region = loc.region?.name?.takeIf { Region.ofName(it) != null } ?: return null
            dbQuery {
                LocationTable.upsert {
                    it[LocationTable.name] = loc.name
                    it[LocationTable.region] = region
                    it[position] = 10_000
                    it[nameFr] = loc.names.firstOrNull { n -> n.language.name == "fr" }?.name
                    it[areas] = loc.areas.joinToString(",") { a -> a.name }
                    it[fetchedAt] = Instant.now()
                }
                LocationTable.selectAll().where { LocationTable.name eq loc.name }.single()
            }
        }

        val areas = row[LocationTable.encounters]?.let { pokeApiJson.decodeFromString(storedAreas, it) }
            ?: fetchEncounters(row[LocationTable.name], row[LocationTable.areas])

        val versionNames = areas.flatMap { a -> a.encounters.map { it.version } }.distinct()
        val versions = versionInfo(versionNames)
        val methodNames = loadMethodNames(areas.flatMap { a -> a.encounters.flatMap { e -> e.methods.map { it.method } } }.toSet())
        val pokemon = pokemonRepository.summaries(areas.flatMap { a -> a.encounters.map { it.pokemonId } }.distinct())
            .associateBy { it.id }

        return LocationDetail(
            name = row[LocationTable.name],
            nameFr = row[LocationTable.nameFr],
            region = row[LocationTable.region],
            versions = versionNames
                .sortedWith(compareByDescending<String> { VersionGroupInfo.order(versions[it]?.second ?: "") }.thenBy { it })
                .map { GameVersion(it, versionLabel(it, versions[it]?.first)) },
            areas = areas.map { area ->
                AreaEncounters(
                    name = area.name,
                    label = area.label,
                    encounters = area.encounters.map { e ->
                        val p = pokemon[e.pokemonId]
                        Encounter(
                            version = e.version,
                            pokemonId = e.pokemonId,
                            name = e.pokemon,
                            nameFr = p?.nameFr,
                            speciesId = p?.speciesId,
                            spriteUrl = p?.spriteUrl,
                            types = p?.types.orEmpty(),
                            methods = e.methods.map { m ->
                                EncounterMethod(m.method, methodNames[m.method] ?: prettySlug(m.method), m.minLevel, m.maxLevel, m.chance)
                            },
                        )
                    }.sortedWith(compareBy({ it.speciesId ?: it.pokemonId }, { it.pokemonId })),
                )
            },
        )
    }

    /** Nom français d'un jeu ; les versions japonaises d'origine (« red-japan ») portent le même nom que les autres. */
    private fun versionLabel(name: String, nameFr: String?): String {
        val label = nameFr ?: prettySlug(name.removeSuffix("-japan"))
        return if (name.endsWith("-japan")) "$label (Japon)" else label
    }

    /** Récupère les zones d'un lieu et regroupe leurs rencontres par jeu, Pokémon et méthode. */
    private suspend fun fetchEncounters(location: String, areaNames: String): List<StoredArea> = coroutineScope {
        val names = areaNames.split(',').filter { it.isNotBlank() }
        val fetched = names.map { area ->
            async { semaphore.withPermit { pokeApi.getLocationArea(area) } }
        }.awaitAll()
        if (fetched.any { it == null } && names.isNotEmpty()) log.warn("Zones manquantes pour {}", location)

        val areas = fetched.filterNotNull().map { toStoredArea(location, it) }
        dbQuery {
            LocationTable.update({ LocationTable.name eq location }) {
                it[encounters] = pokeApiJson.encodeToString(storedAreas, areas)
            }
        }
        areas
    }

    private fun toStoredArea(location: String, area: PokeApiLocationArea): StoredArea {
        val encounters = area.pokemonEncounters.flatMap { pe ->
            val pokemonId = resourceId(pe.pokemon.url) ?: return@flatMap emptyList()
            pe.versionDetails.map { vd ->
                StoredEncounter(
                    version = vd.version.name,
                    pokemonId = pokemonId,
                    pokemon = pe.pokemon.name,
                    methods = vd.encounterDetails.groupBy { it.method.name }.map { (method, details) ->
                        StoredMethod(
                            method = method,
                            minLevel = details.minOf { it.minLevel },
                            maxLevel = details.maxOf { it.maxLevel },
                            chance = details.sumOf { it.chance }.coerceAtMost(100),
                        )
                    },
                )
            }
        }
        // Zone principale (« kanto-route-1-area ») : pas de libellé ; autres : nom français ou nom PokéAPI lisible.
        val suffix = area.name.removePrefix("$location-").removeSuffix("-area").takeIf { it != area.name && it != "area" }
        val label = area.names.firstOrNull { it.language.name == "fr" }?.name?.takeIf { it.isNotBlank() }
            ?: suffix?.let(::prettySlug)
        return StoredArea(area.name, if (suffix == null) null else label, encounters)
    }

    /** Nom français et groupe de version de chaque jeu (récupérés puis mis en cache). */
    private suspend fun versionInfo(names: List<String>): Map<String, Pair<String?, String?>> = coroutineScope {
        val cached = dbQuery {
            if (names.isEmpty()) emptyMap()
            else VersionTable.selectAll().where { VersionTable.name inList names }
                .associate { it[VersionTable.name] to (it[VersionTable.nameFr] to it[VersionTable.versionGroup]) }
        }
        val fetched = names.filterNot { it in cached }.map { name ->
            async {
                semaphore.withPermit {
                    runCatching { pokeApi.getVersion(name) }.getOrNull()?.let { v ->
                        val info = v.names.firstOrNull { it.language.name == "fr" }?.name to v.versionGroup?.name
                        dbQuery {
                            VersionTable.upsert {
                                it[VersionTable.name] = name
                                it[nameFr] = info.first
                                it[versionGroup] = info.second
                            }
                        }
                        name to info
                    }
                }
            }
        }.awaitAll().filterNotNull()
        cached + fetched
    }

    /** Noms français des méthodes de rencontre (« En marchant dans les herbes hautes… »). */
    private suspend fun loadMethodNames(methods: Set<String>): Map<String, String?> = coroutineScope {
        val cached = translations.translations("encounter-method", methods)
        val fetched = methods.filterNot { it in cached }.map { m ->
            async {
                semaphore.withPermit {
                    runCatching { pokeApi.getNames("encounter-method", m)?.names?.firstOrNull { it.language.name == "fr" }?.name }
                        .onSuccess { translations.saveTranslation("encounter-method", m, it) }
                        .getOrNull()
                        .let { m to it }
                }
            }
        }.awaitAll()
        cached + fetched
    }
}
