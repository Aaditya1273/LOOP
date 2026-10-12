"""ANZEN as an MCP server: any MCP client (Claude Desktop, Claude Code, Cursor, ...) gets the same powers as the
OpenClaw skill, and the same limits. It wraps the agent CLI (anzen/agent.py), so the key, the agent's identity and
the owner's onchain budget are shared with it.

  claude mcp add anzen -e ANZEN_OWNER=0x<account key id> -- uv --directory <repo>/backend run python -m anzen.mcp_server

There is deliberately no check-in, cancel or withdraw tool: the treasury only accepts those from the owner's passkey.
"""

import json
import subprocess
import sys

from mcp.server.mcpserver import MCPServer

server = MCPServer("anzen", instructions=(
    "Reserve real-world event places for your human with refundable commitments on Solana, inside an Agent Pass they "
    "approved with their passkey. You can never check in for them: presence needs their passkey and a 45-second "
    "venue code. Your show-up record rises only when they actually attend, so book things they will go to. If an event "
    "is full you join its waitlist with the money held, and get the seat automatically if someone gives one back."))


def _agent(*args: str) -> dict:
    p = subprocess.run([sys.executable, "-m", "anzen.agent", *args], capture_output=True, text=True, timeout=180)
    try:
        return json.loads(p.stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        return {"ok": False, "error": "AgentFailed", "detail": (p.stderr or p.stdout)[-500:]}


@server.tool()
def setup() -> dict:
    """Create this agent's key (and, on Monad, its ERC-8004 identity) once, and return the link the human opens to approve
    an Agent Pass: a budget plus rules (categories, late-fee ceiling, active bookings, how far ahead)."""
    return _agent("init")


@server.tool()
def events(query: str = "") -> dict:
    """Open events the human can still reserve: id, title, venue, start time, commitment amount (micro-tUSD), seats left."""
    return _agent("events", *query.split())


@server.tool()
def reserve(event_id: int) -> dict:
    """Reserve one event over x402, paid from the human's Agent Pass. They get it back by checking in in person; a full
    event puts them on the waitlist."""
    return _agent("reserve", str(event_id))


@server.tool()
def budget() -> dict:
    """How much of the human's approved budget is left for this agent, and until when."""
    return _agent("budget")


@server.tool()
def reputation() -> dict:
    """This agent's show-up rate, written only by ANZEN's settlement (the program's agent record on Solana, the ERC-8004
    reputation registry on Monad)."""
    return _agent("reputation")


if __name__ == "__main__":
    server.run()
