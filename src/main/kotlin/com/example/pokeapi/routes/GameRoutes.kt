package com.example.pokeapi.routes

import com.example.pokeapi.auth.AuthService
import com.example.pokeapi.auth.requireUser
import com.example.pokeapi.game.GameService
import com.example.pokeapi.game.GameSettings
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

@Serializable
data class MovesRequest(val moves: List<String>)

@Serializable
data class RecruitRequest(val accept: Boolean)

@Serializable
data class UseItemRequest(val item: String, val uid: String)

/** Jeu d'aventure : chaque action renvoie l'état complet de la partie du compte connecté. */
fun Route.gameRoutes(service: GameService, auth: AuthService) {
    suspend fun RoutingCall.user(): Int = requireUser(auth).id
    route("/game") {
        get { call.respond(service.view(call.user())) }

        // Nouvelle partie (remplace la partie en cours)
        post("/new") { call.respond(HttpStatusCode.Created, service.newGame(call.user(), call.receive<NewGameRequest>().starter)) }

        delete {
            service.reset(call.user())
            call.respond(HttpStatusCode.NoContent)
        }

        // Entraînement : laisser un Pokémon sur le lieu actuel, le reprendre ou l'échanger
        route("/training") {
            post("/leave") { call.respond(service.leaveTrainee(call.user(), call.receive<SwitchRequest>().uid)) }
            post("/retrieve") { call.respond(service.retrieveTrainee(call.user())) }
            post("/swap") { call.respond(service.swapTrainee(call.user(), call.receive<SwitchRequest>().uid)) }
        }
        // Badges d'arène et leurs effets (profil)
        get("/badges") { call.respond(service.badges(call.user())) }
        // Succès : objectifs qui donnent les objets d'évolution
        get("/achievements") { call.respond(service.achievements(call.user())) }
        // Motisdex : espèces de Kanto vues et rejointes
        get("/dex") { call.respond(service.dex(call.user())) }
        // Vérifie (et corrige) les Pokémon de la partie d'après les données en base : à l'ouverture de la page
        post("/verify") { call.respond(service.verify(call.user())) }
        post("/travel") { call.respond(service.travel(call.user(), call.receive<TravelRequest>().step)) }
        post("/heal") { call.respond(service.heal(call.user())) }
        post("/explore") { call.respond(service.explore(call.user())) }
        post("/gym") { call.respond(service.challengeGym(call.user())) }
        put("/settings") { call.respond(service.updateSettings(call.user(), call.receive<GameSettings>())) }
        put("/pokemon/{uid}/moves") {
            call.respond(service.setMoves(call.user(), call.parameters["uid"]!!, call.receive<MovesRequest>().moves))
        }
        put("/team") { call.respond(service.setTeam(call.user(), call.receive<TeamRequest>().uids)) }
        post("/items/use") {
            val request = call.receive<UseItemRequest>()
            call.respond(service.useItem(call.user(), request.item, request.uid))
        }

        route("/battle") {
            post("/attack") { call.respond(service.attack(call.user(), call.receive<AttackRequest>().move)) }
            post("/switch") { call.respond(service.switchTo(call.user(), call.receive<SwitchRequest>().uid)) }
            post("/flee") { call.respond(service.flee(call.user())) }
            post("/close") { call.respond(service.closeBattle(call.user())) }
            // Ferme le combat terminé et enchaîne sur le Pokémon sauvage suivant
            post("/next") { call.respond(service.nextEncounter(call.user())) }
            // Accueillir ou non le Pokémon qui veut rejoindre l'équipe
            post("/recruit") { call.respond(service.recruit(call.user(), call.receive<RecruitRequest>().accept)) }
        }
    }
}
