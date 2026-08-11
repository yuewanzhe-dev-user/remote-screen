package main

// 智慧投屏 - 黑板端接收器 (Go 版,一键启动)
// =====================================================
// 1. 监听 TCP,接收 pad 推来的 H.264 流,调用 ffplay 解码显示
// 2. 通过 UDP 广播在局域网宣告自身,让 pad 自动发现(无需手输 IP)
//    —— 不用 mDNS 多播(很多 WiFi AP 不转发多播),改用 UDP 广播
//       (几乎所有 AP 都转发广播,兼容性远好于多播)
// 3. 多 pad 冲突处理:新的 pad 连上自动顶掉旧的(先发 kicked 通知,旧 pad 不再重连)
// 4. 控制消息:心跳/顶替/停止,pad 据此区分"网络故障重连"和"被顶替/停止不重连"
// 5. 投屏质量统计:每隔几秒打印码率/帧数/时长
// 6. 防止系统休眠/息屏 + 日志落盘(投屏接收端.log)
//
// 投屏协议 v2(与 pad 端 ScreenCastService 对齐,需两端同时更新):
//   连接建立后,pad 依次发送:
//     1. 一行 JSON 身份:{"v":1,"name":"设备型号"}(兼容旧 pad,首字节不是 '{' 就跳过)
//     2. 8 字节分辨率头:[width(4,big-endian)][height(4,big-endian)]
//     3. 随后 = H.264 裸流
//   黑板端 → pad 的控制消息(行分隔 JSON):
//     {"cmd":"hb"}      每 5 秒心跳一次,pad 据此判断黑板端是否失联
//     {"cmd":"kicked"}  该 pad 被新连接顶替(断开前发,pad 收到后不再重连)
//     {"cmd":"stop"}    黑板端结束投屏,老师关掉了画面窗口(断开前发,pad 收到后停止)
//
// 广播发现协议(与 pad 端 BoardDiscovery 对齐):
//   黑板每 2 秒往 UDP 端口 19999 广播一个 JSON:
//     {"name":"黑板名称","port":8888,"ip":"192.168.x.x"}
//   pad 监听 19999,收到广播即发现黑板。
//   pad 也可主动发 {"cmd":"QUERY"} 到 19999,黑板收到后单播回应。
//
// 运行(双击 投屏接收端.exe 即可):
//   投屏接收端.exe                     默认端口 8888,名称"智慧黑板"
//   投屏接收端.exe 9000                自定义端口
//   投屏接收端.exe 9000 "三年二班黑板"  自定义端口和黑板名称(显示在广播和窗口标题里)
//
// 依赖:需要 ffplay.exe 在同目录(ffmpeg 套件自带)

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	defaultPort    = 8888
	defaultName    = "智慧黑板"
	discoveryPort  = 19999        // pad/黑板 约定的发现端口
	readIdleLimit  = 15 * time.Second // pad 连续无数据超过该时间,判定断流
	heartbeatEvery = 5 * time.Second  // 心跳间隔,pad 端据此检测黑板失联
)

var (
	currentMu      sync.Mutex
	currentCancel  func()  // 终止当前投屏会话的函数
	currentPadAddr string  // 当前投屏的 pad 地址
	stats          = &streamStats{}
	broadcastOK    = false
	logFile        *os.File
	boardName      = defaultName
)

// 投屏会话管理:用 currentPadAddr 判断"我还是不是当前活动会话"
// 顶替时只清理属于自己的全局状态,避免并发下错乱

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

	// 启动 UDP 广播发现服务
	startDiscoveryService(port)

	if broadcastOK {
		logf("📢 局域网广播中(名称:%s),pad 可自动发现", boardName)
	} else {
		logf("ℹ️ 广播服务未启动,pad 需手输 IP 投屏")
	}

	// 显示本机可用 IP(方便老师手输兜底)
	ips := getLocalIPv4s()
	if len(ips) > 0 {
		logf("📋 本机 IP:%s(手输投屏用这个)", ips[0])
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		logf("❌ 监听失败:%v(检查端口 %d 是否被占用)", err, port)
		fmt.Println("按回车退出...")
		fmt.Scanln()
		return
	}

	go statsPrinter()

	fmt.Println("================================================")
	if len(ips) > 0 {
		logf("✅ 黑板端已启动,名称:%s,监听 %s:%d,等待 pad 连接…", boardName, ips[0], port)
	} else {
		logf("✅ 黑板端已启动,名称:%s,监听 0.0.0.0:%d,等待 pad 连接…", boardName, port)
	}
	logf("提示:关闭本窗口或按 Ctrl+C 退出;日志写入 投屏接收端.log")
	fmt.Println("================================================")

	for {
		conn, err := ln.Accept()
		if err != nil {
			logf("接受连接失败:%v", err)
			continue
		}
		go serveClient(conn)
	}
}

// =====================================================
// 日志与系统保活
// =====================================================

// initLog 打开日志文件(失败则仅控制台输出)
func initLog() {
	f, err := os.OpenFile("投屏接收端.log", os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return
	}
	logFile = f
}

// logf 同时输出到控制台和日志文件
func logf(format string, args ...interface{}) {
	line := fmt.Sprintf(format, args...)
	fmt.Println(line)
	if logFile != nil {
		logFile.WriteString(time.Now().Format("2006-01-02 15:04:05 ") + line + "\n")
	}
}

// preventSleep 防止系统休眠/息屏:课堂投屏期间黑板电脑不能睡
func preventSleep() {
	dll, err := syscall.LoadDLL("kernel32.dll")
	if err != nil {
		return
	}
	proc, err := dll.FindProc("SetThreadExecutionState")
	if err != nil {
		return
	}
	// ES_CONTINUOUS(0x80000000) | ES_SYSTEM_REQUIRED(0x1) | ES_DISPLAY_REQUIRED(0x2)
	if r, _, _ := proc.Call(0x80000003); r != 0 {
		logf("🔋 已防止系统休眠/息屏")
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
		logf("⚠️ 未找到本机 IPv4 地址,广播发现无法启动")
		return
	}

	// 监听 UDP 19999,收 pad 的查询并回应
	addr, err := net.ResolveUDPAddr("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
		logf("⚠️ 广播地址解析失败:%v", err)
		return
	}
	listener, err := net.ListenUDP("udp4", addr)
	if err != nil {
		logf("⚠️ 广播监听失败:%v(检查端口 %d 是否被占用)", err, discoveryPort)
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
		logf("📊 [%s] 已投 %s | %.0f KB/s | %d 帧", addr, durStr(d), kbps, frames)
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
	// 用 currentCancel 通道通知旧会话退出
	currentMu.Lock()
	if currentPadAddr != "" {
		logf("🔄 新 pad 连接(%s),断开当前投屏(%s)", addr, currentPadAddr)
		if currentCancel != nil {
			currentCancel() // 通知旧会话退出
		}
	}
	ctxCancel := make(chan struct{})
	currentCancel = func() { close(ctxCancel) }
	currentPadAddr = addr
	currentMu.Unlock()

	// 等一小会儿让旧 ffplay 退出(避免两个 ffplay 抢同一个画面)
	time.Sleep(300 * time.Millisecond)

	defer func() {
		conn.Close()
		// 只有当我还是当前活动会话时,才清理全局状态
		currentMu.Lock()
		if currentPadAddr == addr {
			currentPadAddr = ""
			currentCancel = nil
		}
		currentMu.Unlock()
		logf("📱 %s 断开", addr)
	}()

	// 用带缓冲的 reader,身份行和 H.264 流共用
	reader := bufio.NewReaderSize(conn, 256*1024)

	// === 协议 v2:先读一行 JSON 身份(旧 pad 没有身份行,首字节不是 '{' 就跳过) ===
	padName := ""
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if b, err := reader.Peek(1); err == nil && b[0] == '{' {
		if line, err := reader.ReadString('\n'); err == nil {
			var id struct {
				V    int    `json:"v"`
				Name string `json:"name"`
			}
			if json.Unmarshal([]byte(line), &id) == nil && id.Name != "" {
				padName = id.Name
			}
		}
	}
	if padName != "" {
		logf("📱 学生平板(%s) 已连接:%s", padName, addr)
	} else {
		logf("📱 pad 已连接:%s", addr)
	}

	// === 分辨率头 ===
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	header := make([]byte, 8)
	if _, err := io.ReadFull(reader, header); err != nil {
		logf("[%s] 未发送分辨率头:%v", addr, err)
		return
	}
	width := binary.BigEndian.Uint32(header[0:4])
	height := binary.BigEndian.Uint32(header[4:8])
	logf("[%s] 收到分辨率:%dx%d", addr, width, height)

	ffplayPath := findFFplay()
	if ffplayPath == "" {
		logf("❌ 找不到 ffplay.exe!")
		logf("   请到你下载的 ffmpeg 压缩包的 bin 目录,把 ffplay.exe 拷到本程序同目录。")
		return
	}

	// 不用 -fs(全屏会占满屏幕、没有标题栏,无法最小化)
	// 改用普通窗口模式,带标题栏的最小化/最大化/关闭按钮
	// 通过 -x -y 设置较大的初始尺寸,老师可手动最大化或调整
	cmd := exec.Command(ffplayPath,
		"-flags", "low_delay",
		"-fflags", "nobuffer",
		"-framedrop",
		"-probesize", "32",
		"-analyzeduration", "0",
		"-f", "h264",
		"-i", "pipe:0",
		"-window_title", "智慧投屏 - "+boardName,
		"-x", "1280",
		"-y", "720",
		"-left", "0",
		"-top", "0",
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	stdin, err := cmd.StdinPipe()
	if err != nil {
		logf("[%s] 启动 ffplay 失败:%v", addr, err)
		return
	}
	if err := cmd.Start(); err != nil {
		logf("[%s] 启动 ffplay 失败:%v(请确认 ffplay.exe 可用)", addr, err)
		return
	}
	logf("[%s] ffplay 已启动,等待画面…", addr)
	stats.reset()

	// === 心跳:每 5 秒发一次,pad 据此判断黑板端是否失联 ===
	hbStop := make(chan struct{})
	defer close(hbStop)
	go func() {
		t := time.NewTicker(heartbeatEvery)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
				if _, err := conn.Write([]byte(`{"cmd":"hb"}` + "\n")); err != nil {
					return
				}
			case <-hbStop:
				return
			}
		}
	}()

	// 读取 pad 数据并喂给 ffplay 的 goroutine
	// 每次读都带超时:pad 超过 readIdleLimit 无数据,判定断流(pad 猝死/息屏断网等)
	doneReading := make(chan struct{})
	go func() {
		defer close(doneReading)
		buf := make([]byte, 65536)
		for {
			conn.SetReadDeadline(time.Now().Add(readIdleLimit))
			n, err := reader.Read(buf)
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

	// 等待:被新连接顶掉 / pad 主动断开 / ffplay 被关
	cmdDone := make(chan error, 1)
	go func() { cmdDone <- cmd.Wait() }()

	select {
	case <-ctxCancel:
		// 被新连接顶掉:先通知旧 pad 别重连,再杀 ffplay
		conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
		conn.Write([]byte(`{"cmd":"kicked"}` + "\n"))
		time.Sleep(100 * time.Millisecond)
		if cmd.Process != nil {
			cmd.Process.Kill()
		}
		logf("[%s] 投屏被新连接顶掉,已通知旧 pad", addr)
	case <-doneReading:
		// pad 断开或断流,杀掉 ffplay
		if cmd.Process != nil {
			cmd.Process.Kill()
		}
		logf("[%s] pad 断开连接", addr)
	case <-cmdDone:
		// 老师关掉了 ffplay 窗口:通知 pad 停止投屏,别自动重连
		conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
		conn.Write([]byte(`{"cmd":"stop"}` + "\n"))
		time.Sleep(100 * time.Millisecond)
		logf("[%s] 黑板端关闭画面,已通知 pad 停止投屏", addr)
	}
	stdin.Close()
	cmd.Wait()
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
	dir, err := os.Getwd()
	if err != nil {
		dir = "."
	}
	if exe, err := os.Executable(); err == nil {
		dir = filepath.Dir(exe)
	}
	candidates := []string{
		filepath.Join(dir, "ffplay.exe"),
		"ffplay.exe",
	}
	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			return c
		}
	}
	if p, err := exec.LookPath("ffplay"); err == nil {
		return p
	}
	return ""
}
