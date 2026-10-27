#!/usr/bin/env python3
"""Exercise real Android 14+ gallery permission dialogs on a disposable test device.

Usage (after building/installing the debug APK and sourcing tools/android-env.sh):
  python3 tools/check-gallery-access.py --reset-test-app --serial emulator-5554

--reset-test-app is REQUIRED: this clears SnapSort's local categories, OCR, Keep,
and saved selections before each scenario. Use only an emulator/test device.
The script does not use pm grant to simulate access. It drives the Android
deny/select-photos/allow-all dialogs, checks actual runtime permission state,
and checks which externally seeded screenshots SnapSort can display. Resets
revoke permissions so each scenario begins independently. Only two uniquely
named fixture images and the script's temporary UI dump are cleaned up.

Outputs JSON evidence plus UI XML under --output-dir (default /tmp/...). A
nonzero exit reports the exact failed/unsupported step; no scenario is silently
skipped. Labels/resources support stock English Android; other OEM UIs may need
selector adaptation. This script intentionally never confirms a trash dialog.
If Android groups the unique fixture folder into its curated Screenshots album,
selection proceeds only when a read-only MediaStore query proves that all active
images on this disposable device are exactly the two generated fixtures.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import zlib


PACKAGE = "com.snapsort.app"
IMAGES = "android.permission.READ_MEDIA_IMAGES"
SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"


def png_bytes(red: int, green: int, blue: int) -> bytes:
    """A genuine, decodable, clearly coloured PNG using only the standard library."""
    width, height = 240, 400
    rows = []
    for y in range(height):
        # Distinct pale header/footer make the fixtures obvious in the picker.
        colour = (red, green, blue) if 60 <= y < 340 else (245, 245, 245)
        rows.append(b"\x00" + bytes(colour) * width)

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b"")


class Smoke:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        adb = shutil.which("adb")
        if not adb:
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            adb = str(Path(sdk) / "platform-tools/adb") if sdk else None
        if not adb or not Path(adb).is_file():
            raise RuntimeError("adb was not found. Source tools/android-env.sh first.")
        self.adb = [adb] + (["-s", args.serial] if args.serial else [])
        self.run_id = uuid.uuid4().hex[:12]
        self.output = Path(args.output_dir or f"/tmp/snapsort-permissions-{self.run_id}")
        self.output.mkdir(parents=True, exist_ok=True)
        self.album = f"SnapSortPermission_{self.run_id}"
        self.relative = f"Pictures/Screenshots/{self.album}/"
        self.device_dir = f"/sdcard/{self.relative.rstrip('/')}"
        self.names = [f"Screenshot_SnapSortPermission_{self.run_id}_{letter}.png" for letter in ("A", "B")]
        self.device_dump = f"/sdcard/snapsort-ui-{self.run_id}.xml"
        self.sequence = 0
        self.fixtures: dict[str, str] = {}
        self.report = {"run_id": self.run_id, "package": PACKAGE, "status": "running", "scenarios": [], "fixtures": self.fixtures}
        self.stage = "initialisation"

    def command(self, *args: str, check: bool = True, timeout: int = 40) -> str:
        result = subprocess.run(self.adb + list(args), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"adb {shlex.join(args[:3])} failed: {result.stderr.strip() or result.stdout.strip()}")
        return result.stdout.strip()

    def shell(self, *args: str, check: bool = True, timeout: int = 40) -> str:
        return self.command("shell", shlex.join(args), check=check, timeout=timeout)

    def ui(self, label: str = "window") -> ET.Element:
        # The software emulator occasionally reports a successful dump command
        # without writing a hierarchy. Retry this read, never a consent action.
        last_error = None
        for attempt in range(3):
            try:
                self.shell("rm", "-f", self.device_dump)
                self.shell("uiautomator", "dump", self.device_dump, timeout=30)
                raw = self.command("exec-out", "cat", self.device_dump)
                root = ET.fromstring(raw)
                self.sequence += 1
                safe = re.sub(r"[^a-zA-Z0-9_-]", "_", label)
                (self.output / f"{self.sequence:03d}-{safe}.xml").write_text(raw, encoding="utf-8")
                return root
            except (ET.ParseError, RuntimeError, subprocess.TimeoutExpired) as error:
                last_error = error
                if attempt < 2:
                    print(f"Retrying hierarchy read ({attempt + 1}/3): {error}", file=sys.stderr, flush=True)
                    time.sleep(1)
        raise RuntimeError(f"Android did not return a valid UI hierarchy after three reads: {last_error}")

    @staticmethod
    def matches(root: ET.Element, *, texts: tuple[str, ...] = (), ids: tuple[str, ...] = ()) -> list[ET.Element]:
        return [node for node in root.iter("node") if
                node.get("text", "").casefold() in {text.casefold() for text in texts}
                or any(node.get("resource-id", "").endswith(suffix) for suffix in ids)]

    def wait_node(self, *, texts: tuple[str, ...] = (), ids: tuple[str, ...] = (), timeout: int = 35, label: str = "wait") -> tuple[ET.Element, ET.Element]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            root = self.ui(label)
            found = self.matches(root, texts=texts, ids=ids)
            if found:
                return root, found[0]
            time.sleep(1)
        raise RuntimeError(f"{self.stage}: could not find Android control texts={texts} resource suffixes={ids}. Inspect XML evidence in {self.output}.")

    def tap(self, root: ET.Element, node: ET.Element):
        parents = {child: parent for parent in root.iter() for child in parent}
        target = node
        while target.get("clickable") != "true" and target in parents:
            target = parents[target]
        pattern = r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]"
        bounds = re.fullmatch(pattern, target.get("bounds", ""))
        if not bounds:
            raise RuntimeError(f"No clickable bounds for {node.attrib}")
        left, top, right, bottom = map(int, bounds.groups())
        # Accessibility can expose a partially clipped Compose button's full
        # layout bounds. Intersect every clipping ancestor before tapping; its
        # un-clipped centre can otherwise land on Android's navigation bar.
        ancestor = parents.get(target)
        while ancestor is not None:
            ancestor_bounds = re.fullmatch(pattern, ancestor.get("bounds", ""))
            if ancestor_bounds:
                outer_left, outer_top, outer_right, outer_bottom = map(int, ancestor_bounds.groups())
                left, top = max(left, outer_left), max(top, outer_top)
                right, bottom = min(right, outer_right), min(bottom, outer_bottom)
            ancestor = parents.get(ancestor)
        if right <= left or bottom <= top:
            raise RuntimeError(f"Control is not currently visible: {node.attrib}")
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(0.8)

    def click(self, **kwargs):
        root, node = self.wait_node(**kwargs)
        self.tap(root, node)

    def swipe(self, up: bool = True):
        size = self.shell("wm", "size")
        sizes = re.findall(r"(Override|Physical) size:\s*(\d+)x(\d+)", size)
        if not sizes:
            raise RuntimeError(f"Cannot determine display size: {size}")
        effective = next((entry for entry in sizes if entry[0] == "Override"), sizes[0])
        width, height = map(int, effective[1:])
        self.report["effective_display"] = {"width": width, "height": height}
        start, end = ((height * 3 // 4, height // 3) if up else (height // 3, height * 3 // 4))
        self.shell("input", "swipe", str(width // 2), str(start), str(width // 2), str(end), "350")
        time.sleep(0.6)

    def scroll_find(self, *, texts: tuple[str, ...], attempts: int = 8) -> tuple[ET.Element, ET.Element]:
        for _ in range(attempts):
            root = self.ui("scroll")
            found = self.matches(root, texts=texts)
            if found:
                return root, found[0]
            self.swipe()
        raise RuntimeError(f"{self.stage}: expected app explanation/control {texts} was not found")

    def permission_state(self) -> dict[str, bool]:
        dump = self.shell("dumpsys", "package", PACKAGE)
        state = {}
        for name in (IMAGES, SELECTED):
            matches = re.findall(r"^\s*" + re.escape(name) + r":\s*granted=(true|false)", dump, flags=re.MULTILINE)
            if not matches:
                raise RuntimeError(f"Android did not expose runtime permission status for {name}")
            state[name] = matches[-1] == "true"
        return state

    def reset(self, scenario: str):
        self.stage = scenario
        self.shell("am", "force-stop", PACKAGE)
        result = self.shell("pm", "clear", PACKAGE)
        if "Success" not in result:
            raise RuntimeError(f"Cannot reset the disposable test app: {result}")
        for permission in (IMAGES, SELECTED):
            # Reset only. Never grant a permission to claim the dialog succeeded.
            self.shell("pm", "revoke", PACKAGE, permission, check=False)
            self.shell("pm", "clear-permission-flags", PACKAGE, permission, "user-set", "user-fixed", check=False)
        self.shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        self.wait_node(texts=("Allow access",), label=f"{scenario}-initial")

    def request_access(self):
        root, button = self.scroll_find(texts=("Allow access",))
        self.tap(root, button)

    def seed(self):
        self.stage = "fixture seeding"
        self.shell("mkdir", "-p", self.device_dir)
        colours = ((165, 225, 55), (45, 105, 225))
        for name, colour in zip(self.names, colours):
            file = self.output / name
            file.write_bytes(png_bytes(*colour))
            self.command("push", str(file), f"{self.device_dir}/{name}")
            physical = f"/storage/emulated/0/{self.relative}{name}"
            self.shell("content", "call", "--uri", "content://media", "--method", "scan_file", "--arg", physical, check=False)
            where = f"_display_name='{name}' AND relative_path='{self.relative}'"
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                result = self.shell("content", "query", "--uri", "content://media/external/images/media", "--projection", "_id:volume_name", "--where", where)
                match = re.search(r"_id=(\d+),\s*volume_name=([A-Za-z0-9_]+)", result)
                if match:
                    self.fixtures[name] = f"content://media/{match.group(2)}/images/media/{match.group(1)}"
                    break
                self.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{physical}", check=False)
                time.sleep(1)
            else:
                raise RuntimeError(f"MediaStore did not index the seeded original {name}")

    def visible_fixtures(self) -> list[str]:
        # All collection is the default after reset. Traverse the actual lazy grid.
        found = set()
        for _ in range(8):
            root = self.ui("gallery-originals")
            labels = "\n".join(node.get("text", "") + " " + node.get("content-desc", "") for node in root.iter("node"))
            found.update(name for name in self.names if name in labels)
            self.swipe()
        return sorted(found)

    def denied(self):
        self.reset("denied")
        root, _ = self.scroll_find(texts=("Your screenshots, organised privately",))
        if not any("no account or uploads" in node.get("text", "") for node in root.iter("node")):
            raise RuntimeError("Initial permission explanation did not describe on-device processing")
        self.request_access()
        self.click(texts=("Don't allow", "Don’t allow", "Deny"), ids=("/permission_deny_button",), label="system-deny")
        time.sleep(1)
        state = self.permission_state()
        if state[IMAGES] or state[SELECTED]:
            raise RuntimeError(f"Deny did not remove gallery access: {state}")
        root, node = self.scroll_find(texts=("Already denied? Open app settings",))
        self.tap(root, node)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            settings = self.ui("permission-recovery-settings")
            if any(node.get("package") == "com.android.settings" for node in settings.iter("node")):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Permission recovery did not open Android app settings")
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.report["scenarios"].append({"name": "denied", "status": "passed", "permissions": state, "settings_recovery": True})

    def limited(self):
        self.reset("limited")
        self.request_access()
        self.click(texts=("Select photos", "Select photos and videos"), ids=("/permission_allow_selected_button",), label="system-limited")
        root = self.ui("photo-picker")
        # Prefer the unique album. Android 15 can group screenshot subfolders
        # into its curated Screenshots album, which needs a strict safety guard.
        if not self.matches(root, texts=(self.album,)):
            self.click(texts=("Albums",), ids=("/tab_albums",), label="picker-albums")
        root, album = self.scroll_find(texts=(self.album, "Screenshots"))
        if album.get("text", "").casefold() != self.album.casefold():
            self.require_only_fixture_images()
        self.tap(root, album)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            root = self.ui("picker-fixture-album")
            candidates = [node for node in root.iter("node") if
                any(node.get("resource-id", "").endswith(suffix) for suffix in ("/icon_thumbnail", "/thumbnail", "/image_thumbnail"))
                and node.get("bounds") != "[0,0][0,0]"]
            if candidates:
                self.tap(root, candidates[0])
                break
            time.sleep(1)
        else:
            raise RuntimeError("Could not identify a photo thumbnail inside the uniquely seeded album")
        # Android's permission picker uses an Add/Allow/Done confirmation button.
        root = self.ui("picker-one-selected")
        candidates = [node for node in root.iter("node") if
            re.fullmatch(r"(?:Add|Allow|Done|Select)(?:\s*\(1\))?", node.get("text", ""), re.IGNORECASE)
            or any(node.get("resource-id", "").endswith(suffix) for suffix in ("/button_add", "/button_confirm", "/add_button"))]
        if not candidates:
            raise RuntimeError("One photo was tapped, but the Android picker confirmation could not be identified")
        self.tap(root, candidates[-1])
        time.sleep(2)
        state = self.permission_state()
        if state[IMAGES] or not state[SELECTED]:
            raise RuntimeError(f"The real limited grant did not take effect: {state}")
        self.scroll_find(texts=("You're sharing selected photos",))
        visible = self.visible_fixtures()
        if len(visible) != 1:
            raise RuntimeError(f"Limited access should expose exactly one seeded original, saw {visible}")
        self.report["scenarios"].append({"name": "limited", "status": "passed", "permissions": state, "visible_fixtures": visible})

    def require_only_fixture_images(self):
        """Only choose a generic thumbnail when every active image is ours."""
        result = self.shell("content", "query", "--uri", "content://media/external/images/media",
                            "--projection", "_id:volume_name:_display_name",
                            "--where", "is_pending=0 AND is_trashed=0")
        rows = [line for line in result.splitlines() if line.startswith("Row:")]
        found = {}
        for row in rows:
            match = re.fullmatch(r"Row:\s*\d+\s+_id=(\d+),\s*volume_name=([^,]+),\s*_display_name=(.*)", row)
            if not match:
                raise RuntimeError("Cannot verify the disposable gallery metadata; refusing to select an unidentified photo")
            found[match.group(3)] = f"content://media/{match.group(2)}/images/media/{match.group(1)}"
        if len(rows) != 2 or found != self.fixtures:
            raise RuntimeError("Android grouped the fixture folder into Screenshots, but the active gallery is not exactly the two generated fixtures. Use an empty disposable test device; no unidentified photo was selected.")
        self.report["picker_fixture_guard"] = {"all_active_images_are_fixtures": True, "active_image_count": len(rows)}

    def full(self):
        self.reset("full")
        self.request_access()
        self.click(texts=("Allow all", "Allow all photos", "Allow access to all photos"), ids=("/permission_allow_all_button", "/permission_allow_button"), label="system-full")
        time.sleep(2)
        state = self.permission_state()
        if not state[IMAGES]:
            raise RuntimeError(f"The real allow-all grant did not take effect: {state}")
        visible = self.visible_fixtures()
        if set(visible) != set(self.names):
            raise RuntimeError(f"Full access should expose both seeded originals, saw {visible}")
        self.report["scenarios"].append({"name": "full", "status": "passed", "permissions": state, "visible_fixtures": visible})

    def revoked(self):
        # Start with a real system-dialog grant and a populated collection. Unlike reset(),
        # revoke access in place, preserving app metadata and the original gallery fixtures.
        self.full()
        before = self.report["scenarios"].pop()
        self.stage = "revoked"
        for permission in (IMAGES, SELECTED):
            self.shell("pm", "revoke", PACKAGE, permission)
        self.shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        self.wait_node(texts=("Allow access",), label="revoked-recovery", timeout=60)
        after = self.permission_state()
        if any(after.values()):
            raise RuntimeError(f"Runtime revocation did not take effect: {after}")
        visible = self.visible_fixtures()
        if visible:
            raise RuntimeError(f"Revoked gallery originals must not remain visible: {visible}")
        for name in self.names:
            result = self.shell("content", "query", "--uri", "content://media/external/images/media",
                                "--projection", "_display_name", "--where",
                                f"_display_name='{name}' AND relative_path='{self.relative}'")
            if name not in result:
                raise RuntimeError("A permission revocation fixture unexpectedly disappeared from MediaStore")
        self.report["scenarios"].append({"name": "revoked", "status": "passed", "permissions_before": before["permissions"],
                                         "permissions": after, "visible_fixtures": [], "originals_still_in_gallery": True})

    def cleanup(self):
        # Exact generated names + relative paths protect all pre-existing media.
        errors = []
        for name in self.names:
            where = f"_display_name='{name}' AND relative_path='{self.relative}'"
            try:
                self.shell("content", "delete", "--uri", "content://media/external/images/media", "--where", where)
                self.shell("rm", "-f", f"{self.device_dir}/{name}")
            except Exception as error:
                errors.append(str(error))
        try:
            self.shell("rmdir", self.device_dir, check=False)
            self.shell("rm", "-f", self.device_dump, check=False)
        except Exception as error:
            errors.append(str(error))
        self.report["cleanup_errors"] = errors

    def run(self) -> int:
        try:
            sdk = int(self.shell("getprop", "ro.build.version.sdk"))
            self.report["android_api"] = sdk
            self.report["device"] = self.command("get-serialno")
            if sdk < 34:
                raise RuntimeError("This smoke test requires Android 14/API34 or newer for the selected-photos permission flow")
            self.seed()
            for scenario in self.args.scenarios:
                getattr(self, scenario)()
            self.report["status"] = "passed"
        except Exception as error:
            self.report.update(status="failed", failed_stage=self.stage, error=str(error))
            print(f"FAILED at {self.stage}: {error}", file=sys.stderr)
        finally:
            self.cleanup()
            self.report["evidence_directory"] = str(self.output)
            (self.output / "report.json").write_text(json.dumps(self.report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(self.report, indent=2))
        return 0 if self.report["status"] == "passed" and not self.report["cleanup_errors"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reset-test-app", action="store_true", help="Required: authorise clearing SnapSort metadata on this test device")
    parser.add_argument("--serial", help="adb device serial, e.g. emulator-5554")
    parser.add_argument("--output-dir", help="Store JSON/UI XML evidence here")
    parser.add_argument("--scenarios", nargs="+", choices=("denied", "limited", "full", "revoked"),
                        default=["denied", "limited", "full", "revoked"], help="Run selected scenarios; default includes in-place revocation after a real full grant")
    args = parser.parse_args()
    if not args.reset_test_app:
        parser.error("--reset-test-app is required because these tests clear local SnapSort metadata; use only a disposable test device")
    return Smoke(args).run()


if __name__ == "__main__":
    sys.exit(main())
