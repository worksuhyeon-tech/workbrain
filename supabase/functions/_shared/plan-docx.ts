// 업무계획 → Word(.docx) 표(FR-33, DEC-81 ③④, Design §3.13).
// 회사 양식에 표를 그대로 복사해 붙이도록 세로 A4에 꽉 찬 4칸 표 한 줄(내 줄)만 만든다:
// '전주 (M.D ~ M.D) | 담당자 | 금주 (M.D ~ M.D) | 담당자'. 글씨체는 바탕체, □ 굵게 / - 보통 / * 작은 파란(#0000FF) 글씨.
// 글자 크기는 회사 양식대로 주간 10pt·* 8pt, 월간 12pt·* 8pt(DEC-88, 사용자가 견본을 양식에 맞춰 보고 정함).
// 회사 양식 이미지는 모양만 참고했고 저장소에 넣지 않는다.
import {
  AlignmentType, Document, Packer, Paragraph, ShadingType, Table, TableCell, TableRow, TextRun, VerticalAlign, WidthType,
} from "npm:docx@9.12.0";
import type { PlanCell, PlanLine, WorkPlan } from "./work-plan.ts";

const FONT = "바탕체";
const NOTE_COLOR = "0000FF";
const HEAD_FILL = "D9D9D9";
const CONTENT_W = 9746;          // A4(11906) - 여백 1080 ×2

/** 글자 크기(half-point)와 칸 폭. 담당자 칸은 한글 3글자(글자 크기 ×20 DXA ×3) + 칸 안 여백(좌우 100) + 여유 */
type Look = { main: number; note: number; ownerW: number; textW: number };
function lookOf(kind: WorkPlan["kind"]): Look {
  const main = kind === "weekly" ? 20 : 24;          // 10pt · 12pt
  const ownerW = (main / 2) * 20 * 3 + 300;
  return { main, note: 16, ownerW, textW: (CONTENT_W - ownerW * 2) / 2 };   // * 8pt
}

/** 한 줄 모양. '- '·'* ' 뒤 글이 줄을 넘기면 기호 뒤에 맞춰 들여 쓴다(내어쓰기) */
function lineParagraph(line: PlanLine, look: Look): Paragraph {
  if (line.level === "head") {
    return new Paragraph({
      children: [new TextRun({ text: `□ ${line.text}`, bold: true, size: look.main, font: FONT })],
      indent: { left: look.main * 10, hanging: look.main * 10 },
      spacing: { before: 80, after: 20 },
    });
  }
  if (line.level === "item") {
    return new Paragraph({
      children: [new TextRun({ text: `- ${line.text}`, size: look.main, font: FONT })],
      indent: { left: look.main * 15, hanging: look.main * 10 },
      spacing: { after: 20 },
    });
  }
  return new Paragraph({
    children: [new TextRun({ text: `* ${line.text}`, size: look.note, color: NOTE_COLOR, font: FONT })],
    indent: { left: look.main * 25, hanging: look.note * 10 },
    spacing: { after: 20 },
  });
}

function headCell(text: string, width: number, look: Look): TableCell {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    shading: { type: ShadingType.CLEAR, fill: HEAD_FILL, color: "auto" },
    verticalAlign: VerticalAlign.CENTER,
    margins: { top: 60, bottom: 60, left: 100, right: 100 },
    children: [new Paragraph({
      alignment: AlignmentType.CENTER,
      children: [new TextRun({ text, bold: true, size: look.main, font: FONT })],
    })],
  });
}

function bodyCell(cell: PlanCell, look: Look): TableCell {
  // 빈 칸도 Word가 줄을 만들 수 있게 빈 문단 하나는 둔다
  const children = cell.lines.length ? cell.lines.map((l) => lineParagraph(l, look)) : [new Paragraph({ children: [] })];
  return new TableCell({
    width: { size: look.textW, type: WidthType.DXA },
    margins: { top: 80, bottom: 120, left: 100, right: 100 },
    children,
  });
}

function ownerCell(name: string, look: Look): TableCell {
  return new TableCell({
    width: { size: look.ownerW, type: WidthType.DXA },
    verticalAlign: VerticalAlign.CENTER,
    margins: { top: 80, bottom: 80, left: 100, right: 100 },
    children: [new Paragraph({
      alignment: AlignmentType.CENTER,
      children: [new TextRun({ text: name, size: look.main, font: FONT })],
    })],
  });
}

export async function renderPlanDocx(plan: WorkPlan): Promise<Uint8Array<ArrayBuffer>> {
  const look = lookOf(plan.kind);
  const widths = [look.textW, look.ownerW, look.textW, look.ownerW];
  const table = new Table({
    columnWidths: widths,
    width: { size: CONTENT_W, type: WidthType.DXA },
    rows: [
      new TableRow({
        tableHeader: true,
        children: [
          headCell(plan.previous.label, look.textW, look), headCell("담당자", look.ownerW, look),
          headCell(plan.next.label, look.textW, look), headCell("담당자", look.ownerW, look),
        ],
      }),
      new TableRow({
        children: [bodyCell(plan.previous, look), ownerCell(plan.ownerName, look), bodyCell(plan.next, look), ownerCell(plan.ownerName, look)],
      }),
    ],
  });

  const document = new Document({
    styles: { default: { document: { run: { font: FONT, size: look.main } } } },
    sections: [{
      properties: { page: { margin: { top: 1080, right: 1080, bottom: 1080, left: 1080 } } },
      children: [
        new Paragraph({
          children: [new TextRun({ text: plan.title, bold: true, size: look.main + 4, font: FONT })],
          spacing: { after: 160 },
        }),
        table,
      ],
    }],
  });
  // Response 본문 타입(ArrayBuffer 기반)에 맞게 복사본으로 돌려준다
  return Uint8Array.from(await Packer.toBuffer(document));
}
