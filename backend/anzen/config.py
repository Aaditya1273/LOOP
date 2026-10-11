"""Environment configuration. Nothing silently falls back to another chain or a mock."""

import json
import os
from dataclasses import dataclass
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
DEPLOYMENTS = BACKEND_DIR.parent / "contracts" / "deployments"

NETWORKS = {
    31337: ("Local devnet", None),
    10143: ("Monad testnet", "https://testnet.monadexplorer.com/tx/"),
}
MAINNETS = {1, 143}  # Ethereum, Monad mainnet: the dev faucet is refused there


@dataclass(frozen=True)
class Settings:
    rpc_url: str
    chain_id: int
    treasury: str
    token: str
    relayer_key: str
    issuer_key: str
    db_path: str
    faucet_amount: int  # base units (6 decimals)
    public_rpc: str  # handed to agents so they can register their ERC-8004 identity; must not embed an API key

    @classmethod
    def from_env(cls) -> "Settings":
        chain_id = int(_required("ANZEN_CHAIN_ID"))
        deployment = json.loads((DEPLOYMENTS / f"{chain_id}.json").read_text())
        return cls(
            rpc_url=_required("ANZEN_RPC_URL"),
            chain_id=chain_id,
            treasury=deployment["treasury"],
            token=deployment["token"],
            relayer_key=_required("ANZEN_RELAYER_KEY"),
            issuer_key=_required("ANZEN_ISSUER_KEY"),
            db_path=os.environ.get("ANZEN_DB", str(BACKEND_DIR / "anzen.db")),
            faucet_amount=int(os.environ.get("ANZEN_FAUCET_AMOUNT", "100000000")),  # 100 tUSD
            public_rpc=os.environ.get("ANZEN_PUBLIC_RPC", os.environ["ANZEN_RPC_URL"]),
        )


def _required(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"{name} is not set (see backend/.env.example)")
    return value
