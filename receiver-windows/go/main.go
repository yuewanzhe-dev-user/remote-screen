package main

// 智慧投屏 - 黑板端接收器 (Go 版,一键启动)
// =====================================================
// 1. 监听 TCP,接收 pad 推来的 H.264 流,调用 ffplay 解码全屏显示
// 2. 通过 UDP 广播在局域网宣告自身,让 pad 自动发现(无需手输 IP)
//    —— 不用 mDNS 多播(很多 WiFi AP 不转发多播),改用 UDP 广播
//       (几乎所有 AP 都转发广播,兼容性远好于多播)
// 3. 多 pad 冲突处理:新的 pad 连上自动顶掉旧的
// 4. 投屏质量统计:每隔几秒打印码率/帧数/时长
//
// 投屏协议(与 pad 端 ScreenCastService 对齐):
//   连接建立后前 8 字节 = [width(4,big-endian)][height(4,big-endian)]
//   随后 = H.264 裸流
//
// 广播发现协议(与 pad 端 BoardDiscovery 对齐):
//   黑板每 2 秒往 UDP 端口 19999 广播一个 JSON:
//     {"name":"智慧黑板","port":8888,"ip":"192.168.x.x"}
//   pad 监听 19999,收到广播即发现黑板。
//   pad 也可主动发 {"cmd":"QUERY"} 到 19999,黑板收到后单播回应。
//
// 运行(双击 投屏接收端.exe 即可):
//   投屏接收端.exe            默认端口 8888
//   投屏接收端.exe 9000       自定义端口
//
// 依赖:需要 ffplay.exe 在同目录(ffmpeg 套件自带)

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

const (
	defaultPort   = 8888
	boardName     = "智慧黑板"
	discoveryPort = 19999 // pad/黑板 约定的发现端口
)

var (
	currentMu      sync.Mutex
	currentCancel  func()
	currentPadAddr string
	stats          = &streamStats{}
	broadcastOK    = false
)

type streamStats struct {
	mu         sync.Mutex
	startTime  time.Time
	totalBytes int64
	frames     int
}

func (s *streamStats) add(n int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.totalBytes += int64(n)
	s.frames++
}

func (s *streamStats) snapshot() (duration time.Duration, kbps float64, frames int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := time.Since(s.startTime)
	if d.Seconds() > 0 {
		kbps = float64(s.totalBytes) / 1024.0 / d.Seconds()
	}
	return d, kbps, s.frames
}

func (s *streamStats) reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.startTime = time.Now()
	s.totalBytes = 0
	s.frames = 0
}

// 发现协议的消息格式
type discoveryMsg struct {
	Name string `json:"name"` // 黑板名称
	Port int    `json:"port"` // 投屏服务端口
	IP   string `json:"ip"`   // 黑板 IP(辅助)
	Cmd  string `json:"cmd"`  // QUERY 表示 pad 主动查询
}

func main() {
	port := defaultPort
	if len(os.Args) > 1 {
		if _, err := fmt.Sscanf(os.Args[1], "%d", &port); err != nil {
			fmt.Println("端口参数无效,使用默认 8888")
			port = defaultPort
		}
	}

	// 启动 UDP 广播发现服务
	startDiscoveryService(port)

	if broadcastOK {
		fmt.Printf("📢 局域网广播中(名称:%s),pad 可自动发现\n", boardName)
	} else {
		fmt.Println("ℹ️ 广播服务未启动,pad 需手输 IP 投屏")
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		fmt.Printf("❌ 监听失败:%v(检查端口 %d 是否被占用)\n", err, port)
		fmt.Println("按回车退出...")
		fmt.Scanln()
		return
	}

	go statsPrinter()

	fmt.Println("================================================")
	fmt.Printf("✅ 黑板端已启动,监听 0.0.0.0:%d,等待 pad 连接…\n", port)
	fmt.Println("提示:关闭本窗口或按 Ctrl+C 退出")
	fmt.Println("================================================")

	for {
		conn, err := ln.Accept()
		if err != nil {
			fmt.Printf("接受连接失败:%v\n", err)
			continue
		}
		go serveClient(conn)
	}
}

// =====================================================
// UDP 广播发现服务
// =====================================================
//
// 两个职责:
//  1. 主动广播:每 2 秒往 255.255.255.255:19999 发一次宣告(让被动监听的 pad 发现)
//  2. 响应查询:监听 19999,收到 pad 发的 QUERY 就单播回应(让主动查询的 pad 发现)
//
// 双管齐下,覆盖 pad 端两种工作模式。

func startDiscoveryService(port int) {
	ips := getLocalIPv4s()
	if len(ips) == 0 {
		fmt.Println("⚠️ 未找到本机 IPv4 地址,广播发现无法启动")
		return
	}

	// 监听 UDP 19999,收 pad 的查询并回应
	addr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
		fmt.Printf("⚠️ 广播地址解析失败:%v\n", err)
		return
	}
	listener, err := net.ListenUDP("udp4", addr)
	if err != nil {
		fmt.Printf("⚠️ 广播监听失败:%v(检查端口 %d 是否被占用)\n", err, discoveryPort)
		return
	}
	go respondDiscoveryQueries(listener, port, ips)

	// 主动周期广播
	go broadcastLoop(port, ips)

	broadcastOK = true
}

// respondDiscoveryQueries 监听 pad 发来的查询,收到就回应
func respondDiscoveryQueries(conn *net.UDPConn, port int, ips []string) {
	buf := make([]byte, 1024)
	for {
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		// 尝试解析,pad 可能发 {"cmd":"QUERY"}
		var msg discoveryMsg
		isQuery := false
		if json.Unmarshal(buf[:n], &msg) == nil {
			if msg.Cmd == "QUERY" {
				isQuery = true
			}
		} else if strings.Contains(string(buf[:n]), "QUERY") {
			// 兼容非 JSON 的查询
			isQuery = true
		}
		if isQuery {
			// 单播回应 pad(IP 留空,pad 用来源地址)
			resp := discoveryMsg{Name: boardName, Port: port}
			data, _ := json.Marshal(resp)
			conn.WriteToUDP(data, remote)
		}
	}
}

// broadcastLoop 每 2 秒向 255.255.255.255:19999 广播一次服务宣告
//
// 关键:从每个本机 IP 各发一次广播。
// 因为 UDP 包的来源地址 = 发送 socket 绑定的本地地址,
// pad 端用这个来源地址连接黑板。如果只从一个网卡发,
// 可能选到 pad 不可达的网卡(如手动设了静态 IP 的有线网卡)。
// 每个网卡都发一次,pad 就能收到来自各个网卡的包,
// 其中和 pad 同网段的那个(WLAN)来源地址是 pad 能真正连上的。
// 多个来源地址在 pad 端会合并(同一个黑板名只保留最后收到的)。
func broadcastLoop(port int, ips []string) {
	bcastAddr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf("255.255.255.255:%d", discoveryPort))
	if err != nil {
		return
	}
	for {
		msg := discoveryMsg{Name: boardName, Port: port} // IP 留空,pad 用来源地址
		data, _ := json.Marshal(msg)
		// 对每个本机 IP,绑定该 IP 建立 socket 发广播
		for _, ip := range ips {
			localAddr, err := net.ResolveUDPAddr("udp4", ip+":0")
			if err != nil {
				continue
			}
			conn, err := net.DialUDP("udp4", localAddr, bcastAddr)
			if err != nil {
				continue
			}
			conn.Write(data)
			conn.Close()
		}
		time.Sleep(2 * time.Second)
	}
}

// =====================================================
// 投屏服务
// =====================================================

func statsPrinter() {
	for {
		time.Sleep(5 * time.Second)
		currentMu.Lock()
		addr := currentPadAddr
		currentMu.Unlock()
		if addr == "" {
			continue
		}
		d, kbps, frames := stats.snapshot()
		fmt.Printf("📊 [%s] 已投 %s | %.0f KB/s | %d 帧\n", addr, durStr(d), kbps, frames)
	}
}

func durStr(d time.Duration) string {
	d = d.Round(time.Second)
	m := int(d.Minutes())
	s := int(d.Seconds()) % 60
	return fmt.Sprintf("%02d:%02d", m, s)
}

func serveClient(conn net.Conn) {
	addr := conn.RemoteAddr().String()

	// === 多 pad 冲突处理:新连接顶掉旧的 ===
	currentMu.Lock()
	if currentCancel != nil {
		fmt.Printf("🔄 新 pad 连接(%s),断开当前投屏(%s)\n", addr, currentPadAddr)
		currentCancel()
	}
	ctxCancel := make(chan struct{})
	currentCancel = func() { close(ctxCancel) }
	currentPadAddr = addr
	currentMu.Unlock()

	defer func() {
		currentMu.Lock()
		if currentPadAddr == addr {
			currentPadAddr = ""
			currentCancel = nil
		}
		currentMu.Unlock()
		conn.Close()
		fmt.Printf("📱 %s 断开\n", addr)
	}()

	fmt.Printf("📱 pad 已连接:%s\n", addr)

	header := make([]byte, 8)
	if _, err := io.ReadFull(conn, header); err != nil {
		fmt.Printf("[%s] 未发送分辨率头:%v\n", addr, err)
		return
	}
	width := binary.BigEndian.Uint32(header[0:4])
	height := binary.BigEndian.Uint32(header[4:8])
	fmt.Printf("[%s] 收到分辨率:%dx%d\n", addr, width, height)

	ffplayPath := findFFplay()
	if ffplayPath == "" {
		fmt.Println("❌ 找不到 ffplay.exe!")
		fmt.Println("   请到你下载的 ffmpeg 压缩包的 bin 目录,把 ffplay.exe 拷到本程序同目录。")
		return
	}

	cmd := exec.Command(ffplayPath,
		"-fs",
		"-flags", "low_delay",
		"-fflags", "nobuffer",
		"-framedrop",
		"-probesize", "32",
		"-analyzeduration", "0",
		"-f", "h264",
		"-i", "pipe:0",
		"-window_title", "智慧投屏 - 黑板端",
		"-left", "0",
		"-top", "0",
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	stdin, err := cmd.StdinPipe()
	if err != nil {
		fmt.Printf("[%s] 启动 ffplay 失败:%v\n", addr, err)
		return
	}
	if err := cmd.Start(); err != nil {
		fmt.Printf("[%s] 启动 ffplay 失败:%v(请确认 ffplay.exe 可用)\n", addr, err)
		return
	}
	fmt.Printf("[%s] ffplay 已启动(全屏),等待画面…\n", addr)
	stats.reset()

	go func() {
		buf := make([]byte, 65536)
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				stats.add(n)
				if _, werr := stdin.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()

	cmdDone := make(chan error, 1)
	go func() { cmdDone <- cmd.Wait() }()
	select {
	case <-ctxCancel:
		if cmd.Process != nil {
			cmd.Process.Kill()
		}
		fmt.Printf("[%s] 投屏被新连接顶掉\n", addr)
	case <-cmdDone:
	}
	stdin.Close()
}

func getLocalIPv4s() []string {
	var ips []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ips
	}
	for _, a := range addrs {
		if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			ip4 := ipnet.IP.To4()
			if ip4 == nil {
				continue
			}
			// 过滤掉无效地址:
			//   169.254.x.x —— APIPA 自动私有地址(未连网网卡自动分配的,不可用)
			//   这样只广播真正能用的 IP(如 WiFi 的 192.168.x.x)
			if ip4[0] == 169 && ip4[1] == 254 {
				continue
			}
			ips = append(ips, ip4.String())
		}
	}
	return ips
}

func findFFplay() string {
	if exe, err := os.Executable(); err == nil {
		dir := exe[:len(exe)-len("\\投屏接收端.exe")]
		candidate := dir + "\\ffplay.exe"
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
		if _, err := os.Stat("ffplay.exe"); err == nil {
			return "ffplay.exe"
		}
	}
	if p, err := exec.LookPath("ffplay"); err == nil {
		return p
	}
	return ""
}
