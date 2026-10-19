// Command wallboard is a live contact-center wallboard for the terminal.
//
// It subscribes to the router's Redis pub/sub channel "call-events". On every event, and once
// a second, it reads the current numbers straight from Redis and redraws the screen. It never
// talks to the Java service: Redis is the only contract between the two.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// Redis keys, as documented in RedisKeys.java.
const (
	eventsChannel = "call-events"
	callQueue     = "queue:calls"
	agentsAll     = "agents:all"
	agentsFree    = "agents:available"
	callsActive   = "calls:active"
	statsKey      = "stats"
)

const recentEvents = 8

// callEvent mirrors the Java CallEvent record that is published as JSON.
type callEvent struct {
	Type      string `json:"type"`
	CallID    string `json:"callId"`
	AgentID   string `json:"agentId"`
	From      string `json:"from"`
	Timestamp int64  `json:"timestamp"`
}

// snapshot is everything one screen shows, read from Redis in one round trip.
type snapshot struct {
	waiting, freeAgents, activeCalls int64
	agents                           map[string]string // agent id -> status
	stats                            map[string]string // counters from the stats hash
}

func main() {
	addr := flag.String("redis", envOr("REDIS_ADDR", "localhost:6379"), "Redis host:port")
	flag.Parse()

	ctx := context.Background()
	rdb := redis.NewClient(&redis.Options{Addr: *addr})
	if err := rdb.Ping(ctx).Err(); err != nil {
		log.Fatalf("cannot reach Redis at %s: %v", *addr, err)
	}

	sub := rdb.Subscribe(ctx, eventsChannel)
	defer sub.Close()
	events := sub.Channel()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	var recent []string
	for {
		select {
		case msg, ok := <-events:
			if !ok {
				return
			}
			recent = append(recent, describe(msg.Payload))
			if len(recent) > recentEvents {
				recent = recent[1:]
			}
		case <-ticker.C:
		}
		s, err := read(ctx, rdb)
		draw(*addr, s, err, recent)
	}
}

// read fetches the counters with one pipeline, then each agent's status with a second one.
func read(ctx context.Context, rdb *redis.Client) (snapshot, error) {
	var (
		waiting, free, active *redis.IntCmd
		stats                 *redis.MapStringStringCmd
		ids                   *redis.StringSliceCmd
	)
	_, err := rdb.Pipelined(ctx, func(p redis.Pipeliner) error {
		waiting = p.LLen(ctx, callQueue)
		free = p.ZCard(ctx, agentsFree)
		active = p.SCard(ctx, callsActive)
		stats = p.HGetAll(ctx, statsKey)
		ids = p.SMembers(ctx, agentsAll)
		return nil
	})
	if err != nil {
		return snapshot{}, err
	}

	agents := map[string]string{}
	statuses := map[string]*redis.StringCmd{}
	_, err = rdb.Pipelined(ctx, func(p redis.Pipeliner) error {
		for _, id := range ids.Val() {
			statuses[id] = p.HGet(ctx, "agent:"+id, "status")
		}
		return nil
	})
	if err != nil && err != redis.Nil {
		return snapshot{}, err
	}
	for id, cmd := range statuses {
		agents[id] = cmd.Val()
	}

	return snapshot{
		waiting:     waiting.Val(),
		freeAgents:  free.Val(),
		activeCalls: active.Val(),
		agents:      agents,
		stats:       stats.Val(),
	}, nil
}

func draw(addr string, s snapshot, err error, recent []string) {
	var b strings.Builder
	b.WriteString("\033[H\033[2J") // ANSI: cursor home + clear screen
	fmt.Fprintf(&b, "CONTACT CENTER WALLBOARD   redis %s   %s\n\n", addr, time.Now().Format("15:04:05"))
	if err != nil {
		fmt.Fprintf(&b, "Redis error: %v\n", err)
		fmt.Print(b.String())
		return
	}

	fmt.Fprintf(&b, "  Calls waiting   %3d\n", s.waiting)
	fmt.Fprintf(&b, "  Calls active    %3d\n", s.activeCalls)
	fmt.Fprintf(&b, "  Agents free     %3d / %d\n", s.freeAgents, len(s.agents))
	fmt.Fprintf(&b, "  Average wait    %s\n\n", averageWait(s.stats))

	b.WriteString("  Agents   ")
	ids := make([]string, 0, len(s.agents))
	for id := range s.agents {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		fmt.Fprintf(&b, "%s:%s  ", id, s.agents[id])
	}
	b.WriteString("\n\n")

	fmt.Fprintf(&b, "  Totals   total %s  connected %s  rejected %s  abandoned %s\n\n",
		stat(s.stats, "total"), stat(s.stats, "connected"), stat(s.stats, "rejected"), stat(s.stats, "abandoned"))

	b.WriteString("  Recent events\n")
	for _, line := range recent {
		b.WriteString("    " + line + "\n")
	}
	fmt.Print(b.String())
}

// averageWait is the mean time a connected call waited for an agent (waitMsTotal / connected).
func averageWait(stats map[string]string) string {
	total, _ := strconv.ParseInt(stats["waitMsTotal"], 10, 64)
	connected, _ := strconv.ParseInt(stats["connected"], 10, 64)
	if connected == 0 {
		return "-"
	}
	return fmt.Sprintf("%.1fs", float64(total)/float64(connected)/1000)
}

func describe(payload string) string {
	var e callEvent
	if err := json.Unmarshal([]byte(payload), &e); err != nil {
		return "unreadable event: " + payload
	}
	at := time.UnixMilli(e.Timestamp).Format("15:04:05")
	line := fmt.Sprintf("%s  %-15s %s", at, e.Type, short(e.CallID))
	if e.AgentID != "" {
		line += "  -> " + e.AgentID
	}
	return line
}

// short trims long SIP Call-IDs so event lines stay on one row.
func short(callID string) string {
	if len(callID) > 12 {
		return callID[:12]
	}
	return callID
}

func stat(stats map[string]string, name string) string {
	if v, ok := stats[name]; ok {
		return v
	}
	return "0"
}

func envOr(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
