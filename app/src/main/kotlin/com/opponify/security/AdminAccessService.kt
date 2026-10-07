package com.opponify.security

import com.opponify.api.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AdminAccessService(@Value("\${OPPONIFY_ADMIN_USER_IDS:}") configured:String) {
    private val allowed: Set<UUID> = configured.split(',').mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }.toSet()
    fun requireAdmin(userId:UUID) { if (userId !in allowed) throw ApiException(403,"ADMIN_REQUIRED","Administrative permission is required.") }
}
