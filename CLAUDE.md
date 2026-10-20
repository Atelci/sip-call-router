# CLAUDE.md

Interview showcase project for a "AI-assisted Java Development" role at Avaya (contact-center software).
Goal: a small, working, well-explained demo of **Java backend + Redis + SIP**, with Python (and maybe Go)
as extras. Keep it small and demo-able; clarity beats features. Interview is Wednesday 2026-10-21.

## Stack

- Java 21, Spring Boot 3.5, Maven
- JAIN-SIP reference implementation (`javax.sip:jain-sip-ri`, NIST stack), UDP only, signaling only (no RTP)
- Redis via Spring Data Redis (`StringRedisTemplate` everywhere so keys stay readable in `redis-cli`)
- `simulator/callgen.py`: SIP caller simulator, standard library only
- `wallboard/`: Go terminal wallboard (go-redis), subscribes to `call-events`; built in Docker (no Go on this machine)

## Commands

```bash
docker compose up -d redis
mvn spring-boot:run                                   # SIP udp/5060, REST :8080
python3 simulator/callgen.py --calls 10 --talk 3 8   # simulate callers (add --skills en de billing support)
mvn verify                                            # build + tests
docker compose run --rm wallboard                     # live Go wallboard
```

## Architecture

- `sip/SipServer` — SipListener + `CallSignaling` implementation. Maps SIP requests to router calls and
  router decisions to SIP responses. Keeps open INVITE server transactions in an in-memory map.
- `call/CallRouter` — skills-based routing; all state in Redis; methods still `synchronized`. The dialed SIP user
  is the skill (`sip:billing@...`); unknown skill or `support` = general. One queue per skill, drained oldest-head first.
- `agent/AgentService` — agents with skills; a free agent is in ZSET `agents:available` and in `agents:available:{skill}`
  for each skill (score = idle-since). Claiming is `resources/redis/claim-agent.lua` (removes the agent from every pool).
- `sip/SipTrace` — every SIP message in/out goes to Redis list `sip:{callId}` + channel `sip-messages` (UI ladder diagrams).
- `logs/LogTailer` — tails `logs/sip-call-router.log` (`logging.file.name`) every 500 ms; SSE event `log`, `GET /api/logs`.
- `simulator/` — `CallGenerator` + `SimulatedCaller`: Java port of callgen.py on virtual threads, started from the page.
- `api/` — REST: `/api/agents`, `/api/agents/{id}/status`, `/api/queue`, `/api/calls/active`, `/api/calls/{id}/sip`,
  `/api/stats`, `POST /api/simulator/calls`; SSE `/api/events` with event types `call` and `sip` (`EventStreamController`,
  fed by the Redis listener in `RedisConfig`). Browser page: `static/index.html` (generator, queues, SIP diagrams).
- Redis keys are documented in `redis/RedisKeys.java`.

## Current status

- `mvn verify` passes (14 Testcontainers tests for `CallRouter`) and the end-to-end demo with `callgen.py` works against the real app:
  connect, FIFO queue, 486 when full, BYE drains queue, CANCEL -> 487, skills (`--skills en de billing support`).
  Maven wrapper is in the repo.
- On this Windows machine the default `java` is 1.8: set `JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"`.
- Testcontainers is pinned to 1.21.4 in the pom: Boot's 1.21.3 fails against Docker Engine 29 (old API version).
- JAIN-SIP needs the log4j 1.x API: `reload4j` provides it (old `log4j` is excluded in the pom).

## Backlog (in priority order)

1. ~~Get it compiling and running end to end with `callgen.py`. Add the Maven wrapper.~~ Done 2026-10-19.
2. ~~Tests: `CallRouter` with a fake `CallSignaling` against real Redis via Testcontainers
   (connect, queue FIFO order, queue full -> reject, BYE drains queue, CANCEL removes from queue).~~ Done 2026-10-19
   (`CallRouterTest`, plain JUnit, no Spring context; needs Docker running).
3. ~~Make "pop queued call + claim agent" atomic in one Redis Lua script (multi-instance safe).~~ Done 2026-10-19
   (`resources/redis/claim-next-call.lua`, used by `drainQueue`). The new-call path (claim or enqueue) is not atomic yet.
4. ~~Go extra: a small Go service that subscribes to `call-events` and prints a live wallboard
   (calls waiting, agents free, average wait).~~ Done 2026-10-19 (`wallboard/`, compose profile `wallboard`).
5. ~~Optional: SSE endpoint `/api/events` streaming `call-events` to a browser.~~ Done 2026-10-19
   (page at http://localhost:8080/).
6. ~~Verify `docker compose --profile full up --build` works with SIP over UDP.~~ Done 2026-10-19: works as-is
   (callgen sends `rport`); added `.dockerignore`. Re-checked with skills routing 2026-10-20.
7. ~~Skills-based routing (README "Next steps").~~ Done 2026-10-20. Media, AI overflow and an atomic new-call path
   are talking points for the interview, not planned work.
8. ~~UI: call generator on the page, live SIP ladder diagrams per call, queue view.~~ Done 2026-10-20
   (checked in Docker; the ACK for 486/487 is not drawn because JAIN-SIP absorbs it).
9. ~~App log tail on the page.~~ Done 2026-10-20 (`LogTailer`; JAIN-SIP's log4j output is console-only).
10. Public demo on Render: `render.yaml` added 2026-10-20 (free web service + Key Value, Redis via
    `SPRING_DATA_REDIS_URL`). Checked Render-style in local Docker; not yet deployed (user does that in Render).

## Conventions

- Keep classes small and commented for a reader seeing the code for the first time in an interview.
- No new frameworks unless they clearly earn their place.
- Keep README.md short (1-2 minute read); no "Built with AI assistance" or "Next steps" sections.
