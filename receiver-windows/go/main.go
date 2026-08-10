package main

// 智慧投屏 - 黑板端接收器 (Go 版,一键启动)
// =====================================================
// 1. 监听 TCP,接收 pad 推来的 H.264 流,调用 ffplay 解码全屏显示
// 2. 通过 mDNS 在局域网广播自身,让 pad 自动发现(无需手输 IP)
//    —— 纯标准库实现,主动周期广播 + 响应查询,不依赖第三方库
// 3. 多 pad 冲突处理:新的 pad 连上自动顶掉旧的
// 4. 投屏质量统计:每隔几秒打印码率/帧数/时长
//
// 协议(与 pad 端 ScreenCastService 对齐):
//   连接建立后前 8 字节 = [width(4,big-endian)][height(4,big-endian)]
//   隄后 = H.264 裸流
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
)

const (
	defaultPort   = 8888
	boardName     = "智慧黑板"
	serviceType   = "_smartboard._tcp."
	mdnsMulticast = "224.0.0.251:5353"
)

var (
	currentMu      sync.Mutex
	currentCancel  func()
	currentPadAddr string
	stats          = &streamStats{}
	mdnsOK         = false
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
// mDNS 广播(纯标准库实现)
// =====================================================
//
// 策略:既主动周期广播宣告,也响应 pad 发来的查询。
// 这样无论 pad 的 NsdManager 是被动监听还是主动查询,都能发现。
//
// 主动广播:每 3 秒往 224.0.0.251:5353 发一个包含 PTR/SRV/A 记录的响应包
// 响应查询:监听 5353,收到任意 DNS 查询就回一个完整的响应包

func startMDNSBroadcast(port int) {
	// 准备预编码的广播包(每个本机 IP 一个)
	ips := getLocalIPv4s()
	if len(ips) == 0 {
		fmt.Println("⚠️ 未找到本机 IPv4 地址,mDNS 无法启动")
		return
	}

	// 起一个监听 socket 响应查询
	addr, err := net.ResolveUDPAddr("udp4", mdnsMulticast)
	if err != nil {
		fmt.Printf("⚠️ mDNS 地址解析失败:%v\n", err)
		return
	}

	listener, err := net.ListenMulticastUDP("udp4", nil, addr)
	if err != nil {
		fmt.Printf("⚠️ mDNS 多播监听失败:%v\n", err)
		fmt.Println("   (不影响手输 IP 投屏)")
		// 即使监听失败,也尝试主动广播
	} else {
		go respondQueries(listener, port, ips)
	}

	// 主动广播 goroutine
	go announceLoop(port, ips)

	mdnsOK = true
}

// respondQueries 监听 5353,收到查询就回一个完整响应包
func respondQueries(conn *net.UDPConn, port int, ips []string) {
	buf := make([]byte, 1500)
	for {
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 12 {
			continue
		}
		// 收到任何 mDNS 查询,都回我们的服务宣告(单播回给查询者)
		packet := buildAnnouncement(port, ips)
		conn.WriteToUDP(packet, remote)
	}
}

// announceLoop 每 3 秒主动往多播地址发一次服务宣告
func announceLoop(port int, ips []string) {
	addr, err := net.ResolveUDPAddr("udp4", mdnsMulticast)
	if err != nil {
		return
	}
	for {
		packet := buildAnnouncement(port, ips)
		// 用独立的 socket 发,避免和监听 socket 冲突
		conn, err := net.DialUDP("udp4", nil, addr)
		if err == nil {
			conn.Write(packet)
			conn.Close()
		}
		time.Sleep(3 * time.Second)
	}
}

// buildAnnouncement 构造一个 mDNS 响应包,包含 PTR/SRV/A 三类记录
// 让 pad 的 NsdManager 能解析出服务名、端口、IP
func buildAnnouncement(port int, ips []string) []byte {
	var buf []byte
	instanceLabels := splitLabels(boardName)
	typeLabels := []string{"_smartboard", "_tcp", "local"}
	hostName := "smartboard.local"
	hostLabels := splitLabels(hostName)

	// DNS Header:响应,1 个回答
	header := []byte{
		0x00, 0x00, // Transaction ID
		0x84, 0x00, // Flags: response, authoritative answer
		0x00, 0x00, // Questions
		0x00, 0x00, // Answer RRs(后面填)
		0x00, 0x00, // Authority
		0x00, 0x00, // Additional
	}

	// 收集所有 answer 记录
	var answers [][]byte

	// PTR: _smartboard._tcp.local -> <实例名>._smartboard._tcp.local
	ptrName := encodeLabels(typeLabels)
	ptrRdata := encodeLabels(append(append([]string{}, instanceLabels...), typeLabels...))
	answers = append(answers, buildRecord(ptrName, 12, 4500, ptrRdata)) // type 12 = PTR, class 32768|IN

	// SRV: <实例名>._smartboard._tcp.local -> priority=0, weight=0, port, target=smartboard.local
	srvName := encodeLabels(append(append([]string{}, instanceLabels...), typeLabels...))
	srvRdata := []byte{0x00, 0x00, 0x00, 0x00} // priority + weight
	srvRdata = append(srvRdata, byte(port>>8), byte(port&0xff))
	srvRdata = append(srvRdata, encodeLabels(hostLabels)...)
	answers = append(answers, buildRecord(srvName, 33, 4500, srvRdata)) // type 33 = SRV

	// A: smartboard.local -> 每个本机 IP 一条 A 记录
	aName := encodeLabels(hostLabels)
	for _, ipStr := range ips {
		ip := net.ParseIP(ipStr).To4()
		if ip == nil {
			continue
		}
		answers = append(answers, buildRecord(aName, 1, 4500, ip)) // type 1 = A
	}

	// 回填 answer 数量
	binary.BigEndian.PutUint16(header[6:8], uint16(len(answers)))
	buf = append(buf, header...)
	for _, a := range answers {
		buf = append(buf, a...)
	}
	return buf
}

// buildRecord 构造一条 DNS 资源记录
func buildRecord(name []byte, rtype int, rclass int, rdata []byte) []byte {
	var rec []byte
	rec = append(rec, name...)
	rec = append(rec, byte(rtype>>8), byte(rtype&0xff))
	rec = append(rec, byte(rclass>>8), byte(rclass&0xff))
	rec = append(rec, 0, 0, 0, 75) // TTL 75 秒(mDNS 推荐值)
	rec = append(rec, byte(len(rdata)>>8), byte(len(rdata)&0xff))
	rec = append(rec, rdata...)
	return rec
}

// encodeLabels 把 ["a","b"] 编码成 DNS 标签格式: 1a1b0
func encodeLabels(parts []string) []byte {
	var out []byte
	for _, p := range parts {
		out = append(out, byte(len(p)))
		out = append(out, []byte(p)...)
	}
	out = append(out, 0x00)
	return out
}

// splitLabels 按非字母数字切分名字成多个标签(中文按整段处理)
func splitLabels(s string) []string {
	return []string{s}
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
			if ipnet.IP.To4() != nil {
				ips = append(ips, ipnet.IP.String())
			}
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
