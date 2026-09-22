import argparse
import json
import os
import sys
from pathlib import Path

from .registry import run_task


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", required=True)
    args = parser.parse_args()
    request = json.load(sys.stdin)
    result = run_task(
        request["taskType"], request["inputPath"], request["spec"],
        request["inputSha256"], request["imageDigest"],
    )
    target = Path(args.result)
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, separators=(",", ":")), encoding="utf-8")
    os.replace(temporary, target)


if __name__ == "__main__":
    main()
