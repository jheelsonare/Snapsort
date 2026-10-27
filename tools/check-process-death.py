#!/usr/bin/env python3
"""Real force-stop/reopen checks on a disposable SnapSort emulator or test phone.

Requires both newly built debug APKs installed, adb, and --reset-test-app. Clears
only SnapSort app data. Instrumentation creates unique, app-owned images, and a
cleanup phase deletes exactly those recorded fixture URIs. No user images are
selected. The confirmation scenario kills the app while a real Android trash
dialog is visible. The between-batches scenario confirms one real fixture and
uses a small explicit progress checkpoint, not a 2,001-image production load.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.snapsort.app"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
METHOD = PACKAGE + ".flow.ProcessDeathRecoveryTest#recoversRemainingOriginalsWithoutResumingConfirmation"


class Check:
    def __init__(self, args):
        adb = shutil.which("adb")
        if not adb:
            raise RuntimeError("adb missing; source tools/android-env.sh first")
        self.adb = [adb] + (["-s", args.serial] if args.serial else [])
        self.output = Path(args.output_dir)
        self.output.mkdir(parents=True, exist_ok=True)
        self.report = {"status": "running", "scenarios": [], "cleanup_errors": []}

    def command(self, *args, check=True, timeout=90):
        p = subprocess.run(self.adb + list(args), capture_output=True, text=True, timeout=timeout)
        if check and p.returncode:
            raise RuntimeError(f"adb {shlex.join(args[:3])}: {p.stderr.strip() or p.stdout.strip()}")
        return p.stdout.strip()

    def shell(self, *args, **kwargs):
        return self.command("shell", shlex.join(args), **kwargs)

    def phase(self, phase, background=False):
        args = self.adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", METHOD,
                           "-e", "death_phase", phase, RUNNER]
        log = self.output / f"{phase}-{time.time_ns()}.txt"
        if background:
            handle = log.open("w")
            process = subprocess.Popen(args, stdout=handle, stderr=subprocess.STDOUT)
            handle.close()
            return process, log
        p = subprocess.run(args, capture_output=True, text=True, timeout=360)
        log.write_text(p.stdout + p.stderr)
        if p.returncode or "OK (1 test)" not in p.stdout or "FAILURES!!!" in p.stdout:
            raise RuntimeError(f"Instrumentation {phase} failed; inspect {log}")
        return log

    def checkpoint(self):
        raw = self.command("exec-out", "run-as", PACKAGE, "cat", "files/process-death-ready.json", check=False, timeout=30)
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return None

    def ui(self, label):
        remote = "/sdcard/snapsort-process-death-ui.xml"
        last = None
        for _ in range(3):
            try:
                self.shell("rm", "-f", remote)
                self.shell("uiautomator", "dump", remote, timeout=45)
                raw = self.command("exec-out", "cat", remote)
                root = ET.fromstring(raw)
                (self.output / f"{label}.xml").write_text(raw)
                return root
            except (ET.ParseError, RuntimeError, subprocess.TimeoutExpired) as error:
                last = error
                time.sleep(1)
        raise RuntimeError(f"No readable UI hierarchy: {last}")

    @staticmethod
    def is_trash_dialog(root):
        text = " ".join(n.get("text", "") for n in root.iter("node")).casefold()
        ids = [n.get("resource-id", "") for n in root.iter("node")]
        packages = [n.get("package", "") for n in root.iter("node")]
        return ("isn't responding" not in text and ("trash" in text or "move" in text)
                and any(p.startswith("com.android.providers.media") for p in packages)
                and any(i == "android:id/button1" for i in ids))

    def run_scenario(self, phase):
        print(f"Preparing {phase}", flush=True)
        self.shell("am", "force-stop", PACKAGE)
        assert "Success" in self.shell("pm", "clear", PACKAGE)
        process, log = self.phase(phase, background=True)
        checkpoint = None
        try:
            deadline = time.monotonic() + 420
            while time.monotonic() < deadline:
                checkpoint = self.checkpoint()
                if checkpoint:
                    break
                if process.poll() is not None:
                    raise RuntimeError(f"Preparation exited before ready; inspect {log}")
                time.sleep(3)
            if not checkpoint:
                raise RuntimeError(f"Preparation did not reach a checkpoint; inspect {log}")
            before = self.ui(phase + "-before-kill")
            if phase == "prepare-dialog" and not self.is_trash_dialog(before):
                raise RuntimeError("Expected real Android trash dialog before force-stop")
            pid_before = self.shell("pidof", PACKAGE)
            if not pid_before:
                raise RuntimeError("SnapSort process missing before kill")
            self.shell("am", "force-stop", PACKAGE)
            deadline = time.monotonic() + 30
            while self.shell("pidof", PACKAGE, check=False) and time.monotonic() < deadline:
                time.sleep(1)
            if self.shell("pidof", PACKAGE, check=False):
                raise RuntimeError("force-stop did not terminate SnapSort")
            process.wait(timeout=30)
            # The preparation runner is intentionally killed and is not counted as a passed test.
            print(f"Killed process {pid_before}; reopening {phase}", flush=True)
            self.shell("am", "start", "-n", PACKAGE + "/.MainActivity")
            expected_count = 1 if phase == "prepare-between-batches" else 2
            deadline = time.monotonic() + 180
            recovered = False
            while time.monotonic() < deadline:
                root = self.ui(phase + "-reopened")
                texts = [n.get("text", "") for n in root.iter("node")]
                if self.is_trash_dialog(root):
                    raise RuntimeError("A trash request resumed automatically after reopening")
                if "Cleanup interrupted" in texts and f"{expected_count} selected" in texts:
                    recovered = True
                    break
                time.sleep(2)
            if not recovered:
                raise RuntimeError("Recovered review state/selection did not appear after reopening")
            time.sleep(3)
            if self.is_trash_dialog(self.ui(phase + "-no-auto-confirmation")):
                raise RuntimeError("A confirmation resumed without renewed review")
            verify_log = self.phase("verify")
            self.report["scenarios"].append({"phase": phase, "status": "passed", "killed_pid": pid_before,
                "fixture_uris": checkpoint["uris"], "recovered_selected_count": expected_count,
                "verification_log": str(verify_log), "small_checkpoint_fixture": phase == "prepare-between-batches"})
        finally:
            if process.poll() is None:
                self.shell("am", "force-stop", PACKAGE, check=False)
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.terminate()
            try:
                self.phase("cleanup")
            except Exception as error:
                self.report["cleanup_errors"].append(str(error))
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--reset-test-app", action="store_true", required=True,
                        help="Required consent to clear SnapSort metadata on a disposable emulator/test phone")
    parser.add_argument("--output-dir", default="/tmp/snapsort-process-death")
    parser.add_argument("--scenarios", nargs="+", choices=("dialog", "between-batches"), default=["dialog", "between-batches"])
    args = parser.parse_args()
    check = Check(args)
    try:
        for scenario in args.scenarios:
            check.run_scenario("prepare-" + scenario)
        check.report["status"] = "passed"
    except Exception as error:
        check.report.update(status="failed", error=str(error))
        print(str(error), file=sys.stderr)
    finally:
        check.shell("rm", "-f", "/sdcard/snapsort-process-death-ui.xml", check=False)
        (check.output / "report.json").write_text(json.dumps(check.report, indent=2) + "\n")
    return 0 if check.report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
