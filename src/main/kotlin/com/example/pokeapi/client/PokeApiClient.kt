package com.example.pokeapi.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class PokeApiException(message: String) : RuntimeException(message)

/** Client HTTP minimaliste pour https://pokeapi.co/api/v2 */
class PokeApiClient(
    private val httpClient: HttpClient,
    baseUrl: String = "https://pokeapi.co/api/v2",
) {
    private val baseUrl = baseUrl.trimEnd('/')

    /** Récupère un Pokémon par id ou par nom. Retourne null s'il n'existe pas. */
    suspend fun getPokemon(idOrName: String): PokeApiPokemon? {
        val response = httpClient.get("$baseUrl/pokemon/${idOrName.lowercase().encodeURLPathPart()}")
        return when {
            response.status == HttpStatusCode.NotFound -> null
            response.status.isSuccess() -> response.body()
            else -> throw PokeApiException("PokéAPI a répondu ${response.status} pour le Pokémon '$idOrName'")
        }
    }

    /** Espèce d'un Pokémon (génération, noms traduits). Retourne null si elle n'existe pas. */
    suspend fun getSpecies(idOrName: String): PokeApiSpecies? {
        val response = httpClient.get("$baseUrl/pokemon-species/${idOrName.lowercase().encodeURLPathPart()}")
        return when {
            response.status == HttpStatusCode.NotFound -> null
            response.status.isSuccess() -> response.body()
            else -> throw PokeApiException("PokéAPI a répondu ${response.status} pour l'espèce '$idOrName'")
        }
    }

    suspend fun getPokemonForm(name: String): PokeApiPokemonForm? =
        getOrNull("pokemon-form/${name.lowercase().encodeURLPathPart()}")

    suspend fun getType(name: String): PokeApiType? = getOrNull("type/${name.lowercase().encodeURLPathPart()}")

    suspend fun getAbility(name: String): PokeApiAbility? = getOrNull("ability/${name.lowercase().encodeURLPathPart()}")

    suspend fun getMove(name: String): PokeApiMove? = getOrNull("move/${name.lowercase().encodeURLPathPart()}")

    suspend fun getEvolutionChain(id: Int): PokeApiEvolutionChain? = getOrNull("evolution-chain/$id")

    /** Noms traduits d'une ressource : kind = "item", "move", "location", "pokemon-species"… */
    suspend fun getNames(kind: String, name: String): PokeApiNamedResource? =
        getOrNull("$kind/${name.lowercase().encodeURLPathPart()}")

    private suspend inline fun <reified T> getOrNull(path: String): T? {
        val response = httpClient.get("$baseUrl/$path")
        return when {
            response.status == HttpStatusCode.NotFound -> null
            response.status.isSuccess() -> response.body()
            else -> throw PokeApiException("PokéAPI a répondu ${response.status} pour '$path'")
        }
    }

    /** Nombre d'espèces connues de PokéAPI (taille du Pokédex national). */
    suspend fun speciesCount(): Int {
        val response = httpClient.get("$baseUrl/pokemon-species") { parameter("limit", 1) }
        if (!response.status.isSuccess()) {
            throw PokeApiException("PokéAPI a répondu ${response.status} pour la liste des espèces")
        }
        return response.body<NamedApiResourceList>().count
    }

    /** Liste paginée des Pokémon (noms + URLs). */
    suspend fun listPokemon(limit: Int, offset: Int): NamedApiResourceList {
        val response = httpClient.get("$baseUrl/pokemon") {
            parameter("limit", limit)
            parameter("offset", offset)
        }
        if (!response.status.isSuccess()) {
            throw PokeApiException("PokéAPI a répondu ${response.status} pour la liste des Pokémon")
        }
        return response.body()
    }
}

val pokeApiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** HttpClient de production (moteur CIO) avec retry et timeouts. */
fun createPokeApiHttpClient(): HttpClient = HttpClient(CIO) {
    configurePokeApiClient()
}

fun HttpClientConfig<*>.configurePokeApiClient() {
    expectSuccess = false
    install(ContentNegotiation) { json(pokeApiJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 5_000
    }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 3)
        retryOnException(maxRetries = 3, retryOnTimeout = true)
        exponentialDelay()
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, "ktor-pokeapi-demo/0.1")
    }
}
