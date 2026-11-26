// Cloudflare Pages Function：接收 antiburn 诊断日志，落 D1。
// 路由：POST /api/log  ；绑定名 DB 指向 D1 数据库（见 wrangler.toml）。

const MAX_LOG = 120_000;

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

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ ok: false, error: "bad json" }, 400);
  }

  const e = body.env && typeof body.env === "object" ? body.env : {};
  const log = String(body.log || "").slice(-MAX_LOG);
  const ts = Number(body.ts) || Date.now();
  const code = num(e.moduleCode);

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
    let purged = 0;
    let purgeWarning = null;
    if (code != null) {
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
