import argparse
import json
import os
import sys
from pathlib import Path

from .registry import run_task
from .errors import TaskExecutionError


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", required=True)
    args = parser.parse_args()
    request = json.load(sys.stdin)
    failed = False
    try:
        result = run_task(
            request["taskType"], request["inputPath"], request["spec"],
            request["inputSha256"], request["imageDigest"],
        )
    except Exception as error:
        failed = True
        code = error.code if isinstance(error, TaskExecutionError) else (
            "INVALID_INPUT" if isinstance(error, (ValueError, FileNotFoundError)) else
            "INVALID_CONFIG" if isinstance(error, KeyError) else "TASK_FAILED")
        result = {"error": {"code": code, "message": str(error), "type": type(error).__name__}}
    target = Path(args.result)
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, separators=(",", ":")), encoding="utf-8")
    os.replace(temporary, target)
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
