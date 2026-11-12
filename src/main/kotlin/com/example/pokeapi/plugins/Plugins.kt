package com.example.pokeapi.plugins

import com.example.pokeapi.client.PokeApiException
import com.example.pokeapi.model.ErrorResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
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
            // Les champs à leur valeur par défaut (0, liste vide, null) restent présents dans les réponses.
            encodeDefaults = true
        })
    }
}

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/health" }
    }
}

/**
 * Autorise les appels depuis l'application web (pokeApp).
 * hosts : "*" pour tout autoriser (développement), sinon une liste comme "localhost:8081,pokedex.example.com".
 */
fun Application.configureCors(hosts: List<String>) {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        if (hosts.isEmpty() || "*" in hosts) {
            anyHost()
        } else {
            hosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
    }
}

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Ressource introuvable"))
        }
        exception<BadRequestException> { call, cause ->
            // Corps JSON illisible : erreur de désérialisation encapsulée. Sinon, le message de l'exception
            // (éventuellement recopiée par les coroutines, l'original étant alors dans cause).
            val chain = generateSequence<Throwable>(cause) { it.cause }.toList()
            val invalidBody = chain.any { it is kotlinx.serialization.SerializationException || it is io.ktor.server.plugins.CannotTransformContentToTypeException }
            val message = if (invalidBody) "Corps de requête invalide" else chain.firstNotNullOfOrNull { it.message }
            if (invalidBody) call.application.log.warn("Requête invalide sur ${call.request.path()} : ${cause.cause}")
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
