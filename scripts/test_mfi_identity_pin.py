import base64
import hashlib
import struct
import unittest

from mfi_identity_pin import identity_pin


class MfiIdentityPinTest(unittest.TestCase):
    def response(self, kind: str, certificate: bytes) -> dict:
        return {
            "type": kind,
            "certificate": base64.b64encode(certificate).decode("ascii"),
            "certificateSha256": hashlib.sha256(certificate).hexdigest(),
        }

    def test_mfi_uses_domain_type_and_exact_certificate_bytes(self):
        certificate = b"test-mfi-certificate"
        kind, pin = identity_pin(self.response("mfi", certificate))
        expected = hashlib.sha256(
            b"xcertplay-ultra-auth-identity-v1\0" +
            b"\0\0\0\3MFI" +
            len(certificate).to_bytes(4, "big") + certificate
        ).hexdigest()
        self.assertEqual(("MFI", expected), (kind, pin))
        self.assertNotEqual(hashlib.sha256(certificate).hexdigest(), pin)

    def test_baa_hashes_leaf_and_intermediate_not_package_header(self):
        leaf = b"leaf"
        intermediate = b"intermediate"
        package = struct.pack(">II", len(leaf), len(intermediate)) + leaf + intermediate
        kind, pin = identity_pin(self.response("baa", package))
        expected = hashlib.sha256(
            b"xcertplay-ultra-auth-identity-v1\0" +
            b"\0\0\0\3BAA" +
            len(leaf).to_bytes(4, "big") + leaf +
            len(intermediate).to_bytes(4, "big") + intermediate
        ).hexdigest()
        self.assertEqual(("BAA", expected), (kind, pin))

    def test_bad_checksum_and_package_lengths_fail(self):
        document = self.response("mfi", b"certificate")
        document["certificateSha256"] = "00" * 32
        with self.assertRaises(ValueError):
            identity_pin(document)

        package = struct.pack(">II", 10, 2) + b"short"
        with self.assertRaises(ValueError):
            identity_pin(self.response("baa", package))


if __name__ == "__main__":
    unittest.main()
