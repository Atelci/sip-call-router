# CLAUDE.md

Interview showcase project for a "AI-assisted Java Development" role at Avaya (contact-center software).
Goal: a small, working, well-explained demo of **Java backend + Redis + SIP**, with Python (and maybe Go)
as extras. Keep it small and demo-able; clarity beats features. Interview is Wednesday 2026-10-21.

## Stack

- Java 21, Spring Boot 3.5, Maven
- JAIN-SIP reference implementation (`javax.sip:jain-sip-ri`, NIST stack), UDP only, signaling only (no RTP)
- Redis via Spring Data Redis (`StringRedisTemplate` everywhere so keys stay readable in `redis-cli`)
- `simulator/callgen.py`: SIP caller simulator, standard library only

## Commands

```bash
docker compose up -d redis
mvn spring-boot:run                                   # SIP udp/5060, REST :8080
python3 simulator/callgen.py --calls 10 --talk 3 8   # simulate callers
mvn verify                                            # build + tests
```

## Architecture

- `sip/SipServer` — SipListener + `CallSignaling` implementation. Maps SIP requests to router calls and
  router decisions to SIP responses. Keeps open INVITE server transactions in an in-memory map.
- `call/CallRouter` — routing logic; all state in Redis; `synchronized` (single instance).
- `agent/AgentService` — agents; free agents in ZSET `agents:available` scored by idle-since, `ZPOPMIN` to claim.
- `api/` — REST: `/api/agents`, `/api/agents/{id}/status`, `/api/queue`, `/api/calls/active`, `/api/stats`.
- Redis keys are documented in `redis/RedisKeys.java`.

## Current status

- The Java code was scaffolded **without being compiled** (no Maven access in the scaffolding environment).
  First job: `mvn verify`, fix whatever breaks, then run the end-to-end demo.
- `callgen.py` was tested against a fake SIP server: connect, queue, 486 and CANCEL paths all work.
- JAIN-SIP needs the log4j 1.x API: `reload4j` provides it (old `log4j` is excluded in the pom).

## Backlog (in priority order)

1. Get it compiling and running end to end with `callgen.py`. Add the Maven wrapper (`mvn -N wrapper:wrapper`).
2. Tests: `CallRouter` with a fake `CallSignaling` against real Redis via Testcontainers
   (connect, queue FIFO order, queue full -> reject, BYE drains queue, CANCEL removes from queue).
3. Make "pop queued call + claim agent" atomic in one Redis Lua script (multi-instance safe).
4. Go extra: a small Go service that subscribes to `call-events` and prints a live wallboard
   (calls waiting, agents free, average wait).
5. Optional: SSE endpoint `/api/events` streaming `call-events` to a browser.
6. Verify `docker compose --profile full up --build` works with SIP over UDP.

## Conventions

- Keep classes small and commented for a reader seeing the code for the first time in an interview.
- No new frameworks unless they clearly earn their place.
- Update the "Built with AI assistance" section in README.md with what was done and what needed fixing by hand.
