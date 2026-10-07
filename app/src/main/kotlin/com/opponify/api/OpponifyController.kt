package com.opponify.api

import com.opponify.service.*
import com.opponify.persistence.CurrentUserService
import com.opponify.sport.domain.SportCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class OpponifyController(private val service: OpponifyService, private val users: CurrentUserService) {
    @PostMapping("/opportunities")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request:CreateOpportunityRequest, auth:Authentication)=service.createOpportunity(users.resolve(actor(auth)),request)

    @GetMapping("/opportunities/{id}")
    fun get(@PathVariable id:UUID,auth:Authentication)=service.getOpportunity(users.resolve(auth.name),id)

    @GetMapping("/opportunities")
    fun discover(@RequestParam(required=false) sport:SportCode?,@RequestParam(required=false) town:String?,@RequestParam(defaultValue="50") limit:Int,@RequestParam(required=false) cursor:String?,auth:Authentication)=service.discover(users.resolve(auth.name),sport,town,limit,cursor)

    @PostMapping("/opportunities/{id}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(@PathVariable id:UUID,@RequestParam(required=false) teamId:UUID?,auth:Authentication)=mapOf("requestId" to service.createRequest(users.resolve(actor(auth)),id,teamId))

    @PostMapping("/participation-requests/{id}/accept")
    fun accept(@PathVariable id:UUID,@RequestHeader("Idempotency-Key",required=false) key:String?,auth:Authentication)=mapOf("resourceId" to service.acceptRequest(users.resolve(actor(auth)),id,key))

    @PostMapping("/participation-requests/{id}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun withdraw(@PathVariable id:UUID,auth:Authentication){service.withdrawRequest(users.resolve(actor(auth)),id)}

    @PostMapping("/games/{id}/attendance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun attendance(@PathVariable id:UUID,@RequestBody req:AttendanceRequest,auth:Authentication){service.recordAttendance(users.resolve(actor(auth)),id,req.participantKey,req.state)}

    @PostMapping("/games/{id}/results")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun result(@PathVariable id:UUID,@RequestBody req:ResultRequest,auth:Authentication){service.submitResult(users.resolve(actor(auth)),id,req.payload)}

    @PostMapping("/games/{id}/results/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirm(@PathVariable id:UUID,auth:Authentication){service.confirmResult(users.resolve(actor(auth)),id)}

    @PostMapping("/games/{id}/played")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun played(@PathVariable id:UUID,auth:Authentication){service.markPlayedWithoutResult(users.resolve(actor(auth)),id)}

    @PostMapping("/games/{id}/not-played")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun notPlayed(@PathVariable id:UUID,auth:Authentication){service.markNotPlayed(users.resolve(actor(auth)),id)}

    @PostMapping("/blocks/{targetUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun block(@PathVariable targetUserId:UUID,auth:Authentication){service.block(users.resolve(actor(auth)),targetUserId)}

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    fun report(@RequestBody req:ReportRequest,auth:Authentication)=mapOf("reportId" to service.report(users.resolve(actor(auth)),req.subjectId,req.category,req.description))

    private fun actor(auth:Authentication):String = auth.name
}

data class AttendanceRequest(val participantKey:String,val state:String)
data class ResultRequest(val payload:Map<String,Any>)
data class ReportRequest(val subjectId:UUID?,val category:String,val description:String?)

@RestController
@RequestMapping("/api/v1")
class SchedulingController(private val scheduling: com.opponify.service.SchedulingService, private val users: com.opponify.persistence.CurrentUserService) {
    @PostMapping("/games/{id}/time-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    fun propose(@PathVariable id:UUID,@RequestBody req:TimeProposalRequest,auth:Authentication)=mapOf("proposalId" to scheduling.proposeTime(users.resolve(auth.name),id,req.startAt))
    @PostMapping("/time-proposals/{id}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirm(@PathVariable id:UUID,auth:Authentication){scheduling.confirmTime(users.resolve(auth.name),id)}
    @PostMapping("/games/{id}/changes")
    @ResponseStatus(HttpStatus.CREATED)
    fun change(@PathVariable id:UUID,@RequestBody req:GameChangeRequest,auth:Authentication)=mapOf("changeId" to scheduling.proposeMaterialChange(users.resolve(auth.name),id,req.changeType,req.previousValue,req.proposedValue))
    @PostMapping("/game-changes/{id}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmChange(@PathVariable id:UUID,auth:Authentication){scheduling.confirmMaterialChange(users.resolve(auth.name),id)}
}

data class TimeProposalRequest(val startAt:Instant)
data class GameChangeRequest(val changeType:String,val previousValue:Any,val proposedValue:Any)

@RestController
@RequestMapping("/api/v1/trust")
class TrustController(private val trust:com.opponify.service.TrustService) {
    @GetMapping("/users/{subjectId}") fun user(@PathVariable subjectId:UUID)=trust.get(subjectId,"USER")
    @GetMapping("/teams/{subjectId}") fun team(@PathVariable subjectId:UUID)=trust.get(subjectId,"TEAM")
}

@RestController
@RequestMapping("/api/v1/facilities")
class FacilityController(private val facilities:com.opponify.service.FacilityService, private val users:com.opponify.persistence.CurrentUserService) {
    @GetMapping fun list(@RequestParam(required=false) town:String?,@RequestParam(required=false) sport:String?,@RequestParam(defaultValue="50") limit:Int)=facilities.list(town,sport,limit.coerceIn(1,100))
    @PostMapping("/suggest") @ResponseStatus(HttpStatus.CREATED) fun suggest(@RequestBody req:FacilitySuggestionRequest,auth:Authentication)=mapOf("facilityId" to facilities.suggest(users.resolve(auth.name),req.name,req.town,req.latitude,req.longitude))
}
data class FacilitySuggestionRequest(val name:String,val town:String,val latitude:Double?,val longitude:Double?)

@RestController
@RequestMapping("/api/v1/teams")
class TeamController(private val teams:com.opponify.service.TeamService, private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody req:TeamCreateRequest,auth:Authentication)=mapOf("teamId" to teams.create(users.resolve(auth.name),req.name))
    @PostMapping("/{id}/members") @ResponseStatus(HttpStatus.NO_CONTENT) fun add(@PathVariable id:UUID,@RequestBody req:TeamMemberRequest,auth:Authentication){teams.addMember(users.resolve(auth.name),id,req.userId,req.role)}
    @PostMapping("/{id}/captain") @ResponseStatus(HttpStatus.NO_CONTENT) fun captain(@PathVariable id:UUID,@RequestBody req:CaptainRequest,auth:Authentication){teams.changeCaptain(users.resolve(auth.name),id,req.userId)}
    @PostMapping("/{id}/close") @ResponseStatus(HttpStatus.NO_CONTENT) fun close(@PathVariable id:UUID,auth:Authentication){teams.close(users.resolve(auth.name),id)}
}
data class TeamCreateRequest(val name:String)
data class TeamMemberRequest(val userId:UUID,val role:String)
data class CaptainRequest(val userId:UUID)

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val notifications:com.opponify.service.NotificationService, private val users:com.opponify.persistence.CurrentUserService) {
    @GetMapping fun list(@RequestParam(defaultValue="50") limit:Int,auth:Authentication)=notifications.list(users.resolve(auth.name),limit)
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) fun read(@PathVariable id:UUID,auth:Authentication){notifications.markRead(users.resolve(auth.name),id)}
}

@RestController
@RequestMapping("/api/v1/opportunities")
class OpportunitySchedulingController(private val times:com.opponify.service.OpportunityTimeService, private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/{id}/time-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    fun propose(@PathVariable id:UUID,@RequestBody req:TimeProposalRequest,auth:Authentication)=mapOf("proposalId" to times.propose(users.resolve(auth.name),id,req.startAt))
    @PostMapping("/time-proposals/{proposalId}/confirm")
    fun confirm(@PathVariable proposalId:UUID,auth:Authentication)=mapOf("gameId" to times.confirm(users.resolve(auth.name),proposalId))
}

@RestController
@RequestMapping("/api/v1/messages")
class CommunicationController(private val communication:com.opponify.service.CommunicationService, private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/contexts") @ResponseStatus(HttpStatus.CREATED) fun context(@RequestBody req:MessageContextRequest,auth:Authentication)=mapOf("contextId" to communication.createContext(users.resolve(auth.name),req.otherUserId,req.gameId))
    @GetMapping("/contexts/{id}") fun list(@PathVariable id:UUID,auth:Authentication)=communication.list(users.resolve(auth.name),id)
    @PostMapping("/contexts/{id}/messages") @ResponseStatus(HttpStatus.CREATED) fun send(@PathVariable id:UUID,@RequestBody req:MessageRequest,auth:Authentication)=mapOf("messageId" to communication.send(users.resolve(auth.name),id,req.body))
}
data class MessageContextRequest(val otherUserId:UUID,val gameId:UUID?)
data class MessageRequest(val body:String)

@RestController
@RequestMapping("/api/v1/users")
class HistoryController(private val history:com.opponify.service.HistoryService) {
    @GetMapping("/{subjectId}/history") fun events(@PathVariable subjectId:UUID,@RequestParam(defaultValue="50") limit:Int)=history.publicUserHistory(subjectId,limit)
    @GetMapping("/teams/{teamId}/history") fun teamEvents(@PathVariable teamId:UUID,@RequestParam(defaultValue="50") limit:Int)=history.publicTeamHistory(teamId,limit)
}

@RestController
@RequestMapping("/api/v1/games")
class AttendanceCancellationController(private val attendance:com.opponify.service.AttendanceService, private val cancellation:com.opponify.service.CancellationService, private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/{id}/attendance/claims") @ResponseStatus(HttpStatus.NO_CONTENT) fun claim(@PathVariable id:UUID,@RequestBody req:AttendanceRequest,auth:Authentication){attendance.claim(users.resolve(auth.name),id,req.participantKey,req.state)}
    @PostMapping("/{id}/attendance/confirm") @ResponseStatus(HttpStatus.NO_CONTENT) fun confirm(@PathVariable id:UUID,@RequestBody req:AttendanceRequest,auth:Authentication){attendance.confirm(users.resolve(auth.name),id,req.participantKey,req.state)}
    @PostMapping("/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT) fun cancel(@PathVariable id:UUID,@RequestBody req:CancellationRequest,auth:Authentication){cancellation.cancel(users.resolve(auth.name),id,req.category,req.reason)}
}
data class CancellationRequest(val category:String,val reason:String?)

@RestController
@RequestMapping("/api/v1/account")
class AccountController(private val account:com.opponify.service.AccountService,private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/close") @ResponseStatus(HttpStatus.NO_CONTENT) fun close(auth:Authentication){account.close(users.resolve(auth.name))}
}


@RestController
@RequestMapping("/api/v1/disputes")
class DisputeController(private val disputes:com.opponify.service.DisputeService,private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun open(@RequestBody req:DisputeRequest,auth:Authentication)=mapOf("disputeId" to disputes.open(users.resolve(auth.name),req.gameId,req.subjectType,req.subjectId,req.reason))
    @PostMapping("/{id}/resolve") @ResponseStatus(HttpStatus.NO_CONTENT) fun resolve(@PathVariable id:UUID,@RequestBody req:DisputeResolutionRequest,auth:Authentication){disputes.resolve(users.resolve(auth.name),id,req.resolution)}
}
data class DisputeRequest(val gameId:UUID,val subjectType:String,val subjectId:UUID,val reason:String)
data class DisputeResolutionRequest(val resolution:String)

@RestController
@RequestMapping("/api/v1/games")
class FacilityDisruptionController(private val disruptions:com.opponify.service.FacilityDisruptionService,private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/{id}/facility-disruptions") @ResponseStatus(HttpStatus.CREATED) fun report(@PathVariable id:UUID,@RequestBody req:FacilityDisruptionRequest,auth:Authentication)=mapOf("disruptionId" to disruptions.report(users.resolve(auth.name),id,req.category,req.description))
}
data class FacilityDisruptionRequest(val category:String,val description:String?)

@RestController
@RequestMapping("/api/v1/opportunities")
class OpportunityLifecycleController(private val lifecycle:com.opponify.service.OpportunityLifecycleService,private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/{id}/close") @ResponseStatus(HttpStatus.NO_CONTENT) fun close(@PathVariable id:UUID,auth:Authentication){lifecycle.close(users.resolve(auth.name),id)}
    @PostMapping("/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT) fun cancel(@PathVariable id:UUID,auth:Authentication){lifecycle.cancel(users.resolve(auth.name),id)}
    @PostMapping("/{id}/reopen") @ResponseStatus(HttpStatus.NO_CONTENT) fun reopen(@PathVariable id:UUID,auth:Authentication){lifecycle.reopen(users.resolve(auth.name),id)}
    @PostMapping("/{id}/changes") @ResponseStatus(HttpStatus.CREATED) fun change(@PathVariable id:UUID,@RequestBody req:OpportunityChangeRequest,auth:Authentication)=mapOf("changeId" to lifecycle.proposeChange(users.resolve(auth.name),id,req.changeType,req.previousValue,req.proposedValue))
    @PostMapping("/changes/{changeId}/confirm") @ResponseStatus(HttpStatus.NO_CONTENT) fun confirm(@PathVariable changeId:UUID,auth:Authentication){lifecycle.confirmChange(users.resolve(auth.name),changeId)}
}
data class OpportunityChangeRequest(val changeType:String,val previousValue:Any,val proposedValue:Any)

@RestController
@RequestMapping("/api/v1/participation-requests")
class CreatorParticipationController(private val service:com.opponify.service.OpponifyService,private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/{id}/creator-withdraw") @ResponseStatus(HttpStatus.NO_CONTENT) fun withdraw(@PathVariable id:UUID,auth:Authentication){service.creatorWithdrawAccepted(users.resolve(auth.name),id)}
}

@RestController
@RequestMapping("/api/v1/teams")
class TeamMembershipController(private val memberships:com.opponify.service.TeamMembershipService,private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/{id}/membership-requests") @ResponseStatus(HttpStatus.CREATED) fun request(@PathVariable id:UUID,auth:Authentication)=mapOf("requestId" to memberships.request(users.resolve(auth.name),id))
    @PostMapping("/membership-requests/{requestId}/accept") @ResponseStatus(HttpStatus.NO_CONTENT) fun accept(@PathVariable requestId:UUID,auth:Authentication){memberships.accept(users.resolve(auth.name),requestId)}
    @PostMapping("/membership-requests/{requestId}/reject") @ResponseStatus(HttpStatus.NO_CONTENT) fun reject(@PathVariable requestId:UUID,auth:Authentication){memberships.reject(users.resolve(auth.name),requestId)}
}

@RestController
@RequestMapping("/api/v1/users")
class PlayerProfileController(private val profiles:com.opponify.service.PlayerProfileService,private val users:com.opponify.persistence.CurrentUserService) {
    @GetMapping("/{id}/profile") fun get(@PathVariable id:UUID,auth:Authentication)=profiles.get(id,users.resolve(auth.name))
    @PutMapping("/me/profile") @ResponseStatus(HttpStatus.NO_CONTENT) fun update(@RequestBody req:ProfileUpdateRequest,auth:Authentication){profiles.update(users.resolve(auth.name),req.skillLevel,req.publicProfile)}
}
data class ProfileUpdateRequest(val skillLevel:com.opponify.player.domain.SkillLevel?,val publicProfile:Boolean=true)

@RestController
@RequestMapping("/api/v1/games")
class GameCancellationController(private val cancellation:com.opponify.service.GameCancellationService,private val users:com.opponify.persistence.CurrentUserService) {
    @PostMapping("/{id}/cancel-game") @ResponseStatus(HttpStatus.NO_CONTENT) fun cancel(@PathVariable id:UUID,@RequestBody req:CancellationRequest,auth:Authentication){cancellation.cancel(users.resolve(auth.name),id,req.category,req.reason)}
}
