package com.example.pokeapi

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.configurePokeApiClient
import com.example.pokeapi.model.ImportResult
import com.example.pokeapi.model.Page
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.model.PokemonSummary
import com.example.pokeapi.model.RegionCount
import com.example.pokeapi.model.TypeCount
import com.example.pokeapi.model.Team
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ApplicationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fakePokemon = mapOf(
        "bulbasaur" to pokemonJson(1, "bulbasaur", listOf("grass", "poison"), hp = 45),
        "charmander" to pokemonJson(4, "charmander", listOf("fire"), hp = 39),
        "squirtle" to pokemonJson(7, "squirtle", listOf("water"), hp = 44),
        "chikorita" to pokemonJson(152, "chikorita", listOf("grass"), hp = 60),
    )
    private val fakeSpecies = mapOf(
        "bulbasaur" to speciesJson(1, "bulbasaur", "Bulbizarre", "generation-i"),
        "charmander" to speciesJson(4, "charmander", "Salamèche", "generation-i"),
        "squirtle" to speciesJson(7, "squirtle", "Carapuce", "generation-i"),
        "chikorita" to speciesJson(152, "chikorita", "Germignon", "generation-ii"),
    )
    private val byId = mapOf("1" to "bulbasaur", "4" to "charmander", "7" to "squirtle", "152" to "chikorita")
    private val remoteCalls = AtomicInteger()

    private fun mockPokeApi(): PokeApiClient {
        val engine = MockEngine { request ->
            remoteCalls.incrementAndGet()
            val path = request.url.encodedPath
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path == "/api/v2/pokemon" -> {
                    val results = fakePokemon.keys.joinToString(",") {
                        """{"name":"$it","url":"https://pokeapi.co/api/v2/pokemon/$it/"}"""
                    }
                    respond("""{"count":4,"next":null,"previous":null,"results":[$results]}""", HttpStatusCode.OK, headers)
                }
                path.startsWith("/api/v2/pokemon-species/") -> {
                    val key = path.removePrefix("/api/v2/pokemon-species/")
                    val body = fakeSpecies[byId[key] ?: key]
                    if (body != null) respond(body, HttpStatusCode.OK, headers)
                    else respond("Not Found", HttpStatusCode.NotFound)
                }
                path.startsWith("/api/v2/pokemon/") -> {
                    val key = path.removePrefix("/api/v2/pokemon/")
                    val body = fakePokemon[byId[key] ?: key]
                    if (body != null) respond(body, HttpStatusCode.OK, headers)
                    else respond("Not Found", HttpStatusCode.NotFound)
                }
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        }
        return PokeApiClient(HttpClient(engine) { configurePokeApiClient() }, "https://pokeapi.co/api/v2")
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        val db = Database.connect(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        application { configureApp(db, mockPokeApi()) }
        block()
    }

    @Test
    fun `health renvoie UP`() = appTest {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "UP")
    }

    @Test
    fun `un pokemon est recupere depuis PokeAPI puis servi depuis la base`() = appTest {
        val first = client.get("/pokemon/Bulbasaur")
        assertEquals(HttpStatusCode.OK, first.status)
        val pokemon = json.decodeFromString<Pokemon>(first.bodyAsText())
        assertEquals(1, pokemon.id)
        assertEquals(listOf("grass", "poison"), pokemon.types)
        assertEquals(45, pokemon.stats["hp"])
        assertEquals("Bulbizarre", pokemon.nameFr)
        assertEquals("kanto", pokemon.region)
        assertEquals(1, pokemon.generation)
        assertEquals(45 + 49, pokemon.statTotal)
        assertEquals(2, remoteCalls.get()) // pokemon + pokemon-species

        // Deuxième appel (par id) : aucune requête supplémentaire vers PokéAPI
        val second = client.get("/pokemon/1")
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("bulbasaur", json.decodeFromString<Pokemon>(second.bodyAsText()).name)
        assertEquals(2, remoteCalls.get())
    }

    @Test
    fun `pokemon inconnu renvoie 404`() = appTest {
        val response = client.get("/pokemon/missingno")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `import puis liste filtree par type`() = appTest {
        val importResponse = client.post("/pokemon/import?limit=4")
        assertEquals(HttpStatusCode.OK, importResponse.status)
        val result = json.decodeFromString<ImportResult>(importResponse.bodyAsText())
        assertEquals(4, result.imported)
        assertTrue(result.failed.isEmpty())

        val again = json.decodeFromString<ImportResult>(client.post("/pokemon/import?limit=4").bodyAsText())
        assertEquals(0, again.imported)
        assertEquals(4, again.alreadyCached)

        val all = json.decodeFromString<Page<PokemonSummary>>(client.get("/pokemon").bodyAsText())
        assertEquals(4, all.total)
        assertEquals(listOf(1, 4, 7, 152), all.items.map { it.id })

        val fire = json.decodeFromString<Page<PokemonSummary>>(client.get("/pokemon?type=fire").bodyAsText())
        assertEquals(listOf("charmander"), fire.items.map { it.name })

        val byName = json.decodeFromString<Page<PokemonSummary>>(client.get("/pokemon?name=squ").bodyAsText())
        assertEquals(listOf("squirtle"), byName.items.map { it.name })
    }

    @Test
    fun `pokedex filtre par region, plusieurs types et nom francais, avec tri`() = appTest {
        client.post("/pokemon/import?limit=4")

        suspend fun ids(query: String) =
            json.decodeFromString<Page<PokemonSummary>>(client.get("/pokemon?$query").bodyAsText()).items.map { it.id }

        assertEquals(listOf(1, 4, 7), ids("region=kanto"))
        assertEquals(listOf(152), ids("region=johto"))
        assertEquals(listOf(152), ids("generation=2"))
        assertEquals(listOf(1, 152), ids("type=grass"))
        assertEquals(listOf(1), ids("type=grass,poison"))
        assertEquals(listOf(1), ids("type=grass&type=poison"))
        assertEquals(listOf(1, 152), ids("type=grass&region="))
        assertEquals(listOf(4), ids("name=salam")) // nom français
        assertEquals(listOf(152, 1, 7, 4), ids("sort=statTotal&order=desc"))
        assertEquals(listOf(1, 7, 152, 4), ids("sort=nameFr"))  // Bulbizarre, Carapuce, Germignon, Salamèche
        assertEquals(listOf(1, 4), ids("sort=id&limit=2"))
        assertEquals(listOf(7, 152), ids("sort=id&limit=2&offset=2"))

        assertEquals(HttpStatusCode.BadRequest, client.get("/pokemon?region=atlantis").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/pokemon?sort=color").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/pokemon?order=up").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/pokemon?type=a,b,c").status)
    }

    @Test
    fun `regions et types avec leurs effectifs`() = appTest {
        client.post("/pokemon/import?limit=4")

        val regions = json.decodeFromString<List<RegionCount>>(client.get("/regions").bodyAsText())
        assertEquals(9, regions.size)
        assertEquals(RegionCount("kanto", 1, 3), regions[0])
        assertEquals(RegionCount("johto", 2, 1), regions[1])
        assertEquals(0, regions.last().count)

        val types = json.decodeFromString<List<TypeCount>>(client.get("/types").bodyAsText())
        assertEquals(TypeCount("grass", 2), types.first { it.name == "grass" })
        assertEquals(listOf("fire", "grass", "poison", "water"), types.map { it.name })
    }

    @Test
    fun `CORS autorise l'application web`() = appTest {
        val response = client.options("/pokemon") {
            header(HttpHeaders.Origin, "http://localhost:8081")
            header(HttpHeaders.AccessControlRequestMethod, "GET")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `gestion d'une equipe`() = appTest {
        val created = client.post("/teams") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Starters","pokemon":["bulbasaur","4"]}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val team = json.decodeFromString<Team>(created.bodyAsText())
        assertEquals(listOf("bulbasaur", "charmander"), team.members.map { it.name })

        val added = client.post("/teams/${team.id}/pokemon/squirtle")
        assertEquals(HttpStatusCode.Created, added.status)
        assertEquals(3, json.decodeFromString<Team>(added.bodyAsText()).members.size)

        val removed = client.delete("/teams/${team.id}/members/1")
        assertEquals(HttpStatusCode.OK, removed.status)
        assertEquals(listOf("charmander", "squirtle"), json.decodeFromString<Team>(removed.bodyAsText()).members.map { it.name })

        val renamed = client.patch("/teams/${team.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Kanto"}""")
        }
        assertEquals("Kanto", json.decodeFromString<Team>(renamed.bodyAsText()).name)

        assertEquals(HttpStatusCode.BadRequest, client.post("/teams/${team.id}/pokemon/missingno").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/teams/${team.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/teams/${team.id}").status)
    }

    @Test
    fun `une equipe est limitee a 6 pokemon`() = appTest {
        val created = client.post("/teams") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Full","pokemon":["1","1","1","4","4","7"]}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val team = json.decodeFromString<Team>(created.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.post("/teams/${team.id}/pokemon/7").status)
    }

    @Test
    fun `GET sur import renvoie 405 sans appeler PokeAPI`() = appTest {
        val response = client.get("/pokemon/import")
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals("POST", response.headers[HttpHeaders.Allow])
        assertEquals(0, remoteCalls.get())
    }

    @Test
    fun `swagger UI et spec OpenAPI sont servis`() = appTest {
        val ui = client.get("/swagger")
        assertEquals(HttpStatusCode.OK, ui.status)
        assertContains(ui.bodyAsText(), "swagger-ui")

        val spec = client.get("/swagger/documentation.yaml")
        assertEquals(HttpStatusCode.OK, spec.status)
        assertContains(spec.bodyAsText(), "/pokemon/import")
    }

    @Test
    fun `corps JSON invalide renvoie 400`() = appTest {
        val response = client.post("/teams") {
            contentType(ContentType.Application.Json)
            setBody("""{"nom":"oops"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private fun speciesJson(id: Int, name: String, frenchName: String, generation: String) = """
        {
          "id": $id, "name": "$name",
          "names": [
            {"name": "${name.replaceFirstChar { it.uppercase() }}", "language": {"name": "en", "url": "x"}},
            {"name": "$frenchName", "language": {"name": "fr", "url": "x"}}
          ],
          "generation": {"name": "$generation", "url": "x"}
        }
    """.trimIndent()

    private fun pokemonJson(id: Int, name: String, types: List<String>, hp: Int): String {
        val typesJson = types.withIndex().joinToString(",") { (i, t) ->
            """{"slot":${i + 1},"type":{"name":"$t","url":"https://pokeapi.co/api/v2/type/$t/"}}"""
        }
        return """
        {
          "id": $id, "name": "$name", "height": 7, "weight": 69, "base_experience": 64,
          "order": $id, "is_default": true,
          "sprites": {
            "front_default": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/$id.png",
            "other": {"official-artwork": {"front_default": "https://example.org/$id.png"}}
          },
          "types": [$typesJson],
          "stats": [
            {"base_stat": $hp, "effort": 0, "stat": {"name": "hp", "url": "x"}},
            {"base_stat": 49, "effort": 0, "stat": {"name": "attack", "url": "x"}}
          ],
          "species": {"name": "$name", "url": "https://pokeapi.co/api/v2/pokemon-species/$id/"},
          "abilities": [
            {"ability": {"name": "overgrow", "url": "x"}, "is_hidden": false, "slot": 1},
            {"ability": {"name": "chlorophyll", "url": "x"}, "is_hidden": true, "slot": 3}
          ]
        }
        """.trimIndent()
    }
}
