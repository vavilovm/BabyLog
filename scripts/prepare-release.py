"""Reserve a unique patch tag before building a release; never modifies main."""
import json
import os
import re
import subprocess
from pathlib import Path


def parse_version(tag):
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", tag)
    if not match:
        raise ValueError(f"Expected a stable semantic version, got {tag!r}")
    return tuple(map(int, match.groups()))


def version_code(version):
    major, minor, patch = version
    if minor >= 1000 or patch >= 1000:
        raise ValueError("Minor and patch must be below 1000")
    code = major * 1_000_000 + minor * 1000 + patch
    if not 0 < code <= 2_100_000_000:
        raise ValueError("Version is outside Android's versionCode range")
    return code


def next_version(tags, baseline):
    versions = [baseline]
    for tag in tags:
        try:
            versions.append(parse_version(tag))
        except ValueError:
            pass
    major, minor, patch = max(versions)
    result = (major, minor, patch + 1)
    version_code(result)
    return result


def prepare():
    if os.environ["GITHUB_EVENT_NAME"] == "release":
        version = parse_version(os.environ["GITHUB_REF_NAME"])
    else:
        repository = os.environ["GITHUB_REPOSITORY"]
        source = Path("app/build.gradle.kts").read_text()
        baseline = parse_version(re.search(r'versionName\s*=.*?"(\d+\.\d+\.\d+)"', source).group(1))
        for attempt in range(5):
            response = subprocess.check_output(
                ["gh", "api", f"repos/{repository}/git/matching-refs/tags/v"], text=True
            )
            refs = json.loads(response)
            tags = [item["ref"].removeprefix("refs/tags/") for item in refs]
            existing = []
            for item in refs:
                if item["object"]["sha"] == os.environ["GITHUB_SHA"]:
                    try:
                        existing.append(parse_version(item["ref"].removeprefix("refs/tags/")))
                    except ValueError:
                        pass
            if existing:
                version = max(existing)
                break
            version = next_version(tags, baseline)
            tag = "v" + ".".join(map(str, version))
            result = subprocess.run(
                ["gh", "api", "--method", "POST", f"repos/{repository}/git/refs",
                 "-f", f"ref=refs/tags/{tag}", "-f", f"sha={os.environ['GITHUB_SHA']}"],
                capture_output=True, text=True,
            )
            if result.returncode == 0:
                break
            if "Reference already exists" not in result.stderr + result.stdout:
                raise RuntimeError(result.stderr + result.stdout)
        else:
            raise RuntimeError("Could not reserve a release tag after concurrent releases")
    name = ".".join(map(str, version))
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"name={name}\ntag=v{name}\ncode={version_code(version)}\n")
    print(f"Preparing v{name} (Android versionCode {version_code(version)})")


if __name__ == "__main__":
    prepare()
