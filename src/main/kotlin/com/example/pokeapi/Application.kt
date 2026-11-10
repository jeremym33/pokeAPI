package com.example.pokeapi

import com.example.pokeapi.client.PokeApiClient
import com.example.pokeapi.client.createPokeApiHttpClient
import com.example.pokeapi.db.DatabaseConfig
import com.example.pokeapi.db.DatabaseFactory
import com.example.pokeapi.plugins.configureCors
import com.example.pokeapi.plugins.configureMonitoring
import com.example.pokeapi.plugins.configureSerialization
import com.example.pokeapi.plugins.configureStatusPages
import com.example.pokeapi.repository.AbilityRepository
import com.example.pokeapi.repository.EvolutionRepository
import com.example.pokeapi.repository.MoveRepository
import com.example.pokeapi.repository.PokemonRepository
import com.example.pokeapi.repository.TeamRepository
import com.example.pokeapi.routes.abilityRoutes
import com.example.pokeapi.routes.healthRoutes
import com.example.pokeapi.routes.locationRoutes
import com.example.pokeapi.routes.pokemonRoutes
import com.example.pokeapi.routes.referenceRoutes
import com.example.pokeapi.routes.teamRoutes
import com.example.pokeapi.routes.updateRoutes
import com.example.pokeapi.service.AbilityService
import com.example.pokeapi.service.EvolutionService
import com.example.pokeapi.service.MoveService
import com.example.pokeapi.service.LocationService
import com.example.pokeapi.service.PokedexUpdateService
import com.example.pokeapi.service.PokemonService
import com.example.pokeapi.service.TypeService
import com.example.pokeapi.service.TeamService
import io.ktor.server.application.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.Database

/** Point d'entrée Ktor référencé dans application.yaml. */
fun Application.module() {
    val config = environment.config
    val database = DatabaseFactory.connect(
        DatabaseConfig(
            url = config.property("database.url").getString(),
            user = config.property("database.user").getString(),
            password = config.property("database.password").getString(),
            maxPoolSize = config.property("database.maxPoolSize").getString().toInt(),
        )
    )
    val httpClient = createPokeApiHttpClient()
    val pokeApiClient = PokeApiClient(httpClient, config.property("pokeapi.baseUrl").getString())
    val importConcurrency = config.property("pokeapi.importConcurrency").getString().toInt()
    val corsAllowedHosts = config.property("cors.allowedHosts").getString()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    monitor.subscribe(ApplicationStopped) {
        httpClient.close()
        DatabaseFactory.close()
    }

    configureApp(database, pokeApiClient, importConcurrency, corsAllowedHosts)
}

/** Configuration de l'application, séparée de [module] pour pouvoir injecter des dépendances en test. */
fun Application.configureApp(
    database: Database,
    pokeApiClient: PokeApiClient,
    importConcurrency: Int = 8,
    corsAllowedHosts: List<String> = listOf("*"),
) {
    DatabaseFactory.createSchema(database)

    val pokemonRepository = PokemonRepository(database)
    val pokemonService = PokemonService(pokemonRepository, pokeApiClient, importConcurrency)
    val teamService = TeamService(TeamRepository(database), pokemonService)
    val evolutionRepository = EvolutionRepository(database)
    val evolutionService = EvolutionService(pokemonService, pokemonRepository, evolutionRepository, pokeApiClient)
    val locationService = LocationService(database, pokemonRepository, evolutionRepository, pokeApiClient)
    // Tourne dans la portée de l'application : arrêtée avec elle.
    val updateService = PokedexUpdateService(this, pokemonService, pokeApiClient, importConcurrency)
    val abilityRepository = AbilityRepository(database)
    val abilityService = AbilityService(abilityRepository, pokemonRepository, pokeApiClient)
    val typeService = TypeService(database, pokemonService, abilityRepository, pokeApiClient)
    val moveService = MoveService(pokemonService, pokemonRepository, MoveRepository(database), pokeApiClient, importConcurrency)

    configureSerialization()
    configureMonitoring()
    configureCors(corsAllowedHosts)
    configureStatusPages()

    routing {
        // Interface Swagger : http://localhost:8080/swagger
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        get("/") { call.respondRedirect("/swagger") }

        healthRoutes(database)
        pokemonRoutes(pokemonService, evolutionService, moveService, abilityService, typeService)
        abilityRoutes(abilityService)
        referenceRoutes(pokemonService)
        updateRoutes(updateService)
        locationRoutes(locationService)
        teamRoutes(teamService)
    }
}
