import hashlib
import importlib.metadata
import json
import os
import platform
import sys
import time
from pathlib import Path
from typing import Any


def run_demo_sleep_hash(
    input_path: str,
    spec: dict[str, Any],
    input_sha256: str,
    image_digest: str,
) -> dict[str, Any]:
    started = time.perf_counter()
    sleep_seconds = float(spec.get("sleepSeconds", 0))
    if sleep_seconds < 0 or sleep_seconds > 30:
        raise ValueError("sleepSeconds must be between 0 and 30")
    actual_sha = _verified_sha256(input_path, input_sha256)
    print(f"demo.sleep_hash input sha256={actual_sha}", flush=True)
    time.sleep(sleep_seconds)
    duration = time.perf_counter() - started
    return {
        "summary": {
            "taskType": "demo.sleep_hash",
            "sha256": actual_sha,
            "durationSeconds": duration,
        },
        "manifest": _manifest(
            "demo.sleep_hash", input_path, actual_sha, spec, image_digest, duration, {}
        ),
    }


def run_pyscf_single_point(
    input_path: str,
    spec: dict[str, Any],
    input_sha256: str,
    image_digest: str,
) -> dict[str, Any]:
    actual_sha = _verified_sha256(input_path, input_sha256)
    atom_block = _xyz_atom_block(Path(input_path).read_text(encoding="utf-8"))
    method = str(spec["method"]).strip()
    basis = str(spec["basis"]).strip()
    started = time.perf_counter()

    from pyscf import dft, gto, scf

    molecule = gto.M(
        atom=atom_block,
        unit="Angstrom",
        basis=basis,
        charge=int(spec["charge"]),
        spin=int(spec["spin"]),
        max_memory=int(spec["maxMemoryMb"]),
        verbose=4,
    )
    normalized_method = method.upper()
    if normalized_method == "RHF":
        calculation = scf.RHF(molecule)
    elif normalized_method == "ROHF":
        calculation = scf.ROHF(molecule)
    elif normalized_method == "UHF":
        calculation = scf.UHF(molecule)
    elif normalized_method.startswith("RKS:"):
        calculation = dft.RKS(molecule)
        calculation.xc = method.split(":", 1)[1].strip()
    elif normalized_method.startswith("UKS:"):
        calculation = dft.UKS(molecule)
        calculation.xc = method.split(":", 1)[1].strip()
    else:
        raise ValueError("method must be RHF, ROHF, UHF, RKS:<functional>, or UKS:<functional>")

    calculation.max_memory = int(spec["maxMemoryMb"])
    print(f"Starting {method}/{basis} single-point calculation", flush=True)
    energy = float(calculation.kernel())
    duration = time.perf_counter() - started
    converged = bool(calculation.converged)
    print(f"SCF finished: converged={converged} energy={energy:.15f} Eh", flush=True)
    return {
        "summary": {
            "taskType": "pyscf.single_point",
            "method": method,
            "basis": basis,
            "energyHartree": energy,
            "converged": converged,
            "durationSeconds": duration,
        },
        "manifest": _manifest(
            "pyscf.single_point", input_path, actual_sha, spec, image_digest, duration,
            {"pyscf": importlib.metadata.version("pyscf")},
        ),
    }


def _verified_sha256(input_path: str, expected_sha256: str) -> str:
    digest = hashlib.sha256(Path(input_path).read_bytes()).hexdigest()
    if digest != expected_sha256:
        raise ValueError("Input artifact checksum does not match the immutable job snapshot")
    return digest


def _xyz_atom_block(value: str) -> str:
    lines = value.splitlines()
    if len(lines) < 3:
        raise ValueError("XYZ input is incomplete")
    try:
        atom_count = int(lines[0].strip())
    except ValueError as error:
        raise ValueError("XYZ input has an invalid atom count") from error
    coordinates = [line.strip() for line in lines[2:] if line.strip()]
    if len(coordinates) != atom_count:
        raise ValueError("XYZ atom count does not match its coordinate records")
    return "\n".join(coordinates)


def _manifest(
    task_type: str,
    input_path: str,
    input_sha256: str,
    spec: dict[str, Any],
    image_digest: str,
    duration: float,
    packages: dict[str, str],
) -> dict[str, Any]:
    stable_spec = json.dumps(spec, sort_keys=True, separators=(",", ":")).encode()
    return {
        "task": {"type": task_type, "durationSeconds": duration},
        "artifacts": [{
            "role": "input",
            "path": os.path.basename(input_path),
            "sha256": input_sha256,
            "sizeBytes": Path(input_path).stat().st_size,
        }],
        "environment": {
            "workerImageDigest": image_digest,
            "python": sys.version.split()[0],
            "platform": platform.platform(),
            "packages": packages,
            "specSha256": hashlib.sha256(stable_spec).hexdigest(),
        },
    }
