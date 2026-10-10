package com.opponify.service

import com.opponify.api.ApiException
import com.opponify.persistence.GameRepository
import com.opponify.persistence.OpportunityRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Service
class OpportunityTimeService(private val jdbc:JdbcTemplate,private val opportunities:OpportunityRepository,private val games:GameRepository,@Value("\${opponify.scheduling.time-proposal-expiry}") private val expiry:Duration,@Value("\${opponify.scheduling.default-game-duration}") private val duration:Duration){
    @Transactional fun propose(actor:UUID,opportunityId:UUID,start:Instant):UUID{
        val o=opportunities.find(opportunityId)?:throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found.")
        if(o.status.name!="OPEN")throw ApiException(409,"OPPORTUNITY_NOT_OPEN","Opportunity must be open for time coordination.")
        if(o.timeType.name=="EXACT")throw ApiException(409,"EXACT_TIME_ALREADY_DEFINED","Exact-time opportunities do not need a time proposal.")
        if(actor!=o.creatorUserId && (o.creatorTeamId?.let{canManageTeam(actor,it)}!=true))throw ApiException(403,"CREATOR_AUTHORITY_REQUIRED","Creator authority required to propose exact time.")
        if(start.isBefore(Instant.now()))throw ApiException(422,"TIME_IN_PAST","Proposed time must be future-dated.")
        jdbc.update("UPDATE opportunity_time_proposals SET status='SUPERSEDED' WHERE opportunity_id=? AND status='PROPOSED'",opportunityId)
        val id=UUID.randomUUID();jdbc.update("INSERT INTO opportunity_time_proposals(id,opportunity_id,proposed_start_at,proposer_user_id,expires_at) VALUES(?,?,?,?,?)",id,opportunityId,start,actor,Instant.now().plus(expiry));return id
    }
    @Transactional fun confirm(actor:UUID,proposalId:UUID):UUID?{
        val row=jdbc.queryForList("SELECT * FROM opportunity_time_proposals WHERE id=? AND status='PROPOSED'",proposalId).firstOrNull()?:throw ApiException(404,"PROPOSAL_NOT_FOUND","Active time proposal not found.")
        val o=opportunities.find(row["opportunity_id"] as UUID)?:throw ApiException(404,"OPPORTUNITY_NOT_FOUND","Opportunity not found.")
        if((row["expires_at"] as java.sql.Timestamp).toInstant()<=Instant.now()){jdbc.update("UPDATE opportunity_time_proposals SET status='EXPIRED' WHERE id=?",proposalId);throw ApiException(409,"PROPOSAL_EXPIRED","Time proposal expired.")}
        if(!isAffectedParticipant(actor,o))throw ApiException(403,"AFFECTED_PARTICIPANT_REQUIRED","Only the creator or accepted participant may confirm.")
        jdbc.update("INSERT INTO opportunity_time_confirmations(proposal_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",proposalId,actor)
        val required=requiredConfirmers(o).size
        val confirmed=jdbc.queryForObject("SELECT COUNT(*) FROM opportunity_time_confirmations WHERE proposal_id=?",Int::class.java,proposalId)?:0
        if(confirmed<required)return null
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM scheduled_games WHERE opportunity_id=?)",Boolean::class.java,o.id)==true)throw ApiException(409,"ALREADY_SCHEDULED","Opportunity is already scheduled.")
        val start=(row["proposed_start_at"] as java.sql.Timestamp).toInstant();val end=start.plus(duration)
        val users=requiredConfirmers(o)
        if(users.any{games.hasOverlap(it,start,end)})throw ApiException(409,"SCHEDULE_OVERLAP","A participant has an overlapping scheduled commitment.")
        val gameId=UUID.randomUUID();games.create(gameId,o.id,start,duration,ZoneId.of("UTC"))
        if(o.creatorUserId!=null)games.addParticipant(gameId,o.creatorUserId,null) else games.addParticipant(gameId,null,o.creatorTeamId)
        jdbc.queryForList("SELECT requester_user_id,requester_team_id FROM participation_requests WHERE opportunity_id=? AND status='ACCEPTED'",o.id).forEach{if(it["requester_user_id"]!=null)games.addParticipant(gameId,it["requester_user_id"] as UUID,null)else games.addParticipant(gameId,null,it["requester_team_id"] as UUID)}
        jdbc.update("UPDATE opportunity_time_proposals SET status='CONFIRMED',confirmed_at=NOW() WHERE id=?",proposalId)
        return gameId
    }
    private fun requiredConfirmers(o:com.opponify.opportunity.domain.Opportunity):Set<UUID>{
        val users=mutableSetOf<UUID>();val creator=o.creatorUserId;if(creator!=null)users+=creator else users+=teamReps(o.creatorTeamId!!)
        jdbc.queryForList("SELECT requester_user_id,requester_team_id FROM participation_requests WHERE opportunity_id=? AND status='ACCEPTED'",o.id).forEach{if(it["requester_user_id"]!=null)users+=it["requester_user_id"] as UUID else users+=teamReps(it["requester_team_id"] as UUID)}
        return users
    }
    private fun teamReps(teamId:UUID)=jdbc.queryForList("SELECT user_id FROM team_memberships WHERE team_id=? AND status='ACTIVE' AND role IN ('CAPTAIN','MANAGER') ORDER BY CASE role WHEN 'CAPTAIN' THEN 0 ELSE 1 END,user_id LIMIT 1",teamId).map{it["user_id"] as UUID}
    private fun isAffectedParticipant(actor:UUID,o:com.opponify.opportunity.domain.Opportunity)=requiredConfirmers(o).contains(actor)
    private fun canManageTeam(actor:UUID,teamId:UUID)=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM team_memberships WHERE team_id=? AND user_id=? AND status='ACTIVE' AND role IN ('CAPTAIN','MANAGER'))",Boolean::class.java,teamId,actor)?:false
}
