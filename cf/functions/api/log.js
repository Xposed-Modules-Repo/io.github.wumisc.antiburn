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

    // v3.4.11：旧日志清理不再按「版本号」删。
    //
    // 以前是「删掉 module_code < 本次 versionCode 的所有行」，等于谁上传新版本，
    // 就把其他人的旧版本日志一起清空 —— 实际效果是库里长期只剩最后一次上传。
    // 现在改成按「时间 + 设备 + 总量」保留，别人的日志不会被一次上传带走：
    //   1) 超过 30 天的行删除（老日志本来也没有排障价值）；
    //   2) 同一台设备只留最近 8 条（避免同一人反复上传把别人挤掉）；
    //   3) 全表最多 200 条，超出按 id 从旧到新删。
    // 清理失败只记 warning，绝不影响本次日志已入库的事实。
    let purged = 0;
    let purgeWarning = null;
    try {
      const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
      const d1 = await env.DB.prepare(`DELETE FROM logs WHERE ts < ?`).bind(cutoff).run();
      purged += d1.meta && typeof d1.meta.changes === "number" ? d1.meta.changes : 0;

      const d2 = await env.DB.prepare(
        `DELETE FROM logs
           WHERE brand = ? AND model = ? AND device = ?
             AND id NOT IN (
               SELECT id FROM (
                 SELECT id, ROW_NUMBER() OVER (ORDER BY ts DESC, id DESC) AS rn
                   FROM logs WHERE brand = ? AND model = ? AND device = ?
               ) WHERE rn <= 8
             )`
      )
        .bind(str(e.brand), str(e.model), str(e.device),
              str(e.brand), str(e.model), str(e.device))
        .run();
      purged += d2.meta && typeof d2.meta.changes === "number" ? d2.meta.changes : 0;

      const d3 = await env.DB.prepare(
        `DELETE FROM logs WHERE id < (SELECT MAX(id) FROM logs) - 200`
      ).run();
      purged += d3.meta && typeof d3.meta.changes === "number" ? d3.meta.changes : 0;
    } catch (purgeErr) {
      purgeWarning = String(purgeErr && purgeErr.message || purgeErr);
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
