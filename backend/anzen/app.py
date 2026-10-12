"""HTTP surface of the ANZEN service. Routes only translate HTTP <-> service calls."""

import logging
import os
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from . import store
from .chain import Chain
from .config import Settings
from .errors import STATUS, AnzenError
from .messages import EventConfig
from .service import Anzen

HEX32 = r"^0x[0-9a-fA-F]{64}$"


class Signed(BaseModel):
    """A P-256 account key and its authorization of the action's preimage (see messages.py): 64 bytes r ‖ s
    (Android Keystore), or an abi-encoded WebAuthn assertion whose challenge is sha256(preimage) (passkeys)."""
    qx: str = Field(pattern=HEX32)
    qy: str = Field(pattern=HEX32)
    auth: str = Field(pattern=r"^0x[0-9a-fA-F]{128,8192}$")


class WithDeadline(Signed):
    deadline: int = Field(gt=0)


class Config(BaseModel):
    organizer: str = Field(pattern=HEX32)
    policyHash: str = Field(pattern=HEX32)
    issuer: str = Field(pattern=r"^0x[0-9a-fA-F]{40}$")
    amount: int = Field(gt=0, lt=2**128)
    capacity: int = Field(gt=0, lt=2**32)
    commitDeadline: int = Field(gt=0)
    checkInStart: int = Field(gt=0)
    checkInEnd: int = Field(gt=0)
    noShow: int = Field(ge=0, le=1)


class Terms(BaseModel):
    category: int = Field(ge=0, le=31)
    lateFeeBps: int = Field(ge=0, le=10_000)


class CreateEvent(WithDeadline):
    policy: str = Field(max_length=10_000, description="the exact document whose sha256 is policyHash")
    config: Config
    terms: Terms | None = Field(None, description="present: signed as create/v2 (createEventWithTerms)")


class Key(BaseModel):
    qx: str = Field(pattern=HEX32)
    qy: str = Field(pattern=HEX32)
    auth: str = Field(pattern=r"^0x[0-9a-fA-F]{128,8192}$")


class StaffCheckIn(BaseModel):
    guest: Key
    issuedAt: int = Field(gt=0, lt=2**64)
    staff: Key


class Rules(BaseModel):
    categories: int = Field(ge=0, lt=2**32)
    maxLateFeeBps: int = Field(ge=0, lt=2**16)
    maxActive: int = Field(ge=0, lt=2**8)
    maxLeadDays: int = Field(ge=0, lt=2**16)


class CheckIn(Signed):
    challenge: str = Field(pattern=HEX32)
    expiresAt: int
    issuerSig: str = Field(pattern=r"^0x[0-9a-fA-F]{128}([0-9a-fA-F]{2})?$")  # ed25519 (Solana) or EIP-191


class Delegate(WithDeadline):
    agent: str = Field(pattern=r"^0x([0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")  # EVM address or Solana ed25519 key
    agentId: int = Field(ge=0, lt=2**256, description="ERC-8004 agentId, or 2**256-1 for none")
    cap: int = Field(ge=0, lt=2**128)
    expiry: int = Field(ge=0, lt=2**64)


class Pass(Delegate):
    rules: Rules


class AgentCommit(BaseModel):
    keyId: str = Field(pattern=HEX32)
    deadline: int = Field(gt=0)
    agentSig: str = Field(pattern=r"^0x[0-9a-fA-F]{128}([0-9a-fA-F]{2})?$")


class KeyBody(BaseModel):
    keyId: str = Field(pattern=HEX32)


def build_service(s: Settings) -> Anzen:
    if s.program_id:
        from .solana_chain import SolanaChain
        chain = SolanaChain(s.rpc_url, s.program_id, s.token, s.chain_id, s.relayer_key, s.issuer_key)
    else:
        chain = Chain(s.rpc_url, s.treasury, s.token, s.relayer_key, s.issuer_key)
    return Anzen(store.connect(s.db_path), chain, faucet_amount=s.faucet_amount, public_rpc=s.public_rpc)


def create_app(svc: Anzen | None = None) -> FastAPI:
    svc = svc or build_service(Settings.from_env())
    app = FastAPI(title="ANZEN service", version="0.3.0")
    # The web app runs on another origin. No cookies or credentials exist: every write is a signed action.
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["GET", "POST"], allow_headers=["Content-Type", "X-PAYMENT"],
                       expose_headers=["X-PAYMENT-RESPONSE"])

    @app.exception_handler(AnzenError)
    async def anzen_error(_: Request, e: AnzenError):
        return JSONResponse(status_code=STATUS.get(e.reason, 400), content={"error": e.reason, "detail": e.detail})

    @app.exception_handler(Exception)
    async def unexpected(_: Request, e: Exception):
        logging.getLogger("anzen").exception("unhandled")
        return JSONResponse(status_code=500, content={"error": "ServerError", "detail": f"{type(e).__name__}: {str(e)[:200]}"})

    @app.get("/health")
    def health():
        return svc.health()

    @app.get("/events")
    def events(q: str = "", key: str | None = None):
        return svc.events(q, key)

    @app.get("/events/{event_id}")
    def event(event_id: int, key: str | None = None):
        return svc.event(event_id, key)

    @app.post("/events")
    def create(b: CreateEvent):
        c = b.config
        cfg = EventConfig(bytes.fromhex(c.organizer[2:]), bytes.fromhex(c.policyHash[2:]), c.issuer, c.amount,
                          c.capacity, c.commitDeadline, c.checkInStart, c.checkInEnd, c.noShow)
        terms = (b.terms.category, b.terms.lateFeeBps) if b.terms else None
        return svc.create_event(b.policy, cfg, b.qx, b.qy, b.deadline, b.auth, terms)

    @app.post("/events/{event_id}/release")
    def release(event_id: int, b: WithDeadline):
        return svc.release(event_id, b.qx, b.qy, b.deadline, b.auth)

    @app.post("/events/{event_id}/staff-checkin")
    def staff_check_in(event_id: int, b: StaffCheckIn):
        return svc.staff_check_in(event_id, b.guest.qx, b.guest.qy, b.issuedAt, b.guest.auth, b.staff.qx, b.staff.qy,
                                  b.staff.auth)

    @app.post("/pass")
    def grant_pass(b: Pass):
        r = b.rules
        return svc.grant_pass(b.agent, b.agentId, b.cap, b.expiry, (r.categories, r.maxLateFeeBps, r.maxActive, r.maxLeadDays),
                              b.qx, b.qy, b.deadline, b.auth)

    @app.get("/organizers/{key_id}/dashboard")
    def dashboard(key_id: str):
        return svc.dashboard(key_id)

    @app.api_route("/x402/events/{event_id}/reserve", methods=["GET", "POST"])
    def x402_reserve(event_id: int, request: Request):
        """x402: without X-PAYMENT, 402 + what to sign; with it, the reservation is relayed and confirmed."""
        header = request.headers.get("x-payment")
        if not header:
            return JSONResponse(status_code=402, content=svc.x402_requirements(event_id, str(request.url)))
        body, receipt = svc.x402_pay(event_id, header)
        return JSONResponse(status_code=200 if body["ok"] else 409, content=body, headers={"X-PAYMENT-RESPONSE": receipt})

    @app.post("/events/{event_id}/commit")
    def commit(event_id: int, b: WithDeadline):
        return svc.commit(event_id, b.qx, b.qy, b.deadline, b.auth)

    @app.post("/events/{event_id}/cancel")
    def cancel(event_id: int, b: WithDeadline):
        return svc.cancel(event_id, b.qx, b.qy, b.deadline, b.auth)

    @app.post("/events/{event_id}/desk")
    def desk(event_id: int, b: WithDeadline):
        return svc.open_desk(event_id, b.qx, b.qy, b.deadline, b.auth)

    @app.get("/events/{event_id}/challenge")
    def challenge(event_id: int, token: str):
        return svc.challenge(event_id, token)

    @app.post("/events/{event_id}/checkin")
    def check_in(event_id: int, b: CheckIn):
        return svc.check_in(event_id, b.qx, b.qy, b.challenge, b.expiresAt, b.issuerSig, b.auth)

    @app.post("/delegate")
    def delegate(b: Delegate):
        return svc.delegate(b.agent, b.agentId, b.cap, b.expiry, b.qx, b.qy, b.deadline, b.auth)

    @app.get("/agents")
    def agents():
        return svc.agents()

    @app.get("/agents/{agent}")
    def agent(agent: str, agentId: int | None = None):
        return svc.agent_info(agent, agentId)

    @app.get("/agents/{agent}/card")
    def agent_card(agent: str, request: Request):
        return svc.agent_card(agent, str(request.base_url).rstrip("/"))

    @app.post("/agents/{agent}/gas")
    def agent_gas(agent: str):
        return svc.agent_gas(agent)

    @app.post("/events/{event_id}/agent-commit")
    def agent_commit(event_id: int, b: AgentCommit):
        return svc.commit_by_agent(event_id, b.keyId, b.deadline, b.agentSig)

    @app.post("/events/{event_id}/settle")
    def settle(event_id: int, b: KeyBody):
        return svc.settle(event_id, b.keyId)

    @app.get("/proof/{event_id}/{key_id}")
    def proof(event_id: int, key_id: str):
        return svc.proof(event_id, key_id)

    @app.get("/accounts/{key_id}")
    def account(key_id: str):
        return svc.account(key_id)

    @app.get("/accounts/{key_id}/activity")
    def activity(key_id: str):
        return svc.activity(key_id)

    @app.post("/faucet")
    def faucet(b: KeyBody):
        return svc.faucet(b.keyId)

    @app.get("/tx/{tx}")
    def tx(tx: str):
        return svc.tx(tx)

    # The web app (web/) on the same origin: passkeys are bound to this domain, and no CORS is involved.
    web = Path(__file__).resolve().parents[2] / "web"
    if web.is_dir():
        app.mount("/", StaticFiles(directory=web, html=True), name="web")
    return app


def main() -> None:
    import uvicorn
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    uvicorn.run(create_app(), host=os.environ.get("ANZEN_HOST", "0.0.0.0"), port=int(os.environ.get("PORT") or os.environ.get("ANZEN_PORT", "8790")))


if __name__ == "__main__":
    main()
