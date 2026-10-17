#!/usr/bin/env python3
"""Print xcertplay's domain-separated MFi identity pin, without printing certificates.

This performs one read-only GET /mfi/certificate. Set XCERTPLAY_MFI_TOKEN in the
environment if the provider requires a bearer token; the token is never printed.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import struct
import sys
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


DOMAIN = b"xcertplay-ultra-auth-identity-v1\x00"
MAX_RESPONSE_BYTES = 1_048_576
MAX_CERTIFICATE_BYTES = 65_525


class NoRedirect(HTTPRedirectHandler):
    """Keep a provider bearer token on the explicitly selected origin."""

    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def _field(value: bytes) -> bytes:
    return len(value).to_bytes(4, "big") + value


def identity_pin(document: dict) -> tuple[str, str]:
    """Match MfiAuthenticationIdentity.fromCaptured for MFI and BAA certificates."""
    kind = document.get("type", "mfi").upper()
    if kind not in ("MFI", "BAA"):
        raise ValueError("unsupported MFi certificate type")
    encoded = document.get("certificate")
    expected_hex = document.get("certificateSha256")
    if not isinstance(encoded, str) or not isinstance(expected_hex, str):
        raise ValueError("certificate response is missing required fields")
    try:
        certificate = base64.b64decode(encoded, validate=True)
        expected_hash = bytes.fromhex(expected_hex)
    except (ValueError, base64.binascii.Error) as error:
        raise ValueError("certificate response has invalid encoding") from error
    if not 1 <= len(certificate) <= MAX_CERTIFICATE_BYTES or len(expected_hash) != 32:
        raise ValueError("certificate response length is invalid")
    if not hmac.compare_digest(hashlib.sha256(certificate).digest(), expected_hash):
        raise ValueError("provider certificate checksum does not match")

    material = [kind.encode("ascii")]
    if kind == "BAA":
        if len(certificate) < 8:
            raise ValueError("BAA package is too short")
        leaf_length, intermediate_length = struct.unpack_from(">II", certificate)
        if (leaf_length == 0 or intermediate_length == 0 or
                8 + leaf_length + intermediate_length != len(certificate)):
            raise ValueError("BAA package has invalid lengths")
        material.extend((
            certificate[8:8 + leaf_length],
            certificate[8 + leaf_length:],
        ))
    else:
        material.append(certificate)
    if any(not 1 <= len(part) <= MAX_CERTIFICATE_BYTES for part in material[1:]):
        raise ValueError("certificate component length is invalid")
    digest = hashlib.sha256(DOMAIN + b"".join(_field(part) for part in material))
    return kind, digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--server", default="http://127.0.0.1:8765",
        help="Remote MFi provider base URL (default: local port 8765)",
    )
    arguments = parser.parse_args()
    parsed = urlparse(arguments.server)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        parser.error("provider URL must use http:// or https://")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        parser.error("plain HTTP is allowed only for the local provider")

    headers = {"Accept": "application/json"}
    token = os.environ.get("XCERTPLAY_MFI_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(arguments.server.rstrip("/") + "/mfi/certificate", headers=headers)
    try:
        with build_opener(NoRedirect).open(request, timeout=5) as response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
        if len(payload) > MAX_RESPONSE_BYTES:
            raise ValueError("provider response is too large")
        document = json.loads(payload)
        if not isinstance(document, dict):
            raise ValueError("provider response must be a JSON object")
        kind, pin = identity_pin(document)
    except Exception as error:
        # Do not include provider response bodies, certificates, tokens or URLs in diagnostics.
        print(f"Cannot read MFi identity pin: {type(error).__name__}", file=sys.stderr)
        return 1
    print(f"certificateType={kind}")
    print(f"identitySha256={pin}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
