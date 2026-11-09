// IA Control Relay: liga o Claude (conector MCP) ao app no celular.
// Sem dependências. Rode com: node relay.js
const http = require("http");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");

const MCP_PORT = 8787;    // exposta na internet pelo túnel (protegida pelo segredo na URL)
const PHONE_PORT = 8788;  // só local: o app no celular fala aqui

const secretFile = path.join(os.homedir(), ".iacontrol_secret");
let SECRET;
try {
  SECRET = fs.readFileSync(secretFile, "utf8").trim();
} catch (e) {
  SECRET = crypto.randomBytes(24).toString("hex");
  fs.writeFileSync(secretFile, SECRET, { mode: 0o600 });
}

const queue = [];            // comandos esperando o celular buscar
const waiting = [];          // buscas (long-poll) do celular esperando comando
const pending = new Map();   // id -> { resolve, timer }
let lastPollAt = 0;

function dispatch(cmd, args) {
  return new Promise((resolve) => {
    const id = crypto.randomBytes(8).toString("hex");
    const timer = setTimeout(() => {
      pending.delete(id);
      const i = queue.findIndex((c) => c.id === id);
      if (i >= 0) queue.splice(i, 1);
      resolve("Tempo esgotado: o celular não respondeu. Veja se o app IA Control está ativo e o controle remoto ligado.");
    }, 30000);
    pending.set(id, { resolve, timer });
    const item = { id, cmd, args: args || {} };
    const poll = waiting.shift();
    if (poll) {
      poll.res.writeHead(200, { "Content-Type": "application/json" });
      poll.res.end(JSON.stringify(item));
      clearTimeout(poll.timer);
    } else {
      queue.push(item);
    }
  });
}

function readBody(req, limit = 1e6) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (c) => {
      data += c;
      if (data.length > limit) { reject(new Error("grande demais")); req.destroy(); }
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

// ---------- Lado do celular (somente 127.0.0.1) ----------
http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/poll") {
    lastPollAt = Date.now();
    if (queue.length) {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify(queue.shift()));
    }
    const entry = { res, timer: null };
    entry.timer = setTimeout(() => {
      const i = waiting.indexOf(entry);
      if (i >= 0) waiting.splice(i, 1);
      lastPollAt = Date.now();
      res.writeHead(204);
      res.end();
    }, 25000);
    waiting.push(entry);
    req.on("close", () => {
      clearTimeout(entry.timer);
      const i = waiting.indexOf(entry);
      if (i >= 0) waiting.splice(i, 1);
    });
    return;
  }
  if (req.method === "POST" && req.url === "/result") {
    try {
      const body = JSON.parse(await readBody(req));
      const p = pending.get(body.id);
      if (p) {
        clearTimeout(p.timer);
        pending.delete(body.id);
        p.resolve(String(body.result));
      }
      res.writeHead(200);
      return res.end("ok");
    } catch (e) {
      res.writeHead(400);
      return res.end("erro");
    }
  }
  res.writeHead(404);
  res.end();
}).listen(PHONE_PORT, "127.0.0.1");

// ---------- Lado do Claude (servidor MCP, HTTP) ----------
const str = (d) => ({ type: "string", description: d });
const TOOLS = [
  { name: "status_celular", description: "Diz se o app no celular está conectado e pronto para receber comandos.",
    inputSchema: { type: "object", properties: {} } },
  { name: "ler_tela", description: "Lê o texto da tela atual do celular (e o app em primeiro plano). Use antes de agir para saber o que está na tela.",
    inputSchema: { type: "object", properties: {} } },
  { name: "abrir_app", description: "Abre um app instalado pelo nome (ex.: WhatsApp).",
    inputSchema: { type: "object", properties: { nome: str("Nome do app") }, required: ["nome"] } },
  { name: "tocar_texto", description: "Toca no elemento da tela que contém esse texto (ou descrição de acessibilidade).",
    inputSchema: { type: "object", properties: { texto: str("Texto do botão/elemento") }, required: ["texto"] } },
  { name: "digitar", description: "Digita texto no campo que está em foco (substitui o conteúdo do campo).",
    inputSchema: { type: "object", properties: { texto: str("Texto a digitar") }, required: ["texto"] } },
  { name: "rolar", description: "Rola a tela.",
    inputSchema: { type: "object", properties: { direcao: { type: "string", enum: ["baixo", "cima"] } }, required: ["direcao"] } },
  { name: "voltar", description: "Aperta o botão Voltar do Android.", inputSchema: { type: "object", properties: {} } },
  { name: "inicio", description: "Vai para a tela inicial do Android.", inputSchema: { type: "object", properties: {} } },
];

function rpc(id, result) { return { jsonrpc: "2.0", id, result }; }
function rpcErr(id, code, message) { return { jsonrpc: "2.0", id, error: { code, message } }; }

async function handleRpc(msg) {
  const { id, method, params } = msg;
  if (id === undefined || id === null) return null; // notificações não têm resposta
  switch (method) {
    case "initialize":
      return rpc(id, {
        protocolVersion: (params && params.protocolVersion) || "2025-03-26",
        capabilities: { tools: {} },
        serverInfo: { name: "ia-control-relay", version: "0.1.0" },
      });
    case "ping":
      return rpc(id, {});
    case "tools/list":
      return rpc(id, { tools: TOOLS });
    case "tools/call": {
      const name = params && params.name;
      const args = (params && params.arguments) || {};
      if (!TOOLS.some((t) => t.name === name)) return rpcErr(id, -32602, "Ferramenta desconhecida: " + name);
      let text;
      if (name === "status_celular") {
        const ok = Date.now() - lastPollAt < 40000;
        text = ok ? "Celular conectado e aguardando comandos."
                  : "Celular NÃO conectado. Abra o IA Control e ligue o 'Controle remoto'.";
      } else {
        text = await dispatch(name, args);
      }
      return rpc(id, { content: [{ type: "text", text }] });
    }
    default:
      return rpcErr(id, -32601, "Método não suportado: " + method);
  }
}

http.createServer(async (req, res) => {
  const mcpPath = "/s/" + SECRET + "/mcp";
  if (req.url.split("?")[0] !== mcpPath) { res.writeHead(404); return res.end(); }
  if (req.method !== "POST") { res.writeHead(405, { Allow: "POST" }); return res.end(); }
  try {
    const msg = JSON.parse(await readBody(req));
    const batch = Array.isArray(msg);
    const out = [];
    for (const m of batch ? msg : [msg]) {
      const r = await handleRpc(m);
      if (r) out.push(r);
    }
    if (!out.length) { res.writeHead(202); return res.end(); }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify(batch ? out : out[0]));
  } catch (e) {
    res.writeHead(400, { "Content-Type": "application/json" });
    res.end(JSON.stringify(rpcErr(null, -32700, "JSON inválido")));
  }
}).listen(MCP_PORT, "127.0.0.1", () => {
  console.log("IA Control Relay no ar.");
  console.log("");
  console.log("Seu SEGREDO (não compartilhe):");
  console.log(SECRET);
  console.log("");
  console.log("Caminho do conector: /s/" + SECRET + "/mcp");
  console.log("Em outra sessão do Termux rode:");
  console.log("  cloudflared tunnel --url http://127.0.0.1:" + MCP_PORT);
  console.log("e use como URL do conector:  <endereço do túnel>/s/" + SECRET + "/mcp");
});
