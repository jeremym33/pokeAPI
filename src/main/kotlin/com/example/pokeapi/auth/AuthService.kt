package com.example.pokeapi.auth

import com.example.pokeapi.db.SessionTable
import com.example.pokeapi.db.TeamTable
import com.example.pokeapi.db.UserTable
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Requête sans session valide : réponse 401. */
class UnauthorizedException(message: String = "Connectez-vous pour accéder à cette fonctionnalité") : RuntimeException(message)

/** Ressource déjà existante (nom d'utilisateur pris) : réponse 409. */
class ConflictException(message: String) : RuntimeException(message)

@Serializable
data class UserView(val id: Int, val username: String, val createdAt: String)

@Serializable
data class AuthResponse(val token: String, val user: UserView)

/**
 * Comptes utilisateurs : nom d'utilisateur + mot de passe (haché avec PBKDF2), et sessions par jeton.
 * Seul le hachage SHA-256 du jeton est enregistré : une fuite de la base ne donne pas accès aux sessions.
 */
class AuthService(private val database: Database, private val sessionDuration: Duration = Duration.ofDays(30)) {

    private val random = SecureRandom()

    private suspend fun <T> dbQuery(block: suspend Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, database) { block() }

    suspend fun register(username: String, password: String): AuthResponse {
        val name = validateUsername(username)
        validatePassword(password)
        val hash = hashPassword(password)
        val user = dbQuery {
            if (UserTable.selectAll().where { UserTable.usernameKey eq name.lowercase() }.any()) {
                throw ConflictException("Le nom d'utilisateur « $name » est déjà pris")
            }
            val now = Instant.now()
            val id = UserTable.insertAndGetId {
                it[UserTable.username] = name
                it[usernameKey] = name.lowercase()
                it[passwordHash] = hash
                it[createdAt] = now
            }.value
            // Le premier compte reprend ce qui existait avant les comptes : la partie (id 1) et les équipes sans propriétaire.
            if (id == firstUserId()) {
                TeamTable.update({ TeamTable.ownerId.isNull() }) { it[ownerId] = id }
            }
            UserView(id, name, now.toString())
        }
        return AuthResponse(createSession(user.id), user)
    }

    suspend fun login(username: String, password: String): AuthResponse {
        val row = dbQuery { UserTable.selectAll().where { UserTable.usernameKey eq username.trim().lowercase() }.singleOrNull() }
        // Même travail (hachage) que le compte existe ou non : la durée de réponse ne révèle pas les comptes.
        val valid = verifyPassword(password, row?.get(UserTable.passwordHash) ?: DUMMY_HASH)
        if (row == null || !valid) throw UnauthorizedException("Nom d'utilisateur ou mot de passe incorrect")
        return AuthResponse(createSession(row[UserTable.id].value), row.toView())
    }

    suspend fun logout(token: String) {
        dbQuery { SessionTable.deleteWhere { tokenHash eq sha256(token) } }
    }

    /** Utilisateur d'un jeton de session valide, sinon null. */
    suspend fun userForToken(token: String): UserView? = dbQuery {
        (SessionTable innerJoin UserTable).selectAll()
            .where { (SessionTable.tokenHash eq sha256(token)) and (SessionTable.expiresAt greater Instant.now()) }
            .singleOrNull()?.toView()
    }

    private suspend fun createSession(userId: Int): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = Instant.now()
        dbQuery {
            SessionTable.deleteWhere { expiresAt less now } // ménage des sessions expirées
            SessionTable.insert {
                it[tokenHash] = sha256(token)
                it[SessionTable.userId] = userId
                it[createdAt] = now
                it[expiresAt] = now.plus(sessionDuration)
            }
        }
        return token
    }

    private fun firstUserId(): Int? =
        UserTable.select(UserTable.id).orderBy(UserTable.id).limit(1).singleOrNull()?.get(UserTable.id)?.value

    private fun ResultRow.toView() = UserView(this[UserTable.id].value, this[UserTable.username], this[UserTable.createdAt].toString())

    private fun validateUsername(username: String): String {
        val name = username.trim()
        if (!USERNAME.matches(name)) {
            throw BadRequestException("Le nom d'utilisateur doit faire de 3 à 20 caractères : lettres, chiffres, « - » ou « _ »")
        }
        return name
    }

    private fun validatePassword(password: String) {
        if (password.length < 8) throw BadRequestException("Le mot de passe doit contenir au moins 8 caractères")
        if (password.length > 200) throw BadRequestException("Le mot de passe est trop long (200 caractères au plus)")
    }

    private fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = pbkdf2(password, salt, ITERATIONS)
        val b64 = Base64.getEncoder()
        return listOf(SCHEME, ITERATIONS.toString(), b64.encodeToString(salt), b64.encodeToString(hash)).joinToString(SEPARATOR)
    }

    private fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val b64 = Base64.getDecoder()
        val expected = b64.decode(parts[3])
        val actual = pbkdf2(password, b64.decode(parts[2]), parts[1].toInt())
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SCHEME = "pbkdf2-sha256"
        private const val SEPARATOR = ":"
        private const val ITERATIONS = 210_000
        private val USERNAME = Regex("[A-Za-z0-9_-]{3,20}")
        /** Hachage factice, pour les connexions à un compte inexistant (même durée de calcul). */
        private val DUMMY_HASH = listOf(SCHEME, ITERATIONS.toString(), "AAAAAAAAAAAAAAAAAAAAAA==", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .joinToString(SEPARATOR)
    }
}

/** Jeton envoyé par le client : « Authorization: Bearer … ». */
fun ApplicationCall.bearerToken(): String? =
    request.headers["Authorization"]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()?.ifEmpty { null }

/** Utilisateur connecté, sinon 401. */
suspend fun ApplicationCall.requireUser(auth: AuthService): UserView =
    bearerToken()?.let { auth.userForToken(it) } ?: throw UnauthorizedException()
