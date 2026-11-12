# 목업 10장(docs/02-design/mockups)의 :root 토큰을 Aboard 스타일(DEC-104)로 바꾼다. 화면 구조·클래스는 그대로 둔다.
# 옛 값(이름, 값) → 새 값. 밝은·어두운 블록은 옛 값으로 구분된다(같은 이름도 두 모드 값이 다르다).
# 웹 web/src/index.css·앱 ui/Theme.kt와 같은 값. 한 번만 돌리는 변환이라 다시 돌려도 바뀌는 것이 없다.
import glob, re, sys
from pathlib import Path

# 글래스 아이콘 토큰(--g-*·--glass-*)과 지연·완료·경고 색(--crit-fg·--good-fg·--warn-fg)은 바꾸지 않는다 — 예전 색이 낫다는 사용자 의견(11/13, DEC-104 ⑤)
MAP = {
    ("--canvas", "#ffffff"): "#ffffff", ("--canvas", "#0b1117"): "#141416",
    ("--surface", "#f5f8fb"): "#fafafa", ("--surface", "#0f1620"): "#121214",
    ("--card", "#ffffff"): "#ffffff", ("--card", "#141d27"): "#1c1c1f",
    ("--card-2", "#fbfdfe"): "#fcfcfc", ("--card-2", "#17212c"): "#212124",
    ("--border", "#e3e8ee"): "#ececed", ("--border", "#243240"): "#2c2c30",
    ("--border-strong", "#cfd8e2"): "#cfcfd2", ("--border-strong", "#31424f"): "#45454b",
    ("--ink", "#0e1726"): "#262626", ("--ink", "#eef4fa"): "#f1f1f2",
    ("--body", "#17253d"): "#3a3a3c", ("--body", "#c3d0dc"): "#d6d6d8",
    ("--muted", "#5c6b7c"): "#757577", ("--muted", "#8ea0b2"): "#9b9ba0",
    ("--primary", "#0078d4"): "#008ae8", ("--primary", "#3aa0f0"): "#3aa6f5",
    ("--primary-hover", "#106ebe"): "#0079d0", ("--primary-hover", "#58b0f4"): "#5bb5f7",
    ("--on-primary", "#ffffff"): "#ffffff", ("--on-primary", "#06131d"): "#04131f",
    ("--link", "#0067b8"): "#0077c8", ("--link", "#6bb8f6"): "#6bbcf7",
    ("--dark-action", "#091f2c"): "#262626", ("--dark-action", "#0a1a24"): "#2a2a2e",
    ("--pill-active", "#06161f"): "#262626", ("--pill-active", "#eaf2f9"): "#f1f1f2",
    ("--info-fg", "#0067b8"): "#0069b4", ("--info-fg", "#6bb8f6"): "#8fcfff",
    ("--hero-bg", "linear-gradient(160deg,#eaf3fb 0%,#eef0fb 55%,#f6ecf4 100%)"): "linear-gradient(160deg,#fdf2f8 0%,#f3eefe 50%,#eaf7fb 100%)",
    ("--hero-bg", "linear-gradient(160deg,#0e1a24 0%,#121a2b 55%,#1b1526 100%)"): "linear-gradient(160deg,#1d1720 0%,#18172a 50%,#13201f 100%)",
    # 그림자 대신 테두리·파스텔: 카드 그림자 없음, 떠 있는 것만 옅은 그림자 하나
    ("--shadow-sm", "0 1px 2px rgba(14,23,38,.08),0 1px 1px rgba(14,23,38,.05)"): "none",
    ("--shadow-sm", "0 1px 2px rgba(0,0,0,.4)"): "none",
    ("--shadow-md", "0 2px 10px rgba(14,23,38,.10),0 1px 3px rgba(14,23,38,.06)"): "0 2px 8px rgba(205,205,205,.6)",
    ("--shadow-md", "0 2px 8px rgba(14,23,38,.10),0 1px 3px rgba(14,23,38,.06)"): "0 2px 8px rgba(205,205,205,.6)",
    ("--shadow-md", "0 2px 10px rgba(0,0,0,.45)"): "0 2px 8px rgba(0,0,0,.45)",
    ("--shadow-lg", "0 12px 32px rgba(14,23,38,.14),0 4px 10px rgba(14,23,38,.08)"): "0 4px 16px rgba(205,205,205,.5)",
    ("--shadow-lg", "0 18px 44px rgba(14,23,38,.16),0 6px 14px rgba(14,23,38,.08)"): "0 4px 16px rgba(205,205,205,.5)",
    ("--shadow-lg", "0 14px 36px rgba(0,0,0,.55)"): "0 4px 16px rgba(0,0,0,.5)",
    ("--shadow-lg", "0 18px 44px rgba(0,0,0,.6)"): "0 4px 16px rgba(0,0,0,.5)",
    # 모서리: 입력칸·작은 칸 10px, 카드 24px(버튼은 아래 덧붙인 규칙으로 알약)
    ("--r-md", "4px"): "10px", ("--r-lg", "8px"): "10px", ("--r-xl", "14px"): "24px",
}
EXTRA = "\n  /* DEC-104 Aboard: 버튼은 알약 */\n  .btn{border-radius:var(--r-pill);}\n"
TOKEN = re.compile(r"(--[a-z0-9-]+):([^;}]+)(?=[;}])")

def convert(path: Path) -> int:
    s = path.read_text(encoding="utf-8")
    head, rest = s.split("<style>", 1)
    style, tail = rest.split("</style>", 1)
    n = 0
    def sub(m):
        nonlocal n
        new = MAP.get((m.group(1), m.group(2).strip()))
        if new is None or new == m.group(2).strip():
            return m.group(0)
        n += 1
        return f"{m.group(1)}:{new}"
    style = TOKEN.sub(sub, style)
    if "DEC-104 Aboard" not in style:
        style += EXTRA
    path.write_text(head + "<style>" + style + "</style>" + tail, encoding="utf-8")
    return n

for f in sorted(glob.glob("docs/02-design/mockups/workbrain-*.html")):
    print(Path(f).name, convert(Path(f)))
