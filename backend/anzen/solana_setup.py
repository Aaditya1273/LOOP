"""One-time setup of a deployed anzen_core program: the test-dollar mint (the relayer can mint, for the faucet),
the relayer's token account, and the program's config + vault. Writes contracts/deployments/solana-<chain id>.json,
which the service reads.

  ANZEN_RPC_URL=https://api.devnet.solana.com ANZEN_CHAIN_ID=103 ANZEN_RELAYER_KEY=.. ANZEN_ISSUER_KEY=.. \\
      uv run python -m anzen.solana_setup <program id>
"""

import json
import os
import sys
import time

from solders.instruction import AccountMeta, Instruction
from solders.keypair import Keypair
from solders.message import Message
from solders.pubkey import Pubkey
from solders.system_program import CreateAccountParams, create_account
from solders.transaction import Transaction

from .config import DEPLOYMENTS
from .solana_chain import ATA, SYSTEM, TOKEN, Rpc, disc, keypair_from, u8, u64

MINT_SIZE = 82


def _send(rpc: Rpc, signers: list[Keypair], ixs: list[Instruction]) -> str:
    bh = rpc.blockhash()
    tx = Transaction(signers, Message.new_with_blockhash(ixs, signers[0].pubkey(), bh), bh)
    sim = rpc.simulate(tx)
    if sim.get("err"):
        raise SystemExit(f"setup transaction fails: {sim['err']} {sim.get('logs')}")
    sig = rpc.send(tx)
    for _ in range(60):
        st = rpc.status(sig)
        if st is not None and st.get("confirmationStatus") in ("confirmed", "finalized"):
            if st.get("err"):
                raise SystemExit(f"setup transaction failed: {st['err']}")
            return sig
        time.sleep(0.5)
    raise SystemExit("setup transaction not confirmed")


def setup(rpc_url: str, program_id: str, relayer: Keypair, issuer: Pubkey, chain_id: int) -> dict:
    rpc = Rpc(rpc_url)
    program = Pubkey.from_string(program_id)
    me = relayer.pubkey()
    mint = Keypair()
    rent = rpc.call("getMinimumBalanceForRentExemption", MINT_SIZE)
    create_mint = create_account(CreateAccountParams(from_pubkey=me, to_pubkey=mint.pubkey(), lamports=rent,
                                                     space=MINT_SIZE, owner=TOKEN))
    init_mint = Instruction(TOKEN, u8(20) + u8(6) + bytes(me) + u8(0), [AccountMeta(mint.pubkey(), False, True)])  # InitializeMint2
    ata = Pubkey.find_program_address([bytes(me), bytes(TOKEN), bytes(mint.pubkey())], ATA)[0]
    create_ata = Instruction(ATA, u8(1), [AccountMeta(me, True, True), AccountMeta(ata, False, True),
                                          AccountMeta(me, False, False), AccountMeta(mint.pubkey(), False, False),
                                          AccountMeta(SYSTEM, False, False), AccountMeta(TOKEN, False, False)])
    _send(rpc, [relayer, mint], [create_mint, init_mint, create_ata])
    config = Pubkey.find_program_address([b"config"], program)[0]
    vault = Pubkey.find_program_address([b"vault"], program)[0]
    init = Instruction(program, disc("global", "initialize") + u64(chain_id) + bytes(issuer), [
        AccountMeta(me, True, True), AccountMeta(config, False, True), AccountMeta(mint.pubkey(), False, False),
        AccountMeta(vault, False, True), AccountMeta(TOKEN, False, False), AccountMeta(SYSTEM, False, False)])
    tx = _send(rpc, [relayer], [init])
    return {"chainId": chain_id, "programId": program_id, "mint": str(mint.pubkey()), "config": str(config),
            "vault": str(vault), "relayer": str(me), "issuer": str(issuer), "initTx": tx}


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    chain_id = int(os.environ["ANZEN_CHAIN_ID"])
    d = setup(os.environ["ANZEN_RPC_URL"], sys.argv[1], keypair_from(os.environ["ANZEN_RELAYER_KEY"]),
              keypair_from(os.environ["ANZEN_ISSUER_KEY"]).pubkey(), chain_id)
    out = DEPLOYMENTS / f"solana-{chain_id}.json"
    out.write_text(json.dumps(d, indent=2) + "\n")
    print(json.dumps(d, indent=2))


if __name__ == "__main__":
    main()
