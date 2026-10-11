"""The MCP server speaks real MCP over stdio and offers no way to act as the owner in person."""
import os
import sys

import anyio
from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


def test_mcp_tools_cannot_check_in(tmp_path):
    async def main():
        env = {**os.environ, "ANZEN_AGENT_KEYFILE": str(tmp_path / "agent.key")}
        params = StdioServerParameters(command=sys.executable, args=["-m", "anzen.mcp_server"], env=env)
        async with stdio_client(params, errlog=open(os.devnull, "w")) as (r, w), ClientSession(r, w) as s:
            await s.initialize()
            names = {t.name for t in (await s.list_tools()).tools}
            assert names == {"setup", "events", "reserve", "budget", "reputation"}
            res = await s.call_tool("budget", {})  # no agent key yet: a clean refusal, not a crash
            assert '"NoOwner"' in res.content[0].text or '"NoAgentKey"' in res.content[0].text
    anyio.run(main)
