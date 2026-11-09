package com.example.pokeapi.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 10,
)

object DatabaseFactory {
    private var dataSource: HikariDataSource? = null

    fun connect(config: DatabaseConfig): Database {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        val ds = HikariDataSource(hikari)
        dataSource = ds
        return Database.connect(ds)
    }

    /** Crée les tables et colonnes manquantes (idempotent, ajoute les nouvelles colonnes aux bases existantes). */
    @Suppress("DEPRECATION")
    fun createSchema(database: Database) {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                PokemonTable,
                PokemonTypeTable,
                PokemonStatTable,
                PokemonAbilityTable,
                TeamTable,
                TeamMemberTable,
                EvolutionChainTable,
                TranslationTable,
                MoveTable,
                LearnsetTable,
            )
        }
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }
}
