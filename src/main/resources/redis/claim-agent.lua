-- Claims the longest-idle free agent from one pool and marks it busy, as one atomic step.
-- Redis runs a script without interleaving other commands, so no other client (or router
-- instance) can see a half-done claim.
--
-- Two uses:
--   with a queue (KEYS[2]):  serve the queue: only if a call is waiting, and pop that call too
--   without a queue:         a new call, whose id is ARGV[3]
--
-- An agent sits in several pools (all agents + one per skill), so a claimed agent is removed
-- from all of them; otherwise another skill's call could claim the same agent again.
--
-- KEYS[1]  pool    ZSET  agents:available or agents:available:{skill}
-- KEYS[2]  queue   LIST  queue:calls or queue:calls:{skill}            (optional)
-- ARGV[1]  "agent:"             agent hash prefix
-- ARGV[2]  "agents:available"   pool key prefix (skill pools are "<prefix>:<skill>")
-- ARGV[3]  call id              (only without a queue)
--
-- Returns {callId, agentId}, or an empty array when there is no pairing to make.
-- Note: the agent hash and the other pools are built from the popped id, so this script is
-- for a single Redis node (Redis Cluster wants every key passed in KEYS).

local queue = KEYS[2]
if queue and redis.call('LLEN', queue) == 0 then
    return {}
end

local popped = redis.call('ZPOPMIN', KEYS[1])   -- {member, score} or {}
if #popped == 0 then
    return {}
end
local agentId = popped[1]
local callId = queue and redis.call('LPOP', queue) or ARGV[3]
local agentKey = ARGV[1] .. agentId

redis.call('ZREM', ARGV[2], agentId)
local skills = redis.call('HGET', agentKey, 'skills')   -- "en,billing", or false if none
if skills then
    for skill in string.gmatch(skills, '[^,]+') do
        redis.call('ZREM', ARGV[2] .. ':' .. skill, agentId)
    end
end

redis.call('HSET', agentKey, 'status', 'BUSY', 'currentCallId', callId)
return {callId, agentId}
