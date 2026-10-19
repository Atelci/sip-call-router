-- Pairs the oldest queued call with the longest-idle agent, atomically.
-- Redis runs a script as one step, so no other client (or router instance) can see a
-- half-done pairing: either a call and an agent are both taken, or neither is.
--
-- KEYS[1]  queue:calls        LIST  waiting call ids (FIFO)
-- KEYS[2]  agents:available   ZSET  free agents scored by idle-since
-- ARGV[1]  agent key prefix   "agent:" (the agent hash name depends on the popped id)
--
-- Returns {callId, agentId}, or an empty array when the queue or the agent pool is empty.

if redis.call('LLEN', KEYS[1]) == 0 then
    return {}
end

local agent = redis.call('ZPOPMIN', KEYS[2])   -- {member, score} or {}
if #agent == 0 then
    return {}
end

local agentId = agent[1]
local callId = redis.call('LPOP', KEYS[1])
redis.call('HSET', ARGV[1] .. agentId, 'status', 'BUSY', 'currentCallId', callId)
return {callId, agentId}
