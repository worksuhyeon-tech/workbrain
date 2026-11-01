// 회의록 문서 → Word(.docx) 변환(FR-29, Design §3.8 ③ DocumentRenderPort).
// 모양은 사용자 meeting-minutes 스킬의 빌더(assets/build_docx.js·references/formats.md)를 그대로 따른다:
// 지금 Claude에서 받던 Word와 같아야 회사 메일로 보내도 어색하지 않기 때문이다.
// 외부 호출이 없어 비용·동의 게이트 대상이 아니다. 메일 발송(FR-22)도 이 모듈로 첨부를 만든다.
import {
  AlignmentType, BorderStyle, Document, Footer, Header, Packer, PageNumber, Paragraph,
  ShadingType, Table, TableCell, TableRow, TextRun, WidthType,
} from "npm:docx@9.12.0";

/** process-record/document.ts의 cleanDocument가 저장하는 모양과 같다 */
export type MinutesDoc = {
  title: string;
  sections: { heading: string; lines: string[]; table: { columns: string[]; rows: string[][] } | null }[];
};

const NAVY = "1F3864";
const ROW_ALT = "F2F5FA";
const FONT = "맑은 고딕";
const BODY_SIZE = 20;          // half-point = 10pt
const CONTENT_W = 9746;        // A4 - 여백 1080 DXA ×2
const NUMBER_COL_MIN = 620;    // 더 좁으면 '번호' 머리가 '번/호'로 세로 분리됨(스킬 실패 경험)
const COL_MIN = 900;

// [확인 필요]·[미정]은 색 굵은 글씨(스킬 tokens 규칙)
const MARK: Record<string, string> = { "[확인 필요]": "C00000", "[미정]": "808080" };
const MARK_RE = /(\[확인 필요\]|\[미정\])/g;

function runs(text: string, opts: { bold?: boolean; color?: string; size?: number } = {}): TextRun[] {
  const { bold = false, color = "000000", size = BODY_SIZE } = opts;
  return String(text ?? "").split(MARK_RE).filter((p) => p !== "").map((p) =>
    MARK[p]
      ? new TextRun({ text: p, bold: true, color: MARK[p], size, font: FONT })
      : new TextRun({ text: p, bold, color, size, font: FONT })
  );
}

/** 글줄 하나. 맨 앞 '[머리글]'은 굵게(스킬의 핵심 요약·주요 안건 표기), '-'로 시작하면 한 단 들여 쓴다 */
function line(text: string): Paragraph {
  const head = text.match(/^(\[[^\]]+\])(.*)$/s);
  const children = head && !MARK[head[1]] ? [...runs(head[1], { bold: true }), ...runs(head[2])] : runs(text);
  return new Paragraph({
    children,
    spacing: { after: 60 },
    indent: text.trimStart().startsWith("-") ? { left: 240 } : undefined,
  });
}

function sectionTitle(text: string): Paragraph {
  return new Paragraph({
    children: [new TextRun({ text, bold: true, color: NAVY, size: 26, font: FONT })],
    spacing: { before: 320, after: 140 },
    border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: NAVY, space: 4 } },
  });
}

// 스킬 formats.md의 표준 열 폭(합계 9746). 열 이름은 띄어쓰기·'·'/'/' 차이를 무시하고 맞춘다
const STANDARD_WIDTHS: Record<string, number[]> = {
  "항목|내용": [1560, 8186],
  "구분|논의내용|세부내용|비고": [780, 1560, 4970, 2436],
  "번호|결정된내용|근거/배경|비고": [585, 5068, 2144, 1949],
  "번호|담당자|해야할일|기한|비고": [620, 1462, 4483, 1072, 2109],
  "번호|담당자|해야할일|기한|상태|비고": [620, 1350, 4000, 1000, 800, 1976],
  "번호|확인필요내용|확인대상|이유": [620, 3898, 1755, 3473],
};
const columnKey = (columns: string[]) => columns.map((c) => c.replace(/\s+/g, "").replace(/·/g, "/")).join("|");

/** 표준이 아닌 열(가져온 지침의 다른 표)은 칸 평균 글자 수에 비례해 나누고 합계를 정확히 9746에 맞춘다 */
function columnWidths(columns: string[], rows: string[][]): number[] {
  const standard = STANDARD_WIDTHS[columnKey(columns)];
  if (standard) return standard;
  const narrow = columns.map((c, i) => c.length <= 3 && rows.length > 0 && rows.every((r) => (r[i] ?? "").length <= 3));
  const weight = columns.map((c, i) => {
    const cells = rows.map((r) => (r[i] ?? "").length);
    return Math.max(c.length, cells.length ? cells.reduce((a, b) => a + b, 0) / cells.length : 0, 4);
  });
  const fixed = narrow.reduce((sum, n) => sum + (n ? NUMBER_COL_MIN : 0), 0);
  const flexTotal = weight.reduce((sum, w, i) => sum + (narrow[i] ? 0 : w), 0) || 1;
  const widths = columns.map((_, i) =>
    narrow[i] ? NUMBER_COL_MIN : Math.max(COL_MIN, Math.floor(((CONTENT_W - fixed) * weight[i]) / flexTotal))
  );
  // 반올림·최소 폭으로 생긴 차이는 가장 넓은 열에서 덜거나 더한다(합이 다르면 Word에서 표가 밀림)
  const widest = widths.indexOf(Math.max(...widths));
  widths[widest] += CONTENT_W - widths.reduce((a, b) => a + b, 0);
  return widths;
}

function cell(text: string, width: number, kind: "header" | "alt" | "plain", center = false): TableCell {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    shading: { type: ShadingType.CLEAR, fill: kind === "header" ? NAVY : kind === "alt" ? ROW_ALT : "FFFFFF", color: "auto" },
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({
      children: runs(text, { bold: kind === "header", color: kind === "header" ? "FFFFFF" : "000000" }),
      alignment: kind === "header" || center ? AlignmentType.CENTER : AlignmentType.LEFT,
      spacing: { after: 0 },
    })],
  });
}

function table(columns: string[], rows: string[][]): Table {
  const widths = columnWidths(columns, rows);
  const numberFirst = columns[0]?.replace(/\s+/g, "") === "번호";
  return new Table({
    columnWidths: widths,
    width: { size: CONTENT_W, type: WidthType.DXA },
    rows: [
      new TableRow({ tableHeader: true, children: columns.map((c, i) => cell(c, widths[i], "header")) }),
      ...rows.map((r, ri) => new TableRow({
        children: columns.map((_, i) => cell(r[i] ?? "", widths[i], ri % 2 === 1 ? "alt" : "plain", i === 0 && numberFirst)),
      })),
    ],
  });
}

/** draft: 승인 전 문서는 머리글에 '초안 — 검토 전'을 넣는다(Design §3.8 ③) */
export async function renderMinutesDocx(doc: MinutesDoc, opts: { draft: boolean }): Promise<Uint8Array> {
  const body: (Paragraph | Table)[] = [];
  if (doc.title) {
    body.push(new Paragraph({
      children: [new TextRun({ text: doc.title, bold: true, color: NAVY, size: 36, font: FONT })],
      spacing: { after: 200 },
      border: { bottom: { style: BorderStyle.SINGLE, size: 18, color: NAVY, space: 6 } },
    }));
  }
  for (const s of doc.sections) {
    body.push(sectionTitle(s.heading));
    s.lines.forEach((l) => body.push(line(l)));
    if (s.table?.columns.length) {
      // 글줄 뒤에 표가 붙으면 조금 띄운다
      if (s.lines.length) body.push(new Paragraph({ children: [], spacing: { after: 60 } }));
      body.push(table(s.table.columns, s.table.rows));
    }
  }

  const document = new Document({
    styles: { default: { document: { run: { font: FONT, size: BODY_SIZE } } } },
    sections: [{
      properties: { page: { margin: { top: 1080, right: 1080, bottom: 1080, left: 1080 } } },
      headers: opts.draft
        ? { default: new Header({ children: [new Paragraph({
            alignment: AlignmentType.RIGHT,
            children: [new TextRun({ text: "초안 — 검토 전", size: 18, color: "808080", font: FONT })],
          })] }) }
        : undefined,
      footers: { default: new Footer({ children: [new Paragraph({
        alignment: AlignmentType.CENTER,
        children: [new TextRun({ children: [PageNumber.CURRENT], size: 18, color: "808080", font: FONT })],
      })] }) },
      children: body,
    }],
  });
  return new Uint8Array(await Packer.toBuffer(document));
}
