# 손글씨 글꼴(앱 res/font/hand_accent.ttf)을 화면 부제에 쓰는 글자만 담아 woff2로 줄인다.
# 전체 파일은 3MB라 웹에서 그대로 쓰기 무겁다. 부제를 바꾸거나 더하면 HEADS를 고치고 다시 돌린다:
#   python web/scripts/subset-hand-font.py
from pathlib import Path
from fontTools import subset

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "android/app/src/main/res/font/hand_accent.ttf"
OUT = ROOT / "web/public/fonts/hand-accent.woff2"

# 웹 목업의 손글씨 문구(.hand·.brand .tag)와 앱 PageHeader 부제
HEADS = [
    "일의 기억", "오늘 챙길 것만 모았어요", "오늘 할 일 끝!", "하고 있는 일 한눈에", "회의·통화·메모",
    "말한 건 다 기억해 둘게요", "꼼꼼히 살펴봐요", "검토 끝! 깔끔해요", "반영 완료!", "거의 다 왔어요",
    "그때 뭐라고 했더라", "내 방식대로 맞추기", "천천히 둘러봐도 돼요", "다시 오셨네요", "회사 제출용 표 초안", "자주 쓰는 말",
    "내 스킬로 회의록 쓰기",
]

text = "".join(sorted(set("".join(HEADS)) | set(" .,!?·~()0123456789")))
opts = subset.Options()
opts.flavor = "woff2"
opts.layout_features = ["*"]
font = subset.load_font(str(SRC), opts)
sub = subset.Subsetter(opts)
sub.populate(text=text)
sub.subset(font)
subset.save_font(font, str(OUT), opts)
print(OUT.name, round(OUT.stat().st_size / 1024), "KB,", len(set(text)), "chars")
