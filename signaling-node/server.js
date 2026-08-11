/**
 * 智慧投屏 - 信令服务器 (Node.js 版,本机验证用)
 * =====================================================
 * 职责:WebSocket 转发 SDP/ICE + 提供浏览器接收页
 * 不解释消息内容,纯字节转发给同房间另一端
 *
 * 房间隔离:pad 和浏览器用 ?room=XXX&role=pad|browser 标识,
 * 同一 room 的两端配对,消息只转发给同房间的对方。
 * 多教室同时投屏互不干扰。
 *
 * 运行:node server.js          (默认 8080)
 *      node server.js 9000     (自定义端口)
 */
const http = require("http");
const fs = require("fs");
const path = require("path");
const { WebSocketServer } = require("ws");

const PORT = parseInt(process.argv[2] || "8080", 10);

// 读取接收页 HTML(和 Go 版共用同一个)
const HTML_PATH = path.join(__dirname, "..", "signaling-go", "web", "index.html");
const INDEX_HTML = fs.existsSync(HTML_PATH)
  ? fs.readFileSync(HTML_PATH, "utf-8")
  : "<h1>index.html 未找到,请检查 signaling-go/web/index.html</h1>";

// 房间表:room 名 → { pad: ws, browser: ws }
const rooms = new Map();

function getRoom(name) {
  if (!rooms.has(name)) rooms.set(name, { pad: null, browser: null });
  return rooms.get(name);
}

function relayToPeer(room, fromRole, message) {
  // 把消息转发给同房间的另一端
  const peer = fromRole === "pad" ? room.browser : room.pad;
  if (peer && peer.readyState === peer.OPEN) {
    peer.send(message);
    return true;
  }
  return false;
}

// HTTP 服务:提供接收页
const server = http.createServer((req, res) => {
  if (req.url === "/" || req.url.startsWith("/?")) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(INDEX_HTML);
  } else {
    res.writeHead(404);
    res.end("Not Found");
  }
});

// WebSocket 服务
const wss = new WebSocketServer({ server, path: "/ws" });

wss.on("connection", (ws, req) => {
  // 从 URL 读 room 和 role
  const url = new URL(req.url, "http://localhost");
  const roomName = url.searchParams.get("room") || "default";
  const role = url.searchParams.get("role"); // "pad" 或 "browser"

  if (role !== "pad" && role !== "browser") {
    ws.close(4000, "缺少 role 参数(应为 pad 或 browser)");
    return;
  }

  const room = getRoom(roomName);
  // 登记进房间
  if (role === "pad") {
    if (room.pad && room.pad.readyState === room.pad.OPEN) {
      // 同房间已有 pad,顶掉旧的(支持重新投屏)
      try { room.pad.close(); } catch (_) {}
    }
    room.pad = ws;
  } else {
    room.browser = ws;
  }

  const peerRole = role === "pad" ? "browser" : "pad";
  const peerAlive = room[peerRole] && room[peerRole].readyState === room[peerRole].OPEN;
  ws.send(JSON.stringify({ type: "joined", room: roomName, role, peerOnline: !!peerAlive }));

  // 关键修复:第二方加入时,通知已经在房间里的一方"对方上线了"
  // 这样 pad(先连的)收到通知后,会主动 createOffer 发给浏览器,打破死锁
  if (peerAlive) {
    try {
      room[peerRole].send(JSON.stringify({ type: "peer-online" }));
    } catch (_) {}
  }

  console.log(`[${new Date().toLocaleTimeString()}] ${role} 加入房间 "${roomName}"${peerAlive ? "(对方在线)" : "(等待对方)"}`);

  // 收到消息,转发给同房间另一端
  ws.on("message", (raw) => {
    relayToPeer(room, role, raw.toString());
  });

  ws.on("close", () => {
    // 清理自己的槽位
    if (room[role] === ws) room[role] = null;
    const stillPeer = room[peerRole] && room[peerRole].readyState === room[peerRole].OPEN;
    console.log(`[${new Date().toLocaleTimeString()}] ${role} 离开房间 "${roomName}"`);
    // 通知对方
    if (stillPeer) {
      room[peerRole].send(JSON.stringify({ type: "peer-left" }));
    }
    // 房间空了就清掉,避免内存泄漏
    if (!room.pad && !room.browser) rooms.delete(roomName);
  });

  ws.on("error", () => {});
});

server.listen(PORT, "0.0.0.0", () => {
  console.log("================================================");
  console.log(`✅ 信令服务器已启动,监听 0.0.0.0:${PORT}`);
  console.log(`📱 黑板浏览器打开:http://本机IP:${PORT}/?room=房间号`);
  console.log(`💡 例:http://192.168.x.x:${PORT}/?room=三年二班`);
  console.log(`   Ctrl+C 退出`);
  console.log("================================================");
});
