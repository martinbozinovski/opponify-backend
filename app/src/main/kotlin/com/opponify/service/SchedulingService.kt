package com.opponify.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.opponify.api.ApiException
import com.opponify.persistence.GameRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Service
class SchedulingService(
    private val jdbc: JdbcTemplate,
    private val games: GameRepository,
    private val mapper: ObjectMapper,
    @Value("\${opponify.scheduling.time-proposal-expiry}") private val proposalExpiry: Duration,
    @Value("\${opponify.scheduling.material-change-expiry}") private val changeExpiry: Duration
) {
    @Transactional
    fun proposeTime(actor:UUID,gameId:UUID,start:Instant):UUID {
        val game=games.find(gameId) ?: throw ApiException(404,"GAME_NOT_FOUND","Game not found.")
        val lifecycle=jdbc.queryForObject("SELECT lifecycle FROM scheduled_games WHERE id=?",String::class.java,gameId)
        if(lifecycle !in setOf("SCHEDULED","GAME_TIME")) throw ApiException(409,"GAME_NOT_RESCHEDULABLE","Only scheduled/game-time games may be rescheduled.")
        ensureParticipantOrCreator(actor,gameId)
        if(start.isBefore(Instant.now())) throw ApiException(422,"TIME_IN_PAST","Proposed time must be future-dated.")
        val id=UUID.randomUUID()
        jdbc.update("UPDATE time_proposals SET status='SUPERSEDED',superseded_at=NOW() WHERE game_id=? AND status='PROPOSED'",gameId)
        jdbc.update("INSERT INTO time_proposals(id,game_id,proposed_start_at,proposer_user_id,expires_at,status) VALUES(?,?,?,?,?,'PROPOSED')",id,gameId,start,actor,Instant.now().plus(proposalExpiry))
        return id
    }

    @Transactional
    fun confirmTime(actor:UUID,proposalId:UUID) {
        val row=jdbc.queryForList("SELECT * FROM time_proposals WHERE id=? AND status='PROPOSED'",proposalId).firstOrNull() ?: throw ApiException(404,"PROPOSAL_NOT_FOUND","Active proposal not found.")
        if((row["expires_at"] as java.sql.Timestamp).toInstant()<=Instant.now()) { jdbc.update("UPDATE time_proposals SET status='EXPIRED' WHERE id=?",proposalId); throw ApiException(409,"PROPOSAL_EXPIRED","Time proposal expired.") }
        ensureParticipantOrCreator(actor,row["game_id"] as UUID)
        jdbc.update("INSERT INTO time_proposal_confirmations(proposal_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",proposalId,actor)
        val gameId=row["game_id"] as UUID
        val required=requiredConfirmers(gameId).size
        val confirmed=jdbc.queryForObject("SELECT COUNT(*) FROM time_proposal_confirmations WHERE proposal_id=?",Int::class.java,proposalId) ?: 0
        if(required>0 && confirmed>=required) {
            val start=(row["proposed_start_at"] as java.sql.Timestamp).toInstant()
            val duration=games.find(gameId)?.duration ?: Duration.ofMinutes(90)
            val end=start.plus(duration)
            val participants=jdbc.queryForList("SELECT participant_user_id FROM game_participants WHERE game_id=? AND status='ACTIVE' AND participant_user_id IS NOT NULL",gameId)
            if(participants.any{games.hasOverlap(it["participant_user_id"] as UUID,start,end,gameId)}) throw ApiException(409,"SCHEDULE_OVERLAP","A participant has an overlapping scheduled commitment.")
            jdbc.update("UPDATE scheduled_games SET start_at=?,updated_at=NOW() WHERE id=?",start,gameId)
            jdbc.update("UPDATE time_proposals SET status='CONFIRMED' WHERE id=?",proposalId)
        }
    }

    @Transactional
    fun proposeMaterialChange(actor:UUID,gameId:UUID,changeType:String,previous:Any,proposed:Any):UUID {
        val lifecycle=jdbc.queryForObject("SELECT lifecycle FROM scheduled_games WHERE id=?",String::class.java,gameId)
        if(lifecycle !in setOf("SCHEDULED","GAME_TIME")) throw ApiException(409,"GAME_NOT_CHANGEABLE","Game is not changeable in its current lifecycle.")
        ensureParticipantOrCreator(actor,gameId)
        val id=UUID.randomUUID()
        jdbc.update("UPDATE game_changes SET status='SUPERSEDED' WHERE game_id=? AND status='PROPOSED'",gameId)
        jdbc.update("INSERT INTO game_changes(id,game_id,change_type,previous_value,proposed_value,proposer_user_id,expires_at,status) VALUES(?,?,?,?::jsonb,?::jsonb,?,?, 'PROPOSED')",id,gameId,changeType,mapper.writeValueAsString(previous),mapper.writeValueAsString(proposed),actor,Instant.now().plus(changeExpiry))
        return id
    }

    @Transactional
    fun confirmMaterialChange(actor:UUID,changeId:UUID) {
        val row=jdbc.queryForList("SELECT * FROM game_changes WHERE id=? AND status='PROPOSED'",changeId).firstOrNull() ?: throw ApiException(404,"CHANGE_NOT_FOUND","Active change not found.")
        val gameId=row["game_id"] as UUID
        ensureParticipantOrCreator(actor,gameId)
        if((row["expires_at"] as java.sql.Timestamp).toInstant()<=Instant.now()) { jdbc.update("UPDATE game_changes SET status='EXPIRED' WHERE id=?",changeId); throw ApiException(409,"CHANGE_EXPIRED","Change proposal expired.") }
        jdbc.update("INSERT INTO game_change_confirmations(change_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",changeId,actor)
        val expected=requiredConfirmers(gameId).size
        val confirmed=jdbc.queryForObject("SELECT COUNT(*) FROM game_change_confirmations WHERE change_id=?",Int::class.java,changeId) ?: 0
        if(expected==0 || confirmed<expected) return
        val type=row["change_type"] as String
        val proposed=mapper.readTree(row["proposed_value"].toString())
        when(type) {
            "START_TIME" -> {
                val start=Instant.parse(proposed.asText())
                val duration=games.find(gameId)?.duration ?: Duration.ofMinutes(90)
                val end=start.plus(duration)
                val participants=jdbc.queryForList("SELECT participant_user_id FROM game_participants WHERE game_id=? AND status='ACTIVE' AND participant_user_id IS NOT NULL",gameId)
                if(participants.any{games.hasOverlap(it["participant_user_id"] as UUID,start,end,gameId)}) throw ApiException(409,"SCHEDULE_OVERLAP","A participant has an overlapping scheduled commitment.")
                jdbc.update("UPDATE scheduled_games SET start_at=?,updated_at=NOW() WHERE id=?",start,gameId)
                games.rebuildIntervals(gameId,start,end)
            }
            "FACILITY" -> {
                val facilityId=if(proposed.isNull) null else UUID.fromString(proposed.asText())
                jdbc.update("UPDATE opportunities SET facility_id=?,updated_at=NOW() WHERE id=(SELECT opportunity_id FROM scheduled_games WHERE id=?)",facilityId,gameId)
            }
            "MINIMUM_PARTICIPATION" -> {
                val minimum=proposed.asInt()
                val target=jdbc.queryForObject("SELECT o.target_capacity FROM opportunities o JOIN scheduled_games g ON g.opportunity_id=o.id WHERE g.id=?",Int::class.java,gameId) ?: throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found.")
                val actual=games.activeCount(gameId)
                if(minimum<=0 || minimum>target || minimum>actual) throw ApiException(422,"INVALID_MINIMUM","Minimum participation must be within capacity and cannot exceed current participation.")
                jdbc.update("UPDATE opportunities SET minimum_participation=?,updated_at=NOW() WHERE id=(SELECT opportunity_id FROM scheduled_games WHERE id=?)",minimum,gameId)
            }
            "LOCATION" -> {
                val facility=if (proposed.get("facilityId") == null || proposed.get("facilityId").isNull) null else UUID.fromString(proposed.get("facilityId").asText())
                jdbc.update("UPDATE opportunities SET facility_id=?,location_type=?,free_form_location=?,updated_at=NOW() WHERE id=(SELECT opportunity_id FROM scheduled_games WHERE id=?)",facility,proposed.get("locationType")?.asText() ?: "TOWN",proposed.get("freeFormLocation")?.asText(),gameId)
            }
            "PARTICIPANT_ADD" -> {
                val type=proposed.get("participantType")?.asText() ?: throw ApiException(422,"INVALID_PARTICIPANT_CHANGE","participantType required.")
                val id=UUID.fromString(proposed.get("participantId")?.asText() ?: throw ApiException(422,"INVALID_PARTICIPANT_CHANGE","participantId required."))
                val target=jdbc.queryForObject("SELECT target_capacity FROM opportunities o JOIN scheduled_games g ON g.opportunity_id=o.id WHERE g.id=?",Int::class.java,gameId)!!
                if(games.activeCount(gameId)>=target)throw ApiException(409,"CAPACITY_FULL","Target capacity reached.")
                if(type=="USER") games.addParticipant(gameId,id,null) else if(type=="TEAM") games.addParticipant(gameId,null,id) else throw ApiException(422,"INVALID_PARTICIPANT_TYPE","Unsupported participant type.")
            }
            "PARTICIPANT_REMOVE" -> {
                val type=proposed.get("participantType")?.asText() ?: throw ApiException(422,"INVALID_PARTICIPANT_CHANGE","participantType required.")
                val id=UUID.fromString(proposed.get("participantId")?.asText() ?: throw ApiException(422,"INVALID_PARTICIPANT_CHANGE","participantId required."))
                if(type=="USER") { jdbc.update("UPDATE game_participants SET status='CANCELLED',exited_at=NOW() WHERE game_id=? AND participant_user_id=? AND status='ACTIVE'",gameId,id); jdbc.update("DELETE FROM scheduled_commitment_intervals WHERE game_id=? AND commitment_key=?",gameId,"U:$id") }
                else if(type=="TEAM") { jdbc.update("UPDATE game_participants SET status='CANCELLED',exited_at=NOW() WHERE game_id=? AND participant_team_id=? AND status='ACTIVE'",gameId,id); jdbc.update("DELETE FROM scheduled_commitment_intervals WHERE game_id=? AND commitment_key=?",gameId,"T:$id") }
                else throw ApiException(422,"INVALID_PARTICIPANT_TYPE","Unsupported participant type.")
            }
            else -> throw ApiException(422,"UNSUPPORTED_MATERIAL_CHANGE","Unsupported material change type.")
        }
        jdbc.update("UPDATE game_changes SET status='CONFIRMED' WHERE id=?",changeId)
    }

    private fun requiredConfirmers(gameId:UUID):Set<UUID> {
        val direct=jdbc.queryForList("SELECT participant_user_id FROM game_participants WHERE game_id=? AND status='ACTIVE' AND participant_user_id IS NOT NULL",gameId).mapNotNull{it["participant_user_id"] as UUID?}
        val teamIds=jdbc.queryForList("SELECT DISTINCT participant_team_id FROM game_participants WHERE game_id=? AND participant_team_id IS NOT NULL AND status='ACTIVE'",gameId).mapNotNull{it["participant_team_id"] as UUID?}
        val teamReps=teamIds.mapNotNull{team -> jdbc.queryForList("SELECT user_id FROM team_memberships WHERE team_id=? AND status='ACTIVE' AND role IN ('CAPTAIN','MANAGER') ORDER BY CASE role WHEN 'CAPTAIN' THEN 0 ELSE 1 END,user_id LIMIT 1",team).firstOrNull()?.get("user_id") as UUID?}
        return (direct+teamReps).toSet()
    }

    private fun ensureParticipantOrCreator(actor:UUID,gameId:UUID){
        val allowed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM scheduled_games g JOIN opportunities o ON o.id=g.opportunity_id WHERE g.id=? AND (o.creator_user_id=? OR EXISTS(SELECT 1 FROM game_participants p WHERE p.game_id=g.id AND p.participant_user_id=? AND p.status='ACTIVE') OR EXISTS(SELECT 1 FROM game_participants p JOIN team_memberships m ON m.team_id=p.participant_team_id WHERE p.game_id=g.id AND p.status='ACTIVE' AND m.user_id=? AND m.status='ACTIVE' AND m.role IN ('CAPTAIN','MANAGER'))))",Boolean::class.java,gameId,actor,actor,actor) ?: false
        if(!allowed) throw ApiException(403,"GAME_PARTICIPANT_REQUIRED","User is not authorized for this game.")
    }
}
