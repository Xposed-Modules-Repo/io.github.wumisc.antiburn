// Cloudflare Pages Function：接收 antiburn 诊断日志，落 D1。
// 路由：POST /api/log  ；绑定名 DB 指向 D1 数据库（见 wrangler.toml）。

const MAX_LOG = 120_000;
/** 反馈正文上限：一句话足够，超了截断而不是拒绝。 */
const MAX_MSG = 600;
const MAX_CONTACT = 120;
/** 反馈自带日志上限：够看现场就行，别把反馈表也撑着。 */
const MAX_FEEDBACK_LOG = 40_000;
/** 请求体上限：正常一条日志远小于这个数，超出直接拒绝，别让免费额度被刷爆。 */
const MAX_BODY = 256 * 1024;
/** moduleCode 合理区间：0..999。v3.4.0 起用于钳制删除范围（实际版本号才 142，留足余量）。 */
const MAX_CODE = 999;
/** ts 合理区间：允许前后 30 天偏差，防止伪造时间戳把日志排到几百年后。 */
const TS_WINDOW = 30 * 24 * 3600 * 1000;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json;charset=utf-8",
      "Access-Control-Allow-Origin": "*",
    },
  });
}

export async function onRequestPost(context) {
  const { request, env } = context;
  if (!env || !env.DB) {
    return json({ ok: false, error: "D1 binding missing" }, 500);
  }

  // v3.4.0 修：以前不限体积，request.json() 会被超大 body 拖住
  const len = Number(request.headers.get("content-length") || 0);
  if (len > MAX_BODY) {
    return json({ ok: false, error: "body too large" }, 413);
  }

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ ok: false, error: "bad json" }, 400);
  }

  const e = body.env && typeof body.env === "object" ? body.env : {};
  const log = String(body.log || "").slice(-MAX_LOG);
  const ts = clampTs(Number(body.ts) || Date.now());
  const code = num(e.moduleCode);

  // v3.4.1：带 msg 的请求是「用户反馈」，落到 feedback 表，不参与按版本清理。
  // 反馈的正文比日志正文重要得多，所以先入库、失败也回 500，让用户知道没送达。
  const msg = String(body.msg || "").trim().slice(0, MAX_MSG);
  if (msg) {
    const contact = String(body.contact || "").trim().slice(0, MAX_CONTACT);
    try {
      await env.DB.prepare(
        `INSERT INTO feedback
          (ts, model, brand, manufacturer, device, product, release, sdk,
           module, module_code, msg, contact, log)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
        .bind(
          ts,
          str(e.model), str(e.brand), str(e.manufacturer), str(e.device),
          str(e.product), str(e.release), num(e.sdk),
          str(e.module), code, msg, contact,
          String(body.log || "").slice(-MAX_FEEDBACK_LOG)
        )
        .run();
      return json({ ok: true, feedback: true });
    } catch (err) {
      return json({ ok: false, error: String(err && err.message || err) }, 500);
    }
  }

  try {
    await env.DB.prepare(
      `INSERT INTO logs
        (ts, model, brand, manufacturer, device, product, release, sdk,
         module, module_code, abi, log)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        ts,
        str(e.model), str(e.brand), str(e.manufacturer), str(e.device),
        str(e.product), str(e.release), num(e.sdk),
        str(e.module), code, str(e.abi), log
      )
      .run();

    // 版本挂钩的旧日志清理：本次落库成功后，只保留不低于当前 versionCode 的记录，
    // 清掉更早版本（以及历史上没带 module_code 的未知版本）。同版本、更新版本保留；
    // 旧设备回传旧版本号不会反向删掉更新版本的行。module_code 缺失时跳过清理，避免误删。
    // v3.4.0 修：以前 code 直接来自请求体，一个 POST 带上 code=999999 就能把整表清空。
    // 现在只接受 0..MAX_CODE 的合理版本号，越界一律不清理。
    let purged = 0;
    let purgeWarning = null;
    if (code != null && code >= 0 && code <= MAX_CODE) {
      try {
        const del = await env.DB.prepare(
          `DELETE FROM logs WHERE module_code IS NULL OR module_code < ?`
        )
          .bind(code)
          .run();
        purged = del.meta && typeof del.meta.changes === "number" ? del.meta.changes : 0;
      } catch (purgeErr) {
        purgeWarning = String(purgeErr && purgeErr.message || purgeErr);
      }
    }
    return json({ ok: true, purged, purgeWarning });
  } catch (err) {
    return json({ ok: false, error: String(err && err.message || err) }, 500);
  }
}

export async function onRequestOptions() {
  return new Response(null, {
    status: 204,
    headers: {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    },
  });
}

function str(v) {
  return v == null ? null : String(v).slice(0, 200);
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function clampTs(v) {
  const now = Date.now();
  const n = Number(v);
  if (!Number.isFinite(n)) {
    return now;
  }
  if (Math.abs(n - now) > TS_WINDOW) {
    return now;
  }
  return n;
}
