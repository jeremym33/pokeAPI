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
data class StarterRequest(val starter: String)

@Serializable
data class TravelRequest(
    val step: Int,
    /** Passage d'une région à l'autre : le Pokémon venu d'ailleurs qui accompagne le joueur. */
    /** Starter choisi à la première arrivée dans une région (« chikorita »…). */
    val starter: String? = null,
)

@Serializable
data class AttackRequest(val move: Int)

@Serializable
data class PresetRequest(val name: String)

@Serializable
data class FavoriteRequest(val step: Int, val favorite: Boolean = true)

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

        // Recommencer : la partie est supprimée, après confirmation par le mot de passe du compte
        delete {
            val user = call.user()
            auth.confirmPassword(user, call.receive<PasswordRequest>().password)
            service.reset(user)
            call.respond(HttpStatusCode.NoContent)
        }

        // Pension : déposer / reprendre un Pokémon, prendre l'œuf ; ?region= vise la pension d'une autre région ouverte
        // (par défaut, celle de la région actuelle)
        route("/daycare") {
            post("/deposit") {
                call.respond(service.depositDayCare(call.user(), call.receive<SwitchRequest>().uid, call.request.queryParameters["region"]))
            }
            post("/withdraw") {
                call.respond(service.withdrawDayCare(call.user(), call.receive<SwitchRequest>().uid, call.request.queryParameters["region"]))
            }
            post("/egg") { call.respond(service.takeEgg(call.user(), call.request.queryParameters["region"])) }
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
        // Fiche d'une espèce vue ou rejointe : où et comment l'obtenir
        get("/dex/{number}") {
            val number = call.parameters["number"]?.toIntOrNull() ?: throw io.ktor.server.plugins.BadRequestException("Numéro invalide")
            call.respond(service.dexSpecies(call.user(), number))
        }
        // Fiche d'un Pokémon du joueur : statistiques et évolutions possibles
        get("/pokemon/{uid}") { call.respond(service.fighterDetails(call.user(), call.parameters["uid"]!!)) }
        // Vérifie (et corrige) les Pokémon de la partie d'après les données en base : à l'ouverture de la page
        post("/verify") { call.respond(service.verify(call.user())) }
        post("/travel") {
            val request = call.receive<TravelRequest>()
            call.respond(service.travel(call.user(), request.step, request.starter))
        }
        // Starter de la région actuelle (partie arrivée dans la région avant cette règle)
        post("/region-starter") { call.respond(service.chooseRegionStarter(call.user(), call.receive<StarterRequest>().starter)) }
        post("/heal") { call.respond(service.heal(call.user())) }
        // Soin rapide : retour à la ville la plus proche et soin
        post("/heal-nearest") { call.respond(service.healNearest(call.user())) }
        // Zones favorites (raccourcis de voyage)
        post("/favorites") {
            val request = call.receive<FavoriteRequest>()
            call.respond(service.setFavorite(call.user(), request.step, request.favorite))
        }
        // Équipes enregistrées
        route("/team-presets") {
            post { call.respond(service.saveTeamPreset(call.user(), call.receive<PresetRequest>().name)) }
            post("/apply") { call.respond(service.applyTeamPreset(call.user(), call.receive<PresetRequest>().name)) }
            delete("/{name}") { call.respond(service.deleteTeamPreset(call.user(), call.parameters["name"]!!)) }
        }
        // Journal : statistiques et moments forts
        get("/journal") { call.respond(service.journal(call.user())) }
        post("/explore") { call.respond(service.explore(call.user())) }
        // Ligue (Plateau Indigo) : prochain membre du Conseil, puis le Maître
        post("/league") { call.respond(service.challengeLeague(call.user())) }
        // Quitter le défi en cours (sinon toute autre action est refusée) : il reprendra au premier membre
        post("/league/abandon") { call.respond(service.abandonLeague(call.user())) }
        post("/gym") { call.respond(service.challengeGym(call.user())) }
        put("/settings") { call.respond(service.updateSettings(call.user(), call.receive<GameSettings>())) }
        put("/pokemon/{uid}/moves") {
            call.respond(service.setMoves(call.user(), call.parameters["uid"]!!, call.receive<MovesRequest>().moves))
        }
        // Relâcher un Pokémon (il quitte la partie)
        post("/release") { call.respond(service.release(call.user(), call.receive<SwitchRequest>().uid)) }
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
