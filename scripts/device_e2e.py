"""Drive Shonko on an emulator/device through the critical journeys against the LOCAL devnet (scripts/dev_stack.sh).

    python3 scripts/device_e2e.py <serial> all          # every journey below, in order
    python3 scripts/device_e2e.py <serial> tap "Text"   # helpers for poking at a screen by hand
    python3 scripts/device_e2e.py <serial> texts

Needs: the dev stack running, the debug APK built, and a screen lock PIN on the device (the account key requires
one). The script sets PIN 1111 if the device has none and removes it at the end. Real signatures, real local chain:
nothing is mocked. Screenshots go to .dev/e2e/.
"""
import html
import re
import subprocess
import sys
import time
from pathlib import Path

SERIAL = sys.argv[1]
PKG = "com.anzen.shonko"
APK = "app/build/outputs/apk/debug/app-debug.apk"
PIN = "1111"
SHOTS = Path(".dev/e2e")
ROOT = Path(__file__).resolve().parent.parent


def adb(*args: str) -> str:
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True).stdout


def screen() -> list[tuple[str, tuple[int, int]]]:
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    xml = adb("shell", "cat", "/sdcard/u.xml")
    out = []
    for text, desc, x1, y1, x2, y2 in re.findall(
            r'text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        label = html.unescape(text or desc).replace("\n", " ")
        out.append((label, ((int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2)))
    return out


def texts() -> str:
    return " | ".join(t for t, _ in screen() if t)


def find(label: str):
    items = screen()
    exact = next((xy for t, xy in items if t.lower() == label.lower()), None)
    return exact or next((xy for t, xy in items if label.lower() in t.lower()), None)


def wait_for(label: str, timeout: float = 30) -> tuple[int, int]:
    end = time.time() + timeout
    while time.time() < end:
        xy = find(label)
        if xy:
            return xy
        time.sleep(0.7)
    raise AssertionError(f"'{label}' not on screen after {timeout}s. Screen: {texts()}")


def tap(label: str, timeout: float = 30) -> None:
    x, y = wait_for(label, timeout)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.6)


def scroll_to(label: str, tries: int = 8) -> None:
    for _ in range(tries):
        if find(label):
            return
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "300")
        time.sleep(0.5)
    raise AssertionError(f"'{label}' not found after scrolling. Screen: {texts()}")


def open_link(uri: str) -> None:
    """am start through the device shell: quote the URI or `&` splits the command."""
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{uri}'", PKG)
    time.sleep(1.0)


def type_text(text: str) -> None:
    adb("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(0.3)


def enter_pin() -> None:
    """The system authorization prompt: biometric isn't enrolled on the emulator, so it asks for the PIN."""
    time.sleep(1.5)
    type_text(PIN)
    adb("shell", "input", "keyevent", "66")
    time.sleep(1.0)


def unlock() -> None:
    """Wake the device and get past the lock screen (swipe, or the test PIN once it's set)."""
    adb("shell", "input", "keyevent", "224")
    adb("shell", "wm", "dismiss-keyguard")
    time.sleep(1)
    if "isKeyguardShowing=true" in adb("shell", "dumpsys", "window"):
        type_text(PIN)
        adb("shell", "input", "keyevent", "66")
        time.sleep(1)


def shot(name: str) -> None:
    SHOTS.mkdir(parents=True, exist_ok=True)
    with open(SHOTS / f"{name}.png", "wb") as f:
        f.write(subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def warp(seconds: int) -> None:
    subprocess.run([str(ROOT / "scripts" / "warp.sh"), str(seconds)], check=True, capture_output=True)


def seed(*args: str) -> str:
    return subprocess.run(["uv", "run", "python", "-m", "anzen.seed", *args], cwd=ROOT / "backend",
                          capture_output=True, text=True, check=True).stdout.strip()


def step(name: str):
    print(f"\n== {name}")


# ------------------------------------------------------------------ journeys

def fresh_install() -> None:
    adb("uninstall", PKG)
    out = adb("install", "-r", str(ROOT / APK))
    assert "Success" in out, out
    adb("shell", "pm", "grant", PKG, "android.permission.CAMERA")


def onboarding() -> None:
    step("1. new user onboarding")
    adb("shell", "am", "start", "-n", f"{PKG}/com.anzen.MainActivity")
    wait_for("Find events worth showing up for.")
    shot("01_onboarding")
    tap("Continue")
    wait_for("Commit to show.")
    tap("Continue")
    wait_for("Prove your presence.")
    tap("Continue")
    wait_for("Unlock your outcome.")
    tap("Get started")
    wait_for("Create your ANZEN key")
    shot("02_setup")
    tap("Create key")
    wait_for("Today")
    shot("03_home_new")


def faucet() -> None:
    step("faucet: test dollars")
    tap("Get 100 test dollars")
    wait_for("100 test dollars added", 60)
    wait_for("Test dollars received")
    shot("04_home_funded")


def discover_and_commit(title: str) -> None:
    step("3/5. discovery, details, commitment preview and authorization")
    tap("Explore")
    wait_for(title)
    shot("05_explore")
    tap(title)
    wait_for("Commitment per person")
    shot("06_event")
    tap("Review commitment")
    wait_for("Authorize 5 tUSD")
    shot("07_commit_review")
    tap("Authorize 5 tUSD")
    enter_pin()
    wait_for("Commitment recorded", 60)
    shot("08_commit_verified")
    tap("Done")


def cancelled_authorization(event_id: int) -> None:
    step("cancelled authorization leaves nothing funded")
    open_link(f"anzen://event/{event_id}")
    tap("Review commitment")
    tap("Authorize 5 tUSD")
    time.sleep(1.5)
    wait_for("Commit 5 tUSD", 10)  # the system prompt names the exact action
    for _ in range(3):  # Back closes the PIN keyboard, then cancels the prompt
        if find("Cancelled"):
            break
        adb("shell", "input", "keyevent", "4")
        time.sleep(1.2)
    wait_for("Cancelled", 20)
    assert find("Authorize 5 tUSD"), "a cancelled authorization must leave the commitment unfunded and retryable"
    shot("09_commit_cancelled")
    adb("shell", "input", "keyevent", "4")
    adb("shell", "input", "keyevent", "4")


def check_in(event_id: int) -> None:
    step("6/7. check-in: invalid code, expired code, then a fresh code")
    warp(16 * 60)  # into the event's check-in window
    open_link(f"anzen://checkin?e={event_id}&c=0x12&x=1&s=0x34")
    wait_for("That link isn't an ANZEN link", 15)
    uri = seed("desk", str(event_id))
    warp(60)  # this code has now expired
    open_link(uri)
    wait_for("Expired", 20)
    shot("10_checkin_expired")
    uri = seed("desk", str(event_id))
    open_link(uri)
    tap("Sign check-in", 20)
    enter_pin()
    wait_for("You're checked in", 60)
    shot("11_checked_in")
    tap("Done")


def settle_and_passport(event_id: int) -> None:
    step("9/10. settlement and passport with its evidence")
    warp(3 * 3600)
    open_link(f"anzen://event/{event_id}")
    tap("View outcome", 20)
    wait_for("Ready to settle")
    tap("Apply outcome")
    wait_for("Commitment returned", 90)
    shot("12_outcome")
    tap("View supporter passport")
    wait_for("Issued onchain")
    shot("13_passport")
    scroll_to("View proof")
    tap("View proof")
    wait_for("Verified onchain")
    shot("14_proof")


def network_failure() -> None:
    step("11. failed network request is reported, not hidden")
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/com.anzen.MainActivity")
    wait_for("Can't reach ANZEN", 30)
    shot("15_offline")
    adb("shell", "svc", "wifi", "enable")
    adb("shell", "svc", "data", "enable")
    time.sleep(3)
    tap("Try again")
    wait_for("Available", 30)


def restore_session() -> None:
    step("2/12. returning user: the key restores the session after a restart")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/com.anzen.MainActivity")
    wait_for("Today", 20)
    assert not find("Create your ANZEN key")


def create_event_validation() -> None:
    step("4. event creation: validation blocks an incomplete event")
    for _ in range(6):
        if find("Create event"):
            break
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.8)
    tap("Create event")
    wait_for("The event")
    shot("16_create")
    wait_for("Add a title.")
    tap("Title")
    type_text("Night market zine swap")
    tap("Creator name")
    type_text("Test Creator")
    adb("shell", "input", "keyevent", "4")
    tap("Continue")
    wait_for("When and where")
    wait_for("Add where people meet.")
    shot("17_create_schedule")
    adb("shell", "input", "keyevent", "4")  # back a step
    adb("shell", "input", "keyevent", "4")  # leave: draft saved
    wait_for("Draft saved", 10)


def publish_and_desk() -> None:
    step("4b. publish an event from the wizard, then run its check-in desk")
    tap("Create event")  # the draft from the previous journey resumes
    for _ in range(3):
        if find("The event"):
            break
        adb("shell", "input", "keyevent", "4")
    wait_for("The event")
    tap("Continue")
    tap("Venue or meeting point")
    type_text("Test Hall")
    adb("shell", "input", "keyevent", "4")
    tap("Event starts")
    tap("OK")  # native date picker: a week from now
    tap("OK")  # native time picker
    wait_for("Commitments close")
    shot("18_create_schedule_filled")
    tap("Continue")
    wait_for("The commitment")
    tap("Continue")
    tap("Dispute contact")
    type_text("organizer@example.com")
    adb("shell", "input", "keyevent", "4")
    tap("Continue")
    wait_for("Outcomes")
    tap("Continue")
    wait_for("This is exactly what supporters agree to.")
    shot("19_create_review")
    tap("Authorize and publish")
    enter_pin()
    wait_for("Commitment per person", 90)  # landed on the published event
    shot("20_published_event")
    scroll_to("Open check-in desk")
    tap("Open check-in desk")
    tap("Open desk")
    enter_pin()
    wait_for("Codes appear when check-in opens", 30)
    # Warp exactly into this event's window (earlier runs may have moved the local chain ahead of wall time).
    import json, urllib.request
    api = lambda path: json.load(urllib.request.urlopen("http://127.0.0.1:8790" + path))
    e = max(api("/events"), key=lambda x: x["id"])
    warp(e["checkInStart"] - api("/health")["now"] + 30)
    wait_for("Venue check-in code", 60)  # the desk retries and shows a fresh code
    shot("21_desk_qr")
    adb("shell", "input", "keyevent", "4")


def agent_budget() -> None:
    step("AI agent: approve a budget from the agent's link, agent reserves, presence stays human")
    import json, os, sqlite3
    env = {**os.environ, "ANZEN_AGENT_KEYFILE": str(ROOT / ".dev" / "e2e-agent.key"), "ANZEN_URL": "http://127.0.0.1:8790"}
    run = lambda *a, **kw: subprocess.run([str(ROOT / "skills/anzen/scripts/anzen-agent"), *a], capture_output=True,
                                          text=True, env={**env, **kw})
    link = json.loads(run("init").stdout)["appLink"]
    open_link(link)
    wait_for("Your agent is asking for a budget", 20)
    shot("22_agent_request")
    scroll_to("Approve 20 tUSD for 7 days")
    tap("Approve 20 tUSD for 7 days")
    enter_pin()
    wait_for("Budget left", 60)  # the sealed panel appears once the contract shows the budget
    scroll_to("Budget updated")
    shot("23_agent_approved")
    treasury = json.loads((ROOT / "contracts/deployments/31337.json").read_text())["treasury"]
    db = sqlite3.connect(ROOT / f"backend/anzen-local-{treasury}.db")
    owner = db.execute("SELECT key_id FROM relays WHERE action='delegate' ORDER BY created_at DESC LIMIT 1").fetchone()[0]
    event_id = int(seed("quick", f"Agent pick {time.strftime('%H%M%S')}"))
    out = json.loads(run("reserve", str(event_id), ANZEN_OWNER=owner).stdout)
    assert out["ok"], out
    for _ in range(4):
        adb("shell", "input", "keyevent", "4")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/com.anzen.MainActivity")
    wait_for("Your AI agent", 30)
    wait_for("15 of 20 tUSD left")
    shot("24_home_agent")
    scroll_to("See all")
    tap("See all")
    wait_for("Reserved by your agent", 20)
    shot("25_activity_agent")
    adb("shell", "input", "keyevent", "4")


def main() -> None:
    cmd = sys.argv[2] if len(sys.argv) > 2 else "all"
    if cmd == "tap":
        return tap(sys.argv[3])
    if cmd == "texts":
        return print(texts())
    if cmd == "shot":
        return shot(sys.argv[3])
    unlock()  # fresh boots start on the lock screen
    adb("shell", "locksettings", "set-pin", PIN)
    unlock()
    try:
        # Fresh events each run: commitments close 10 min after publishing, check-in opens at 15.
        stamp = time.strftime("%H%M%S")
        main_id = int(seed("quick", f"E2E zine {stamp}"))
        spare_id = int(seed("quick", f"E2E spare {stamp}"))
        fresh_install()
        onboarding()
        faucet()
        discover_and_commit(f"E2E zine {stamp}")
        cancelled_authorization(spare_id)
        agent_budget()
        restore_session()
        check_in(main_id)
        settle_and_passport(main_id)
        create_event_validation()
        publish_and_desk()
        network_failure()
        print("\nALL JOURNEYS PASSED")
    finally:
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
        adb("shell", "locksettings", "clear", "--old", PIN)


if __name__ == "__main__":
    main()
