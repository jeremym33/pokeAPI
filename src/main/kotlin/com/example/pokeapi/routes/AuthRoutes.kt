package com.example.pokeapi.routes

import com.example.pokeapi.auth.AuthService
import com.example.pokeapi.auth.bearerToken
import com.example.pokeapi.auth.requireUser
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CredentialsRequest(val username: String, val password: String)

/** Comptes : inscription, connexion (jeton de session), déconnexion, compte connecté. */
fun Route.authRoutes(auth: AuthService) {
    route("/auth") {
        post("/register") {
            val request = call.receive<CredentialsRequest>()
            call.respond(HttpStatusCode.Created, auth.register(request.username, request.password))
        }
        post("/login") {
            val request = call.receive<CredentialsRequest>()
            call.respond(auth.login(request.username, request.password))
        }
        post("/logout") {
            call.bearerToken()?.let { auth.logout(it) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/me") { call.respond(call.requireUser(auth)) }
    }
}
