package main

// 智慧投屏 - 黑板端接收器 (Go 版,一键启动)
// =====================================================
// 监听 TCP,接收 pad 推来的 H.264 流,调用 ffmpeg 解码显示。
//
// 协议(与 pad 端 ScreenCastService 对齐):
//   连接建立后前 8 字节 = [width(4,big-endian)][height(4,big-endian)]
//   随后 = H.264 裸流
//
// 运行(双击 投屏接收端.exe 即可,或在命令行):
//   投屏接收端.exe            默认端口 8888
//   投屏接收端.exe 9000       自定义端口
//
// 编译:go build -o 投屏接收端.exe main.go
// 依赖:需要 ffmpeg.exe 在同目录或在 PATH 中

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"sync"
)

const defaultPort = 8888

func main() {
	port := defaultPort
	if len(os.Args) > 1 {
		if _, err := fmt.Sscanf(os.Args[1], "%d", &port); err != nil {
			fmt.Println("端口参数无效,使用默认 8888")
			port = defaultPort
		}
	}

	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		fmt.Printf("❌ 监听失败:%v(检查端口 %d 是否被占用)\n", err, port)
		fmt.Println("按回车退出...")
		fmt.Scanln()
		return
	}
	fmt.Printf("✅ 黑板端已启动,监听 0.0.0.0:%d,等待 pad 连接…\n", port)
	fmt.Println("提示:关闭本窗口或按 Ctrl+C 退出")

	for {
		conn, err := ln.Accept()
		if err != nil {
			fmt.Printf("接受连接失败:%v\n", err)
			continue
		}
		// 一个 pad 一个 goroutine
		go serveClient(conn)
	}
}

func serveClient(conn net.Conn) {
	addr := conn.RemoteAddr().String()
	fmt.Printf("📱 pad 已连接:%s\n", addr)
	defer func() {
		conn.Close()
		fmt.Printf("📱 %s 断开\n", addr)
	}()

	// 1. 先读 8 字节分辨率头
	header := make([]byte, 8)
	if _, err := io.ReadFull(conn, header); err != nil {
		fmt.Printf("[%s] 未发送分辨率头:%v\n", addr, err)
		return
	}
	width := binary.BigEndian.Uint32(header[0:4])
	height := binary.BigEndian.Uint32(header[4:8])
	fmt.Printf("[%s] 收到分辨率:%dx%d\n", addr, width, height)

	// 2. 找 ffmpeg(先同目录,再 PATH)
	ffmpegPath := findFFmpeg()
	if ffmpegPath == "" {
		fmt.Println("❌ 找不到 ffmpeg.exe!请把 ffmpeg.exe 放到本程序同目录,或加入 PATH。")
		return
	}

	// 3. 启动 ffmpeg:从 stdin 收 H.264,解码后用 SDL 窗口显示
	// 注意:新版 ffmpeg 的 h264 raw demuxer 不再支持 -video_size,分辨率解码器会自己从流里读
	cmd := exec.Command(ffmpegPath,
		"-probesize", "32",
		"-analyzeduration", "0",
		"-f", "h264",
		"-i", "pipe:0",
		"-pix_fmt", "yuv420p",
		"-f", "sdl", "智慧投屏 - 黑板端",
	)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	stdin, err := cmd.StdinPipe()
	if err != nil {
		fmt.Printf("[%s] 启动 ffmpeg 失败:%v\n", addr, err)
		return
	}
	if err := cmd.Start(); err != nil {
		fmt.Printf("[%s] 启动 ffmpeg 失败:%v(请确认 ffmpeg.exe 可用)\n", addr, err)
		return
	}
	fmt.Printf("[%s] ffmpeg 已启动,等待画面…\n", addr)

	// 4. 持续把 pad 发来的数据喂给 ffmpeg
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		buf := make([]byte, 65536)
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				if _, werr := stdin.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()

	wg.Wait()
	stdin.Close()
	cmd.Wait()
}

// findFFmpeg 先找同目录下的 ffmpeg.exe,找不到再查 PATH
func findFFmpeg() string {
	// 1. 同目录
	if exe, err := os.Executable(); err == nil {
		dir := exe[:len(exe)-len("\\投屏接收端.exe")]
		candidate := dir + "\\ffmpeg.exe"
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
		// 退化:工作目录
		if _, err := os.Stat("ffmpeg.exe"); err == nil {
			return "ffmpeg.exe"
		}
	}
	// 2. PATH
	if p, err := exec.LookPath("ffmpeg"); err == nil {
		return p
	}
	return ""
}
