/**
 * SwingFit Firebase Functions v2 — index.js
 * - 지역: asia-northeast3 (서울)
 * - Firestore 경로 규약:
 *   • swing-analyses : 스윙 분석 결과 문서
 *   • ball-analyses  : 볼/궤적 분석 결과 문서
 *   • users/{uid}/stats/summary : 사용자 요약 통계(최신 50건 집계)
 *
 * 환경변수:
 *   firebase functions:secrets:set GEMINI_API_KEY
 */

const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

// ---- Gemini SDK -------------------------------------------------------------
/**
 * 패키지: @google/generative-ai
 * npm i @google/generative-ai
 */
const { GoogleGenerativeAI } = require("@google/generative-ai");
const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");

// ---- Firebase Admin ---------------------------------------------------------
admin.initializeApp();
const db = admin.firestore();

// ---- 공통 옵션 --------------------------------------------------------------
const fnOpts = {
  region: "asia-northeast3",
  timeoutSeconds: 60,
  memory: "512MiB",
  secrets: [GEMINI_API_KEY],
};

// ---- 유틸리티 ---------------------------------------------------------------
const assertAuth = (request) => {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "로그인이 필요합니다.");
  }
  return request.auth.uid;
};

const assertString = (value, field) => {
  if (typeof value !== "string" || !value.trim()) {
    throw new HttpsError("invalid-argument", `${field} 필드는 비어있지 않은 문자열이어야 합니다.`);
  }
};

const assertObject = (value, field) => {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", `${field} 필드는 객체(Object)여야 합니다.`);
  }
};

const nowTs = () => admin.firestore.FieldValue.serverTimestamp();

// ---- 0) 기본 HTTP 핸들러 ----------------------------------------------------
exports.helloWorld = onRequest(fnOpts, (req, res) => {
  logger.info("helloWorld 호출");
  res.status(200).send("SwingFit Cloud Functions v2 (서울)");
});

// ---- 1) 메시지 샘플 (기존 유지) ---------------------------------------------
exports.addMessage = onCall(fnOpts, async (request) => {
  const uid = assertAuth(request);
  const { text } = request.data || {};
  assertString(text, "text");

  const docRef = await db.collection("messages").add({
    text,
    uid,
    createdAt: nowTs(),
  });

  return { messageId: docRef.id, text };
});

exports.onMessageCreated = onDocumentCreated(
  {
    ...fnOpts,
    document: "messages/{messageId}",
  },
  (event) => {
    const snap = event.data;
    if (!snap) {
      logger.error("onMessageCreated: 데이터 없음");
      return;
    }
    logger.info("새 메시지", {
      messageId: event.params.messageId,
      data: snap.data(),
    });
  }
);

// ---- 2) 분석 결과 저장 (디바이스 → 서버) ------------------------------------
/**
 * createAnalysisRecord
 * - 클라이언트가 로컬 추론 결과/메타데이터를 제출
 * - type: "swing" | "ball"
 * - 예시 data:
 *   {
 *     type: "swing",
 *     videoPath: "gs://.../swing_20251019_....mp4",
 *     model: { name: "yolov8n-fp16", version: "2025-10-16" },
 *     device: { brand: "SAMSUNG", model: "S25 Ultra", soc: "SD8 Elite" },
 *     metrics: {
 *       clubSpeed: 43.2,
 *       tempo: 3.1,
 *       backswingFrames: 120,
 *       impactFrame: 422,
 *       carry: 178.4,
 *       confidence: 0.71
 *     },
 *     notes: "백스윙 탑 이후 인식 보완 실험"
 *   }
 */
exports.createAnalysisRecord = onCall(fnOpts, async (request) => {
  const uid = assertAuth(request);
  const data = request.data || {};
  const { type, videoPath, model, device, metrics, notes } = data;

  if (type !== "swing" && type !== "ball") {
    throw new HttpsError("invalid-argument", 'type은 "swing" 또는 "ball" 이어야 합니다.');
  }
  assertString(videoPath, "videoPath");
  assertObject(model || {}, "model");
  assertObject(device || {}, "device");
  assertObject(metrics || {}, "metrics");

  const coll = type === "swing" ? "swing-analyses" : "ball-analyses";

  const doc = {
    uid,
    type,
    videoPath,
    model,
    device,
    metrics,
    notes: typeof notes === "string" ? notes : "",
    createdAt: nowTs(),
    // 인덱싱/필터 편의용 파생 필드
    clubSpeed: typeof metrics.clubSpeed === "number" ? metrics.clubSpeed : null,
    carry: typeof metrics.carry === "number" ? metrics.carry : null,
    confidence: typeof metrics.confidence === "number" ? metrics.confidence : null,
  };

  const ref = await db.collection(coll).add(doc);
  logger.info(`createAnalysisRecord 저장 완료: ${coll}/${ref.id}`, { uid, type });

  return { id: ref.id, collection: coll };
});

// ---- 3) 분석 생성 트리거: 사용자 통계 갱신 -----------------------------------
/**
 * onAnalysisCreated
 * - swing-analyses, ball-analyses 공통 후처리
 * - 최신 50건만 샘플링 집계(비용/쿼리 제한 고려)
 * - 산출: 평균 구단속도/캐리/신뢰도, 분석 횟수, 최신 모델 버전
 */
const aggregateSummary = async (uid) => {
  const takeN = 50;
  const srcColls = ["swing-analyses", "ball-analyses"];

  let count = 0;
  let speedSum = 0;
  let carrySum = 0;
  let confSum = 0;
  let lastModel = null;
  let lastCreated = null;

  for (const c of srcColls) {
    const qs = await db
      .collection(c)
      .where("uid", "==", uid)
      .orderBy("createdAt", "desc")
      .limit(takeN)
      .get();

    qs.forEach((d) => {
      const x = d.data();
      count += 1;
      if (typeof x.clubSpeed === "number") speedSum += x.clubSpeed;
      if (typeof x.carry === "number") carrySum += x.carry;
      if (typeof x.confidence === "number") confSum += x.confidence;
      if (!lastCreated || (x.createdAt && x.createdAt.toMillis() > lastCreated.toMillis())) {
        lastCreated = x.createdAt || lastCreated;
        lastModel = (x.model && (x.model.version || x.model.name)) || lastModel;
      }
    });
  }

  const summaryRef = db.doc(`users/${uid}/stats/summary`);
  const payload = {
    updatedAt: nowTs(),
    totalAnalysesSampled: count,
    meanClubSpeed: count ? speedSum / count : null,
    meanCarry: count ? carrySum / count : null,
    meanConfidence: count ? confSum / count : null,
    lastModel: lastModel || null,
    lastAnalysisAt: lastCreated || null,
    sampleWindow: 50,
  };
  await summaryRef.set(payload, { merge: true });
  return payload;
};

// swing-analyses 생성 시
exports.onSwingAnalysisCreated = onDocumentCreated(
  { ...fnOpts, document: "swing-analyses/{docId}" },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const { uid } = snap.data() || {};
    if (!uid) return;
    const res = await aggregateSummary(uid);
    logger.info("onSwingAnalysisCreated → summary 갱신", { uid, res });
  }
);

// ball-analyses 생성 시
exports.onBallAnalysisCreated = onDocumentCreated(
  { ...fnOpts, document: "ball-analyses/{docId}" },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const { uid } = snap.data() || {};
    if (!uid) return;
    const res = await aggregateSummary(uid);
    logger.info("onBallAnalysisCreated → summary 갱신", { uid, res });
  }
);

// ---- 4) Gemini 기반 코칭 요청 ----------------------------------------------
/**
 * requestCoachingTips
 * - 최근 분석(스윙 우선, 없으면 볼) 1~3건을 불러와 코칭 팁 생성
 * - 출력 포맷: concise bullet(개조식), actionable
 * - 파라미터:
 *   { limit?: number (1~3), tone?: "ko-brief" | "ko-detailed" }
 */
exports.requestCoachingTips = onCall(fnOpts, async (request) => {
  const uid = assertAuth(request);
  const { limit = 2, tone = "ko-brief" } = request.data || {};
  const k = Math.min(Math.max(parseInt(limit, 10) || 2, 1), 3);

  // 최근 스윙→볼 순서로 최대 k건 취합
  const pull = async (coll, remain) => {
    if (remain <= 0) return [];
    const qs = await db
      .collection(coll)
      .where("uid", "==", uid)
      .orderBy("createdAt", "desc")
      .limit(remain)
      .get();
    return qs.docs.map((d) => ({ id: d.id, coll, ...d.data() }));
  };

  let items = await pull("swing-analyses", k);
  if (items.length < k) {
    const extra = await pull("ball-analyses", k - items.length);
    items = items.concat(extra);
  }
  if (items.length === 0) {
    throw new HttpsError("failed-precondition", "분석 데이터가 없습니다.");
  }

  // 프롬프트 구성(필요한 핵심 지표만 추출)
  const compact = items.map((x) => ({
    coll: x.coll,
    id: x.id,
    createdAt: x.createdAt?.toDate?.().toISOString?.() ?? null,
    model: x.model?.version || x.model?.name || null,
    metrics: {
      clubSpeed: x.metrics?.clubSpeed ?? x.clubSpeed ?? null,
      carry: x.metrics?.carry ?? x.carry ?? null,
      tempo: x.metrics?.tempo ?? null,
      backswingFrames: x.metrics?.backswingFrames ?? null,
      impactFrame: x.metrics?.impactFrame ?? null,
      confidence: x.metrics?.confidence ?? x.confidence ?? null,
    },
    notes: x.notes || "",
    device: x.device || {},
  }));

  const sys = [
    "역할: 골프 스윙 분석 코치.",
    "목표: 사용자 최근 분석 지표를 바탕으로 즉시 적용 가능한 간결한 코칭 팁 제공.",
    "원칙:",
    "- 개조식(•)으로 4~7개 핵심 팁만.",
    "- 측정 근거를 1~2줄로 요약(숫자 인용).",
    "- 과한 추측 금지. 데이터 없는 부분은 '확실하지 않음'으로 표기.",
    "- 기기/모델 차이로 인한 변동 가능성도 1줄 주의문구.",
  ].join("\n");

  const userCtx = JSON.stringify(compact, null, 2);
  const style =
    tone === "ko-detailed"
      ? "상세 톤(각 팁 2문장 이내)로."
      : "매우 간결 톤(한 줄 팁).";

  const prompt = [
    sys,
    "",
    "다음은 사용자 최근 분석 샘플이다:",
    "```json",
    userCtx,
    "```",
    "",
    `${style} 출력은 한국어로만. 마크다운 사용 금지.`,
  ].join("\n");

  const genAI = new GoogleGenerativeAI(GEMINI_API_KEY.value());
  const model = genAI.getGenerativeModel({ model: "gemini-1.5-pro" });

  const result = await model.generateContent(prompt);
  const text = result.response?.text?.() ?? "";

  // 코칭 로그 저장(선택)
  await db.collection("coaching-logs").add({
    uid,
    createdAt: nowTs(),
    usedDocs: compact.map((x) => ({ coll: x.coll, id: x.id })),
    outputLen: text.length,
  });

  return { tips: text };
});

// ---- 5) 사용자 분석 목록/삭제 (관리 도구) -----------------------------------
/**
 * listAnalyses
 * - type: "swing" | "ball" | "all"
 * - 페이지네이션: startAfter(optional, createdAt Timestamp millis), limit(<=20)
 */
exports.listAnalyses = onCall(fnOpts, async (request) => {
  const uid = assertAuth(request);
  const { type = "all", startAfter, limit = 10 } = request.data || {};
  const take = Math.min(Math.max(parseInt(limit, 10) || 10, 1), 20);

  const fetchColl = async (coll) => {
    let q = db.collection(coll).where("uid", "==", uid).orderBy("createdAt", "desc");
    if (startAfter) {
      const ts = admin.firestore.Timestamp.fromMillis(Number(startAfter));
      q = q.startAfter(ts);
    }
    const snap = await q.limit(take).get();
    return snap.docs.map((d) => ({ id: d.id, coll, ...d.data() }));
  };

  let rows = [];
  if (type === "swing" || type === "all") rows = rows.concat(await fetchColl("swing-analyses"));
  if (type === "ball" || type === "all") rows = rows.concat(await fetchColl("ball-analyses"));

  // all 모드면 createdAt 기준 재정렬 뒤 상위 take개로 잘라 반환
  if (type === "all") {
    rows.sort((a, b) => {
      const ta = a.createdAt?.toMillis?.() ?? 0;
      const tb = b.createdAt?.toMillis?.() ?? 0;
      return tb - ta;
    });
    rows = rows.slice(0, take);
  }

  const next =
    rows.length > 0
      ? rows[rows.length - 1].createdAt?.toMillis?.() ?? null
      : null;

  return { items: rows, nextStartAfter: next };
});

/**
 * deleteAnalysis
 * - 본인 소유 문서만 삭제
 * - 입력: { type: "swing"|"ball", id: "..." }
 */
exports.deleteAnalysis = onCall(fnOpts, async (request) => {
  const uid = assertAuth(request);
  const { type, id } = request.data || {};
  if (type !== "swing" && type !== "ball") {
    throw new HttpsError("invalid-argument", 'type은 "swing" 또는 "ball" 이어야 합니다.');
  }
  assertString(id, "id");

  const coll = type === "swing" ? "swing-analyses" : "ball-analyses";
  const ref = db.collection(coll).doc(id);
  const snap = await ref.get();
  if (!snap.exists) {
    throw new HttpsError("not-found", "문서를 찾을 수 없습니다.");
  }
  if (snap.data().uid !== uid) {
    throw new HttpsError("permission-denied", "본인 문서만 삭제할 수 있습니다.");
  }
  await ref.delete();
  logger.info("deleteAnalysis 완료", { uid, coll, id });
  return { ok: true };
});