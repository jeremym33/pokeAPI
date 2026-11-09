package com.example.pokeapi.routes

import com.example.pokeapi.model.CreateTeamRequest
import com.example.pokeapi.model.ErrorResponse
import com.example.pokeapi.model.HealthResponse
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
        // GET /pokemon?limit=20&offset=0&type=fire&name=char  -> Pokémon présents en base
        get {
            val limit = call.intParam("limit", 20).coerceIn(1, MAX_PAGE_SIZE)
            val offset = call.intParam("offset", 0).coerceAtLeast(0)
            val type = call.request.queryParameters["type"]
            val name = call.request.queryParameters["name"]
            call.respond(service.list(limit, offset, type, name))
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
