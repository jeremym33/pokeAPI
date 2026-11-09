package com.example.pokeapi.routes

import com.example.pokeapi.model.CreateTeamRequest
import com.example.pokeapi.model.ErrorResponse
import com.example.pokeapi.model.HealthResponse
import com.example.pokeapi.model.PokemonQuery
import com.example.pokeapi.model.PokemonSort
import com.example.pokeapi.model.Region
import com.example.pokeapi.model.RenameTeamRequest
import com.example.pokeapi.service.PokemonService
import com.example.pokeapi.service.TeamService
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private const val MAX_PAGE_SIZE = 100
private const val MAX_IMPORT_SIZE = 2000

fun Route.healthRoutes(database: Database) {
    get("/health") {
        val dbOk = runCatching {
            newSuspendedTransaction(Dispatchers.IO, database) { exec("SELECT 1") { it.next() } }
        }.isSuccess
        val status = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, HealthResponse(if (dbOk) "UP" else "DOWN", if (dbOk) "UP" else "DOWN"))
    }
}

fun Route.pokemonRoutes(service: PokemonService) {
    route("/pokemon") {
        // GET /pokemon?type=fire,flying&region=kanto&name=char&sort=statTotal&order=desc
        get {
            call.respond(service.list(call.pokemonQuery()))
        }

        // GET /pokemon/{idOrName}?refresh=true  -> base, sinon pokeapi.co (puis mise en cache)
        get("/{idOrName}") {
            val idOrName = call.parameters["idOrName"]!!
            val refresh = call.request.queryParameters["refresh"]?.toBooleanStrictOrNull() ?: false
            val pokemon = service.getPokemon(idOrName, refresh)
                ?: throw NotFoundException("Pokémon '$idOrName' introuvable")
            call.respond(pokemon)
        }

        // POST /pokemon/import?limit=151&offset=0  -> import en masse depuis pokeapi.co
        post("/import") {
            val limit = call.intParam("limit", 151)
            if (limit !in 1..MAX_IMPORT_SIZE) {
                throw BadRequestException("limit doit être compris entre 1 et $MAX_IMPORT_SIZE")
            }
            val offset = call.intParam("offset", 0).coerceAtLeast(0)
            call.respond(service.import(limit, offset))
        }

        // Sans cette route, GET /pokemon/import serait interprété comme le Pokémon nommé « import ».
        get("/import") {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respond(
                HttpStatusCode.MethodNotAllowed,
                ErrorResponse("L'import se lance en POST, par exemple : curl -X POST \"http://localhost:8080/pokemon/import?limit=151\""),
            )
        }
    }
}

fun Route.referenceRoutes(service: PokemonService) {
    get("/regions") { call.respond(service.regions()) }
    get("/types") { call.respond(service.types()) }
}

private fun RoutingCall.pokemonQuery(): PokemonQuery {
    val params = request.queryParameters
    // type=fire,flying ou type=fire&type=flying
    val types = params.getAll("type").orEmpty()
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (types.size > 2) throw BadRequestException("Un Pokémon a au plus 2 types")

    val region = params["region"]?.takeIf { it.isNotBlank() }?.let {
        Region.ofName(it)?.apiName
            ?: throw BadRequestException("Région inconnue : '$it' (${Region.entries.joinToString { r -> r.apiName }})")
    }
    val sort = params["sort"]?.let {
        PokemonSort.ofParam(it)
            ?: throw BadRequestException("Tri inconnu : '$it' (${PokemonSort.entries.joinToString { s -> s.param }})")
    } ?: PokemonSort.ID
    val descending = when (params["order"]?.lowercase()) {
        null, "asc" -> false
        "desc" -> true
        else -> throw BadRequestException("order doit valoir 'asc' ou 'desc'")
    }
    val generation = params["generation"]?.let {
        it.toIntOrNull()?.takeIf { g -> g in 1..Region.entries.size }
            ?: throw BadRequestException("generation doit être compris entre 1 et ${Region.entries.size}")
    }

    return PokemonQuery(
        limit = intParam("limit", 20).coerceIn(1, MAX_PAGE_SIZE),
        offset = intParam("offset", 0).coerceAtLeast(0),
        types = types,
        region = region,
        generation = generation,
        name = params["name"],
        sort = sort,
        descending = descending,
    )
}

fun Route.teamRoutes(service: TeamService) {
    route("/teams") {
        get { call.respond(service.findAll()) }

        post {
            val request = call.receive<CreateTeamRequest>()
            call.respond(HttpStatusCode.Created, service.create(request.name, request.pokemon))
        }

        route("/{teamId}") {
            get { call.respond(service.find(call.teamId())) }

            patch {
                val request = call.receive<RenameTeamRequest>()
                call.respond(service.rename(call.teamId(), request.name))
            }

            delete {
                service.delete(call.teamId())
                call.respond(HttpStatusCode.NoContent)
            }

            // POST /teams/{teamId}/pokemon/{idOrName}
            post("/pokemon/{idOrName}") {
                val team = service.addMember(call.teamId(), call.parameters["idOrName"]!!)
                call.respond(HttpStatusCode.Created, team)
            }

            // DELETE /teams/{teamId}/members/{position}
            delete("/members/{position}") {
                val position = call.parameters["position"]?.toIntOrNull()
                    ?: throw BadRequestException("position doit être un entier")
                call.respond(service.removeMember(call.teamId(), position))
            }
        }
    }
}

private fun RoutingCall.intParam(name: String, default: Int): Int {
    val raw = request.queryParameters[name] ?: return default
    return raw.toIntOrNull() ?: throw BadRequestException("$name doit être un entier")
}

private fun RoutingCall.teamId(): Int =
    parameters["teamId"]?.toIntOrNull() ?: throw BadRequestException("L'id d'équipe doit être un entier")
