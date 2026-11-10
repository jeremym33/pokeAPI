package com.example.pokeapi.routes

import com.example.pokeapi.model.CreateTeamRequest
import com.example.pokeapi.model.ErrorResponse
import com.example.pokeapi.model.HealthResponse
import com.example.pokeapi.model.PokemonQuery
import com.example.pokeapi.model.PokemonSort
import com.example.pokeapi.model.Region
import com.example.pokeapi.model.RenameTeamRequest
import com.example.pokeapi.service.AbilityService
import com.example.pokeapi.service.EvolutionService
import com.example.pokeapi.service.MoveService
import com.example.pokeapi.service.LocationService
import com.example.pokeapi.service.PokedexUpdateService
import com.example.pokeapi.service.PokemonService
import com.example.pokeapi.service.TeamService
import com.example.pokeapi.service.TypeService
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

fun Route.pokemonRoutes(
    service: PokemonService,
    evolutionService: EvolutionService,
    moveService: MoveService,
    abilityService: AbilityService,
    typeService: TypeService,
) {
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
            call.respond(abilityService.withAbilityDetails(pokemon))
        }

        // GET /pokemon/{idOrName}/evolutions  -> arbre d'évolution avec les conditions en français
        get("/{idOrName}/evolutions") {
            val idOrName = call.parameters["idOrName"]!!
            val chain = evolutionService.evolutions(idOrName)
                ?: throw NotFoundException("Pokémon '$idOrName' introuvable")
            call.respond(chain)
        }

        // GET /pokemon/{idOrName}/moves  -> attaques apprises dans le jeu le plus récent
        get("/{idOrName}/moves") {
            val idOrName = call.parameters["idOrName"]!!
            val moves = moveService.moves(idOrName)
                ?: throw NotFoundException("Pokémon '$idOrName' introuvable")
            call.respond(moves)
        }

        // GET /pokemon/{idOrName}/type-defenses  -> faiblesses, résistances et immunités
        get("/{idOrName}/type-defenses") {
            val idOrName = call.parameters["idOrName"]!!
            val defenses = typeService.defenses(idOrName)
                ?: throw NotFoundException("Pokémon '$idOrName' introuvable")
            call.respond(defenses)
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

fun Route.abilityRoutes(service: AbilityService) {
    // GET /abilities/{name}  -> talent détaillé et Pokémon qui le possèdent
    get("/abilities/{name}") {
        val name = call.parameters["name"]!!
        val ability = service.ability(name) ?: throw NotFoundException("Talent '$name' introuvable")
        call.respond(ability)
    }
}

fun Route.updateRoutes(service: PokedexUpdateService) {
    route("/pokedex/update") {
        // État de la mise à jour (à interroger régulièrement pendant qu'elle tourne)
        get { call.respond(service.status()) }

        // POST /pokedex/update?mode=missing|all  -> lance la mise à jour en arrière-plan
        post {
            val refreshAll = when (call.request.queryParameters["mode"] ?: "missing") {
                "missing" -> false
                "all" -> true
                else -> throw BadRequestException("mode doit valoir 'missing' ou 'all'")
            }
            val started = service.start(refreshAll)
            if (started == null) {
                call.respond(HttpStatusCode.Conflict, service.status())
            } else {
                call.respond(HttpStatusCode.Accepted, started)
            }
        }

        // DELETE /pokedex/update  -> arrête la mise à jour en cours
        delete {
            if (!service.cancel()) throw NotFoundException("Aucune mise à jour en cours")
            call.respond(HttpStatusCode.Accepted, service.status())
        }
    }
}

fun Route.locationRoutes(service: LocationService) {
    // GET /regions/{name}/locations  -> lieux de la région (villes, routes, grottes…)
    get("/regions/{name}/locations") {
        val name = call.parameters["name"]!!
        val region = service.regionLocations(name) ?: throw NotFoundException("Région '$name' introuvable")
        call.respond(region)
    }

    // GET /locations/{name}  -> Pokémon rencontrés dans le lieu, zone par zone et jeu par jeu
    get("/locations/{name}") {
        val name = call.parameters["name"]!!
        val location = service.location(name) ?: throw NotFoundException("Lieu '$name' introuvable")
        call.respond(location)
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
        it.toIntOrNull()?.takeIf { g -> g in 1..Region.MAX_GENERATION }
            ?: throw BadRequestException("generation doit être compris entre 1 et ${Region.MAX_GENERATION}")
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
