#!/usr/bin/env python3
"""
SIP call generator: simulates customers calling the contact center.

Each simulated caller is a tiny SIP user agent client (UAC) on its own UDP socket:

    INVITE  ->  100 Trying
            ->  182 Queued (maybe)         waiting for an agent
            ->  180 Ringing + 200 OK       connected (X-Agent-Id tells us who)
    ACK
    ... talks for a random time ...
    BYE     ->  200 OK

    If the router answers 486 Busy Here the caller gives up.
    If a queued caller runs out of patience it sends CANCEL (-> 200 OK, 487 on the INVITE).

No dependencies beyond the standard library.

The user part of the dialed URI picks the router's skill queue: sip:support@... is a general
call, sip:billing@... waits for an agent with the billing skill (see --skills).

Usage:
    python3 callgen.py --calls 8 --talk 3 8 --patience 15
    python3 callgen.py --calls 10 --skills en de billing support
"""

import argparse
import random
import socket
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

print_lock = threading.Lock()


def log(caller: int, msg: str) -> None:
    with print_lock:
        print(f"{time.strftime('%H:%M:%S')}  caller-{caller:02d}  {msg}", flush=True)


@dataclass
class SipResponse:
    status: int
    reason: str
    headers: dict  # lower-case name -> value (first occurrence)

    @property
    def cseq_method(self) -> str:
        return self.headers.get("cseq", "0 ?").split()[-1]

    @classmethod
    def parse(cls, data: bytes) -> "SipResponse":
        text = data.decode(errors="replace")
        head = text.split("\r\n\r\n", 1)[0]
        lines = head.split("\r\n")
        _, code, *reason = lines[0].split(" ", 2)
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                name, value = line.split(":", 1)
                headers.setdefault(name.strip().lower(), value.strip())
        return cls(int(code), reason[0] if reason else "", headers)


@dataclass
class Result:
    caller: int
    dialed: str
    outcome: str  # CONNECTED / REJECTED / ABANDONED / FAILED
    agent: str = "-"
    wait: float = 0.0
    talk: float = 0.0


class Caller:
    def __init__(self, n: int, host: str, port: int, talk_range, patience: float, dialed: str = "support"):
        self.n = n
        self.dialed = dialed
        self.server = (host, port)
        self.talk_range = talk_range
        self.patience = patience

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.connect(self.server)  # picks the right local address + port
        self.local_host, self.local_port = self.sock.getsockname()

        self.call_id = f"{uuid.uuid4().hex[:12]}@callgen"
        self.from_tag = uuid.uuid4().hex[:8]
        self.invite_branch = self.branch()
        self.request_uri = f"sip:{dialed}@{host}:{port}"

    # ------------------------------------------------------------- message building

    @staticmethod
    def branch() -> str:
        return "z9hG4bK" + uuid.uuid4().hex[:16]  # magic cookie required by RFC 3261

    def build(self, method: str, uri: str, branch: str, cseq: int, to_tag: str | None = None) -> bytes:
        to = f"<sip:{self.dialed}@{self.server[0]}>" + (f";tag={to_tag}" if to_tag else "")
        lines = [
            f"{method} {uri} SIP/2.0",
            f"Via: SIP/2.0/UDP {self.local_host}:{self.local_port};branch={branch};rport",
            "Max-Forwards: 70",
            f'From: "Caller {self.n}" <sip:caller{self.n}@{self.local_host}>;tag={self.from_tag}',
            f"To: {to}",
            f"Call-ID: {self.call_id}",
            f"CSeq: {cseq} {method}",
            f"Contact: <sip:caller{self.n}@{self.local_host}:{self.local_port}>",
            "User-Agent: callgen.py",
            "Content-Length: 0",
            "",
            "",
        ]
        return "\r\n".join(lines).encode()

    def send(self, msg: bytes) -> None:
        self.sock.send(msg)

    def recv(self, timeout: float) -> SipResponse | None:
        self.sock.settimeout(max(timeout, 0.01))
        try:
            return SipResponse.parse(self.sock.recv(65535))
        except socket.timeout:
            return None

    @staticmethod
    def to_tag(resp: SipResponse) -> str | None:
        to = resp.headers.get("to", "")
        return to.split("tag=", 1)[1].split(";")[0] if "tag=" in to else None

    def ack_non_2xx(self, resp: SipResponse) -> None:
        # ACK for a failure response reuses the INVITE branch (same transaction)
        self.send(self.build("ACK", self.request_uri, self.invite_branch, 1, self.to_tag(resp)))

    # ------------------------------------------------------------- call flow

    def run(self) -> Result:
        try:
            return self._run()
        except Exception as e:  # keep one broken call from killing the run
            log(self.n, f"error: {e}")
            return Result(self.n, self.dialed, "FAILED")
        finally:
            self.sock.close()

    def _run(self) -> Result:
        start = time.monotonic()
        self.send(self.build("INVITE", self.request_uri, self.invite_branch, 1))
        log(self.n, f"-> INVITE {self.dialed}")

        cancelled = False
        while True:
            remaining = self.patience - (time.monotonic() - start)
            if remaining <= 0 and not cancelled:
                self.send(self.build("CANCEL", self.request_uri, self.invite_branch, 1))
                log(self.n, f"-> CANCEL (gave up after {self.patience:.0f}s in queue)")
                cancelled = True

            resp = self.recv(remaining if not cancelled else 5)
            if resp is None:
                if cancelled:
                    log(self.n, "no answer to CANCEL")
                    return Result(self.n, self.dialed, "FAILED")
                continue

            if resp.cseq_method == "CANCEL":
                continue  # 200 OK for the CANCEL itself; the 487 follows

            if resp.status == 100:
                continue
            if resp.status == 182:
                log(self.n, f"<- 182 Queued (position {resp.headers.get('x-queue-position', '?')})")
                continue
            if resp.status == 180:
                log(self.n, "<- 180 Ringing")
                continue
            if 200 <= resp.status < 300:
                return self.talk(resp, time.monotonic() - start)

            # final failure: 486 Busy Here, 487 Request Terminated, ...
            self.ack_non_2xx(resp)
            log(self.n, f"<- {resp.status} {resp.reason}")
            outcome = "ABANDONED" if resp.status == 487 else "REJECTED"
            return Result(self.n, self.dialed, outcome, wait=time.monotonic() - start)

    def talk(self, ok: SipResponse, waited: float) -> Result:
        agent = ok.headers.get("x-agent-id", "?")
        to_tag = self.to_tag(ok)
        contact = ok.headers.get("contact", "").strip("<>").split(">")[0] or self.request_uri

        # ACK for 2xx is its own transaction: new branch, sent to the Contact
        self.send(self.build("ACK", contact, self.branch(), 1, to_tag))
        log(self.n, f"<- 200 OK  connected to agent '{agent}' after {waited:.1f}s  -> ACK")

        talk = random.uniform(*self.talk_range)
        time.sleep(talk)

        self.send(self.build("BYE", contact, self.branch(), 2, to_tag))
        log(self.n, f"-> BYE after {talk:.1f}s of talking")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            resp = self.recv(deadline - time.monotonic())
            if resp and resp.cseq_method == "BYE":
                log(self.n, f"<- {resp.status} {resp.reason} (BYE)")
                break
        return Result(self.n, self.dialed, "CONNECTED", agent, waited, talk)


def main() -> None:
    parser = argparse.ArgumentParser(description="Simulate callers hitting the SIP call router")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5060)
    parser.add_argument("--calls", type=int, default=8, help="number of callers")
    parser.add_argument("--interval", type=float, default=0.5, help="seconds between new callers")
    parser.add_argument("--talk", type=float, nargs=2, default=[3, 8], metavar=("MIN", "MAX"),
                        help="talk time range in seconds")
    parser.add_argument("--patience", type=float, default=20, help="seconds a caller waits in queue")
    parser.add_argument("--skills", nargs="+", default=["support"], metavar="USER",
                        help="users to dial, one picked at random per caller (e.g. en de billing support)")
    parser.add_argument("--seed", type=int, help="random seed, for a repeatable run")
    args = parser.parse_args()
    random.seed(args.seed)

    print(f"Calling {', '.join(args.skills)} at {args.host}:{args.port} with {args.calls} callers\n")
    with ThreadPoolExecutor(max_workers=args.calls) as pool:
        futures = []
        for n in range(1, args.calls + 1):
            caller = Caller(n, args.host, args.port, args.talk, args.patience, random.choice(args.skills))
            futures.append(pool.submit(caller.run))
            time.sleep(args.interval)
        results = [f.result() for f in futures]

    print("\nSummary")
    print(f"{'caller':<11}{'dialed':<10}{'outcome':<12}{'agent':<10}{'wait s':>8}{'talk s':>8}")
    for r in results:
        print(f"caller-{r.caller:02d}  {r.dialed:<10}{r.outcome:<12}{r.agent:<10}{r.wait:>8.1f}{r.talk:>8.1f}")
    for outcome in ("CONNECTED", "REJECTED", "ABANDONED", "FAILED"):
        count = sum(r.outcome == outcome for r in results)
        if count:
            print(f"  {outcome.lower()}: {count}")


if __name__ == "__main__":
    main()
