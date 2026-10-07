package com.opponify.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig(@Value("\${opponify.security.dev-mode:false}") private val devMode:Boolean){
    @Bean
    fun securityFilterChain(http:HttpSecurity):SecurityFilterChain {
        http.csrf { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }.authorizeHttpRequests {
            it.requestMatchers("/api/v1/health","/actuator/health").permitAll().anyRequest().authenticated()
        }
        return http.build()
    }
}
