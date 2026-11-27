import type { SecondaryKeyLogic, TavernCharacterCard, WorldbookEntry } from './types';

export interface WorldbookMatchTrace {
  entry: WorldbookEntry;
  triggerReason: string;
  recursionDepth: number;
  includedInBudget: boolean;
  compressed: boolean;
}

export interface WorldbookEvaluationResult {
  matchedTraces: WorldbookMatchTrace[];
  injectedText: string;
  totalCharsUsed: number;
  charBudget: number;
  truncatedCount: number;
}

export function checkWorldbookTrigger(entry: WorldbookEntry, corpus: string): string | null {
  const normalized = corpus.toLowerCase();
  const primaryHits = entry.primaryKeys
    .map((k) => k.trim())
    .filter((k) => k.length > 0 && normalized.includes(k.toLowerCase()));
  const secondaryClean = entry.secondaryKeys.map((k) => k.trim()).filter((k) => k.length > 0);
  const secondaryHits = secondaryClean.filter((k) => normalized.includes(k.toLowerCase()));

  switch (entry.secondaryLogic) {
    case 'NONE':
      return primaryHits.length > 0 ? `命中主词: ${primaryHits.join('/')}` : null;
    case 'AND_ANY':
      return primaryHits.length > 0 && (secondaryClean.length === 0 || secondaryHits.length > 0)
        ? `主词(${primaryHits[0]}) AND 次级(${secondaryHits.join('/')})`
        : null;
    case 'AND_ALL':
      return primaryHits.length > 0 && secondaryClean.length > 0 && secondaryHits.length === secondaryClean.length
        ? `主词(${primaryHits[0]}) AND 全部次级(${secondaryHits.join('+')})`
        : null;
    case 'OR':
      if (primaryHits.length > 0) return `主词命中: ${primaryHits[0]}`;
      if (secondaryHits.length > 0) return `OR 次级命中: ${secondaryHits[0]}`;
      return null;
    case 'NOT_ANY':
      return primaryHits.length > 0 && secondaryHits.length === 0
        ? `主词(${primaryHits[0]}) 且排除次级词`
        : null;
    case 'NOT_ALL':
      return primaryHits.length > 0 && (secondaryClean.length === 0 || secondaryHits.length < secondaryClean.length)
        ? `主词(${primaryHits[0]}) 且未同时命中全部排除词`
        : null;
  }
}

/**
 * Full Tavern-grade Worldbook Evaluator:
 * - Always-active entries
 * - Primary keys + Secondary AND/OR/NOT conditions
 * - Priority sorting
 * - Cycle-safe recursive scanning up to maxRecursionDepth
 * - Char budget enforcement with overflow compression
 */
export function evaluateWorldbook(
  entries: WorldbookEntry[],
  scanText: string,
  charBudget = 2400,
  maxRecursionDepth = 3,
): WorldbookEvaluationResult {
  const activeEntries = entries.filter((e) => e.enabled);
  const matchedById = new Map<string, { entry: WorldbookEntry; reason: string; depth: number }>();

  for (const entry of activeEntries) {
    if (entry.alwaysActive) {
      matchedById.set(entry.id, { entry, reason: '常驻条目', depth: 0 });
      continue;
    }
    const reason = checkWorldbookTrigger(entry, scanText);
    if (reason) {
      matchedById.set(entry.id, { entry, reason, depth: 0 });
    }
  }

  let frontier = Array.from(matchedById.values())
    .map((m) => m.entry)
    .filter((e) => e.recursiveScan && !e.preventFurtherRecursion);

  let depth = 1;
  while (depth <= maxRecursionDepth && frontier.length > 0) {
    const recursionCorpus =
      scanText + '\n' + frontier.map((e) => `${e.title}\n${e.content}`).join('\n');
    const newlyMatched: WorldbookEntry[] = [];
    for (const entry of activeEntries) {
      if (matchedById.has(entry.id)) continue;
      const reason = checkWorldbookTrigger(entry, recursionCorpus);
      if (reason) {
        matchedById.set(entry.id, { entry, reason: `递归第${depth}层 · ${reason}`, depth });
        if (entry.recursiveScan && !entry.preventFurtherRecursion) {
          newlyMatched.push(entry);
        }
      }
    }
    frontier = newlyMatched;
    depth++;
  }

  const ordered = Array.from(matchedById.values()).sort((a, b) => {
    if (a.entry.alwaysActive !== b.entry.alwaysActive) return a.entry.alwaysActive ? -1 : 1;
    if (a.entry.priority !== b.entry.priority) return b.entry.priority - a.entry.priority;
    return a.depth - b.depth;
  });

  let usedChars = 0;
  let truncatedCount = 0;
  const traces: WorldbookMatchTrace[] = [];
  const injectedBlocks: string[] = [];

  for (const item of ordered) {
    const block = `【世界书 · ${item.entry.title}】\n${item.entry.content.trim()}`;
    const remaining = charBudget - usedChars;
    if (block.length <= remaining) {
      usedChars += block.length + 2;
      injectedBlocks.push(block);
      traces.push({
        entry: item.entry,
        triggerReason: item.reason,
        recursionDepth: item.depth,
        includedInBudget: true,
        compressed: false,
      });
    } else if (remaining >= 90) {
      const compressedBody =
        item.entry.content.trim().slice(0, Math.max(40, remaining - item.entry.title.length - 36)) + '…（预算截断）';
      const compressedBlock = `【世界书 · ${item.entry.title}】\n${compressedBody}`;
      usedChars += compressedBlock.length + 2;
      injectedBlocks.push(compressedBlock);
      traces.push({
        entry: item.entry,
        triggerReason: item.reason,
        recursionDepth: item.depth,
        includedInBudget: true,
        compressed: true,
      });
    } else {
      truncatedCount++;
      traces.push({
        entry: item.entry,
        triggerReason: item.reason,
        recursionDepth: item.depth,
        includedInBudget: false,
        compressed: false,
      });
    }
  }

  return {
    matchedTraces: traces,
    injectedText: injectedBlocks.join('\n\n'),
    totalCharsUsed: usedChars,
    charBudget,
    truncatedCount,
  };
}

export function importTavernWorldbookJson(jsonText: string): WorldbookEntry[] {
  const parsed = JSON.parse(jsonText) as { entries?: Record<string, Record<string, unknown>> | Array<Record<string, unknown>> };
  if (!parsed || !parsed.entries) {
    throw new Error('JSON 中缺少 entries 字段');
  }
  const rawList = Array.isArray(parsed.entries) ? parsed.entries : Object.values(parsed.entries);

  return rawList
    .map((item, idx): WorldbookEntry | null => {
      const content = String(item.content ?? '').trim();
      if (!content) return null;
      const title = String(item.comment ?? item.name ?? item.title ?? `导入条目 ${idx + 1}`).trim();
      const primaryKeys = normalizeKeyList(item.key ?? item.keys);
      const secondaryKeys = normalizeKeyList(item.keysecondary ?? item.secondary_keys);
      const constant = Boolean(item.constant ?? item.alwaysActive ?? false);
      const enabled = !Boolean(item.disable) && (item.enabled === undefined ? true : Boolean(item.enabled));
      const priority = Number(item.order ?? item.priority ?? 50);
      const selective = item.selective === undefined ? secondaryKeys.length > 0 : Boolean(item.selective);
      const logicCode = Number(item.selectiveLogic ?? 0);
      const secondaryLogic: SecondaryKeyLogic = !selective || secondaryKeys.length === 0
        ? 'NONE'
        : logicCode === 1
          ? 'NOT_ALL'
          : logicCode === 2
            ? 'NOT_ANY'
            : logicCode === 3
              ? 'AND_ALL'
              : 'AND_ANY';

      return {
        id: `wb_imp_${Date.now()}_${idx}`,
        title,
        content,
        enabled,
        alwaysActive: constant,
        primaryKeys,
        secondaryKeys,
        secondaryLogic,
        priority: Number.isFinite(priority) ? priority : 50,
        recursiveScan: true,
        preventFurtherRecursion: Boolean(item.preventRecursion ?? false),
        category: '酒馆导入',
      };
    })
    .filter((x): x is WorldbookEntry => x !== null);
}

function normalizeKeyList(raw: unknown): string[] {
  if (Array.isArray(raw)) {
    return raw.map((x) => String(x).trim()).filter(Boolean);
  }
  if (typeof raw === 'string') {
    return raw.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
  }
  return [];
}

// --- Tavern Character Card V1/V2/V3 JSON & PNG tEXt (chara / ccv3) Codec ---

const PNG_SIG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

export function parseTavernCardJson(jsonText: string): TavernCharacterCard {
  const root = JSON.parse(jsonText) as Record<string, unknown>;
  const data = (root.data && typeof root.data === 'object' ? root.data : root) as Record<string, unknown>;
  const name = String(data.name ?? '').trim();
  if (!name) throw new Error('角色卡缺少 name 字段');
  const tags = Array.isArray(data.tags) ? data.tags.map((t) => String(t).trim()).filter(Boolean) : [];

  return {
    id: `card_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    specVersion: String(root.spec ?? 'chara_card_v2'),
    name,
    description: String(data.description ?? ''),
    personality: String(data.personality ?? ''),
    scenario: String(data.scenario ?? ''),
    firstMessage: String(data.first_mes ?? ''),
    tags,
  };
}

export function encodeTavernCardToPngBytes(card: TavernCharacterCard): Uint8Array {
  const json = JSON.stringify({
    spec: 'chara_card_v2',
    spec_version: '2.0',
    data: {
      name: card.name,
      description: card.description,
      personality: card.personality,
      scenario: card.scenario,
      first_mes: card.firstMessage,
      tags: card.tags,
    },
  });
  const utf8Bytes = new TextEncoder().encode(json);
  let binary = '';
  utf8Bytes.forEach((b) => {
    binary += String.fromCharCode(b);
  });
  const b64 = btoa(binary);

  const ihdr = new Uint8Array([0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0]);
  const textPayload = new TextEncoder().encode(`chara\0${b64}`);
  const chunks = [
    PNG_SIG,
    buildPngChunk('IHDR', ihdr),
    buildPngChunk('tEXt', textPayload),
    buildPngChunk('IEND', new Uint8Array(0)),
  ];
  const total = chunks.reduce((s, c) => s + c.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.length;
  }
  return out;
}

export function parseTavernCardFromPngBytes(bytes: Uint8Array): TavernCharacterCard {
  if (bytes.length < 16 || !PNG_SIG.every((v, idx) => bytes[idx] === v)) {
    throw new Error('无效的 PNG 文件头');
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let offset = 8;
  let charaB64: string | null = null;
  let ccv3B64: string | null = null;

  while (offset + 12 <= bytes.length) {
    const length = view.getUint32(offset, false);
    const type = String.fromCharCode(bytes[offset + 4], bytes[offset + 5], bytes[offset + 6], bytes[offset + 7]);
    const dataStart = offset + 8;
    const dataEnd = dataStart + length;
    if (dataEnd + 4 > bytes.length) break;

    if (type === 'tEXt') {
      const slice = bytes.subarray(dataStart, dataEnd);
      const nulIdx = slice.indexOf(0);
      if (nulIdx > 0) {
        const keyword = new TextDecoder('latin1').decode(slice.subarray(0, nulIdx)).toLowerCase();
        const value = new TextDecoder('latin1').decode(slice.subarray(nulIdx + 1)).trim();
        if (keyword === 'ccv3') ccv3B64 = value;
        else if (keyword === 'chara') charaB64 = value;
      }
    }
    offset = dataEnd + 4;
    if (type === 'IEND') break;
  }

  const chosen = ccv3B64 ?? charaB64;
  if (!chosen) {
    throw new Error('PNG 中未包含酒馆角色卡元数据块 (chara / ccv3)');
  }
  const binary = atob(chosen);
  const decodedBytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    decodedBytes[i] = binary.charCodeAt(i);
  }
  const decodedJson = new TextDecoder('utf-8').decode(decodedBytes);
  return parseTavernCardJson(decodedJson);
}

function buildPngChunk(type: string, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + data.length);
  const view = new DataView(out.buffer);
  view.setUint32(0, data.length, false);
  for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
  out.set(data, 8);
  const crc = crc32(out.subarray(4, 8 + data.length));
  view.setUint32(8 + data.length, crc >>> 0, false);
  return out;
}

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c >>> 0;
  }
  return table;
})();

export function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = CRC_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
