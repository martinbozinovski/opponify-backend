package com.opponify.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.opponify.api.ApiException
import com.opponify.opportunity.domain.*
import com.opponify.participation.domain.RequestStatus
import com.opponify.persistence.GameRepository
import com.opponify.persistence.OpportunityRepository
import com.opponify.persistence.ParticipationRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Service
class OpponifyService(
    private val jdbc: JdbcTemplate,
    private val opportunities: OpportunityRepository,
    private val requests: ParticipationRepository,
    private val games: GameRepository,
    private val mapper: ObjectMapper,
    private val resultValidation: ResultValidationService,
    private val trust: TrustService,
    @Value("\${opponify.participation.request-expiry}") private val requestExpiry: Duration,
    @Value("\${opponify.scheduling.default-game-duration}") private val defaultDuration: Duration,
    @Value("\${opponify.post-game.resolution-window}") private val resolutionWindow: Duration
) {
    @Transactional
    fun createOpportunity(actor: UUID, req: CreateOpportunityRequest): Opportunity {
        if (req.creatorTeamId != null && !canManageTeam(actor, req.creatorTeamId)) throw ApiException(403,"TEAM_AUTHORITY_REQUIRED","User cannot commit for this team.")
        if (req.minimumParticipation > req.targetCapacity) throw ApiException(422,"INVALID_CAPACITY","Minimum cannot exceed target.")
        if (req.timeType == TimeType.EXACT && req.startAt == null) throw ApiException(422,"EXACT_TIME_REQUIRED","Exact opportunities require a start time.")
        if (req.timeType != TimeType.EXACT && req.startAt != null && req.endAt == null) throw ApiException(422,"INVALID_TIME_RANGE","Range/flexible time must provide a complete range or no exact start.")
        val id=UUID.randomUUID()
        val opportunity=Opportunity(id,if(req.creatorTeamId==null) actor else null,req.creatorTeamId,req.sport,req.need,req.timeType,req.startAt,req.endAt,req.town,req.facilityId,req.targetCapacity,req.minimumParticipation,req.skillLevel,req.desiredOpponentLevel,OpportunityStatus.OPEN)
        val expiry=req.expiresAt ?: req.endAt ?: req.startAt?.plus(Duration.ofHours(24))
        opportunities.insert(opportunity,expiry)
        audit(actor,"OPPORTUNITY_CREATED","opportunity",id)
        return opportunity
    }

    @Transactional(readOnly=true)
    fun getOpportunity(viewer:UUID,id:UUID):Opportunity { val o=opportunities.find(id) ?: throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found."); return o }

    @Transactional(readOnly=true)
    fun discover(viewer:UUID,sport:com.opponify.sport.domain.SportCode?,town:String?,limit:Int,cursor:String?)=opportunities.list(sport,town,OpportunityStatus.OPEN,limit.coerceIn(1,100),viewer,cursor)

    @Transactional
    fun createRequest(actor:UUID,opportunityId:UUID,teamId:UUID?):UUID {
        val o=opportunities.find(opportunityId) ?: throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found.")
        if(o.status!=OpportunityStatus.OPEN) throw ApiException(409,"OPPORTUNITY_NOT_OPEN","Opportunity is not open.")
        if(teamId!=null && !canManageTeam(actor,teamId)) throw ApiException(403,"TEAM_AUTHORITY_REQUIRED","User cannot request for this team.")
        val id=UUID.randomUUID(); requests.insert(id,opportunityId,if(teamId==null)actor else null,teamId,Instant.now().plus(requestExpiry)); audit(actor,"PARTICIPATION_REQUESTED","request",id); return id
    }

    @Transactional
    fun acceptRequest(actor:UUID,requestId:UUID,idempotencyKey:String?):UUID {
        val request=requests.find(requestId) ?: throw ApiException(404,"REQUEST_NOT_FOUND","Request not found.")
        val opportunityId=request["opportunity_id"] as UUID
        val o=opportunities.find(opportunityId) ?: throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found.")
        val requesterUser=request["requester_user_id"] as UUID?
        if(requesterUser!=null && o.creatorUserId!=null && (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM blocks WHERE (blocker_user_id=? AND blocked_user_id=?) OR (blocker_user_id=? AND blocked_user_id=?))",Boolean::class.java,o.creatorUserId,requesterUser,requesterUser,o.creatorUserId)?:false)) throw ApiException(409,"USER_BLOCKED","Participation cannot proceed because the users are blocked.")
        if(o.creatorUserId!=actor && (o.creatorTeamId?.let{canManageTeam(actor,it)}!=true)) throw ApiException(403,"CREATOR_AUTHORITY_REQUIRED","Only the opportunity creator or authorized team representative can accept.")
        if(o.status!=OpportunityStatus.OPEN) throw ApiException(409,"OPPORTUNITY_NOT_OPEN","Opportunity is no longer open.")
        if(requesterUser!=null && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM participation_requests WHERE opportunity_id=? AND requester_user_id=? AND status='ACCEPTED')",Boolean::class.java,o.id,requesterUser)==true) throw ApiException(409,"ALREADY_ACCEPTED","Requester already has accepted participation in this opportunity.")
        if(request["requester_team_id"]!=null && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM participation_requests WHERE opportunity_id=? AND requester_team_id=? AND status='ACCEPTED')",Boolean::class.java,o.id,request["requester_team_id"] as UUID)==true) throw ApiException(409,"ALREADY_ACCEPTED","Team already has accepted participation in this opportunity.")
        jdbc.queryForObject("SELECT id FROM opportunities WHERE id=? FOR UPDATE",UUID::class.java,opportunityId) ?: throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found.")
        val accepted=requests.pendingCount(opportunityId)
        if(accepted >= o.targetCapacity) throw ApiException(409,"CAPACITY_FULL","Target capacity has been reached.")
        if(requests.accept(requestId)!=1) return requestId
        val gameId = if(o.timeType==TimeType.EXACT) {
            val start=o.startAt ?: throw ApiException(409,"EXACT_TIME_REQUIRED","Cannot schedule without exact time.")
            val end=start.plus(defaultDuration)
            val existing=jdbc.queryForList("SELECT id FROM scheduled_games WHERE opportunity_id=? AND lifecycle NOT IN ('CANCELLED','NOT_PLAYED') ORDER BY created_at LIMIT 1",o.id).firstOrNull()
            val id=existing?.get("id") as UUID? ?: UUID.randomUUID()
            if(existing==null){
                val creatorUsers=participantUsersForOpportunity(o)
                if(creatorUsers.any{games.hasOverlap(it,start,end)}) throw ApiException(409,"SCHEDULE_OVERLAP","A creator participant has an overlapping scheduled commitment.")
                games.create(id,o.id,start,defaultDuration,ZoneId.of("UTC"))
                if(o.creatorUserId!=null) games.addParticipant(id,o.creatorUserId,null) else games.addParticipant(id,null,o.creatorTeamId)
            }
            if(games.activeCount(id) >= o.targetCapacity) throw ApiException(409,"CAPACITY_FULL","Target capacity has been reached.")
            val requesterTeam=request["requester_team_id"] as UUID?
            val requesterUsers=if(requesterUser!=null) listOf(requesterUser) else teamUsers(requesterTeam!!)
            if(requesterUsers.any{games.hasOverlap(it,start,end,id)}) throw ApiException(409,"SCHEDULE_OVERLAP","A participant has an overlapping scheduled commitment.")
            if(requesterUser!=null) games.addParticipant(id,requesterUser,null) else games.addParticipant(id,null,requesterTeam)
            if(games.activeCount(id) >= o.targetCapacity) jdbc.update("UPDATE opportunities SET status='CLOSED',updated_at=NOW() WHERE id=? AND status='OPEN'",o.id)
            id
        } else null
        audit(actor,"PARTICIPATION_ACCEPTED","request",requestId); return gameId ?: requestId
    }

    @Transactional
    fun creatorWithdrawAccepted(actor:UUID,requestId:UUID) {
        val r=requests.find(requestId) ?: throw ApiException(404,"REQUEST_NOT_FOUND","Request not found.")
        val o=opportunities.find(r["opportunity_id"] as UUID) ?: throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found.")
        if(o.creatorUserId!=actor && (o.creatorTeamId?.let{canManageTeam(actor,it)}!=true)) throw ApiException(403,"CREATOR_AUTHORITY_REQUIRED","Creator authority required.")
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM scheduled_games WHERE opportunity_id=?)",Boolean::class.java,o.id)==true) throw ApiException(409,"ALREADY_SCHEDULED","Accepted participation cannot be creator-withdrawn after scheduling.")
        if(requests.withdraw(requestId)!=1) throw ApiException(409,"REQUEST_NOT_WITHDRAWABLE","Accepted participation cannot be withdrawn in its current state.")
        audit(actor,"ACCEPTED_PARTICIPATION_WITHDRAWN_BY_CREATOR","request",requestId)
    }

    @Transactional
    fun withdrawRequest(actor:UUID,requestId:UUID) {
        val r=requests.find(requestId) ?: throw ApiException(404,"REQUEST_NOT_FOUND","Request not found.")
        val requester=r["requester_user_id"] as UUID?
        if(requester!=actor) throw ApiException(403,"REQUESTER_REQUIRED","Only the requester can withdraw.")
        val opportunityId=r["opportunity_id"] as UUID
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM scheduled_games WHERE opportunity_id=?)",Boolean::class.java,opportunityId)==true) throw ApiException(409,"ALREADY_SCHEDULED","After scheduling, use game cancellation rules instead of request withdrawal.")
        if(requests.withdraw(requestId)!=1) throw ApiException(409,"REQUEST_NOT_WITHDRAWABLE","Request is no longer withdrawable.")
        audit(actor,"PARTICIPATION_WITHDRAWN","request",requestId)
    }

    @Transactional
    fun cancelGame(actor:UUID,gameId:UUID) {
        val game=games.find(gameId) ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        val owner=jdbc.queryForObject("SELECT o.creator_user_id FROM scheduled_games g JOIN opportunities o ON o.id=g.opportunity_id WHERE g.id=?",UUID::class.java,gameId)
        val team=jdbc.queryForObject("SELECT o.creator_team_id FROM scheduled_games g JOIN opportunities o ON o.id=g.opportunity_id WHERE g.id=?",UUID::class.java,gameId)
        if(owner!=actor && (team==null || !canManageTeam(actor,team))) throw ApiException(403,"GAME_AUTHORITY_REQUIRED","Only the creator or authorized team representative may cancel this game.")
        games.updateLifecycle(gameId,"CANCELLED"); audit(actor,"GAME_CANCELLED","game",gameId)
    }

    @Transactional
    fun recordAttendance(actor:UUID,gameId:UUID,participantKey:String,state:String) {
        ensureGameParticipant(actor,gameId)
        requireState(state,setOf("EXPECTED","CLAIMED_ATTENDED","CLAIMED_ABSENT","CONFIRMED_ATTENDED","CONFIRMED_ABSENT","DISPUTED","UNRESOLVED"))
        if(games.find(gameId)==null) throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        jdbc.update("INSERT INTO attendance_events(id,game_id,participant_key,submitted_by,state) VALUES(?,?,?,?,?)",UUID.randomUUID(),gameId,participantKey,actor,state)
        audit(actor,"ATTENDANCE_RECORDED","game",gameId)
    }

    @Transactional
    fun submitResult(actor:UUID,gameId:UUID,payload:Map<String,Any>) {
        ensureGameParticipant(actor,gameId)
        val game=games.find(gameId) ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        val now=Instant.now()
        if(game.startAt.isAfter(now)) throw ApiException(409,"GAME_NOT_STARTED","Results cannot be submitted before game time.")
        if(now.isAfter(game.endAt.plus(resolutionWindow))) throw ApiException(409,"POST_GAME_WINDOW_EXPIRED","The post-game resolution window has expired.")
        val node=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(payload)
        val sport=resultValidation.validate(gameId,node)
        val json=mapper.writeValueAsString(payload)
        jdbc.update("INSERT INTO results(game_id,state,payload,submitted_by,sport,schema_version) VALUES(?, 'SUBMITTED', ?::jsonb, ?, ?, 1) ON CONFLICT(game_id) DO UPDATE SET state='SUBMITTED',payload=EXCLUDED.payload,submitted_by=EXCLUDED.submitted_by,sport=EXCLUDED.sport,schema_version=1,updated_at=NOW()",gameId,json,actor,sport.name)
        audit(actor,"RESULT_SUBMITTED","game",gameId)
    }

    @Transactional
    fun confirmResult(actor:UUID,gameId:UUID) {
        ensureGameParticipant(actor,gameId)
        val game=games.find(gameId) ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        if(Instant.now().isAfter(game.endAt.plus(resolutionWindow))) throw ApiException(409,"POST_GAME_WINDOW_EXPIRED","The post-game resolution window has expired.")
        val updated=jdbc.update("UPDATE results SET state='CONFIRMED',confirmed_by=?,updated_at=NOW() WHERE game_id=? AND state='SUBMITTED' AND submitted_by<>?",actor,gameId,actor)
        if(updated!=1) throw ApiException(409,"RESULT_NOT_CONFIRMABLE","No submitted result is awaiting confirmation.")
        games.updateLifecycle(gameId,"PLAYED"); val eventId=UUID.randomUUID(); audit(actor,"RESULT_CONFIRMED","game",gameId); addCompletionEvidence(gameId,eventId); addResultEvidence(gameId,eventId)
    }

    @Transactional
    fun markPlayedWithoutResult(actor:UUID,gameId:UUID) {
        ensureGameParticipant(actor,gameId)
        val game=games.find(gameId) ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        if(Instant.now().isBefore(game.startAt) || Instant.now().isAfter(game.endAt.plus(resolutionWindow))) throw ApiException(409,"POST_GAME_WINDOW_INVALID","Game is outside its resolution window.")
        if(games.find(gameId)==null) throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        jdbc.update("INSERT INTO results(game_id,state,submitted_by) VALUES(?,'PLAYED_NO_RESULT',?) ON CONFLICT(game_id) DO UPDATE SET state='PLAYED_NO_RESULT',updated_at=NOW()",gameId,actor)
        games.updateLifecycle(gameId,"PLAYED"); val eventId=UUID.randomUUID(); audit(actor,"GAME_MARKED_PLAYED","game",gameId); addCompletionEvidence(gameId,eventId)
    }

    @Transactional
    fun markNotPlayed(actor:UUID,gameId:UUID) {
        ensureGameParticipant(actor,gameId)
        val game=games.find(gameId) ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        if(Instant.now().isBefore(game.startAt) || Instant.now().isAfter(game.endAt.plus(resolutionWindow))) throw ApiException(409,"POST_GAME_WINDOW_INVALID","Game is outside its resolution window.")
        if(games.find(gameId)==null) throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        games.updateLifecycle(gameId,"NOT_PLAYED"); audit(actor,"GAME_MARKED_NOT_PLAYED","game",gameId)
    }

    @Transactional
    fun block(actor:UUID,target:UUID) { if(actor==target) throw ApiException(422,"SELF_BLOCK","Cannot block yourself."); jdbc.update("INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES(?,?) ON CONFLICT DO NOTHING",actor,target); audit(actor,"USER_BLOCKED","user",target) }
    @Transactional
    fun report(actor:UUID,subject:UUID?,category:String,description:String?):UUID { val id=UUID.randomUUID(); jdbc.update("INSERT INTO reports(id,reporter_user_id,subject_id,category,description) VALUES(?,?,?,?,?)",id,actor,subject,category,description); audit(actor,"REPORT_CREATED","report",id); return id }

    private fun addResultEvidence(gameId:UUID,eventId:UUID){
        jdbc.queryForList("SELECT participant_user_id,participant_team_id FROM game_participants WHERE game_id=? AND status='ACTIVE'",gameId).forEach { row ->
            val user=row["participant_user_id"] as UUID?
            val team=row["participant_team_id"] as UUID?
            if(user!=null) trust.addEvidence(user,UUID.nameUUIDFromBytes("$eventId:RESULT:$user".toByteArray()),"CONFIRMED_RESULT",0,subjectType="USER")
            if(team!=null) trust.addEvidence(team,UUID.nameUUIDFromBytes("$eventId:RESULT:TEAM:$team".toByteArray()),"CONFIRMED_RESULT",0,subjectType="TEAM")
        }
    }

    private fun addCompletionEvidence(gameId:UUID,eventId:UUID){
        val participants=jdbc.queryForList("SELECT participant_user_id,participant_team_id FROM game_participants WHERE game_id=? AND status='ACTIVE'",gameId)
        val users=participants.mapNotNull{it["participant_user_id"] as UUID?}
        val teams=participants.mapNotNull{it["participant_team_id"] as UUID?}
        users.forEach { subject ->
            val opponents=users.filter{it!=subject}
            if(opponents.isEmpty()) trust.addEvidence(subject,UUID.nameUUIDFromBytes("$eventId:$subject".toByteArray()),"CONFIRMED_GAME",0)
            else opponents.forEach{opponent->trust.addEvidence(subject,UUID.nameUUIDFromBytes("$eventId:$subject:$opponent".toByteArray()),"CONFIRMED_GAME",0,opponent)}
        }
        teams.forEach { subject ->
            val opponents=teams.filter{it!=subject}
            if(opponents.isEmpty()) trust.addEvidence(subject,UUID.nameUUIDFromBytes("$eventId:TEAM:$subject".toByteArray()),"CONFIRMED_GAME",0,subjectType="TEAM")
            else opponents.forEach{opponent->trust.addEvidence(subject,UUID.nameUUIDFromBytes("$eventId:TEAM:$subject:$opponent".toByteArray()),"CONFIRMED_GAME",0,opponent,"TEAM")}
        }
    }

    private fun ensureGameParticipant(actor:UUID,gameId:UUID){
        val direct=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM game_participants WHERE game_id=? AND participant_user_id=? AND status='ACTIVE')",Boolean::class.java,gameId,actor) ?: false
        val team=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM game_participants p JOIN team_memberships m ON m.team_id=p.participant_team_id WHERE p.game_id=? AND m.user_id=? AND m.status='ACTIVE' AND p.status='ACTIVE')",Boolean::class.java,gameId,actor) ?: false
        if(!direct && !team) throw ApiException(403,"GAME_PARTICIPANT_REQUIRED","User is not an active game participant.")
    }
    private fun teamUsers(teamId:UUID):List<UUID> =
        jdbc.queryForList("SELECT user_id FROM team_memberships WHERE team_id=? AND status='ACTIVE'",teamId).map{it["user_id"] as UUID}
    private fun participantUsersForOpportunity(o:Opportunity):List<UUID> {
        val user=o.creatorUserId
        val team=o.creatorTeamId
        return if(user!=null) listOf(user) else if(team!=null) teamUsers(team) else emptyList()
    }
    private fun canManageTeam(actor:UUID,teamId:UUID):Boolean=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM team_memberships WHERE team_id=? AND user_id=? AND status='ACTIVE' AND role IN ('CAPTAIN','MANAGER'))",Boolean::class.java,teamId,actor) ?: false
    private fun audit(actor:UUID,action:String,type:String,id:UUID){
        val eventId=UUID.randomUUID()
        val payload=mapper.writeValueAsString(mapOf("actorUserId" to actor.toString()))
        jdbc.update("INSERT INTO domain_events(event_id,aggregate_id,event_type,occurred_at,payload_version,payload) VALUES(?,?,?,NOW(),1,?::jsonb)",eventId,id,action,payload)
        jdbc.update("INSERT INTO audit_log(actor_user_id,action,resource_type,resource_id) VALUES(?,?,?,?)",actor,action,type,id)
    }
    private fun requireState(value:String,allowed:Set<String>){if(value !in allowed)throw ApiException(422,"INVALID_STATE","Unsupported state: $value")}
}

data class CreateOpportunityRequest(
    val creatorTeamId:UUID?, val sport:com.opponify.sport.domain.SportCode, val need:NeedType,
    val timeType:TimeType, val startAt:Instant?, val endAt:Instant?, val town:String?, val facilityId:UUID?, val targetCapacity:Int,
    val minimumParticipation:Int, val skillLevel:com.opponify.player.domain.SkillLevel?, val desiredOpponentLevel:com.opponify.player.domain.SkillLevel?, val expiresAt:Instant?
)
