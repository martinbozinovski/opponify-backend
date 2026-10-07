package com.opponify.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("prod", "production")
class ProductionConfigurationValidator(
    @Value("\${DB_URL:}") private val dbUrl:String,
    @Value("\${DB_USERNAME:}") private val dbUsername:String,
    @Value("\${DB_PASSWORD:}") private val dbPassword:String,
    @Value("\${FIREBASE_ISSUER_URI:}") private val firebaseIssuer:String,
    @Value("\${OPPONIFY_SECURITY_DEV_MODE:false}") private val devMode:Boolean,
): ApplicationRunner {
    override fun run(args:ApplicationArguments){
        require(dbUrl.isNotBlank()) { "DB_URL is required in production" }
        require(dbUsername.isNotBlank()) { "DB_USERNAME is required in production" }
        require(dbPassword.isNotBlank()) { "DB_PASSWORD is required in production" }
        require(firebaseIssuer.isNotBlank()) { "FIREBASE_ISSUER_URI is required in production" }
        require(!devMode) { "OPPONIFY_SECURITY_DEV_MODE must be false in production" }
    }
}
