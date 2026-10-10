package com.opponify.service

import com.opponify.api.ApiException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class AttendanceService(
    private val jdbc:JdbcTemplate,
    private val trust:TrustService,
    @Value("\${opponify.post-game.resolution-window}") private val resolutionWindow:Duration
) {
    @Transactional
    fun claim(actor:UUID,gameId:UUID,participantKey:String,state:String){
        val allowed=setOf("CLAIMED_ATTENDED","CLAIMED_ABSENT")
        if(state !in allowed)throw ApiException(422,"INVALID_ATTENDANCE_CLAIM","Only attendance claims can be submitted here.")
        ensureParticipant(actor,gameId)
        val row=jdbc.queryForList("SELECT start_at,duration_seconds FROM scheduled_games WHERE id=?",gameId).firstOrNull() ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        val start=(row["start_at"] as java.sql.Timestamp).toInstant(); val end=start.plusSeconds((row["duration_seconds"] as Number).toLong())
        if(Instant.now().isBefore(end) || Instant.now().isAfter(end.plus(resolutionWindow))) throw ApiException(409,"POST_GAME_WINDOW_INVALID","Attendance is outside its resolution window.")
        jdbc.update("INSERT INTO attendance_events(id,game_id,participant_key,submitted_by,state) VALUES(?,?,?,?,?)",UUID.randomUUID(),gameId,participantKey,actor,state)
    }
    @Transactional
    fun confirm(actor:UUID,gameId:UUID,participantKey:String,state:String){
        ensureParticipant(actor,gameId)
        val row=jdbc.queryForList("SELECT start_at,duration_seconds FROM scheduled_games WHERE id=?",gameId).firstOrNull() ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        val start=(row["start_at"] as java.sql.Timestamp).toInstant(); val end=start.plusSeconds((row["duration_seconds"] as Number).toLong())
        if(Instant.now().isBefore(end) || Instant.now().isAfter(end.plus(resolutionWindow))) throw ApiException(409,"POST_GAME_WINDOW_INVALID","Attendance confirmation is outside its resolution window.")
        val expected=if(state=="CONFIRMED_ABSENT")"CLAIMED_ABSENT" else if(state=="CONFIRMED_ATTENDED")"CLAIMED_ATTENDED" else throw ApiException(422,"INVALID_CONFIRMATION","Unsupported attendance confirmation.")
        val claim=jdbc.queryForList("SELECT * FROM attendance_events WHERE game_id=? AND participant_key=? AND state=? AND submitted_by<>? ORDER BY created_at DESC LIMIT 1",gameId,participantKey,expected,actor).firstOrNull() ?: throw ApiException(409,"NO_OTHER_CLAIM","A matching claim from another participant is required.")
        val eventId=UUID.randomUUID()
        jdbc.update("INSERT INTO attendance_events(id,game_id,participant_key,submitted_by,state) VALUES(?,?,?,?,?)",eventId,gameId,participantKey,actor,state)
        if(state=="CONFIRMED_ABSENT") {
            val subject=participantKey.toUuidOrNull() ?: throw ApiException(422,"INVALID_PARTICIPANT_KEY","Participant key must identify a participant.")
            val isTeam=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM game_participants WHERE game_id=? AND participant_team_id=? AND status='ACTIVE')",Boolean::class.java,gameId,subject) ?: false
            trust.addEvidence(subject,eventId,"CONFIRMED_NO_SHOW",5,subjectType=if(isTeam)"TEAM" else "USER")
        }
    }
    @Transactional fun resolveUnresolved(gameId:UUID,participantKey:String){
        jdbc.update("UPDATE attendance_events SET state='UNRESOLVED' WHERE game_id=? AND participant_key=? AND state LIKE 'CLAIMED_%'",gameId,participantKey)
    }
    private fun ensureParticipant(actor:UUID,gameId:UUID){
        val direct=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM game_participants WHERE game_id=? AND participant_user_id=? AND status='ACTIVE')",Boolean::class.java,gameId,actor)?:false
        val teamRep=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM game_participants p JOIN team_memberships m ON m.team_id=p.participant_team_id WHERE p.game_id=? AND m.user_id=? AND m.status='ACTIVE' AND m.role IN ('CAPTAIN','MANAGER') AND p.status='ACTIVE')",Boolean::class.java,gameId,actor)?:false
        if(!direct&&!teamRep)throw ApiException(403,"GAME_PARTICIPANT_REQUIRED","User is not an active game participant or authorized team representative.")
    }
    private fun String.toUuidOrNull()=runCatching{UUID.fromString(this)}.getOrNull()
}
