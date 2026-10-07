package com.opponify.api

import com.opponify.persistence.CurrentUserService
import com.opponify.security.AdminAccessService
import com.opponify.service.FacilityService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val admin:AdminAccessService,
    private val users:CurrentUserService,
    private val jdbc:JdbcTemplate,
    private val facilities:FacilityService,
){
    @GetMapping("/reports")
    fun reports(auth:Authentication):List<Map<String,Any?>> { val actor=users.resolve(auth.name); admin.requireAdmin(actor); return jdbc.queryForList("SELECT * FROM reports ORDER BY created_at DESC LIMIT 200") }

    @PostMapping("/facilities/{id}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reviewFacility(@PathVariable id:UUID,@RequestBody req:AdminFacilityReviewRequest,auth:Authentication){ val actor=users.resolve(auth.name); admin.requireAdmin(actor); facilities.review(actor,id,req.status) }

    @PostMapping("/facilities/{id}/sport-association")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reviewSportAssociation(@PathVariable id:UUID,@RequestBody req:AdminFacilitySportRequest,auth:Authentication){ val actor=users.resolve(auth.name); admin.requireAdmin(actor); facilities.setSportAssociation(actor,id,req.sport,req.status) }
}

data class AdminFacilityReviewRequest(val status:String)
data class AdminFacilitySportRequest(val sport:String,val status:String)
