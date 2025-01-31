package io.beatmaps.common.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializerOrNull
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.postgresql.util.PGobject
import javax.sql.DataSource

fun setupDB(defaultDb: String = "beatmaps", app: String = "unknown"): DataSource {
    val dbHost = System.getenv("DB_HOST") ?: "localhost"
    val dbPort = System.getenv("DB_PORT") ?: "5432"
    val dbUser = System.getenv("DB_USER") ?: "beatmaps"
    val dbName = System.getenv("DB_NAME") ?: defaultDb
    val dbPass = System.getenv("DB_PASSWORD") ?: "insecure-password"
    val dbLeakThreshold = System.getenv("DB_LEAK_THRESHOLD")?.toLongOrNull() ?: 60000

    Database.registerDialect(BMPGDialect.DIALECT_NAME) { BMPGDialect() }

    return HikariDataSource(
        HikariConfig().apply {
            poolName = "pg-pool"
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = "jdbc:${BMPGDialect.DIALECT_NAME}://$dbHost:$dbPort/$dbName?reWriteBatchedInserts=true&ApplicationName=$app"
            username = dbUser
            password = dbPass
            minimumIdle = 2
            idleTimeout = 10000
            leakDetectionThreshold = dbLeakThreshold
            maximumPoolSize = 20
            connectionTestQuery = "SELECT 1"
        }
    ).also {
        Database.connect(it)
    }
}

class BMPGDialect : PostgreSQLDialect() {
    override val supportsSubqueryUnions: Boolean
        get() = true

    companion object {
        const val DIALECT_NAME: String = "postgresql"
    }
}

inline fun <reified T : Enum<T>> Table.postgresEnumeration(
    columnName: String,
    postgresEnumName: String
): Column<T> {
    val descriptor = T::class.serializerOrNull()?.descriptor
    val lookup = enumValues<T>().associateBy { descriptor?.getElementName(it.ordinal) }
    return customEnumeration(
        columnName, postgresEnumName,
        { value -> lookup[value as String] ?: throw IllegalArgumentException("$columnName has value ($value) not found in $postgresEnumName") }, { PGEnum(postgresEnumName, it, descriptor) }
    )
}

class PGEnum<T : Enum<T>>(enumTypeName: String, enumValue: T?, descriptor: SerialDescriptor?) : PGobject() {
    init {
        value = enumValue?.ordinal?.let { ord -> descriptor?.getElementName(ord) }
        type = enumTypeName
    }
}
