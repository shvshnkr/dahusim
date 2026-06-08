import re
from pathlib import Path

src = Path(r"C:\Users\user\go\pkg\mod\github.com\shtorm-7\sing-box-extended@v1.13.12-extended-2.4.0.0.20260607061226-9c80cf371c19\option\v2ray_transport.go")
dst = Path(__file__).resolve().parent / "overlay" / "option" / "v2ray_transport.go"

content = src.read_text(encoding="utf-8")
content = re.sub(r"\tKCPOptions\s+V2RayKCPOptions\s+`json:\"-\"`\n", "", content)
content = re.sub(r"\tcase C\.V2RayTransportTypeKCP:\n\t\tv = o\.KCPOptions\n", "", content)
content = re.sub(r"\tcase C\.V2RayTransportTypeKCP:\n\t\tv = &o\.KCPOptions\n", "", content)
idx = content.find("type V2RayKCPOptions struct")
if idx >= 0:
    content = content[:idx].rstrip() + "\n"

dst.parent.mkdir(parents=True, exist_ok=True)
dst.write_text(content, encoding="utf-8")
print(f"written {len(content)} bytes to {dst}")
