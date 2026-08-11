package main

// 智慧投屏 - 黑板端 (WebRTC 信令 + 接收页 + 局域网发现)
// =====================================================
// 老师双击本程序即可:
//   1. 起 HTTP 提供接收页 (内嵌 index.html)
//   2. 起 WebSocket 信令 /ws
//   3. UDP:19999 广播,pad 自动发现
//   4. Windows 优先 WebView2 单窗口;失败则打开系统浏览器
//   5. 同房间新 pad 顶替旧 pad;教师可一键断开当前投屏
//   6. 可选房间 token(默认关闭,开启后随发现广播,不挡一键连接)
//
// 运行:
//   智慧投屏.exe
//   智慧投屏.exe 8080
//   智慧投屏.exe 8080 "三年二班"
//   智慧投屏.exe 8080 "三年二班" "班级密钥"   # 可选 token
// 环境变量 SMARTCAST_TOKEN 也可设置 token(与参数二选一,参数优先)

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

//go:embed web/*
var webFS embed.FS

const (
	defaultPort   = 8080
	defaultName   = "智慧黑板"
	defaultRoom   = "default"
	discoveryPort = 19999
)

var (
	boardName    = defaultName
	boardRoom    = defaultRoom
	boardPort    = defaultPort
	boardToken   = "" // 空=不校验;非空=pad/browser 需带相同 token
	// 画质由黑板控制(学生 pad 不可改): balanced|clear|smooth
	boardQuality = "balanced"
	qualityMu    sync.RWMutex
	broadcastOK  = false
	logFile      *os.File

	upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool { return true },
	}

	roomsMu sync.Mutex
	rooms   = map[string]*roomState{}
)

type wsClient struct {
	conn *websocket.Conn
	mu   sync.Mutex
	role string
}

func (c *wsClient) writeJSON(v interface{}) error {
	if c == nil || c.conn == nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteJSON(v)
}

func (c *wsClient) writeMessage(messageType int, data []byte) error {
	if c == nil || c.conn == nil {
		return nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteMessage(messageType, data)
}

func (c *wsClient) close() {
	if c == nil || c.conn == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.conn.Close()
}

type roomState struct {
	pad     *wsClient
	browser *wsClient
	padMu   sync.Mutex
	brMu    sync.Mutex
}

type discoveryMsg struct {
	Name    string `json:"name,omitempty"`
	Port    int    `json:"port,omitempty"`
	IP      string `json:"ip,omitempty"`
	Room    string `json:"room,omitempty"`
	Type    string `json:"type,omitempty"`
	Token   string `json:"token,omitempty"`   // 仅在配置了 token 时出现
	Quality string `json:"quality,omitempty"` // balanced|clear|smooth,黑板控制
	Cmd     string `json:"cmd,omitempty"`
}

func normalizeQuality(q string) string {
	switch strings.ToLower(strings.TrimSpace(q)) {
	case "clear", "1080", "1080p", "high":
		return "clear"
	case "smooth", "720", "720p", "low":
		return "smooth"
	default:
		return "balanced"
	}
}

func getBoardQuality() string {
	qualityMu.RLock()
	defer qualityMu.RUnlock()
	return boardQuality
}

func setBoardQuality(q string) string {
	q = normalizeQuality(q)
	qualityMu.Lock()
	boardQuality = q
	qualityMu.Unlock()
	return q
}

// 通知房间内 pad 切换画质(教师在 exe 上改)
func notifyPadQuality(roomName, quality string) {
	room := getRoom(roomName)
	if pad := peerClient(room, "pad"); pad != nil {
		_ = pad.writeJSON(map[string]string{
			"type":    "quality",
			"quality": quality,
		})
	}
}

func main() {
	if len(os.Args) > 1 {
		if _, err := fmt.Sscanf(os.Args[1], "%d", &boardPort); err != nil {
			fmt.Println("端口参数无效,使用默认 8080")
			boardPort = defaultPort
		}
	}
	if len(os.Args) > 2 {
		boardName = os.Args[2]
	}
	// token: 第 4 参数优先,否则环境变量
	if len(os.Args) > 3 {
		boardToken = strings.TrimSpace(os.Args[3])
	} else if t := strings.TrimSpace(os.Getenv("SMARTCAST_TOKEN")); t != "" {
		boardToken = t
	}
	// 画质: 环境变量 SMARTCAST_QUALITY=balanced|clear|smooth
	if q := strings.TrimSpace(os.Getenv("SMARTCAST_QUALITY")); q != "" {
		boardQuality = normalizeQuality(q)
	}

	initLog()
	defer func() {
		if logFile != nil {
			logFile.Close()
		}
	}()
	preventSleep()

	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		logf("❌ 内嵌网页资源失败: %v", err)
		waitExit()
		return
	}
	fileServer := http.FileServer(http.FS(sub))
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" || strings.HasPrefix(r.URL.Path, "/?") {
			data, err := webFS.ReadFile("web/index.html")
			if err != nil {
				http.Error(w, "index.html missing", 500)
				return
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-cache")
			_, _ = w.Write(data)
			return
		}
		fileServer.ServeHTTP(w, r)
	})
	mux.HandleFunc("/ws", handleWS)
	mux.HandleFunc("/api/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"name":    boardName,
			"port":    boardPort,
			"room":    boardRoom,
			"type":    "webrtc",
			"tokenOn": boardToken != "",
			"quality": getBoardQuality(),
			"hasPad":  roomHasPad(boardRoom),
			"ips":     getLocalIPv4s(),
		})
	})
	// 教师设置画质: GET/POST /api/quality?quality=balanced|clear|smooth
	mux.HandleFunc("/api/quality", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		if r.Method == http.MethodGet {
			_ = json.NewEncoder(w).Encode(map[string]string{"quality": getBoardQuality()})
			return
		}
		if r.Method != http.MethodPost && r.Method != http.MethodPut {
			http.Error(w, "method not allowed", 405)
			return
		}
		if boardToken != "" {
			t := r.URL.Query().Get("token")
			if t == "" {
				t = r.Header.Get("X-Smartcast-Token")
			}
			if t != boardToken {
				http.Error(w, "token invalid", 403)
				return
			}
		}
		q := r.URL.Query().Get("quality")
		if q == "" {
			var body struct {
				Quality string `json:"quality"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			q = body.Quality
		}
		q = setBoardQuality(q)
		notifyPadQuality(boardRoom, q)
		logf("画质已切换为 %s (由教师端设置)", q)
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"ok":      true,
			"quality": q,
		})
	})
	// 教师端/调试: POST /api/stop 断开当前 pad
	mux.HandleFunc("/api/stop", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodGet {
			http.Error(w, "method not allowed", 405)
			return
		}
		// 可选 token 校验
		if boardToken != "" {
			t := r.URL.Query().Get("token")
			if t == "" {
				t = r.Header.Get("X-Smartcast-Token")
			}
			if t != boardToken {
				http.Error(w, "token invalid", 403)
				return
			}
		}
		ok := stopCurrentPad(boardRoom, "teacher-api")
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"ok":      ok,
			"message": map[bool]string{true: "已断开当前投屏", false: "当前无 pad 在投屏"}[ok],
		})
	})

	addr := fmt.Sprintf("0.0.0.0:%d", boardPort)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		logf("❌ 监听失败: %v (端口 %d 是否被占用?)", err, boardPort)
		waitExit()
		return
	}

	startDiscoveryService(boardPort)

	ips := getLocalIPv4s()
	fmt.Println("================================================")
	logf("✅ 智慧投屏(黑板端)已启动")
	logf("   名称: %s", boardName)
	logf("   端口: %d  房间: %s", boardPort, boardRoom)
	logf("   画质: %s (教师在界面切换;学生不可改)", getBoardQuality())
	if boardToken != "" {
		logf("   Token: 已启用(发现广播自动携带,pad 一键仍可用)")
	} else {
		logf("   Token: 未启用(默认,不影响自动连接)")
	}
	if len(ips) > 0 {
		logf("   本机 IP: %s", strings.Join(ips, ", "))
		logf("   本机页: http://127.0.0.1:%d/?room=%s", boardPort, url.QueryEscape(boardRoom))
	}
	if broadcastOK {
		logf("📢 局域网广播中,pad 可自动发现")
	} else {
		logf("ℹ️ 广播未启动,pad 需在高级设置手输 IP")
	}
	logf("提示: 关闭窗口退出; 日志 → 智慧投屏.log")
	fmt.Println("================================================")

	pageURL := fmt.Sprintf("http://127.0.0.1:%d/?room=%s", boardPort, url.QueryEscape(boardRoom))
	if boardToken != "" {
		pageURL += "&token=" + url.QueryEscape(boardToken)
	}

	// HTTP 在后台;主线程跑 WebView(或阻塞 wait)
	go func() {
		if err := http.Serve(ln, mux); err != nil {
			logf("HTTP 服务结束: %v", err)
		}
	}()

	time.Sleep(400 * time.Millisecond)
	if !openEmbeddedView(pageURL) {
		// 回退:系统浏览器
		openBrowser(pageURL)
		logf("已回退到系统浏览器;按 Ctrl+C 或关闭控制台退出")
		// 无 WebView 时保持进程
		select {}
	}
}

// ===================== WebSocket 信令 =====================

func handleWS(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	roomName := q.Get("room")
	if roomName == "" {
		roomName = boardRoom
	}
	role := q.Get("role")
	if role != "pad" && role != "browser" {
		http.Error(w, "缺少 role=pad|browser", 400)
		return
	}
	// token: 仅当黑板配置了 token 才校验;空 token 配置=完全开放
	if boardToken != "" {
		if q.Get("token") != boardToken {
			http.Error(w, "token 无效", 403)
			logf("[%s] %s 拒绝:token 不匹配", roomName, role)
			return
		}
	}

	raw, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		logf("WS upgrade 失败: %v", err)
		return
	}
	client := &wsClient{conn: raw, role: role}
	room := getRoom(roomName)

	replacedPad := false
	if role == "pad" {
		room.padMu.Lock()
		old := room.pad
		room.pad = client
		room.padMu.Unlock()
		if old != nil {
			_ = old.writeJSON(map[string]string{"type": "kicked"})
			time.Sleep(120 * time.Millisecond)
			old.close()
			replacedPad = true
			logf("[%s] 新 pad 顶替旧连接", roomName)
		}
	} else {
		room.brMu.Lock()
		old := room.browser
		room.browser = client
		room.brMu.Unlock()
		if old != nil {
			old.close()
		}
	}

	peerRole := "browser"
	if role == "browser" {
		peerRole = "pad"
	}
	peerAlive := peerClient(room, peerRole) != nil

	_ = client.writeJSON(map[string]interface{}{
		"type":       "joined",
		"room":       roomName,
		"role":       role,
		"peerOnline": peerAlive,
		"boardName":  boardName,
		"tokenOn":    boardToken != "",
		"quality":    getBoardQuality(),
	})

	if peerAlive {
		if peer := peerClient(room, peerRole); peer != nil {
			_ = peer.writeJSON(map[string]string{"type": "peer-online"})
		}
	}

	if replacedPad {
		if br := peerClient(room, "browser"); br != nil {
			_ = br.writeJSON(map[string]string{"type": "session-reset", "reason": "pad-replaced"})
			_ = br.writeJSON(map[string]string{"type": "peer-online"})
		}
	}

	logf("[%s] %s 加入%s", roomName, role, map[bool]string{true: "(对方在线)", false: "(等待对方)"}[peerAlive])

	for {
		_, data, err := raw.ReadMessage()
		if err != nil {
			break
		}
		text := string(data)
		if strings.Contains(text, `"type":"ping"`) {
			_ = client.writeJSON(map[string]string{"type": "pong"})
			continue
		}
		if strings.Contains(text, `"type":"pong"`) {
			continue
		}
		// 教师/浏览器:断开当前 pad
		if role == "browser" && strings.Contains(text, `"type":"teacher-stop"`) {
			if stopCurrentPad(roomName, "teacher-ui") {
				_ = client.writeJSON(map[string]string{"type": "session-reset", "reason": "teacher-stop"})
				_ = client.writeJSON(map[string]interface{}{
					"type":    "teacher-stop-ok",
					"message": "已断开当前投屏",
				})
			} else {
				_ = client.writeJSON(map[string]interface{}{
					"type":    "teacher-stop-ok",
					"message": "当前无 pad 在投屏",
				})
			}
			continue
		}
		// 教师设置画质
		if role == "browser" && strings.Contains(text, `"type":"set-quality"`) {
			var msg struct {
				Type    string `json:"type"`
				Quality string `json:"quality"`
			}
			if json.Unmarshal(data, &msg) == nil {
				q := setBoardQuality(msg.Quality)
				notifyPadQuality(roomName, q)
				_ = client.writeJSON(map[string]interface{}{
					"type":    "quality-ok",
					"quality": q,
				})
				logf("[%s] 教师设置画质 → %s", roomName, q)
			}
			continue
		}
		peer := peerClient(room, peerRole)
		if peer != nil {
			_ = peer.writeMessage(websocket.TextMessage, data)
		}
	}

	wasActive := false
	if role == "pad" {
		room.padMu.Lock()
		if room.pad == client {
			room.pad = nil
			wasActive = true
		}
		room.padMu.Unlock()
	} else {
		room.brMu.Lock()
		if room.browser == client {
			room.browser = nil
			wasActive = true
		}
		room.brMu.Unlock()
	}
	client.close()

	if !wasActive {
		logf("[%s] %s 旧连接退出(已被顶替,忽略)", roomName, role)
		return
	}
	logf("[%s] %s 离开", roomName, role)

	if peer := peerClient(room, peerRole); peer != nil {
		_ = peer.writeJSON(map[string]string{"type": "peer-left"})
	}
	roomsMu.Lock()
	if peerClient(room, "pad") == nil && peerClient(room, "browser") == nil {
		delete(rooms, roomName)
	}
	roomsMu.Unlock()
}

func stopCurrentPad(roomName, reason string) bool {
	room := getRoom(roomName)
	room.padMu.Lock()
	pad := room.pad
	room.pad = nil
	room.padMu.Unlock()
	if pad == nil {
		return false
	}
	_ = pad.writeJSON(map[string]string{"type": "kicked", "reason": reason})
	// 也发 stop 兼容
	_ = pad.writeJSON(map[string]string{"type": "stop", "reason": reason})
	time.Sleep(80 * time.Millisecond)
	pad.close()
	logf("[%s] 教师断开当前投屏 (%s)", roomName, reason)
	if br := peerClient(room, "browser"); br != nil {
		_ = br.writeJSON(map[string]string{"type": "session-reset", "reason": reason})
	}
	return true
}

func roomHasPad(roomName string) bool {
	roomsMu.Lock()
	r := rooms[roomName]
	roomsMu.Unlock()
	if r == nil {
		return false
	}
	return peerClient(r, "pad") != nil
}

func getRoom(name string) *roomState {
	roomsMu.Lock()
	defer roomsMu.Unlock()
	r, ok := rooms[name]
	if !ok {
		r = &roomState{}
		rooms[name] = r
	}
	return r
}

func peerClient(room *roomState, role string) *wsClient {
	if role == "pad" {
		room.padMu.Lock()
		defer room.padMu.Unlock()
		return room.pad
	}
	room.brMu.Lock()
	defer room.brMu.Unlock()
	return room.browser
}

// ===================== UDP 发现 =====================

func startDiscoveryService(port int) {
	ips := getLocalIPv4s()
	if len(ips) == 0 {
		logf("⚠️ 未找到本机 IPv4,广播发现无法启动")
		return
	}
	addr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
		logf("⚠️ 广播地址解析失败: %v", err)
		return
	}
	listener, err := net.ListenUDP("udp4", addr)
	if err != nil {
		logf("⚠️ 广播监听失败: %v (端口 %d 是否占用?)", err, discoveryPort)
		return
	}
	go respondDiscoveryQueries(listener, port)
	go broadcastLoop(port, ips)
	broadcastOK = true
}

func makeAnnounce(port int) discoveryMsg {
	msg := discoveryMsg{
		Name:    boardName,
		Port:    port,
		Room:    boardRoom,
		Type:    "webrtc",
		Quality: getBoardQuality(),
	}
	// 仅启用 token 时写入,pad 发现后自动带上;不启用则字段省略
	if boardToken != "" {
		msg.Token = boardToken
	}
	return msg
}

func respondDiscoveryQueries(conn *net.UDPConn, port int) {
	buf := make([]byte, 1024)
	for {
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		var msg discoveryMsg
		isQuery := false
		if json.Unmarshal(buf[:n], &msg) == nil {
			if strings.EqualFold(msg.Cmd, "QUERY") {
				isQuery = true
			}
		} else if strings.Contains(string(buf[:n]), "QUERY") {
			isQuery = true
		}
		if isQuery {
			data, _ := json.Marshal(makeAnnounce(port))
			_, _ = conn.WriteToUDP(data, remote)
		}
	}
}

func broadcastLoop(port int, ips []string) {
	bcastAddr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf("255.255.255.255:%d", discoveryPort))
	if err != nil {
		return
	}
	for {
		data, _ := json.Marshal(makeAnnounce(port))
		for _, ip := range ips {
			localAddr, err := net.ResolveUDPAddr("udp4", ip+":0")
			if err != nil {
				continue
			}
			c, err := net.DialUDP("udp4", localAddr, bcastAddr)
			if err != nil {
				continue
			}
			_, _ = c.Write(data)
			_ = c.Close()
		}
		time.Sleep(2 * time.Second)
	}
}

// ===================== 工具 =====================

func getLocalIPv4s() []string {
	var ips []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return ips
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			var ip net.IP
			switch v := a.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() {
				continue
			}
			ip = ip.To4()
			if ip == nil {
				continue
			}
			s := ip.String()
			if strings.HasPrefix(s, "169.254.") {
				continue
			}
			ips = append(ips, s)
		}
	}
	return ips
}

func openBrowser(urlStr string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		// 优先 Edge 应用模式(更接近单窗口)
		edge := exec.Command("cmd", "/c", "start", "", "msedge", "--app="+urlStr, "--start-fullscreen")
		if err := edge.Start(); err == nil {
			logf("🌐 已用 Edge 应用模式打开接收页")
			return
		}
		cmd = exec.Command("cmd", "/c", "start", "", urlStr)
	case "darwin":
		cmd = exec.Command("open", urlStr)
	default:
		cmd = exec.Command("xdg-open", urlStr)
	}
	if err := cmd.Start(); err != nil {
		logf("⚠️ 自动打开浏览器失败: %v ,请手动打开 %s", err, urlStr)
	} else {
		logf("🌐 已尝试打开浏览器接收页")
	}
}

func preventSleep() {
	if runtime.GOOS != "windows" {
		return
	}
	dll, err := syscall.LoadDLL("kernel32.dll")
	if err != nil {
		return
	}
	proc, err := dll.FindProc("SetThreadExecutionState")
	if err != nil {
		return
	}
	if r, _, _ := proc.Call(0x80000003); r != 0 {
		logf("🔋 已防止系统休眠/息屏")
	}
}

func initLog() {
	f, err := os.OpenFile("智慧投屏.log", os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return
	}
	logFile = f
}

func logf(format string, args ...interface{}) {
	line := fmt.Sprintf(format, args...)
	fmt.Println(line)
	if logFile != nil {
		_, _ = logFile.WriteString(time.Now().Format("2006-01-02 15:04:05 ") + line + "\n")
	}
}

func waitExit() {
	fmt.Println("按回车退出...")
	_, _ = fmt.Scanln()
}
