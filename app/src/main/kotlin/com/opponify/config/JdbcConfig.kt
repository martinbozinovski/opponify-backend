package com.opponify.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * The PostgreSQL JDBC driver cannot bind java.time.Instant ("Can't infer the SQL type to use for an
 * instance of java.time.Instant"). Services bind Instant values directly, so every positional argument
 * is normalised here, in one place, to a UTC OffsetDateTime (timestamptz) before it reaches the driver.
 */
class InstantAwareJdbcTemplate(dataSource: DataSource) : JdbcTemplate(dataSource) {
    override fun newArgPreparedStatementSetter(args: Array<out Any?>?): PreparedStatementSetter =
        super.newArgPreparedStatementSetter(args?.map { toJdbcValue(it) }?.toTypedArray())

    companion object {
        fun toJdbcValue(value: Any?): Any? =
            if (value is Instant) OffsetDateTime.ofInstant(value, ZoneOffset.UTC) else value
    }
}

@Configuration
class JdbcConfig {
    @Bean
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = InstantAwareJdbcTemplate(dataSource)
}
