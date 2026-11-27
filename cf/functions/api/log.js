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
  // v3.4.12：来源 IP 参与留存分组（厂商 + 机型 + IP 只留最近 3 条）。
  // 优先 CF-Connecting-IP（Cloudflare 保证是真实客户端地址），再退 X-Forwarded-For 首段。
  const ip = clientIp(request);
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
         module, module_code, abi, ip, log)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        ts,
        str(e.model), str(e.brand), str(e.manufacturer), str(e.device),
        str(e.product), str(e.release), num(e.sdk),
        str(e.module), code, str(e.abi), ip, log
      )
      .run();

    // v3.4.12：留存口径换成「厂商 + 机型 + 来源 IP，各留最近 3 条」，彻底不看版本号。
    //
    // 演进过程：v3.2.39 按 versionCode 删（开发者版本永远最新，一上传就把别人清空）；
    // v3.4.11 改成「同设备留 8 条」，但同一台设备换了网络、或用同一型号的两个人撞车时
    // 仍会互相挤掉。现在把来源 IP 并进分组键，一台机器一个出口 IP 一个槽位，
    // 每次上传只滚动自己那 3 条，别人的一条不动：
    //   1) 超过 30 天的行删除（老日志没有排障价值，也防无限膨胀）；
    //   2) 每个 (brand, model, ip) 组只留最近 3 条（ts 倒序，同 ts 按 id）；
    //   3) 全表硬上限 1000 条，防伪造 IP 刷库把免费额度吃光（正常用不到）。
    // 清理失败只记 warning，绝不影响本次日志已入库的事实。
    let purged = 0;
    let purgeWarning = null;
    try {
      const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
      const d1 = await env.DB.prepare(`DELETE FROM logs WHERE ts < ?`).bind(cutoff).run();
      purged += d1.meta && typeof d1.meta.changes === "number" ? d1.meta.changes : 0;

      // 一条语句把**所有**分组都修到 3 条：老库里按 8 条留下的富余也会被顺手收掉。
      const d2 = await env.DB.prepare(
        `DELETE FROM logs
           WHERE id IN (
             SELECT id FROM (
               SELECT id,
                      ROW_NUMBER() OVER (
                        PARTITION BY COALESCE(brand, '?'), COALESCE(model, '?'), COALESCE(ip, '?')
                        ORDER BY ts DESC, id DESC
                      ) AS rn
                 FROM logs
             ) WHERE rn > 3
           )`
      ).run();
      purged += d2.meta && typeof d2.meta.changes === "number" ? d2.meta.changes : 0;

      const d3 = await env.DB.prepare(
        `DELETE FROM logs WHERE id < (SELECT MAX(id) FROM logs) - 1000`
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

/** 客户端 IP：Cloudflare 的 CF-Connecting-IP 最可信；拿不到再退 XFF 首段，都不行就记 "?"。 */
function clientIp(request) {
  const h = request.headers;
  const cf = h.get("cf-connecting-ip");
  if (cf && cf.trim()) {
    return cf.trim().slice(0, 64);
  }
  const xff = h.get("x-forwarded-for");
  if (xff) {
    const first = xff.split(",")[0].trim();
    if (first) {
      return first.slice(0, 64);
    }
  }
  return "?";
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
