package com.example.pokeapi

import com.example.pokeapi.game.BadgesView
import com.example.pokeapi.game.RouteSpecies
import com.example.pokeapi.game.UnlockedAchievement
import com.example.pokeapi.game.Achievements
import com.example.pokeapi.game.AchievementsView
import com.example.pokeapi.game.DexEntry
import com.example.pokeapi.game.DexStatus
import com.example.pokeapi.game.DexView
import com.example.pokeapi.auth.AuthResponse
import com.example.pokeapi.auth.UserView
import com.example.pokeapi.db.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.configurePokeApiClient
import com.example.pokeapi.client.NamedApiResource
import com.example.pokeapi.client.PokeApiMoveSlot
import com.example.pokeapi.client.PokeApiMoveVersionDetail
import com.example.pokeapi.model.AbilityDetail
import com.example.pokeapi.model.learnsetOf
import com.example.pokeapi.db.GameSaveTable
import com.example.pokeapi.game.BattleKind
import com.example.pokeapi.game.BattleOutcome
import com.example.pokeapi.game.GameSettings
import com.example.pokeapi.game.GameView
import com.example.pokeapi.model.EncounterMethod
import com.example.pokeapi.model.EvolutionChain
import com.example.pokeapi.model.LocationCategory
import com.example.pokeapi.model.LocationDetail
import com.example.pokeapi.model.RegionLocations
import com.example.pokeapi.model.ImportResult
import com.example.pokeapi.model.Page
import com.example.pokeapi.model.Pokemon
import com.example.pokeapi.model.PokemonMoves
import com.example.pokeapi.model.PokedexUpdateStatus
import com.example.pokeapi.model.UpdateState
import com.example.pokeapi.model.PokemonSummary
import com.example.pokeapi.model.RegionCount
import com.example.pokeapi.model.TypeCount
import com.example.pokeapi.model.TypeDefenses
import com.example.pokeapi.model.TypeMultiplier
import com.example.pokeapi.model.Team
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ApplicationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fakePokemon = mapOf(
        "bulbasaur" to pokemonJson(1, "bulbasaur", listOf("grass", "poison"), hp = 45, moves = bulbasaurMoves),
        "charmander" to pokemonJson(4, "charmander", listOf("fire"), hp = 39),
        "squirtle" to pokemonJson(7, "squirtle", listOf("water"), hp = 44),
        "chikorita" to pokemonJson(152, "chikorita", listOf("grass"), hp = 60),
    )
    private fun learn(level: Int, method: String, version: String) =
        """{"level_learned_at": $level, "move_learn_method": {"name": "$method", "url": "x"}, "version_group": {"name": "$version", "url": "x"}}"""

    private val bulbasaurMoves get() = """
        [
          {"move": {"name": "tackle", "url": "x"}, "version_group_details": [${learn(1, "level-up", "red-blue")}, ${learn(1, "level-up", "scarlet-violet")}, ${learn(0, "machine", "champions")}]},
          {"move": {"name": "vine-whip", "url": "x"}, "version_group_details": [${learn(3, "level-up", "scarlet-violet")}]},
          {"move": {"name": "petal-dance", "url": "x"}, "version_group_details": [${learn(0, "egg", "scarlet-violet")}]},
          {"move": {"name": "razor-leaf", "url": "x"}, "version_group_details": [${learn(0, "machine", "red-blue")}]}
        ]
    """.trimIndent()

    private fun moveJson(name: String, fr: String, type: String, damageClass: String, power: Int?) = """
        {"id": 1, "name": "$name", "power": ${power ?: "null"}, "accuracy": 100, "pp": 25, "priority": 0,
         "type": {"name": "$type", "url": "x"}, "damage_class": {"name": "$damageClass", "url": "x"},
         "names": [{"name": "$fr", "language": {"name": "fr", "url": "x"}}],
         "flavor_text_entries": [
           {"flavor_text": "Old text.", "language": {"name": "fr", "url": "x"}},
           {"flavor_text": "Charge l'ennemi\navec un violent\nplaquage.", "language": {"name": "fr", "url": "x"}}
         ]}
    """.trimIndent()

    private val fakeMoves = mapOf(
        "tackle" to moveJson("tackle", "Charge", "normal", "physical", 40),
        "vine-whip" to moveJson("vine-whip", "Fouet Lianes", "grass", "physical", 45),
        "petal-dance" to moveJson("petal-dance", "Danse Fleurs", "grass", "special", 120),
        "razor-leaf" to moveJson("razor-leaf", "Tranch'Herbe", "grass", "physical", 55),
    )

    /** Engrais : texte avec « \n » littéraux et effet en plusieurs paragraphes. Chlorophylle : effet en anglais seulement. */
    private val fakeAbilities = mapOf(
        "overgrow" to """
            {"id": 65, "name": "overgrow", "generation": {"name": "generation-iii", "url": "x"},
             "names": [{"name": "Engrais", "language": {"name": "fr", "url": "x"}}],
             "flavor_text_entries": [
               {"flavor_text": "Ancien texte.", "language": {"name": "fr", "url": "x"}},
               {"flavor_text": "Augmente la puissance des capacités\\nde type Plante en cas de besoin.", "language": {"name": "fr", "url": "x"}}
             ],
             "effect_entries": [
               {"effect": "Bonus de 50 %.\n\nNe s'applique\nqu'en dessous de 1/3 des PV.", "short_effect": "Court.", "language": {"name": "fr", "url": "x"}}
             ],
             "pokemon": [
               {"is_hidden": false, "pokemon": {"name": "ivysaur", "url": "https://pokeapi.co/api/v2/pokemon/2/"}},
               {"is_hidden": false, "pokemon": {"name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon/1/"}},
               {"is_hidden": false, "pokemon": {"name": "venusaur-mega", "url": "https://pokeapi.co/api/v2/pokemon/10033/"}}
             ]}
        """.trimIndent(),
        "chlorophyll" to """
            {"id": 34, "name": "chlorophyll", "generation": {"name": "generation-iii", "url": "x"},
             "names": [{"name": "Chlorophylle", "language": {"name": "fr", "url": "x"}}],
             "flavor_text_entries": [],
             "effect_entries": [{"effect": "", "short_effect": "Doubles Speed in sun.", "language": {"name": "en", "url": "x"}}],
             "pokemon": [{"is_hidden": true, "pokemon": {"name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon/1/"}}]}
        """.trimIndent(),
    )

    private fun fr(name: String) = """[{"name": "$name", "language": {"name": "fr", "url": "x"}}]"""
    private fun res(name: String) = """{"name": "$name", "url": "x"}"""
    private fun walk(min: Int, max: Int, chance: Int) =
        """{"min_level": $min, "max_level": $max, "chance": $chance, "method": ${res("walk")}}"""

    /** Région simulée : une ville sans rencontres, deux routes (dans le désordre) et une grotte. */
    private val fakeGeo = mapOf(
        "region/kanto" to """{"id": 1, "name": "kanto", "names": ${fr("Kanto")}, "locations": [
            ${res("kanto-route-2")}, ${res("pallet-town")}, ${res("kanto-route-1")}, ${res("mt-moon")}]}""",
        "location/pallet-town" to """{"id": 1, "name": "pallet-town", "names": ${fr("Bourg Palette")}, "areas": [], "region": ${res("kanto")}}""",
        "location/kanto-route-1" to """{"id": 2, "name": "kanto-route-1", "names": ${fr("Route 1")}, "areas": [${res("kanto-route-1-area")}], "region": ${res("kanto")}}""",
        "location/kanto-route-2" to """{"id": 3, "name": "kanto-route-2", "names": ${fr("Route 2")}, "areas": [], "region": ${res("kanto")}}""",
        "location/mt-moon" to """{"id": 4, "name": "mt-moon", "names": ${fr("Mont Sélénite")}, "areas": [], "region": ${res("kanto")}}""",
        "location-area/kanto-route-1-area" to """{"name": "kanto-route-1-area", "names": [], "pokemon_encounters": [
            {"pokemon": {"name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon/1/"}, "version_details": [
              {"version": ${res("red")}, "max_chance": 50, "encounter_details": [${walk(2, 3, 20)}, ${walk(3, 4, 30)}]},
              {"version": ${res("lets-go-pikachu")}, "max_chance": 100, "encounter_details": [${walk(3, 5, 100)}]},
              {"version": ${res("red-japan")}, "max_chance": 50, "encounter_details": [${walk(2, 3, 20)}]},
              {"version": ${res("leafgreen")}, "max_chance": 100, "encounter_details": [${walk(2, 3, 100)}]}
            ]},
            {"pokemon": {"name": "chikorita", "url": "https://pokeapi.co/api/v2/pokemon/152/"}, "version_details": [
              {"version": ${res("leafgreen")}, "max_chance": 100, "encounter_details": [${walk(2, 3, 100)}]}
            ]}]}""",
        "version/red-japan" to """{"name": "red-japan", "names": ${fr("Rouge")}, "version_group": ${res("red-green-japan")}}""",
        "version/leafgreen" to """{"name": "leafgreen", "names": ${fr("Vert Feuille")}, "version_group": ${res("firered-leafgreen")}}""",
        "version/red" to """{"name": "red", "names": ${fr("Rouge")}, "version_group": ${res("red-blue")}}""",
        "version/lets-go-pikachu" to """{"name": "lets-go-pikachu", "names": ${fr("Let's Go, Pikachu")}, "version_group": ${res("lets-go-pikachu-lets-go-eevee")}}""",
        "encounter-method/walk" to """{"name": "walk", "names": ${fr("Herbes hautes")}}""",
    )

    /** Pokémon accessibles individuellement mais absents de la liste (/pokemon), pour les évolutions. */
    private val extraPokemon = mapOf(
        "ivysaur" to pokemonJson(2, "ivysaur", listOf("grass", "poison"), hp = 60),
        // Florizarre apprend Danse Fleurs en évoluant (niveau 0)
        "venusaur" to pokemonJson(3, "venusaur", listOf("grass", "poison"), hp = 80, moves = """
            [{"move": {"name": "tackle", "url": "x"}, "version_group_details": [${learn(1, "level-up", "scarlet-violet")}]},
             {"move": {"name": "petal-dance", "url": "x"}, "version_group_details": [${learn(0, "level-up", "scarlet-violet")}]}]
        """.trimIndent()),
        // Formes fictives de Germignon : une forme régionale (importée avec l'espèce) et une méga (ignorée)
        "bayleef" to pokemonJson(153, "bayleef", listOf("grass"), hp = 60),
        "chikorita-hisui" to pokemonJson(10999, "chikorita-hisui", listOf("water"), hp = 10, species = "chikorita" to 152, isDefault = false),
        "chikorita-mega" to pokemonJson(10998, "chikorita-mega", listOf("grass"), hp = 90, species = "chikorita" to 152, isDefault = false),
        // Famille d'Évoli : Aquali (Kanto) et Phyllali (Sinnoh)
        "eevee" to pokemonJson(133, "eevee", listOf("normal"), hp = 55),
        "vaporeon" to pokemonJson(134, "vaporeon", listOf("water"), hp = 130),
        "leafeon" to pokemonJson(470, "leafeon", listOf("grass"), hp = 65),
    )
    private val fakeSpecies = mapOf(
        "bulbasaur" to speciesJson(1, "bulbasaur", "Bulbizarre", "generation-i", chainId = 1),
        "ivysaur" to speciesJson(2, "ivysaur", "Herbizarre", "generation-i", chainId = 1),
        "venusaur" to speciesJson(3, "venusaur", "Florizarre", "generation-i", chainId = 1),
        "charmander" to speciesJson(4, "charmander", "Salamèche", "generation-i"),
        "squirtle" to speciesJson(7, "squirtle", "Carapuce", "generation-i"),
        "chikorita" to speciesJson(152, "chikorita", "Germignon", "generation-ii", chainId = 2, forms = listOf("chikorita-hisui", "chikorita-mega")),
        "bayleef" to speciesJson(153, "bayleef", "Macronium", "generation-ii", chainId = 2),
        "eevee" to speciesJson(133, "eevee", "Évoli", "generation-i", chainId = 3),
        "vaporeon" to speciesJson(134, "vaporeon", "Aquali", "generation-i", chainId = 3),
        "leafeon" to speciesJson(470, "leafeon", "Phyllali", "generation-iv", chainId = 3),
    )
    private val byId = mapOf(
        "1" to "bulbasaur", "2" to "ivysaur", "3" to "venusaur", "4" to "charmander", "7" to "squirtle", "152" to "chikorita",
        "10999" to "chikorita-hisui", "10998" to "chikorita-mega", "153" to "bayleef",
        "133" to "eevee", "134" to "vaporeon", "470" to "leafeon",
    )

    /**
     * Chaîne de Germignon : la forme par défaut évolue au niveau 16, la forme de Hisui (fictive) avec une Pierre Foudre
     * vers la forme par défaut de Macronium (comme Miaouss de Galar → Berserkatt).
     */
    private val chikoritaChain = """
        {"id": 2, "chain": {
          "is_baby": false, "species": {"name": "chikorita", "url": "https://pokeapi.co/api/v2/pokemon-species/152/"},
          "evolution_details": [],
          "evolves_to": [{
            "is_baby": false, "species": {"name": "bayleef", "url": "https://pokeapi.co/api/v2/pokemon-species/153/"},
            "evolution_details": [
              {"trigger": {"name": "level-up", "url": "x"}, "min_level": 16, "is_default": true,
               "required_pokemon_form": {"name": "chikorita", "url": "x"}},
              {"trigger": {"name": "use-item", "url": "x"}, "item": {"name": "thunder-stone", "url": "x"}, "is_default": true,
               "required_pokemon_form": {"name": "chikorita-hisui", "url": "x"}}
            ],
            "evolves_to": []
          }]
        }}
    """.trimIndent()

    /** Chaîne d'Évoli : Aquali (Kanto, Pierre Eau) et Phyllali (Sinnoh, Pierre Plante). */
    private val eeveeChain = """
        {"id": 3, "chain": {
          "is_baby": false, "species": {"name": "eevee", "url": "https://pokeapi.co/api/v2/pokemon-species/133/"},
          "evolution_details": [],
          "evolves_to": [
            {"is_baby": false, "species": {"name": "vaporeon", "url": "https://pokeapi.co/api/v2/pokemon-species/134/"},
             "evolution_details": [{"trigger": {"name": "use-item", "url": "x"}, "item": {"name": "water-stone", "url": "x"}}], "evolves_to": []},
            {"is_baby": false, "species": {"name": "leafeon", "url": "https://pokeapi.co/api/v2/pokemon-species/470/"},
             "evolution_details": [{"trigger": {"name": "use-item", "url": "x"}, "item": {"name": "leaf-stone", "url": "x"}}], "evolves_to": []}
          ]
        }}
    """.trimIndent()

    /** Chaîne de Bulbizarre : la condition non par défaut doit être ignorée, l'objet traduit. */
    private val bulbasaurChain = """
        {"id": 1, "chain": {
          "is_baby": false, "species": {"name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon-species/1/"},
          "evolution_details": [],
          "evolves_to": [{
            "is_baby": false, "species": {"name": "ivysaur", "url": "https://pokeapi.co/api/v2/pokemon-species/2/"},
            "evolution_details": [
              {"trigger": {"name": "level-up", "url": "x"}, "min_level": 16, "is_default": true, "time_of_day": ""},
              {"trigger": {"name": "level-up", "url": "x"}, "location": {"name": "eterna-forest", "url": "x"}, "is_default": false}
            ],
            "evolves_to": [{
              "is_baby": false, "species": {"name": "venusaur", "url": "https://pokeapi.co/api/v2/pokemon-species/3/"},
              "evolution_details": [{"trigger": {"name": "use-item", "url": "x"}, "item": {"name": "thunder-stone", "url": "x"}}],
              "evolves_to": []
            }]
          }]
        }}
    """.trimIndent()
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
                path == "/api/v2/type/grass" -> respond(
                    typeJson("grass", double = "flying poison bug fire ice", half = "ground water grass electric"),
                    HttpStatusCode.OK, headers,
                )
                path == "/api/v2/type/poison" -> respond(
                    typeJson("poison", double = "ground psychic", half = "fighting poison bug grass fairy"),
                    HttpStatusCode.OK, headers,
                )
                path == "/api/v2/pokemon-form/chikorita-hisui" ->
                    respond("""{"names":[{"name":"Germignon de Hisui","language":{"name":"fr","url":"x"}}]}""", HttpStatusCode.OK, headers)
                path.startsWith("/api/v2/region/") || path.startsWith("/api/v2/location/") ||
                    path.startsWith("/api/v2/location-area/") || path.startsWith("/api/v2/version/") ||
                    path.startsWith("/api/v2/encounter-method/") -> {
                    val body = fakeGeo[path.removePrefix("/api/v2/")]
                    if (body != null) respond(body, HttpStatusCode.OK, headers)
                    else respond("Not Found", HttpStatusCode.NotFound)
                }
                path.startsWith("/api/v2/ability/") -> {
                    val body = fakeAbilities[path.removePrefix("/api/v2/ability/")]
                    if (body != null) respond(body, HttpStatusCode.OK, headers)
                    else respond("Not Found", HttpStatusCode.NotFound)
                }
                path.startsWith("/api/v2/move/") -> {
                    val body = fakeMoves[path.removePrefix("/api/v2/move/")]
                    if (body != null) respond(body, HttpStatusCode.OK, headers)
                    else respond("Not Found", HttpStatusCode.NotFound)
                }
                path == "/api/v2/evolution-chain/1" -> respond(bulbasaurChain, HttpStatusCode.OK, headers)
                path == "/api/v2/evolution-chain/2" -> respond(chikoritaChain, HttpStatusCode.OK, headers)
                path == "/api/v2/evolution-chain/3" -> respond(eeveeChain, HttpStatusCode.OK, headers)
                path == "/api/v2/item/thunder-stone" ->
                    respond("""{"names":[{"name":"Pierre Foudre","language":{"name":"fr","url":"x"}}]}""", HttpStatusCode.OK, headers)
                path == "/api/v2/pokemon-species" ->
                    respond("""{"count":4,"next":null,"previous":null,"results":[]}""", HttpStatusCode.OK, headers)
                path.startsWith("/api/v2/pokemon-species/") -> {
                    val key = path.removePrefix("/api/v2/pokemon-species/")
                    val body = fakeSpecies[byId[key] ?: key]
                    if (body != null) respond(body, HttpStatusCode.OK, headers)
                    else respond("Not Found", HttpStatusCode.NotFound)
                }
                path.startsWith("/api/v2/pokemon/") -> {
                    val key = path.removePrefix("/api/v2/pokemon/")
                    val name = byId[key] ?: key
                    val body = fakePokemon[name] ?: extraPokemon[name]
                    if (body != null) respond(body, HttpStatusCode.OK, headers)
                    else respond("Not Found", HttpStatusCode.NotFound)
                }
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        }
        return PokeApiClient(HttpClient(engine) { configurePokeApiClient() }, "https://pokeapi.co/api/v2")
    }

    /** Clients d'un test : [client] est connecté avec un compte (« sacha »), [anonymous] ne l'est pas. */
    private class AppScope(val client: HttpClient, val anonymous: HttpClient)

    private fun appTest(block: suspend AppScope.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        val db = Database.connect(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        application { configureApp(db, mockPokeApi()) }
        database = db
        val anonymous = createClient { }
        val token = register(anonymous, "sacha").token
        val authed = createClient { defaultRequest { header(HttpHeaders.Authorization, "Bearer $token") } }
        AppScope(authed, anonymous).block()
    }

    private suspend fun register(client: HttpClient, username: String, password: String = "pikachu123"): AuthResponse {
        val response = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString<AuthResponse>(response.bodyAsText())
    }

    /** Base du test en cours (pour préparer une situation de jeu directement dans la sauvegarde). */
    private lateinit var database: Database

    /** Modifie la sauvegarde du jeu d'aventure. */
    private fun editSave(change: (JsonObject) -> JsonObject) = transaction(database) {
        val row = GameSaveTable.selectAll().where { GameSaveTable.id eq 1 }.single()
        val edited = change(Json.parseToJsonElement(row[GameSaveTable.data]).jsonObject)
        GameSaveTable.update({ GameSaveTable.id eq 1 }) { it[data] = edited.toString() }
    }

    @Test
    fun `comptes - inscription, connexion, deconnexion`() = appTest {
        // Le compte « sacha » existe (créé pour le test) : même nom, casse différente, refusé
        val duplicate = anonymous.post("/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"Sacha","password":"autremotdepasse"}""")
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals(HttpStatusCode.BadRequest, anonymous.post("/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"ondine","password":"court"}""")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, anonymous.post("/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"a b","password":"assezlongpourtant"}""")
        }.status)

        val me = json.decodeFromString<UserView>(client.get("/auth/me").bodyAsText())
        assertEquals("sacha", me.username)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer faux") }.status)

        // Connexion (nom insensible à la casse), mauvais mot de passe, compte inconnu : même message
        val login = anonymous.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody("""{"username":"SACHA","password":"pikachu123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = json.decodeFromString<AuthResponse>(login.bodyAsText()).token
        val wrong = anonymous.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody("""{"username":"sacha","password":"mauvais!!"}""")
        }
        val unknown = anonymous.post("/auth/login") {
            contentType(ContentType.Application.Json); setBody("""{"username":"personne","password":"pikachu123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(wrong.bodyAsText(), unknown.bodyAsText())

        // Déconnexion : le jeton ne marche plus
        assertEquals(HttpStatusCode.OK, anonymous.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        anonymous.post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer $token") }.status)

        // Le mot de passe n'est pas enregistré en clair
        transaction(database) {
            val stored = UserTable.selectAll().single()[UserTable.passwordHash]
            assertTrue(stored.startsWith("pbkdf2-sha256:") && "pikachu123" !in stored)
        }
    }

    @Test
    fun `comptes - chaque compte a sa partie et ses equipes`() = appTest {
        // Sans compte : aventure et équipes refusées, Pokédex accessible
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/game").status)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/teams").status)
        assertEquals(HttpStatusCode.OK, anonymous.get("/pokemon/bulbasaur").status)

        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") }
        val team = client.post("/teams") { contentType(ContentType.Application.Json); setBody("""{"name":"Kanto","pokemon":["bulbasaur"]}""") }
        val teamId = json.decodeFromString<Team>(team.bodyAsText()).id

        // Un deuxième compte ne voit ni la partie ni les équipes du premier
        val other = register(anonymous, "ondine").token
        suspend fun HttpRequestBuilder.asOther() = header(HttpHeaders.Authorization, "Bearer $other")
        assertEquals(false, json.decodeFromString<GameView>(anonymous.get("/game") { asOther() }.bodyAsText()).started)
        assertEquals("[]", anonymous.get("/teams") { asOther() }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, anonymous.get("/teams/$teamId") { asOther() }.status)
        assertEquals(HttpStatusCode.NotFound, anonymous.delete("/teams/$teamId") { asOther() }.status)

        val second = anonymous.post("/game/new") { asOther(); contentType(ContentType.Application.Json); setBody("""{"starter":"squirtle"}""") }
        assertEquals("Carapuce", json.decodeFromString<GameView>(second.bodyAsText()).team.single().name)
        // La partie du premier compte n'a pas changé
        assertEquals("Bulbizarre", json.decodeFromString<GameView>(client.get("/game").bodyAsText()).team.single().name)
        assertEquals(1, json.decodeFromString<List<Team>>(client.get("/teams").bodyAsText()).size)
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
        assertEquals(4, remoteCalls.get()) // pokemon + pokemon-species + 2 talents

        // Deuxième appel (par id) : aucune requête supplémentaire vers PokéAPI
        val second = client.get("/pokemon/1")
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("bulbasaur", json.decodeFromString<Pokemon>(second.bodyAsText()).name)
        assertEquals(4, remoteCalls.get())
    }

    @Test
    fun `talents detailles dans la fiche du pokemon`() = appTest {
        val pokemon = json.decodeFromString<Pokemon>(client.get("/pokemon/bulbasaur").bodyAsText())
        val (overgrow, chlorophyll) = pokemon.abilities

        assertEquals("Engrais", overgrow.nameFr)
        assertEquals(false, overgrow.hidden)
        assertEquals(3, overgrow.generation)
        // Texte le plus récent, « \n » littéraux nettoyés
        assertEquals("Augmente la puissance des capacités de type Plante en cas de besoin.", overgrow.description)
        // Paragraphes de l'effet conservés, retours à la ligne simples supprimés
        assertEquals("Bonus de 50 %.\n\nNe s'applique qu'en dessous de 1/3 des PV.", overgrow.effect)
        assertEquals("fr", overgrow.effectLanguage)

        assertEquals("Chlorophylle", chlorophyll.nameFr)
        assertEquals(true, chlorophyll.hidden)
        assertEquals(null, chlorophyll.description)
        assertEquals("Doubles Speed in sun.", chlorophyll.effect) // repli sur short_effect anglais
        assertEquals("en", chlorophyll.effectLanguage)

        // Talents en cache : pas de nouvel appel
        val callsBefore = remoteCalls.get()
        client.get("/pokemon/1")
        assertEquals(callsBefore, remoteCalls.get())
    }

    @Test
    fun `fiche d'un talent avec les pokemon qui l'ont`() = appTest {
        client.get("/pokemon/bulbasaur") // Bulbizarre en base
        val detail = json.decodeFromString<AbilityDetail>(client.get("/abilities/overgrow").bodyAsText())
        assertEquals("Engrais", detail.nameFr)
        // Triés par numéro, forme méga exclue ; nom français seulement pour les Pokémon en base
        assertEquals(listOf(1, 2), detail.pokemon.map { it.id })
        assertEquals("Bulbizarre", detail.pokemon[0].nameFr)
        assertEquals(null, detail.pokemon[1].nameFr)

        assertEquals(HttpStatusCode.NotFound, client.get("/abilities/unknown-ability").status)
    }

    @Test
    fun `faiblesses et resistances de type, mises en cache`() = appTest {
        val response = client.get("/pokemon/bulbasaur/type-defenses")
        assertEquals(HttpStatusCode.OK, response.status)
        val defenses = json.decodeFromString<TypeDefenses>(response.bodyAsText())

        assertEquals(listOf("grass", "poison"), defenses.types)
        assertEquals(18, defenses.multipliers.size)
        assertEquals(setOf("fire", "ice", "flying", "psychic"), defenses.weaknesses.map { it.type }.toSet())
        assertTrue(defenses.weaknesses.all { it.multiplier == 2.0 })
        // Résistances : la plus forte d'abord (Plante ×¼)
        assertEquals(TypeMultiplier("grass", 0.25), defenses.resistances.first())
        assertEquals(5, defenses.resistances.size)
        assertTrue(defenses.immunities.isEmpty())
        assertTrue(defenses.abilityNotes.isEmpty()) // Engrais et Chlorophylle ne changent rien

        val callsBefore = remoteCalls.get()
        client.get("/pokemon/1/type-defenses")
        assertEquals(callsBefore, remoteCalls.get()) // types en cache
        assertEquals(HttpStatusCode.NotFound, client.get("/pokemon/missingno/type-defenses").status)
    }

    @Test
    fun `forme regionale demandee directement, autres formes hors du pokedex`() = appTest {
        val form = json.decodeFromString<Pokemon>(client.get("/pokemon/chikorita-hisui").bodyAsText())
        assertEquals(10999, form.id)
        assertEquals(152, form.speciesId)
        assertEquals("Germignon de Hisui", form.nameFr)
        assertEquals("hisui", form.regionalForm)

        // Une méga ouverte directement a sa fiche, mais n'apparaît pas dans le Pokédex
        val mega = json.decodeFromString<Pokemon>(client.get("/pokemon/chikorita-mega").bodyAsText())
        assertEquals(null, mega.regionalForm)
        val list = json.decodeFromString<Page<PokemonSummary>>(client.get("/pokemon").bodyAsText())
        assertEquals(listOf(10999), list.items.map { it.id })
    }

    @Test
    fun `mise a jour du pokedex en arriere-plan avec avancement`() = appTest {
        suspend fun status() = json.decodeFromString<PokedexUpdateStatus>(client.get("/pokedex/update").bodyAsText())
        suspend fun waitForEnd(): PokedexUpdateStatus {
            repeat(200) {
                val s = status()
                if (s.state != UpdateState.RUNNING) return s
                kotlinx.coroutines.delay(25)
            }
            error("La mise à jour ne s'est pas terminée")
        }

        assertEquals(UpdateState.IDLE, status().state)
        // Les valeurs par défaut (0, liste vide, null) sont bien présentes dans le JSON
        val raw = client.get("/pokedex/update").bodyAsText()
        listOf("\"state\": \"idle\"", "\"processed\": 0", "\"failed\": [", "\"error\": null").forEach {
            assertContains(raw, it)
        }

        val start = client.post("/pokedex/update")
        assertEquals(HttpStatusCode.Accepted, start.status)
        assertEquals("missing", json.decodeFromString<PokedexUpdateStatus>(start.bodyAsText()).mode)
        val first = waitForEnd()
        assertEquals(UpdateState.COMPLETED, first.state)
        assertEquals(4, first.total)
        assertEquals(4, first.processed)
        assertEquals(4, first.imported)
        assertEquals(1, first.regionalForms)
        assertEquals(0, first.failedCount)
        assertTrue(first.finishedAt != null)
        val list = json.decodeFromString<Page<PokemonSummary>>(client.get("/pokemon").bodyAsText())
        assertEquals(5, list.total)

        // Rien à faire la deuxième fois
        client.post("/pokedex/update?mode=missing")
        val second = waitForEnd()
        assertEquals(4, second.alreadyUpToDate)
        assertEquals(0, second.imported)

        // Tout actualiser : tous les Pokémon sont récupérés à nouveau
        client.post("/pokedex/update?mode=all")
        val all = waitForEnd()
        assertEquals("all", all.mode)
        assertEquals(4, all.imported)
        assertEquals(0, all.alreadyUpToDate)

        assertEquals(HttpStatusCode.BadRequest, client.post("/pokedex/update?mode=tout").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/pokedex/update").status) // rien à arrêter
    }

    @Test
    fun `lieux d'une region et pokemon rencontres, mis en cache`() = appTest {
        client.get("/pokemon/bulbasaur") // Bulbizarre en base pour son nom français

        val region = json.decodeFromString<RegionLocations>(client.get("/regions/kanto/locations").bodyAsText())
        assertEquals("Kanto", region.nameFr)
        // Villes, puis routes par numéro, puis grottes
        assertEquals(listOf("Bourg Palette", "Route 1", "Route 2", "Mont Sélénite"), region.locations.map { it.nameFr })
        assertEquals(listOf(LocationCategory.CITY, LocationCategory.ROUTE, LocationCategory.ROUTE, LocationCategory.NATURE), region.locations.map { it.category })
        assertEquals(listOf(false, true, false, false), region.locations.map { it.hasEncounters })

        val route1 = json.decodeFromString<LocationDetail>(client.get("/locations/kanto-route-1").bodyAsText())
        assertEquals("Route 1", route1.nameFr)
        // Jeux du plus récent au plus ancien
        // Jeux du plus récent au plus ancien ; la version japonaise d'origine est distinguée
        assertEquals(listOf("Let's Go, Pikachu", "Vert Feuille", "Rouge", "Rouge (Japon)"), route1.versions.map { it.label })
        val area = route1.areas.single()
        assertEquals(null, area.label) // zone principale
        val red = area.encounters.single { it.version == "red" }
        assertEquals("Bulbizarre", red.nameFr)
        assertEquals(1, red.speciesId)
        // Deux lignes « walk » regroupées : niveaux 2 à 4, 50 % au total
        assertEquals(listOf(EncounterMethod("walk", "Herbes hautes", 2, 4, 50)), red.methods)

        // Deuxième consultation : tout vient de la base
        val callsBefore = remoteCalls.get()
        client.get("/regions/kanto/locations")
        assertEquals(route1, json.decodeFromString<LocationDetail>(client.get("/locations/kanto-route-1").bodyAsText()))
        assertEquals(callsBefore, remoteCalls.get())

        assertEquals(HttpStatusCode.NotFound, client.get("/regions/orre/locations").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/locations/nowhere").status)
    }

    @Test
    fun `jeu d'aventure - starter, route, combats, progression et arene`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

        assertEquals(false, game(client.get("/game")).started)
        assertEquals(HttpStatusCode.BadRequest, post("/new", """{"starter":"pikachu"}""").status)

        var v = game(post("/new", """{"starter":"bulbasaur"}"""))
        assertEquals("Bulbizarre", v.team.single().name)
        assertEquals(5, v.team.single().level)
        assertEquals(1, v.caught)
        assertEquals(15, v.levelCap)
        assertEquals("Bourg Palette", v.steps[0].nameFr)
        assertEquals(1, v.unlockedStep) // la Route 1 est la prochaine étape
        assertEquals(HttpStatusCode.BadRequest, post("/travel", """{"step":3}""").status)
        val noWild = post("/explore") // pas de Pokémon sauvage à Bourg Palette (données simulées)
        assertEquals(HttpStatusCode.BadRequest, noWild.status)
        assertContains(noWild.bodyAsText(), "Aucun Pokémon sauvage ici")

        v = game(post("/travel", """{"step":1}"""))
        assertEquals(1, v.currentStep)
        assertTrue(v.steps[1].hasEncounters)
        assertEquals(10, v.steps[1].winsNeeded)
        // Route 1 simulée : seulement des Bulbizarre, déjà vus avec le starter
        assertEquals(1 to 1, v.steps[1].speciesSeen to v.steps[1].speciesTotal)
        assertEquals(listOf(RouteSpecies(1, true, "Bulbizarre", v.team.single().spriteUrl)), v.steps[1].species)

        // Combats sur la Route 1 jusqu'à la franchir (retour au Centre Pokémon en cas de défaite)
        var battles = 0
        while (!v.steps[1].cleared && battles < 80) {
            assertTrue(v.steps[1].wins < 10, "franchie seulement à 10 victoires")
            if (v.currentStep != 1) v = game(post("/travel", """{"step":1}"""))
            if (v.team.all { it.fainted }) { v = game(post("/travel", """{"step":0}""")); v = game(post("/heal")); continue }
            v = game(post("/explore"))
            assertEquals(BattleKind.WILD, v.battle!!.kind)
            while (v.battle!!.outcome == null) {
                val b = v.battle!!
                v = if (b.mustSwitch) game(post("/battle/switch", """{"uid":"${v.team.first { !it.fainted }.uid}"}"""))
                else game(post("/battle/attack", """{"move":0}"""))
            }
            assertTrue(v.battle!!.log.isNotEmpty())
            if (v.battle!!.recruit != null) v = game(post("/battle/recruit", """{"accept":true}"""))
            v = game(post("/battle/close"))
            battles++
        }
        assertTrue(v.steps[1].cleared, "Route 1 non franchie après $battles combats")
        assertEquals(10, v.steps[1].wins)
        assertTrue(v.unlockedStep > 1)

        // Les lieux absents de PokéAPI simulé sont franchis d'office : on arrive à la première arène (Argenta)
        val gymStep = v.steps.first { it.gym != null }
        assertEquals(gymStep.index, v.unlockedStep)
        assertEquals("grass", gymStep.gym!!.type) // type le plus fréquent avant l'arène (Bulbizarre sur la Route 1)
        v = game(post("/travel", """{"step":${gymStep.index}}"""))
        v = game(post("/heal"))
        v = game(post("/gym"))
        assertEquals(BattleKind.GYM, v.battle!!.kind)
        assertEquals(HttpStatusCode.BadRequest, post("/battle/flee").status) // pas de fuite en arène

        // Profil : badges (aucun obtenu), premier badge : type connu car l'arène est accessible
        val badges = json.decodeFromString<BadgesView>(client.get("/game/badges").bodyAsText())
        assertEquals(16, badges.total) // 8 arènes de Kanto, 8 de Johto
        assertEquals(0, badges.obtained)
        assertEquals("Badge Plante", badges.badges.first().name)
        assertEquals(false, badges.badges.first().obtained)
        assertEquals("Niveau maximum : 15 → 22", badges.badges.first().effects.first())

        assertEquals(HttpStatusCode.NoContent, client.delete("/game").status)
        assertEquals(false, game(client.get("/game")).started)
    }

    @Test
    fun `aventure - route franchie avec 10 victoires et toutes ses especes vues`() = appTest {
        suspend fun view() = json.decodeFromString<GameView>(client.get("/game").bodyAsText())
        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"squirtle"}""") }
        // 10 victoires sur la Route 1, mais Bulbizarre (seule espèce de la route simulée) jamais vu
        editSave { save -> JsonObject(save + mapOf("wins" to json.parseToJsonElement("""{"1": 10}"""), "seen" to json.parseToJsonElement("[7]"), "caught" to json.parseToJsonElement("[7]"))) }
        var v = view()
        assertEquals(false, v.steps[1].cleared)
        assertEquals(0 to 1, v.steps[1].speciesSeen to v.steps[1].speciesTotal)
        assertEquals(1, v.unlockedStep)
        // Une rencontre : Bulbizarre est vu, la route est franchie
        client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":1}""") }
        v = json.decodeFromString<GameView>(client.post("/game/explore").bodyAsText())
        assertEquals(true, v.steps[1].cleared)
        assertTrue(v.unlockedStep > 1)

        // Partie d'avant la règle : une route franchie avec 3 victoires le reste
        client.post("/game/battle/flee")
        editSave { save ->
            JsonObject(save + mapOf(
                "wins" to json.parseToJsonElement("""{"1": 3}"""), "seen" to json.parseToJsonElement("[7]"),
                "clearedSteps" to json.parseToJsonElement("[]"), "routeRules" to JsonPrimitive(0),
            ))
        }
        v = view()
        assertEquals(true, v.steps[1].cleared)
    }

    @Test
    fun `aventure - nouvelle attaque apprise des la montee de niveau, en plein combat`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        // Bulbizarre niveau 2, juste avant le niveau 3 où il apprend Fouet Lianes (données simulées), et ne connaissant que Charge
        editSave { save ->
            val team = save["team"]!!.jsonArray
            val moves = team[0].jsonObject["moves"]!!.jsonArray.filter { it.jsonObject["name"]!!.jsonPrimitive.content == "tackle" }
            val first = JsonObject(team[0].jsonObject + mapOf("level" to JsonPrimitive(2), "xp" to JsonPrimitive(26), "moves" to JsonArray(moves), "knownMoves" to JsonArray(moves)))
            JsonObject(save + ("team" to JsonArray(listOf(first))))
        }
        var v = game(post("/travel", """{"step":1}"""))
        assertEquals(listOf("Charge"), v.team[0].moves.map { it.nameFr })

        var learned = false
        repeat(20) {
            if (learned) return@repeat
            // Après une défaite, on est ramené (soigné) au Centre Pokémon de Bourg Palette
            if (v.currentStep != 1) v = game(post("/travel", """{"step":1}"""))
            v = game(post("/explore"))
            while (v.battle!!.outcome == null && !learned) {
                v = game(post("/battle/attack", """{"move":0}"""))
                if (v.battle!!.log.any { it == "Bulbizarre apprend Fouet Lianes !" }) {
                    learned = true
                    // Attaque disponible tout de suite, avant la fin du combat
                    assertTrue(v.battle!!.active.moves.any { it.name == "vine-whip" })
                }
            }
            if (v.battle != null) {
                while (v.battle!!.outcome == null) v = game(post("/battle/attack", """{"move":0}"""))
                if (v.battle!!.recruit != null) v = game(post("/battle/recruit", """{"accept":false}"""))
                v = game(post("/battle/close"))
            }
        }
        assertTrue(learned, "Fouet Lianes jamais appris")
        assertTrue(v.team[0].moves.any { it.name == "vine-whip" })
    }

    @Test
    fun `aventure - attaques qui drainent et qui frappent plusieurs fois`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        // Attaques sans échec possible : Méga-Sangsue (draine 50 %) et un double coup faible
        val drain = """{"name":"mega-drain","nameFr":"Méga-Sangsue","type":"grass","damageClass":"special","power":40,"accuracy":null,"drain":50}"""
        val double = """{"name":"double-kick","nameFr":"Double Pied","type":"fighting","damageClass":"physical","power":5,"accuracy":null,"minHits":2,"maxHits":2}"""
        editSave { save ->
            val team = save["team"]!!.jsonArray
            val moves = json.parseToJsonElement("[$drain, $double]")
            val first = JsonObject(team[0].jsonObject + mapOf("level" to JsonPrimitive(30), "xp" to JsonPrimitive(27000), "hp" to JsonPrimitive(5), "moves" to moves))
            JsonObject(save + ("team" to JsonArray(listOf(first))))
        }
        game(post("/travel", """{"step":1}"""))
        var v = game(post("/explore"))
        v = game(post("/battle/attack", """{"move":0}"""))
        val log = v.battle!!.log
        assertTrue(log.any { it.contains("a son énergie drainée ! Bulbizarre récupère") }, log.toString())
        assertTrue(v.team[0].hp > 5)

        var multi = false
        repeat(10) {
            if (multi) return@repeat
            if (v.battle?.outcome != null) {
                if (v.battle!!.recruit != null) v = game(post("/battle/recruit", """{"accept":false}"""))
                v = game(post("/battle/close"))
                if (v.currentStep != 1) game(post("/travel", """{"step":1}"""))
                v = game(post("/explore"))
            }
            v = game(post("/battle/attack", """{"move":1}"""))
            multi = v.battle!!.log.any { it == "Touché 2 fois !" }
        }
        assertTrue(multi, "Double Pied n'a jamais frappé deux fois")
    }

    @Test
    fun `aventure - attaques qui modifient les statistiques`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        val swordsDance = """{"name":"swords-dance","nameFr":"Danse Lames","type":"normal","damageClass":"status","power":0,"accuracy":null,"statChanges":{"attack":2},"statSelf":true}"""
        val growl = """{"name":"growl","nameFr":"Rugissement","type":"normal","damageClass":"status","power":0,"accuracy":null,"statChanges":{"attack":-1}}"""
        val tackle = """{"name":"tackle","nameFr":"Charge","type":"normal","damageClass":"physical","power":1,"accuracy":null}"""
        editSave { save ->
            val team = save["team"]!!.jsonArray
            val first = JsonObject(team[0].jsonObject + mapOf(
                "level" to JsonPrimitive(30), "xp" to JsonPrimitive(27000), "hp" to JsonPrimitive(90),
                "moves" to json.parseToJsonElement("[$swordsDance, $growl, $tackle]"),
            ))
            JsonObject(save + ("team" to JsonArray(listOf(first))))
        }
        game(post("/travel", """{"step":1}"""))
        game(post("/explore"))
        var v = game(post("/battle/attack", """{"move":0}"""))
        assertTrue(v.battle!!.log.contains("L'Attaque de Bulbizarre augmente beaucoup !"), v.battle!!.log.toString())
        assertEquals(mapOf("attack" to 2), v.battle!!.active.stages)
        v = game(post("/battle/attack", """{"move":1}"""))
        assertTrue(v.battle!!.log.any { it.matches(Regex("L'Attaque de .+ adverse baisse !")) }, v.battle!!.log.toString())
        assertEquals(-1, v.battle!!.enemy.stages["attack"])
        // Fuite : les changements de statistiques disparaissent
        v = game(post("/battle/flee"))
        assertEquals(emptyMap(), v.team[0].stages)
    }

    @Test
    fun `aventure - confusion et peur`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        val confuseRay = """{"name":"confuse-ray","nameFr":"Onde Folie","type":"ghost","damageClass":"status","power":0,"accuracy":null,"ailment":"confusion"}"""
        // Morsure qui apeure presque toujours, et Bluff (100 % : premier tour seulement) ; dégâts minimes pour durer
        val bite = """{"name":"bite","nameFr":"Morsure","type":"dark","damageClass":"physical","power":1,"accuracy":null,"flinchChance":99}"""
        val fakeOut = """{"name":"fake-out","nameFr":"Bluff","type":"normal","damageClass":"physical","power":1,"accuracy":null,"flinchChance":100}"""
        editSave { save ->
            val team = save["team"]!!.jsonArray
            val first = JsonObject(team[0].jsonObject + mapOf(
                "level" to JsonPrimitive(40), "xp" to JsonPrimitive(64000), "hp" to JsonPrimitive(120),
                "base" to json.parseToJsonElement("""{"hp":45,"attack":5,"defense":200,"special-attack":5,"special-defense":200,"speed":200}"""),
                "moves" to json.parseToJsonElement("[$fakeOut, $confuseRay, $bite]"),
            ))
            JsonObject(save + ("team" to JsonArray(listOf(first))))
        }
        game(post("/travel", """{"step":1}"""))
        game(post("/explore"))
        // Bluff au premier tour : l'adversaire, plus lent, a peur
        var v = game(post("/battle/attack", """{"move":0}"""))
        assertTrue(v.battle!!.log.any { it.endsWith("adverse a peur ! Il ne peut pas attaquer.") }, v.battle!!.log.toString())
        // … mais plus ensuite
        v = game(post("/battle/attack", """{"move":0}"""))
        assertTrue(v.battle!!.log.takeLast(4).contains("Mais cela échoue."), v.battle!!.log.toString())

        v = game(post("/battle/attack", """{"move":1}"""))
        assertTrue(v.battle!!.log.any { it.endsWith("adverse devient confus !") }, v.battle!!.log.toString())
        assertTrue(v.battle!!.enemy.confused)
        v = game(post("/battle/attack", """{"move":1}"""))
        // Lignes depuis la deuxième Onde Folie
        val afterSecond = v.battle!!.log.drop(v.battle!!.log.indexOfLast { it.endsWith("utilise Onde Folie !") })
        assertTrue(afterSecond.any { it.contains("est déjà confus") || it.contains("n'est plus confus") }, v.battle!!.log.toString())
        repeat(6) { if (v.battle!!.outcome == null) v = game(post("/battle/attack", """{"move":2}""")) }
        assertTrue(v.battle!!.log.any { it.endsWith("est confus…") }, v.battle!!.log.toString())
        if (v.battle!!.outcome == null) v = game(post("/battle/flee"))
        assertEquals(false, v.team[0].confused)
    }

    @Test
    fun `aventure - verification des pokemon a l'ouverture`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        assertEquals(false, game(client.post("/game/verify")).started)
        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") }
        // Partie saine : rien à corriger
        assertEquals(emptyList(), game(client.post("/game/verify")).corrections)

        // Sauvegarde abîmée : mauvais nom et types, Fouet Lianes faussé, attaque connue perdue, 5 attaques dont une en double, trop de PV
        editSave { save ->
            val team = save["team"]!!.jsonArray
            val p1 = team[0].jsonObject
            val moves = p1["moves"]!!.jsonArray.map { m ->
                if (m.jsonObject["name"]!!.jsonPrimitive.content == "vine-whip") JsonObject(m.jsonObject + ("power" to JsonPrimitive(999))) else m
            }
            val first = JsonObject(p1 + mapOf(
                "nameFr" to JsonPrimitive("Mauvais nom"), "types" to json.parseToJsonElement("""["fire"]"""), "hp" to JsonPrimitive(999),
                "moves" to JsonArray(moves + moves + moves.take(1)), "knownMoves" to JsonArray(moves.drop(1)),
            ))
            JsonObject(save + ("team" to JsonArray(listOf(first))))
        }
        val v = game(client.post("/game/verify"))
        val p1 = v.team[0]
        assertEquals("Bulbizarre", p1.name)
        assertEquals(listOf("grass", "poison"), p1.types)
        assertEquals(p1.maxHp, p1.hp)
        assertEquals(45, p1.moves.single { it.name == "vine-whip" }.power)
        assertEquals(listOf("vine-whip", "tackle"), p1.moves.map { it.name })
        assertEquals(1, v.corrections.size, v.corrections.toString())
        val line = v.corrections.single()
        assertTrue(line.startsWith("Bulbizarre : informations") && "détail des attaques" in line && "attaques équipées" in line && "PV" in line, line)
        // Corrigé et enregistré : plus rien à corriger
        assertEquals(emptyList(), game(client.post("/game/verify")).corrections)
    }

    @Test
    fun `aventure - motisdex des pokemon vus et rejoints`() = appTest {
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/game/dex").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/game/dex").status) // pas encore de partie
        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") }
        var dex = json.decodeFromString<DexView>(client.get("/game/dex").bodyAsText())
        assertEquals(151, dex.entries.size)
        assertEquals(1, dex.caught)
        assertEquals(1, dex.seen)
        val bulbasaur = dex.entries.first()
        assertEquals(DexStatus.CAUGHT, bulbasaur.status)
        assertEquals("Bulbizarre", bulbasaur.name)
        assertEquals(1, bulbasaur.owned)
        // Espèce jamais rencontrée : rien n'est révélé
        val ivysaur = dex.entries[1]
        assertEquals(DexEntry(2, DexStatus.UNKNOWN), ivysaur)

        // Rencontre sur la Route 1 (Bulbizarre dans les données simulées), puis Herbizarre « vu » dans la sauvegarde
        client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":1}""") }
        client.post("/game/explore")
        editSave { save -> JsonObject(save + ("seen" to json.parseToJsonElement("[1, 2]"))) }
        dex = json.decodeFromString<DexView>(client.get("/game/dex").bodyAsText())
        assertEquals(DexStatus.SEEN, dex.entries[1].status)
        assertEquals("Herbizarre", dex.entries[1].name)
        assertEquals(0, dex.entries[1].owned)
        assertEquals(2, dex.seen)
        assertEquals(1, dex.caught)
    }

    @Test
    fun `aventure - les succes donnent les objets d'evolution`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        var list = json.decodeFromString<AchievementsView>(client.get("/game/achievements").bodyAsText())
        assertEquals(0, list.done)
        assertEquals(Achievements.ALL.size, list.total)

        // Bulbizarre très fort : première victoire assurée
        editSave { save ->
            val team = save["team"]!!.jsonArray
            val strong = JsonObject(team[0].jsonObject + mapOf("level" to JsonPrimitive(40), "xp" to JsonPrimitive(64000), "hp" to JsonPrimitive(200)))
            JsonObject(save + ("team" to JsonArray(listOf(strong))))
        }
        game(post("/travel", """{"step":1}"""))
        var v = game(post("/explore"))
        while (v.battle!!.outcome == null) v = game(post("/battle/attack", """{"move":0}"""))
        assertEquals(BattleOutcome.WON, v.battle!!.outcome)
        // Annoncé dans le journal du combat ; aucun objet lâché par le Pokémon vaincu
        assertTrue(v.battle!!.log.contains("Succès débloqué : Premier combat ! Vous recevez : Pierre Plante."), v.battle!!.log.toString())
        assertTrue(v.battle!!.log.none { "laisse tomber" in it })
        assertEquals(listOf("leaf-stone" to 1), v.items.map { it.item to it.count })
        // Notification : signalée par la réponse de l'action, pas par les lectures suivantes
        assertEquals(listOf(UnlockedAchievement("wins-1", "Premier combat", listOf("Pierre Plante"))), v.unlocked)
        assertEquals(emptyList(), json.decodeFromString<GameView>(client.get("/game").bodyAsText()).unlocked)
        list = json.decodeFromString<AchievementsView>(client.get("/game/achievements").bodyAsText())
        assertEquals(listOf("wins-1"), list.achievements.filter { it.done }.map { it.id })
        assertEquals(1, list.achievements.single { it.id == "wins-10" }.progress)

        // Débloqué une seule fois
        if (v.battle!!.recruit != null) v = game(post("/battle/recruit", """{"accept":false}"""))
        v = game(post("/battle/close"))
        assertEquals(1, v.items.single { it.item == "leaf-stone" }.count)
    }

    @Test
    fun `aventure - entrainement d'un pokemon laisse sur un lieu`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        // Deuxième Pokémon dans l'équipe (copie du starter)
        editSave { save ->
            val first = save["team"]!!.jsonArray[0].jsonObject
            JsonObject(save + ("team" to JsonArray(listOf(first, JsonObject(first + ("uid" to JsonPrimitive("p2")))))))
        }
        // Pas d'entraînement en ville
        assertEquals(HttpStatusCode.BadRequest, post("/training/leave", """{"uid":"p2"}""").status)
        game(post("/travel", """{"step":1}"""))
        var v = game(post("/training/leave", """{"uid":"p2"}"""))
        assertEquals(listOf("p1"), v.team.map { it.uid })
        assertEquals(listOf(1 to "p2"), v.training.map { it.step to it.fighter.uid })
        assertEquals(10, v.trainingSeconds)
        assertEquals(HttpStatusCode.BadRequest, post("/training/leave", """{"uid":"p1"}""").status) // déjà un Pokémon ici
        assertEquals(HttpStatusCode.BadRequest, post("/training/swap", """{"uid":"inconnu"}""").status)

        // 100 secondes plus tard : 10 combats simulés contre les Pokémon de la Route 1
        editSave { save ->
            val training = save["training"]!!.jsonObject
            val t = training["1"]!!.jsonObject
            val past = System.currentTimeMillis() - 100_000
            JsonObject(save + ("training" to JsonObject(mapOf("1" to JsonObject(t + ("lastSync" to JsonPrimitive(past)))))))
        }
        v = game(client.get("/game"))
        val trainee = v.training.single()
        assertEquals(10, trainee.fights)
        assertTrue(trainee.xpGained > 0)
        assertTrue(trainee.fighter.level > 5, "niveau ${trainee.fighter.level}")
        assertEquals(trainee.fighter.maxHp, trainee.fighter.hp)
        assertTrue(trainee.log.any { it.contains("monte au niveau") }, trainee.log.toString())

        // Ailleurs, on ne peut pas le reprendre
        game(post("/travel", """{"step":0}"""))
        assertEquals(HttpStatusCode.BadRequest, post("/training/retrieve").status)
        // De retour : échange avec p1, qui prend sa place à l'entraînement
        game(post("/travel", """{"step":1}"""))
        v = game(post("/training/swap", """{"uid":"p1"}"""))
        assertEquals(listOf("p2"), v.team.map { it.uid })
        assertEquals(trainee.fighter.level, v.team.single().level)
        assertEquals("p1", v.training.single().fighter.uid)
        assertEquals(0, v.training.single().fights)
        assertTrue(v.lastEvent!!.startsWith("Bulbizarre revient de l'entraînement (10 combats"), v.lastEvent)
        // Reprise : retour dans l'équipe
        v = game(post("/training/retrieve"))
        assertEquals(listOf("p2", "p1"), v.team.map { it.uid })
        assertEquals(emptyList(), v.training)
    }

    @Test
    fun `aventure - coup de pouce invisible tous les 10 combats, parfois chromatique`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"squirtle"}"""))
        game(post("/travel", """{"step":1}"""))
        // 10e combat sur la Route 1 : Bulbizarre (jamais vu) garanti, sans aucune annonce
        editSave { save -> JsonObject(save + ("boostCounters" to json.parseToJsonElement("""{"1": 9}"""))) }
        var v = game(post("/explore"))
        assertEquals("Bulbizarre", v.battle!!.enemy.name)
        assertTrue(v.battle!!.log.none { "pouce" in it }, v.battle!!.log.toString())
        game(post("/battle/flee")); game(post("/battle/close"))

        // Sur de nombreux coups de pouce, environ 1 sur 10 est chromatique, avec ses images chromatiques
        var shiny = 0
        var boosts = 0
        repeat(80) {
            editSave { save -> JsonObject(save + ("boostCounters" to json.parseToJsonElement("""{"1": 9}"""))) }
            v = game(post("/explore"))
            val counters = transaction(database) { GameSaveTable.selectAll().where { GameSaveTable.id eq 1 }.single()[GameSaveTable.data] }
                .let { Json.parseToJsonElement(it).jsonObject["boostCounters"].toString() }
            if (counters == """{"1":0}""") boosts++
            val enemy = v.battle!!.enemy
            if (enemy.shiny) {
                shiny++
                assertTrue(enemy.spriteUrl!!.contains("/shiny/"), enemy.spriteUrl)
                assertTrue(v.battle!!.log.first().contains("chromatique ✨"))
            }
            game(post("/battle/flee")); game(post("/battle/close"))
        }
        assertEquals(80, boosts, "coups de pouce")
        assertTrue(shiny in 1..25, "$shiny chromatiques sur 80")
        // Motisdex : Bulbizarre chromatique vu, avec son image chromatique
        var dex = json.decodeFromString<DexView>(client.get("/game/dex").bodyAsText())
        assertEquals(1, dex.shinySeen)
        assertEquals(0, dex.shinyCaught)
        assertTrue(dex.entries.first().shinySeen && !dex.entries.first().shinyCaught)
        assertTrue(dex.entries.first().shinyArtworkUrl!!.contains("/shiny/"))
        assertEquals(null, dex.entries[6].shinyArtworkUrl) // Carapuce : chromatique jamais vu
        // Un chromatique dans l'équipe (recrue, évolution…) : rejoint en chromatique
        editSave { save ->
            val team = save["team"]!!.jsonArray
            JsonObject(save + ("team" to JsonArray(listOf(JsonObject(team[0].jsonObject + ("shiny" to JsonPrimitive(true)))))))
        }
        game(post("/travel", """{"step":0}"""))
        dex = json.decodeFromString<DexView>(client.get("/game/dex").bodyAsText())
        assertEquals(2, dex.shinySeen)
        assertEquals(1, dex.shinyCaught)
        assertTrue(dex.entries[6].shinyCaught)
        game(post("/travel", """{"step":1}"""))
        // Combats ordinaires : jamais chromatiques
        repeat(5) {
            editSave { save -> JsonObject(save + ("boostCounters" to json.parseToJsonElement("""{"1": 0}"""))) }
            v = game(post("/explore"))
            val saved = transaction(database) { GameSaveTable.selectAll().where { GameSaveTable.id eq 1 }.single()[GameSaveTable.data] }
            assertEquals(false, v.battle!!.enemy.shiny, "${v.battle!!.log} ${Json.parseToJsonElement(saved).jsonObject["boostCounters"]}")
            game(post("/battle/flee")); game(post("/battle/close"))
        }
    }

    @Test
    fun `aventure - pas d'evolution hors de Kanto`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        // Bulbizarre très fort, et un Germignon (Johto) niveau 20 : il évoluerait en Macronium au niveau 16
        client.get("/pokemon/chikorita")
        editSave { save ->
            val first = save["team"]!!.jsonArray[0].jsonObject
            val strong = JsonObject(first + mapOf("level" to JsonPrimitive(40), "xp" to JsonPrimitive(64000), "hp" to JsonPrimitive(200)))
            val chikorita = JsonObject(first + mapOf(
                "uid" to JsonPrimitive("p2"), "pokemonId" to JsonPrimitive(152), "speciesId" to JsonPrimitive(152),
                "level" to JsonPrimitive(20), "xp" to JsonPrimitive(8000),
            ))
            JsonObject(save + ("team" to JsonArray(listOf(strong, chikorita))))
        }
        game(post("/travel", """{"step":1}"""))
        var v = game(post("/explore"))
        while (v.battle!!.outcome == null) v = game(post("/battle/attack", """{"move":0}"""))
        // Fin du combat : Bulbizarre (Kanto) évolue, pas Germignon
        assertTrue(v.battle!!.log.any { it == "Quoi ? Bulbizarre évolue en Herbizarre !" }, v.battle!!.log.toString())
        assertTrue(v.battle!!.log.none { "Germignon évolue" in it }, v.battle!!.log.toString())
        assertEquals(152, v.team.single { it.uid == "p2" }.pokemonId)
        // Ni par objet : Macronium (hors Kanto) n'est jamais proposé
        assertTrue(v.items.none { "p2" in it.usableOn })
    }

    @Test
    fun `aventure - un pokemon d'une region pas encore debloquee redevient sa pre-evolution de Kanto`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") }
        listOf("eevee", "vaporeon", "leafeon", "chikorita", "bayleef").forEach { client.get("/pokemon/$it") }
        // Phyllali chromatique niveau 30 dans l'équipe, Aquali (Kanto) et Macronium (Johto, sans pré-évolution de Kanto) dans la boîte
        editSave { save ->
            val first = save["team"]!!.jsonArray[0].jsonObject
            fun copy(uid: String, id: Int, extra: Map<String, JsonElement> = emptyMap()) = JsonObject(first + mapOf(
                "uid" to JsonPrimitive(uid), "pokemonId" to JsonPrimitive(id), "speciesId" to JsonPrimitive(id),
            ) + extra)
            JsonObject(save + mapOf(
                "team" to JsonArray(listOf(first, copy("p2", 470, mapOf("level" to JsonPrimitive(30), "xp" to JsonPrimitive(27000), "shiny" to JsonPrimitive(true))))),
                "box" to JsonArray(listOf(copy("p3", 134), copy("p4", 153))),
            ))
        }
        val v = game(client.post("/game/verify"))
        val p2 = v.team.single { it.uid == "p2" }
        assertEquals(133, p2.pokemonId) // Évoli
        assertEquals("Évoli", p2.name)
        assertEquals(listOf("normal"), p2.types)
        assertEquals(30, p2.level)
        assertEquals(true, p2.shiny)
        assertEquals(134, v.box.single { it.uid == "p3" }.pokemonId) // Aquali reste Aquali
        assertEquals(153, v.box.single { it.uid == "p4" }.pokemonId) // conservé
        assertTrue(v.corrections.contains("Phyllali vient d'une région pas encore débloquée : il redevient Évoli."), v.corrections.toString())
        assertTrue(v.corrections.any { it.contains("pas de pré-évolution de Kanto") }, v.corrections.toString())
        // Corrigé une fois pour toutes : seul Macronium est encore signalé
        val again = game(client.post("/game/verify"))
        assertTrue(again.corrections.none { "redevient" in it }, again.corrections.toString())
    }

    @Test
    fun `aventure - pension, oeuf et eclosion`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        game(post("/new", """{"starter":"bulbasaur"}"""))
        // Deux autres Bulbizarre : un dans l'équipe, un dans la boîte
        editSave { save ->
            val first = save["team"]!!.jsonArray[0].jsonObject
            fun copy(uid: String) = JsonObject(first + ("uid" to JsonPrimitive(uid)))
            JsonObject(save + mapOf("team" to JsonArray(listOf(first, copy("p2"))), "box" to JsonArray(listOf(copy("p3"))), "uidCounter" to JsonPrimitive(3)))
        }
        // Bourg Palette (ville) : dépôt de p2 et p3
        var v = game(post("/daycare/deposit", """{"uid":"p2"}"""))
        assertEquals(1, v.dayCare!!.pokemon.size)
        assertEquals(HttpStatusCode.BadRequest, post("/daycare/deposit", """{"uid":"p1"}""").status) // dernier de l'équipe
        v = game(post("/daycare/deposit", """{"uid":"p3"}"""))
        assertEquals(listOf("p2", "p3"), v.dayCare!!.pokemon.map { it.uid })
        assertEquals(true, v.dayCare!!.compatible)
        assertTrue(v.dayCare!!.nextEggInSeconds!! in 290..300)
        assertEquals(HttpStatusCode.BadRequest, post("/daycare/egg").status) // pas encore d'œuf

        // 5 minutes plus tard : un œuf attend
        editSave { save ->
            val d = save["dayCare"]!!.jsonObject
            JsonObject(save + ("dayCare" to JsonObject(d + ("nextEggAt" to JsonPrimitive(System.currentTimeMillis() - 1000)))))
        }
        v = game(client.get("/game"))
        assertEquals(true, v.dayCare!!.eggWaiting)
        v = game(post("/daycare/egg"))
        assertEquals(listOf(10 to 10), v.eggs.map { it.fightsLeft to it.fightsTotal }) // 20 cycles → 10 combats
        assertEquals(false, v.dayCare!!.eggWaiting)

        // Sur la route, l'œuf éclot à la fin d'un combat
        editSave { save ->
            val egg = save["eggs"]!!.jsonArray[0].jsonObject
            JsonObject(save + ("eggs" to JsonArray(listOf(JsonObject(egg + ("fightsLeft" to JsonPrimitive(1)))))))
        }
        v = game(post("/travel", """{"step":1}"""))
        game(post("/explore"))
        game(post("/battle/flee"))
        v = game(post("/battle/close"))
        assertTrue(v.lastEvent!!.contains("L'œuf éclot ! C'est un Bulbizarre"), v.lastEvent)
        assertEquals(emptyList(), v.eggs)
        val baby = v.team.last()
        assertEquals(1, baby.level)
        assertEquals("p4", baby.uid)
        // Reprendre un Pokémon de la pension : seulement en ville
        assertEquals(HttpStatusCode.BadRequest, post("/daycare/withdraw", """{"uid":"p2"}""").status)
        game(post("/travel", """{"step":0}"""))
        v = game(post("/daycare/withdraw", """{"uid":"p2"}"""))
        assertTrue(v.team.any { it.uid == "p2" })
        assertEquals(null, v.dayCare!!.nextEggInSeconds) // plus de couple
    }

    @Test
    fun `aventure - seulement des pokemon de Kanto dans les rencontres`() = appTest {
        // La Route 1 simulée a aussi un Germignon (Johto) dans Vert Feuille : il n'apparaît jamais et ne compte pas
        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"squirtle"}""") }
        client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":1}""") }
        repeat(20) {
            val v = json.decodeFromString<GameView>(client.post("/game/explore").bodyAsText())
            assertEquals("Bulbizarre", v.battle!!.enemy.name)
            assertEquals(1, v.steps[1].speciesTotal)
            client.post("/game/battle/flee"); client.post("/game/battle/close")
        }
    }

    @Test
    fun `aventure - relacher un pokemon`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun release(uid: String) = client.post("/game/release") { contentType(ContentType.Application.Json); setBody("""{"uid":"$uid"}""") }
        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") }
        // Un Germignon (hors Kanto) dans l'équipe, un autre dans la boîte
        editSave { save ->
            val first = save["team"]!!.jsonArray[0].jsonObject
            fun chikorita(uid: String) = JsonObject(first + mapOf("uid" to JsonPrimitive(uid), "pokemonId" to JsonPrimitive(152), "speciesId" to JsonPrimitive(152)))
            JsonObject(save + mapOf("team" to JsonArray(listOf(first, chikorita("p2"))), "box" to JsonArray(listOf(chikorita("p3")))))
        }
        var v = game(release("p2"))
        assertEquals(listOf("p1"), v.team.map { it.uid })
        assertTrue(v.lastEvent!!.contains("a été relâché"), v.lastEvent)
        v = game(release("p3"))
        assertEquals(emptyList(), v.box)
        assertEquals(HttpStatusCode.BadRequest, release("p1").status) // dernier Pokémon de l'équipe
        assertEquals(HttpStatusCode.BadRequest, release("inconnu").status)
    }

    @Test
    fun `aventure - ligue au plateau indigo puis pokemon rares`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        client.post("/pokemon/import?limit=4")
        listOf("ivysaur", "venusaur", "eevee", "vaporeon").forEach { client.get("/pokemon/$it") }
        var v = game(post("/new", """{"starter":"bulbasaur"}"""))
        val league = v.steps.indexOfFirst { it.location == "indigo-plateau" }
        val cave = v.steps.indexOfFirst { it.location == "cerulean-cave" }
        val gyms = v.steps.filter { it.gym != null }.map { it.index }
        // 8 badges, Bulbizarre très fort, au Plateau Indigo ; types du Conseil connus des données simulées
        editSave { save ->
            val first = save["team"]!!.jsonArray[0].jsonObject
            // Florizarre (n'évolue plus) niveau 100, très fort
            val strong = JsonObject(first + mapOf(
                "pokemonId" to JsonPrimitive(3), "speciesId" to JsonPrimitive(3),
                "level" to JsonPrimitive(100), "xp" to JsonPrimitive(1_000_000), "hp" to JsonPrimitive(999),
                "base" to json.parseToJsonElement("""{"hp":255,"attack":255,"defense":255,"special-attack":255,"special-defense":255,"speed":255}"""),
            ))
            JsonObject(save + mapOf(
                "team" to JsonArray(listOf(strong)), "badges" to json.parseToJsonElement(gyms.toString()),
                "stepIndex" to JsonPrimitive(league), "lastCenter" to JsonPrimitive(league),
                "clearedSteps" to json.parseToJsonElement("[1]"),
                // Types dont les données simulées ont des Pokémon (hors Pokémon rares)
                "leagueTypes" to json.parseToJsonElement("""["grass", "poison", "grass", "poison"]"""),
            ))
        }
        v = game(client.get("/game"))
        assertEquals(false, v.steps[league].cleared) // la Grotte Azurée attend la victoire contre le Maître
        assertEquals(league, v.unlockedStep)
        assertEquals(5, v.league!!.opponents.size)
        assertEquals("Maître de la Ligue", v.league!!.opponents.last().name)

        // Les 4 membres puis le Maître, sans soin entre les combats
        for (stage in 0..4) {
            v = game(post("/league"))
            assertEquals(BattleKind.LEAGUE, v.battle!!.kind)
            assertEquals(v.league!!.opponents[stage].name, v.battle!!.title)
            assertEquals(false, v.battle!!.canFlee)
            while (v.battle!!.outcome == null) v = game(post("/battle/attack", """{"move":0}"""))
            assertEquals(BattleOutcome.WON, v.battle!!.outcome)
            v = game(post("/battle/close"))
            if (stage < 4) {
                assertEquals(stage + 1, v.league!!.stage)
                assertEquals(HttpStatusCode.BadRequest, post("/heal").status) // pas de répit
            }
        }
        assertEquals(1, v.league!!.championWins)
        assertEquals(0, v.league!!.stage)
        assertTrue(v.unlocked.any { it.id == "league" } || json.decodeFromString<AchievementsView>(client.get("/game/achievements").bodyAsText()).achievements.single { it.id == "league" }.done)
        assertTrue(v.steps[league].cleared)
        assertTrue(v.unlockedStep >= cave)

        // Pokémon rares sur la Route 1 (seul lieu simulé) : Salamèche, Carapuce, Évoli ; pas leurs évolutions
        val rare = v.steps[1].rare.map { it.number }.toSet()
        assertEquals(setOf(4, 7, 133), rare)
        // Ils n'apparaissent que par le coup de pouce
        game(post("/travel", """{"step":1}"""))
        repeat(10) {
            editSave { save -> JsonObject(save + ("boostCounters" to json.parseToJsonElement("""{"1": 9}"""))) }
            v = game(post("/explore"))
            assertTrue(v.battle!!.enemy.speciesId in rare, v.battle!!.enemy.name)
            assertTrue(v.battle!!.enemy.level >= 50)
            game(post("/battle/flee")); game(post("/battle/close"))
        }
        repeat(5) {
            editSave { save -> JsonObject(save + ("boostCounters" to json.parseToJsonElement("""{"1": 0}"""))) }
            v = game(post("/explore"))
            assertEquals("Bulbizarre", v.battle!!.enemy.name)
            game(post("/battle/flee")); game(post("/battle/close"))
        }
    }

    @Test
    fun `aventure - ligue fermee sans les 8 badges, defi repris du debut apres une defaite`() = appTest {
        client.post("/pokemon/import?limit=4")
        val v0 = json.decodeFromString<GameView>(client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") }.bodyAsText())
        val league = v0.steps.indexOfFirst { it.location == "indigo-plateau" }
        editSave { save -> JsonObject(save + mapOf("stepIndex" to JsonPrimitive(league), "lastCenter" to JsonPrimitive(league))) }
        assertEquals(false, json.decodeFromString<GameView>(client.get("/game").bodyAsText()).league!!.open)
        assertEquals(HttpStatusCode.BadRequest, client.post("/game/league").status)
        // Badges, Conseil au 3e membre ; quitter le plateau remet le défi à zéro
        val gyms = v0.steps.filter { it.gym != null }.map { it.index }
        editSave { save ->
            JsonObject(save + mapOf(
                "badges" to json.parseToJsonElement(gyms.toString()), "leagueStage" to JsonPrimitive(2),
                "clearedSteps" to json.parseToJsonElement("[1]"), // Route 1 franchie : le retour au plateau est possible
            ))
        }
        val moved = json.decodeFromString<GameView>(client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":0}""") }.bodyAsText())
        assertEquals(null, moved.league)
        val back = json.decodeFromString<GameView>(client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":$league}""") }.bodyAsText())
        assertEquals(0, back.league!!.stage)
    }

    @Test
    fun `aventure - johto s'ouvre avec les 151 pokemon de Kanto`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        var v = game(client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") })
        val newBark = v.steps.indexOfFirst { it.location == "new-bark-town" }
        assertEquals("johto", v.steps[newBark].region)
        assertEquals(false, v.johtoUnlocked)
        assertEquals(151, v.dexSize)
        // Tout Kanto franchi (lieux, arènes, Ligue), mais 150 Pokémon seulement : Johto reste fermé
        val gyms = v.steps.filter { it.gym != null && it.region == "kanto" }.map { it.index }
        editSave { save ->
            JsonObject(save + mapOf(
                "badges" to json.parseToJsonElement(gyms.toString()), "championWins" to JsonPrimitive(1),
                "clearedSteps" to json.parseToJsonElement("[1]"), "caught" to json.parseToJsonElement((1..150).toList().toString()),
            ))
        }
        v = game(client.get("/game"))
        assertEquals(newBark - 1, v.unlockedStep)
        // Le 151e : Johto s'ouvre à l'action suivante, avec un œuf de starter de Johto
        editSave { save -> JsonObject(save + ("caught" to json.parseToJsonElement((1..151).toList().toString()))) }
        v = game(client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":0}""") })
        assertEquals(true, v.johtoUnlocked)
        assertEquals(251, v.dexSize)
        assertTrue(v.lastEvent!!.contains("Johto est ouverte"), v.lastEvent)
        assertEquals(1, v.eggs.size)
        // Bourg Geon accessible (et, dans les données simulées sans rencontres, jusqu'à la première arène de Johto)
        assertEquals(v.steps.first { it.region == "johto" && it.gym != null }.index, v.unlockedStep)
        assertEquals(251, json.decodeFromString<DexView>(client.get("/game/dex").bodyAsText()).total)
        // Une seule fois
        v = game(client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":$newBark}""") })
        assertEquals(1, v.eggs.size)
        assertEquals(newBark, v.currentStep)
    }

    @Test
    fun `aventure - attaques equipees et connues`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun setMoves(vararg names: String) = client.put("/game/pokemon/p1/moves") {
            contentType(ContentType.Application.Json)
            setBody("""{"moves":[${names.joinToString { "\"$it\"" }}]}""")
        }
        client.post("/game/new") { contentType(ContentType.Application.Json); setBody("""{"starter":"bulbasaur"}""") }
        // Partie d'une version précédente : pas de liste d'attaques connues, elle est reconstituée
        editSave { save ->
            val team = save["team"]!!.jsonArray
            JsonObject(save + ("team" to JsonArray(listOf(JsonObject(team[0].jsonObject - "knownMoves")))))
        }
        var v = game(client.get("/game"))
        assertEquals(listOf("vine-whip", "tackle"), v.team[0].moves.map { it.name })
        assertEquals(emptyList(), v.team[0].knownMoves)

        // Une seule attaque équipée : l'autre reste connue
        v = game(setMoves("vine-whip"))
        assertEquals(listOf("vine-whip"), v.team[0].moves.map { it.name })
        assertEquals(listOf("tackle"), v.team[0].knownMoves.map { it.name })
        assertEquals(3, v.team[0].moves[0].level)

        // On la rééquipe, dans l'ordre choisi
        v = game(setMoves("tackle", "vine-whip"))
        assertEquals(listOf("tackle", "vine-whip"), v.team[0].moves.map { it.name })
        assertEquals(emptyList(), v.team[0].knownMoves)

        assertEquals(HttpStatusCode.BadRequest, setMoves().status)
        assertEquals(HttpStatusCode.BadRequest, setMoves("tackle", "tackle").status)
        val unknown = setMoves("razor-leaf")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertTrue(unknown.bodyAsText().contains("ne connaît pas"), unknown.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.put("/game/pokemon/p99/moves") {
            contentType(ContentType.Application.Json); setBody("""{"moves":["tackle"]}""")
        }.status)

        // Pas pendant un combat
        client.post("/game/travel") { contentType(ContentType.Application.Json); setBody("""{"step":1}""") }
        client.post("/game/explore")
        assertEquals(HttpStatusCode.BadRequest, setMoves("tackle").status)
    }

    @Test
    fun `aventure - reglages - pokemon suivant et recrues seulement nouvelles`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        suspend fun settings(next: Boolean, onlyNew: Boolean) = game(client.put("/game/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"nextEncounterButton":$next,"recruitOnlyNew":$onlyNew}""")
        })

        var v = game(post("/new", """{"starter":"bulbasaur"}"""))
        assertEquals(GameSettings(nextEncounterButton = true, recruitOnlyNew = false), v.settings)
        v = settings(next = true, onlyNew = true)
        assertEquals(true, v.settings.recruitOnlyNew)
        v = game(post("/travel", """{"step":1}"""))

        // La Route 1 simulée n'a que des Bulbizarre, déjà obtenu avec le starter : aucune proposition
        v = game(post("/explore"))
        var wins = 0
        var chained = 0
        val logs = mutableListOf<String>()
        while (wins < 3 && chained < 30) {
            while (v.battle!!.outcome == null) {
                v = if (v.battle!!.mustSwitch) game(post("/battle/switch", """{"uid":"${v.team.first { !it.fainted }.uid}"}"""))
                else game(post("/battle/attack", """{"move":0}"""))
            }
            val b = v.battle!!
            logs += b.log
            assertEquals(null, b.recruit)
            if (b.outcome == BattleOutcome.WON) {
                wins++
                assertTrue(b.canContinue) // « Pokémon suivant » proposé
                v = game(post("/battle/next")) // enchaîne directement
                assertEquals(null, v.battle!!.outcome)
                assertEquals(1, v.currentStep)
                chained++
            } else {
                assertEquals(false, b.canContinue) // défaite : retour au Centre Pokémon
                v = game(post("/battle/close"))
                v = game(post("/travel", """{"step":1}"""))
                v = game(post("/explore"))
            }
        }
        assertTrue(wins >= 3, "seulement $wins victoires")
        assertTrue(logs.any { it.contains("déjà dans votre Pokédex") })

        // Réglage désactivé : plus de « Pokémon suivant »
        settings(next = false, onlyNew = true)
        v = game(client.get("/game"))
        while (v.battle!!.outcome == null) {
            v = if (v.battle!!.mustSwitch) game(post("/battle/switch", """{"uid":"${v.team.first { !it.fainted }.uid}"}"""))
            else game(post("/battle/attack", """{"move":0}"""))
        }
        assertEquals(false, v.battle!!.canContinue)
        assertEquals(HttpStatusCode.BadRequest, post("/battle/attack", """{"move":0}""").status)
    }

    @Test
    fun `aventure - equipe modifiable depuis le navigateur (CORS PUT)`() = appTest {
        val preflight = client.options("/game/team") {
            header(HttpHeaders.Origin, "http://localhost:8081")
            header(HttpHeaders.AccessControlRequestMethod, "PUT")
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
    }

    @Test
    fun `aventure - recrue au choix et evolution par objet`() = appTest {
        suspend fun game(response: HttpResponse): GameView {
            assertTrue(response.status.isSuccess(), response.bodyAsText())
            return json.decodeFromString<GameView>(response.bodyAsText())
        }
        suspend fun post(path: String, body: String? = null) = client.post("/game$path") {
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

        var v = game(post("/new", """{"starter":"bulbasaur"}"""))
        v = game(post("/travel", """{"step":1}"""))

        // Combats jusqu'à ce qu'un Pokémon veuille nous rejoindre : on le refuse, puis on accepte le suivant
        var refused = false
        var accepted = false
        var battles = 0
        while (!accepted && battles < 60) {
            // Après une défaite, on est ramené au Centre Pokémon de Bourg Palette
            if (v.team.all { it.fainted }) { v = game(post("/travel", """{"step":0}""")); v = game(post("/heal")) }
            if (v.currentStep != 1) v = game(post("/travel", """{"step":1}"""))
            v = game(post("/explore"))
            while (v.battle!!.outcome == null) {
                v = if (v.battle!!.mustSwitch) game(post("/battle/switch", """{"uid":"${v.team.first { !it.fainted }.uid}"}"""))
                else game(post("/battle/attack", """{"move":0}"""))
            }
            val recruit = v.battle!!.recruit
            if (recruit != null) {
                val teamSize = v.team.size
                assertEquals(HttpStatusCode.BadRequest, post("/battle/attack", """{"move":0}""").status) // combat terminé
                v = game(post("/battle/recruit", """{"accept":${refused}}"""))
                assertEquals(null, v.battle!!.recruit)
                if (!refused) { assertEquals(teamSize, v.team.size); refused = true } else { assertEquals(teamSize + 1, v.team.size); accepted = true }
            }
            v = game(post("/battle/close"))
            battles++
        }
        assertTrue(accepted, "Aucun Pokémon n'a voulu nous rejoindre en $battles combats")

        // Sortir un Pokémon de l'équipe vers la boîte
        val second = v.team[1].uid
        v = game(client.put("/game/team") { contentType(ContentType.Application.Json); setBody("""{"uids":["${v.team[0].uid}"]}""") })
        assertEquals(1, v.team.size)
        assertTrue(v.box.any { it.uid == second })

        assertEquals(HttpStatusCode.BadRequest, post("/items/use", """{"item":"thunder-stone","uid":"p1"}""").status) // pas de pierre

        // Herbizarre en tête d'équipe, une Pierre Foudre et une Pierre Lune dans le sac (préparés dans la sauvegarde)
        editSave { save ->
            val team = save["team"]!!.jsonArray
            val first = JsonObject(team[0].jsonObject + ("pokemonId" to JsonPrimitive(2)))
            val items = JsonObject(mapOf("thunder-stone" to JsonPrimitive(1), "moon-stone" to JsonPrimitive(1)))
            JsonObject(save + ("team" to JsonArray(listOf(first) + team.drop(1))) + ("items" to items))
        }
        v = game(client.get("/game"))
        val stone = v.items.single { it.item == "thunder-stone" }
        assertEquals(1, stone.count)
        assertEquals(listOf("p1"), stone.usableOn) // Herbizarre évolue avec une Pierre Foudre (données simulées)
        assertTrue(v.items.single { it.item == "moon-stone" }.usableOn.isEmpty())

        v = game(post("/items/use", """{"item":"thunder-stone","uid":"p1"}"""))
        assertEquals(3, v.team[0].pokemonId) // Florizarre
        assertTrue(v.items.none { it.item == "thunder-stone" }) // pierre consommée
        assertTrue(v.lastEvent!!.contains("évolue"))
        // Attaque apprise à l'évolution, équipée (place libre)
        assertTrue(v.lastEvent!!.contains("Florizarre apprend Danse Fleurs !"), v.lastEvent)
        assertEquals(0, v.team[0].moves.single { it.name == "petal-dance" }.level)
        // Un objet sans effet est refusé
        assertEquals(HttpStatusCode.BadRequest, post("/items/use", """{"item":"moon-stone","uid":"p1"}""").status)
        assertEquals(HttpStatusCode.NotFound, post("/shop/buy", """{"item":"moon-stone"}""").status) // plus de boutique
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
        assertEquals(1, result.regionalForms) // Germignon de Hisui (la méga est ignorée)
        assertTrue(result.failed.isEmpty())

        val again = json.decodeFromString<ImportResult>(client.post("/pokemon/import?limit=4").bodyAsText())
        assertEquals(0, again.imported)
        assertEquals(4, again.alreadyCached)
        assertEquals(0, again.regionalForms)

        val all = json.decodeFromString<Page<PokemonSummary>>(client.get("/pokemon").bodyAsText())
        assertEquals(5, all.total)
        // Ordre du Pokédex national : la forme régionale suit sa forme de base
        assertEquals(listOf(1, 4, 7, 152, 10999), all.items.map { it.id })
        val form = all.items.last()
        assertEquals(152, form.speciesId)
        assertEquals("hisui", form.regionalForm)
        assertEquals("Germignon de Hisui", form.nameFr)
        assertEquals("hisui", form.region)
        assertEquals(listOf("water"), form.types)

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
        assertEquals(listOf(10999), ids("region=hisui"))
        assertEquals(listOf(152), ids("generation=2"))
        assertEquals(listOf(1, 152), ids("type=grass"))
        assertEquals(listOf(1), ids("type=grass,poison"))
        assertEquals(listOf(1), ids("type=grass&type=poison"))
        assertEquals(listOf(1, 152), ids("type=grass&region="))
        assertEquals(listOf(4), ids("name=salam")) // nom français
        assertEquals(listOf(152, 1, 7, 4, 10999), ids("sort=statTotal&order=desc"))
        // Bulbizarre, Carapuce, Germignon, Germignon de Hisui, Salamèche
        assertEquals(listOf(1, 7, 152, 10999, 4), ids("sort=nameFr"))
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
        assertEquals(10, regions.size)
        assertEquals(RegionCount("hisui", 8, 1), regions.first { it.name == "hisui" })
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
    fun `arbre d'evolution avec conditions en francais, mis en cache`() = appTest {
        val response = client.get("/pokemon/ivysaur/evolutions")
        assertEquals(HttpStatusCode.OK, response.status)
        val chain = json.decodeFromString<EvolutionChain>(response.bodyAsText())

        assertEquals(1, chain.id)
        val root = chain.roots.single()
        assertEquals("Bulbizarre", root.pokemon?.nameFr) // récupéré automatiquement
        assertTrue(root.conditions.isEmpty())
        val ivysaur = root.evolvesTo.single()
        assertEquals("Herbizarre", ivysaur.pokemon?.nameFr)
        assertEquals(listOf("Niveau 16"), ivysaur.conditions) // la condition non par défaut est ignorée
        val venusaur = ivysaur.evolvesTo.single()
        assertEquals(3, venusaur.speciesId)
        assertEquals(listOf("Utiliser Pierre Foudre"), venusaur.conditions)

        // Deuxième appel (depuis un autre membre) : tout vient de la base
        val callsBefore = remoteCalls.get()
        val again = json.decodeFromString<EvolutionChain>(client.get("/pokemon/venusaur/evolutions").bodyAsText())
        assertEquals(chain, again)
        assertEquals(callsBefore, remoteCalls.get())
    }

    @Test
    fun `lignee propre a une forme regionale`() = appTest {
        val chain = json.decodeFromString<EvolutionChain>(client.get("/pokemon/chikorita/evolutions").bodyAsText())
        // Deux lignées : la forme de base, puis la forme régionale ; la méga n'apparaît pas
        assertEquals(listOf("chikorita", "chikorita-hisui"), chain.roots.map { it.name })
        val (base, hisui) = chain.roots
        assertEquals(listOf("Niveau 16"), base.evolvesTo.single().conditions)
        assertEquals("Germignon de Hisui", hisui.pokemon?.nameFr)
        assertEquals(152, hisui.speciesId)
        assertEquals(10999, hisui.pokemon?.id)
        val bayleef = hisui.evolvesTo.single()
        assertEquals("Macronium", bayleef.pokemon?.nameFr)
        assertEquals(listOf("Utiliser Pierre Foudre"), bayleef.conditions)

        // Même arbre depuis la forme régionale
        assertEquals(chain, json.decodeFromString<EvolutionChain>(client.get("/pokemon/10999/evolutions").bodyAsText()))
    }

    @Test
    fun `pokemon sans chaine d'evolution`() = appTest {
        val chain = json.decodeFromString<EvolutionChain>(client.get("/pokemon/squirtle/evolutions").bodyAsText())
        assertEquals(null, chain.id)
        assertEquals("squirtle", chain.roots.single().name)
        assertTrue(chain.roots.single().evolvesTo.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/pokemon/missingno/evolutions").status)
    }

    @Test
    fun `attaques apprises dans le jeu le plus recent`() = appTest {
        val response = client.get("/pokemon/bulbasaur/moves")
        assertEquals(HttpStatusCode.OK, response.status)
        val moves = json.decodeFromString<PokemonMoves>(response.bodyAsText())

        // Jeu principal le plus récent (pas Rouge/Bleu, pas le spin-off Champions)
        assertEquals("scarlet-violet", moves.versionGroup?.name)
        assertEquals("Écarlate / Violet", moves.versionGroup?.label)
        // Par niveau (triées par niveau), puis œufs
        assertEquals(listOf("Charge", "Fouet Lianes", "Danse Fleurs"), moves.moves.map { it.nameFr })
        val tackle = moves.moves.first()
        assertEquals(1, tackle.level)
        assertEquals("level-up", tackle.method)
        assertEquals("Par niveau", tackle.methodLabel)
        assertEquals("normal", tackle.type)
        assertEquals("physical", tackle.damageClass)
        assertEquals(40, tackle.power)
        assertEquals("Charge l'ennemi avec un violent plaquage.", tackle.description)
        assertEquals("Œufs", moves.moves.last().methodLabel)
        assertEquals(null, moves.moves.last().level)

        // Tranch'Herbe (seulement dans Rouge/Bleu) n'apparaît pas et n'est jamais récupérée
        assertTrue(moves.moves.none { it.name == "razor-leaf" })

        // Deuxième appel : tout vient de la base
        val callsBefore = remoteCalls.get()
        assertEquals(moves, json.decodeFromString<PokemonMoves>(client.get("/pokemon/1/moves").bodyAsText()))
        assertEquals(callsBefore, remoteCalls.get())

        assertEquals(HttpStatusCode.NotFound, client.get("/pokemon/missingno/moves").status)
    }

    @Test
    fun `seul le jeu le plus recent est stocke`() {
        fun slot(move: String, vararg versions: String) = PokeApiMoveSlot(
            NamedApiResource(move, "x"),
            versions.map { PokeApiMoveVersionDetail(1, NamedApiResource("level-up", "x"), NamedApiResource(it, "x")) },
        )
        val learnset = learnsetOf(
            listOf(slot("tackle", "red-blue", "scarlet-violet", "champions"), slot("razor-leaf", "red-blue"))
        )
        assertEquals(setOf("scarlet-violet"), learnset.versionGroups.keys)
        assertEquals(listOf("tackle"), learnset.versionGroups.getValue("scarlet-violet").map { it.m })
        // Un Pokémon absent des jeux principaux garde son spin-off le plus récent
        assertEquals(setOf("champions"), learnsetOf(listOf(slot("tackle", "champions"))).versionGroups.keys)
        assertTrue(learnsetOf(emptyList()).versionGroups.isEmpty())
    }

    @Test
    fun `pokemon sans attaque connue`() = appTest {
        val moves = json.decodeFromString<PokemonMoves>(client.get("/pokemon/squirtle/moves").bodyAsText())
        assertEquals(null, moves.versionGroup)
        assertTrue(moves.moves.isEmpty())
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
        assertContains(response.bodyAsText(), "Corps de requête invalide")
    }

    private fun typeJson(name: String, double: String, half: String, none: String = ""): String {
        fun list(types: String) = types.split(' ').filter { it.isNotBlank() }
            .joinToString(",") { """{"name":"$it","url":"x"}""" }
        return """{"name": "$name", "damage_relations": {
            "double_damage_from": [${list(double)}], "half_damage_from": [${list(half)}], "no_damage_from": [${list(none)}],
            "double_damage_to": [], "half_damage_to": [], "no_damage_to": []}}"""
    }

    private fun speciesJson(
        id: Int, name: String, frenchName: String, generation: String, chainId: Int? = null, forms: List<String> = emptyList(),
    ) = """
        {
          "id": $id, "name": "$name",
          "names": [
            {"name": "${name.replaceFirstChar { it.uppercase() }}", "language": {"name": "en", "url": "x"}},
            {"name": "$frenchName", "language": {"name": "fr", "url": "x"}}
          ],
          "varieties": [{"is_default": true, "pokemon": {"name": "$name", "url": "x"}}${forms.joinToString("") { ",{\"is_default\": false, \"pokemon\": {\"name\": \"$it\", \"url\": \"x\"}}" }}],
          "egg_groups": [{"name": "monster", "url": "x"}], "hatch_counter": 20,
          "generation": {"name": "$generation", "url": "x"}${chainId?.let { ",\n          \"evolution_chain\": {\"url\": \"https://pokeapi.co/api/v2/evolution-chain/$it/\"}" } ?: ""}
        }
    """.trimIndent()

    private fun pokemonJson(
        id: Int, name: String, types: List<String>, hp: Int, moves: String = "[]",
        species: Pair<String, Int> = name to id, isDefault: Boolean = true,
    ): String {
        val typesJson = types.withIndex().joinToString(",") { (i, t) ->
            """{"slot":${i + 1},"type":{"name":"$t","url":"https://pokeapi.co/api/v2/type/$t/"}}"""
        }
        return """
        {
          "id": $id, "name": "$name", "height": 7, "weight": 69, "base_experience": 64,
          "order": $id, "is_default": $isDefault, "forms": [{"name": "$name", "url": "x"}],
          "sprites": {
            "front_default": "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/$id.png",
            "other": {"official-artwork": {"front_default": "https://example.org/$id.png"}}
          },
          "types": [$typesJson],
          "stats": [
            {"base_stat": $hp, "effort": 0, "stat": {"name": "hp", "url": "x"}},
            {"base_stat": 49, "effort": 0, "stat": {"name": "attack", "url": "x"}}
          ],
          "species": {"name": "${species.first}", "url": "https://pokeapi.co/api/v2/pokemon-species/${species.second}/"},
          "moves": $moves,
          "abilities": [
            {"ability": {"name": "overgrow", "url": "x"}, "is_hidden": false, "slot": 1},
            {"ability": {"name": "chlorophyll", "url": "x"}, "is_hidden": true, "slot": 3}
          ]
        }
        """.trimIndent()
    }
}
