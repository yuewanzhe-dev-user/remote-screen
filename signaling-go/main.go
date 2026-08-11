package main

// 智慧投屏 - 黑板端 (WebRTC 信令 + 接收页 + 局域网发现)
// =====================================================
// 老师双击本程序即可:
//   1. 起 HTTP 提供接收页 (内嵌 index.html,无需 Node/ffmpeg)
//   2. 起 WebSocket 信令 /ws ,转发 offer/answer/ice
//   3. UDP:19999 广播,让 pad 自动发现(无需手输 IP/房间)
//   4. 自动打开本机浏览器全屏等待投屏
//   5. 同房间新 pad 顶替旧 pad(发 kicked)
//
// 运行:
//   智慧投屏.exe                     端口 8080,名称「智慧黑板」
//   智慧投屏.exe 8080                自定义端口
//   智慧投屏.exe 8080 "三年二班"     自定义端口和显示名
//
// 发现协议(与 pad BoardDiscovery 对齐):
//   每 2 秒 UDP 广播 → 255.255.255.255:19999
//   {"name":"三年二班","port":8080,"room":"default","type":"webrtc"}
//   pad 也可发 {"cmd":"QUERY"},本端单播回应

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
	defaultRoom   = "default" // 一块黑板一个进程,固定房间,学生无感
	discoveryPort = 19999
)

var (
	boardName   = defaultName
	boardRoom   = defaultRoom
	boardPort   = defaultPort
	broadcastOK = false
	logFile     *os.File

	upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool { return true },
	}

	roomsMu sync.Mutex
	// room → 两端连接
	rooms = map[string]*roomState{}
)

// wsClient 带写锁,避免并发 Write 崩溃
type wsClient struct {
	conn *websocket.Conn
	mu   sync.Mutex
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
	Name string `json:"name,omitempty"`
	Port int    `json:"port,omitempty"`
	IP   string `json:"ip,omitempty"`
	Room string `json:"room,omitempty"`
	Type string `json:"type,omitempty"` // "webrtc"
	Cmd  string `json:"cmd,omitempty"`  // QUERY
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

	initLog()
	defer func() {
		if logFile != nil {
			logFile.Close()
		}
	}()
	preventSleep()

	// 静态页: / 与 /?room=...
	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		logf("❌ 内嵌网页资源失败: %v", err)
		waitExit()
		return
	}
	fileServer := http.FileServer(http.FS(sub))
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// 根路径强制走 index.html;其它静态资源交给 FileServer
		if r.URL.Path == "/" || strings.HasPrefix(r.URL.Path, "/?") {
			data, err := webFS.ReadFile("web/index.html")
			if err != nil {
				http.Error(w, "index.html missing", 500)
				return
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-cache")
			w.Write(data)
			return
		}
		fileServer.ServeHTTP(w, r)
	})
	mux.HandleFunc("/ws", handleWS)
	// 健康检查/本机信息(调试用)
	mux.HandleFunc("/api/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"name": boardName,
			"port": boardPort,
			"room": boardRoom,
			"type": "webrtc",
			"ips":  getLocalIPv4s(),
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
	logf("   端口: %d  房间: %s (学生无感)", boardPort, boardRoom)
	if len(ips) > 0 {
		logf("   本机 IP: %s", strings.Join(ips, ", "))
		logf("   浏览器: http://127.0.0.1:%d/?room=%s", boardPort, url.QueryEscape(boardRoom))
	}
	if broadcastOK {
		logf("📢 局域网广播中,pad 可自动发现")
	} else {
		logf("ℹ️ 广播未启动,pad 需在高级设置手输 IP")
	}
	logf("提示: 关闭本窗口退出; 日志 → 智慧投屏.log")
	fmt.Println("================================================")

	// 自动打开本机浏览器接收页
	go func() {
		time.Sleep(600 * time.Millisecond)
		openBrowser(fmt.Sprintf("http://127.0.0.1:%d/?room=%s", boardPort, url.QueryEscape(boardRoom)))
	}()

	if err := http.Serve(ln, mux); err != nil {
		logf("HTTP 服务结束: %v", err)
	}
}

// ===================== WebSocket 信令 =====================

func handleWS(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	roomName := q.Get("room")
	if roomName == "" {
		roomName = boardRoom
	}
	role := q.Get("role") // pad | browser
	if role != "pad" && role != "browser" {
		http.Error(w, "缺少 role=pad|browser", 400)
		return
	}

	raw, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		logf("WS upgrade 失败: %v", err)
		return
	}
	client := &wsClient{conn: raw}

	room := getRoom(roomName)

	// 登记 + 顶替
	if role == "pad" {
		room.padMu.Lock()
		old := room.pad
		room.pad = client
		room.padMu.Unlock()
		if old != nil {
			// 新 pad 顶替旧 pad
			_ = old.writeJSON(map[string]string{"type": "kicked"})
			old.close()
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
	})

	// 第二方加入 → 通知先到的一方
	if peerAlive {
		if peer := peerClient(room, peerRole); peer != nil {
			_ = peer.writeJSON(map[string]string{"type": "peer-online"})
		}
	}

	logf("[%s] %s 加入%s", roomName, role, map[bool]string{true: "(对方在线)", false: "(等待对方)"}[peerAlive])

	// 读消息并转发给对端
	for {
		_, data, err := raw.ReadMessage()
		if err != nil {
			break
		}
		peer := peerClient(room, peerRole)
		if peer != nil {
			_ = peer.writeMessage(websocket.TextMessage, data)
		}
	}

	// 清理:只有「当前槽位仍是自己」才算真正离开
	// (被顶替的旧 pad 退出时不要清新 pad,也不要给 browser 发 peer-left)
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
	// 房间空则删除
	roomsMu.Lock()
	if peerClient(room, "pad") == nil && peerClient(room, "browser") == nil {
		delete(rooms, roomName)
	}
	roomsMu.Unlock()
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
			resp := discoveryMsg{
				Name: boardName,
				Port: port,
				Room: boardRoom,
				Type: "webrtc",
			}
			data, _ := json.Marshal(resp)
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
		msg := discoveryMsg{
			Name: boardName,
			Port: port,
			Room: boardRoom,
			Type: "webrtc",
		}
		data, _ := json.Marshal(msg)
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
		// 用 cmd start 打开默认浏览器
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
	// ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
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
	fmt.Scanln()
}
