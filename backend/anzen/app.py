"""HTTP surface of the ANZEN service. Routes only translate HTTP <-> service calls."""

import logging
import os

from fastapi import FastAPI, Request
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
    """A P-256 key and its low-s signature (r, s) over the action's preimage (see messages.py)."""
    qx: str = Field(pattern=HEX32)
    qy: str = Field(pattern=HEX32)
    r: str = Field(pattern=HEX32)
    s: str = Field(pattern=HEX32)


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


class CreateEvent(WithDeadline):
    policy: str = Field(max_length=10_000, description="the exact document whose sha256 is policyHash")
    config: Config


class CheckIn(Signed):
    challenge: str = Field(pattern=HEX32)
    expiresAt: int
    issuerSig: str = Field(pattern=r"^0x[0-9a-fA-F]{130}$")


class Delegate(WithDeadline):
    agent: str = Field(pattern=r"^0x[0-9a-fA-F]{40}$")
    cap: int = Field(ge=0, lt=2**128)
    expiry: int = Field(ge=0, lt=2**64)


class AgentCommit(BaseModel):
    keyId: str = Field(pattern=HEX32)
    deadline: int = Field(gt=0)
    agentSig: str = Field(pattern=r"^0x[0-9a-fA-F]{130}$")


class KeyBody(BaseModel):
    keyId: str = Field(pattern=HEX32)


def build_service(s: Settings) -> Anzen:
    chain = Chain(s.rpc_url, s.treasury, s.token, s.relayer_key, s.issuer_key)
    return Anzen(store.connect(s.db_path), chain, faucet_amount=s.faucet_amount)


def create_app(svc: Anzen | None = None) -> FastAPI:
    svc = svc or build_service(Settings.from_env())
    app = FastAPI(title="ANZEN service", version="0.2.0")

    @app.exception_handler(AnzenError)
    async def anzen_error(_: Request, e: AnzenError):
        return JSONResponse(status_code=STATUS.get(e.reason, 400), content={"error": e.reason, "detail": e.detail})

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
        return svc.create_event(b.policy, cfg, b.qx, b.qy, b.deadline, b.r, b.s)

    @app.post("/events/{event_id}/commit")
    def commit(event_id: int, b: WithDeadline):
        return svc.commit(event_id, b.qx, b.qy, b.deadline, b.r, b.s)

    @app.post("/events/{event_id}/cancel")
    def cancel(event_id: int, b: WithDeadline):
        return svc.cancel(event_id, b.qx, b.qy, b.deadline, b.r, b.s)

    @app.post("/events/{event_id}/desk")
    def desk(event_id: int, b: WithDeadline):
        return svc.open_desk(event_id, b.qx, b.qy, b.deadline, b.r, b.s)

    @app.get("/events/{event_id}/challenge")
    def challenge(event_id: int, token: str):
        return svc.challenge(event_id, token)

    @app.post("/events/{event_id}/checkin")
    def check_in(event_id: int, b: CheckIn):
        return svc.check_in(event_id, b.qx, b.qy, b.challenge, b.expiresAt, b.issuerSig, b.r, b.s)

    @app.post("/delegate")
    def delegate(b: Delegate):
        return svc.delegate(b.agent, b.cap, b.expiry, b.qx, b.qy, b.deadline, b.r, b.s)

    @app.get("/agents/{agent}")
    def agent(agent: str):
        return svc.agent_info(agent)

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

    return app


def main() -> None:
    import uvicorn
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    uvicorn.run(create_app(), host=os.environ.get("ANZEN_HOST", "0.0.0.0"), port=int(os.environ.get("ANZEN_PORT", "8790")))


if __name__ == "__main__":
    main()
