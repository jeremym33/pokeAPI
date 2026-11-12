package com.example.pokeapi.routes

import com.example.pokeapi.game.GameService
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class NewGameRequest(val starter: String)

@Serializable
data class TravelRequest(val step: Int)

@Serializable
data class AttackRequest(val move: Int)

@Serializable
data class SwitchRequest(val uid: String)

@Serializable
data class TeamRequest(val uids: List<String>)

/** Jeu d'aventure : chaque action renvoie l'état complet de la partie. */
fun Route.gameRoutes(service: GameService) {
    route("/game") {
        get { call.respond(service.view()) }

        // Nouvelle partie (remplace la partie en cours)
        post("/new") { call.respond(HttpStatusCode.Created, service.newGame(call.receive<NewGameRequest>().starter)) }

        delete {
            service.reset()
            call.respond(HttpStatusCode.NoContent)
        }

        post("/travel") { call.respond(service.travel(call.receive<TravelRequest>().step)) }
        post("/heal") { call.respond(service.heal()) }
        post("/explore") { call.respond(service.explore()) }
        post("/gym") { call.respond(service.challengeGym()) }
        put("/team") { call.respond(service.setTeam(call.receive<TeamRequest>().uids)) }

        route("/battle") {
            post("/attack") { call.respond(service.attack(call.receive<AttackRequest>().move)) }
            post("/switch") { call.respond(service.switchTo(call.receive<SwitchRequest>().uid)) }
            post("/flee") { call.respond(service.flee()) }
            post("/close") { call.respond(service.closeBattle()) }
        }
    }
}
