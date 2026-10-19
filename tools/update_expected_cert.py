#!/usr/bin/env python3
"""把正式签名证书的 SHA-256 写进 xycore.cpp 的 EXPECTED_CERT_XOR。

用法:
    python3 tools/update_expected_cert.py <keystore> <alias> <storepass>

需要 JAVA_HOME 指向 JDK 17（或 PATH 里有 keytool）。
改完后重新构建 release APK；debug 包仍使用原来的调试证书哈希。
"""
import re
import subprocess
import sys
from pathlib import Path

XOR_KEY = 0x5A
CPP = Path(__file__).resolve().parent.parent / "app/src/main/cpp/xycore.cpp"


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    keystore, alias, storepass = sys.argv[1:4]
    out = subprocess.run(
        ["keytool", "-list", "-v", "-keystore", keystore,
         "-alias", alias, "-storepass", storepass],
        check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    ).stdout
    m = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", out)
    if not m:
        print("未找到 SHA256 指纹", file=sys.stderr)
        return 1
    digest = bytes.fromhex(m.group(1).replace(":", ""))
    if len(digest) != 32:
        print("SHA256 长度不是 32 字节", file=sys.stderr)
        return 1

    values = ", ".join(f"0x{b ^ XOR_KEY:02x}" for b in digest)
    array = "static const uint8_t EXPECTED_CERT_XOR[32] = {\n" \
            f"        {values}\n" \
            "    };"
    text = CPP.read_text(encoding="utf-8")
    pattern = re.compile(
        r"static const uint8_t EXPECTED_CERT_XOR\[32\] = \{.*?\};",
        re.S,
    )
    if not pattern.search(text):
        print("xycore.cpp 中未找到 EXPECTED_CERT_XOR", file=sys.stderr)
        return 1
    CPP.write_text(pattern.sub(array, text, count=1), encoding="utf-8")
    print(f"已写入证书 SHA-256: {m.group(1)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
