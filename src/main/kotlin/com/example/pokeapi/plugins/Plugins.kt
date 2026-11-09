package com.example.pokeapi.plugins

import com.example.pokeapi.client.PokeApiException
import com.example.pokeapi.model.ErrorResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            explicitNulls = true
        })
    }
}

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/health" }
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Ressource introuvable"))
        }
        exception<BadRequestException> { call, cause ->
            // Les erreurs de désérialisation JSON sont encapsulées dans une BadRequestException.
            val message = if (cause.cause != null) "Corps de requête invalide" else cause.message
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message ?: "Requête invalide"))
        }
        exception<PokeApiException> { call, cause ->
            call.application.log.warn("Erreur PokéAPI : ${cause.message}")
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("PokéAPI est indisponible, réessayez plus tard"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Erreur inattendue", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Erreur interne du serveur"))
        }
    }
}
