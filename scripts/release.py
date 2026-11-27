#!/usr/bin/env python3
"""Maintain app versions and publish verified APKs from a clean Git checkout."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = ROOT / "version.properties"
FEED = ROOT / "updates" / "stable.json"
REPO = "AndreDon0/ambient-recorder"
PACKAGE = "com.dream.ambientrecorder"
CERTIFICATE = "42de7d1c946702f6ea9117b1bd4f9ba5167ef796342f1cab08408546c618da19"
VERSION_FORMAT = re.compile(r"(0|[1-9][0-9]{0,3})(\.(0|[1-9][0-9]{0,3})){2}")
MAX_APK = 32 * 1024 * 1024


def run(*args):
    return subprocess.run(args, cwd=ROOT, check=True, stdout=subprocess.PIPE, text=True).stdout.strip()


def gh(*args):
    return run("gh", "release", *args, "--repo", REPO)


def version():
    values = dict(line.split("=", 1) for line in VERSION.read_text().splitlines() if line)
    name, code = values["versionName"], int(values["versionCode"])
    if not VERSION_FORMAT.fullmatch(name) or not 1 <= code <= 2_100_000_000:
        raise ValueError("Invalid version.properties")
    return name, code


def bump(part):
    name, code = version()
    positions = {"major": 0, "minor": 1, "patch": 2}
    pieces = [int(piece) for piece in name.split(".")]
    index = positions[part]
    pieces[index:] = [pieces[index] + 1] + [0] * (2 - index)
    new_name = ".".join(map(str, pieces))
    if not VERSION_FORMAT.fullmatch(new_name) or code >= 2_100_000_000:
        raise ValueError("Version exceeds the supported limit")
    VERSION.write_text(f"versionCode={code + 1}\nversionName={new_name}\n")
    print(f"{new_name} ({code + 1})")


def sdk_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to your Android SDK path.")
    return Path(sdk) / "build-tools" / "36.1.0"


def verify_certificate(apk):
    result = run(str(sdk_tools() / "apksigner"), "verify", "--print-certs", str(apk))
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", result)
    if [digest.lower() for digest in digests] != [CERTIFICATE]:
        raise ValueError("The APK must use the original Ambient Recorder signing key.")


def sign(source, output, keystore, alias):
    if source == output or output.exists():
        raise ValueError("Choose a new output APK to avoid replacing an existing file.")
    tools = sdk_tools()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ambient-sign-") as directory:
        aligned = Path(directory) / "aligned.apk"
        signed = Path(directory) / "signed.apk"
        run(str(tools / "zipalign"), "-P", "16", "4", str(source), str(aligned))
        command = [str(tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias", alias,
                   "--v4-signing-enabled", "false", "--out", str(signed)]
        for option, variable in (("--ks-pass", "AMBIENT_STORE_PASSWORD"), ("--key-pass", "AMBIENT_KEY_PASSWORD")):
            if variable in os.environ:
                command.extend([option, "env:" + variable])
        subprocess.run([*command, str(aligned)], cwd=ROOT, check=True)
        verify_certificate(signed)
        output.write_bytes(signed.read_bytes())
    print(output)


def verify_release(apk, notes_file):
    name, code = version()
    if apk.name != f"Ambient-Recorder-{name}.apk" or not 1 <= apk.stat().st_size <= MAX_APK:
        raise ValueError(f"Expected Ambient-Recorder-{name}.apk, up to 32 MiB.")
    verify_certificate(apk)
    badging = run(str(sdk_tools() / "aapt2"), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^minSdkVersion:'(\d+)'", badging, re.M) or re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or package.groups() != (PACKAGE, str(code), name) or not sdk or int(sdk[1]) < 29:
        raise ValueError("APK package, version, or minimum Android version is incorrect.")
    notes = notes_file.read_text().strip()
    if not 1 <= len(notes) <= 4000:
        raise ValueError("Release notes must contain 1–4000 characters.")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return {
        "schema": 1, "versionCode": code, "versionName": name, "minSdk": int(sdk[1]),
        "size": apk.stat().st_size, "sha256": digest, "notes": {"en": notes},
        "url": f"https://github.com/{REPO}/releases/download/v{name}/{apk.name}",
    }


def checked_head():
    if run("git", "branch", "--show-current") != "main" or run("git", "status", "--porcelain"):
        raise ValueError("Commit your changes on main before publishing.")
    remote = run("git", "remote", "get-url", "origin").removesuffix(".git")
    if remote not in (f"https://github.com/{REPO}", f"git@github.com:{REPO}", f"ssh://git@github.com/{REPO}"):
        raise ValueError(f"Set origin to {REPO} before publishing.")
    head = run("git", "rev-parse", "HEAD")
    remote_ref = run("git", "ls-remote", "origin", "refs/heads/main").split()
    if not remote_ref or remote_ref[0] != head:
        raise ValueError("Push main before publishing.")
    return head


def publish(apk, notes_file):
    head = checked_head()
    manifest = verify_release(apk, notes_file)
    previous = json.loads(FEED.read_text()) if FEED.exists() else None
    if previous and previous["versionCode"] >= manifest["versionCode"]:
        if previous == manifest:
            print(manifest["url"])
            return
        raise ValueError("Increase versionCode before publishing a new release.")

    name = manifest["versionName"]
    tag = "v" + name
    existing = json.loads(gh("list", "--limit", "100", "--json", "tagName,isDraft,isPrerelease"))
    release = next((item for item in existing if item["tagName"] == tag), None)
    if release:
        target = json.loads(gh("view", tag, "--json", "targetCommitish"))["targetCommitish"]
        if release["isPrerelease"] or target != head:
            remote_tag = run("git", "ls-remote", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}").splitlines()
            if release["isPrerelease"] or not remote_tag or remote_tag[-1].split()[0] != head:
                raise ValueError("This version tag belongs to a different release. Bump the version first.")

    with tempfile.TemporaryDirectory(prefix="ambient-release-") as directory:
        checksum = Path(directory) / (apk.name + ".sha256")
        checksum.write_text(f"{manifest['sha256']}  {apk.name}\n")
        if not release:
            gh("create", tag, "--target", head, "--title", f"Ambient Recorder {name}",
               "--notes-file", str(notes_file), "--draft")
        if not release or release["isDraft"]:
            gh("upload", tag, str(apk), str(checksum), "--clobber")
            gh("edit", tag, "--draft=false", "--latest")
        else:
            gh("download", tag, "--pattern", apk.name, "--dir", directory)
            if hashlib.sha256((Path(directory) / apk.name).read_bytes()).hexdigest() != manifest["sha256"]:
                raise ValueError("The published APK differs from this build. Bump the version first.")

    FEED.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    run("git", "add", str(FEED))
    run("git", "commit", "-m", f"Publish {name} update feed")
    run("git", "push", "origin", "main")
    print(manifest["url"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_subparsers(dest="action", required=True)
    action.add_parser("show", help="Show versionName and versionCode.")
    bump_parser = action.add_parser("bump", help="Bump the version name and increment versionCode.")
    bump_parser.add_argument("part", choices=("major", "minor", "patch"))
    sign_parser = action.add_parser("sign", help="Align and sign the release with the original key.")
    sign_parser.add_argument("source", type=Path)
    sign_parser.add_argument("output", type=Path)
    sign_parser.add_argument("--keystore", type=Path, required=True)
    sign_parser.add_argument("--alias", default="androiddebugkey")
    publish_parser = action.add_parser("publish", help="Publish a signed release and advance the stable feed.")
    publish_parser.add_argument("apk", type=Path)
    publish_parser.add_argument("--notes-file", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == "show":
            name, code = version()
            print(f"{name} ({code})")
        elif args.action == "bump":
            bump(args.part)
        elif args.action == "sign":
            sign(args.source.resolve(), args.output.resolve(), args.keystore.expanduser().resolve(), args.alias)
        else:
            publish(args.apk.resolve(), args.notes_file.resolve())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
