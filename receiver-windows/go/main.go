package main

// 智慧投屏 - 黑板端接收器 (Go 版,一键启动)
// =====================================================
// 1. 监听 TCP,接收 pad 推来的 H.264 流,调用 ffplay 解码全屏显示
// 2. 通过 mDNS 在局域网广播自身,让 pad 自动发现(无需手输 IP)
// 3. 多 pad 冲突处理:新的 pad 连上自动顶掉旧的
// 4. 投屏质量统计:每隔几秒打印码率/帧数/时长
//
// 协议(与 pad 端 ScreenCastService 对齐):
//   连接建立后前 8 字节 = [width(4,big-endian)][height(4,big-endian)]
//   随后 = H.264 裸流
//
// 运行(双击 投屏接收端.exe 即可):
//   投屏接收端.exe            默认端口 8888
//   投屏接收端.exe 9000       自定义端口
//
// 依赖:需要 ffplay.exe 在同目录(ffmpeg 套件自带)

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"sync"
	"time"

	"github.com/hashicorp/mdns"
)

const (
	defaultPort = 8888
	boardName   = "智慧黑板" // 显示给 pad 的名称(mDNS 实例名)
	serviceType = "_smartboard._tcp."
)

// 全局:当前正在投屏的会话。新的 pad 连上会把旧的顶掉(冲突处理)
var (
	currentMu      sync.Mutex
	currentCancel  func()  // 终止当前投屏会话的函数
	currentPadAddr string  // 当前投屏的 pad 地址
	stats          = &streamStats{}
	server         *mdns.Server
)

// 投屏质量统计
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

func main() {
	port := defaultPort
	if len(os.Args) > 1 {
		if _, err := fmt.Sscanf(os.Args[1], "%d", &port); err != nil {
			fmt.Println("端口参数无效,使用默认 8888")
			port = defaultPort
		}
	}

	// 启动 mDNS 广播(让 pad 自动发现本黑板)
	startMDNSBroadcast(port)

	// 根据 mDNS 实际状态显示提示
	if mdnsOK {
		fmt.Printf("📢 局域网广播中(名称:%s),pad 可自动发现\n", boardName)
	} else {
		fmt.Println("ℹ️ mDNS 未启动,pad 需手输 IP 投屏")
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		fmt.Printf("❌ 监听失败:%v(检查端口 %d 是否被占用)\n", err, port)
		fmt.Println("按回车退出...")
		fmt.Scanln()
		return
	}

	// 启动质量统计后台打印
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

// mdnsOK 标记 mDNS 是否成功启动(给 main 显示正确提示用)
var mdnsOK = false

// startMDNSBroadcast 用 hashicorp/mdns 库在局域网广播本黑板
func startMDNSBroadcast(port int) {
	// 构造 mDNS 服务:pad 端搜 _smartboard._tcp. 能发现
	// host 必须是 FQDN(完整域名,以点结尾),否则 mdns 库报错
	// 用 .local. 后缀是 mDNS 标准的本地域名约定
	rawHost, _ := os.Hostname()
	if rawHost == "" {
		rawHost = "smartboard"
	}
	host := rawHost + ".local."

	// 收集本机 IPv4 作为服务的 IP 列表(让 pad 解析时直接拿到 IP)
	var ips []net.IP
	for _, ipStr := range getLocalIPv4s() {
		ips = append(ips, net.ParseIP(ipStr))
	}

	service, err := mdns.NewMDNSService(
		boardName,            // 实例名,pad 端会显示这个名字
		serviceType,          // _smartboard._tcp.
		"",                   // domain
		host,                 // 主机名(FQDN,以点结尾)
		port,                 // 端口
		ips,                  // 本机 IP 列表(类型 []net.IP)
		[]string{"room=3-2"}, // TXT 记录,可放教室号等
	)
	if err != nil {
		fmt.Printf("⚠️ mDNS 服务初始化失败:%v(不影响手输 IP 投屏)\n", err)
		return
	}

	s, err := mdns.NewServer(&mdns.Config{Zone: service})
	if err != nil {
		fmt.Printf("⚠️ mDNS 启动失败:%v(不影响手输 IP 投屏)\n", err)
		return
	}
	server = s
	mdnsOK = true
}

// getLocalIPv4s 获取本机非环回的 IPv4 地址列表
func getLocalIPv4s() []string {
	var ips []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ips
	}
	for _, a := range addrs {
		if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ipnet.IP.To4() != nil {
				ips = append(ips, ipnet.IP.String())
			}
		}
	}
	return ips
}

// 启动一个后台 goroutine,每 5 秒打印一次投屏质量
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

// serveClient 处理一个 pad 的连接
func serveClient(conn net.Conn) {
	addr := conn.RemoteAddr().String()

	// === 多 pad 冲突处理:新连接顶掉旧的 ===
	currentMu.Lock()
	if currentCancel != nil {
		fmt.Printf("🔄 新 pad 连接(%s),断开当前投屏(%s)\n", addr, currentPadAddr)
		currentCancel() // 终止旧的投屏会话
	}
	ctxCancel := make(chan struct{})
	currentCancel = func() { close(ctxCancel) }
	currentPadAddr = addr
	currentMu.Unlock()

	// 退出时清理自己
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

	// 1. 先读 8 字节分辨率头
	header := make([]byte, 8)
	if _, err := io.ReadFull(conn, header); err != nil {
		fmt.Printf("[%s] 未发送分辨率头:%v\n", addr, err)
		return
	}
	width := binary.BigEndian.Uint32(header[0:4])
	height := binary.BigEndian.Uint32(header[4:8])
	fmt.Printf("[%s] 收到分辨率:%dx%d\n", addr, width, height)

	// 2. 找 ffplay
	ffplayPath := findFFplay()
	if ffplayPath == "" {
		fmt.Println("❌ 找不到 ffplay.exe!")
		fmt.Println("   请到你下载的 ffmpeg 压缩包的 bin 目录,把 ffplay.exe 拷到本程序同目录。")
		return
	}

	// 3. 启动 ffplay:-fs 全屏,-flags low_delay 低延迟
	cmd := exec.Command(ffplayPath,
		"-fs", // 启动即全屏(full screen)
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

	// 4. 持续把 pad 发来的数据喂给 ffplay,同时统计流量
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

	// 等待会话被取消(被新连接顶掉),或 ffplay 自然结束
	cmdDone := make(chan error, 1)
	go func() { cmdDone <- cmd.Wait() }()
	select {
	case <-ctxCancel:
		// 被新连接顶掉,杀掉 ffplay
		if cmd.Process != nil {
			cmd.Process.Kill()
		}
		fmt.Printf("[%s] 投屏被新连接顶掉\n", addr)
	case <-cmdDone:
		// ffplay 被用户关闭或异常退出
	}
	stdin.Close()
}

// findFFplay 先找同目录下的 ffplay.exe,找不到再查 PATH
func findFFplay() string {
	// 1. 同目录
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
	// 2. PATH
	if p, err := exec.LookPath("ffplay"); err == nil {
		return p
	}
	return ""
}
