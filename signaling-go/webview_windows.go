//go:build windows

package main

import (
	"runtime"
	"time"

	"github.com/jchv/go-webview2"
)

// openEmbeddedView 用 WebView2 真·单窗口显示接收页。
// 需要系统已装 Edge WebView2 Runtime(Win10/11 通常已有)。
// 返回 false 表示失败,调用方回退浏览器。
func openEmbeddedView(pageURL string) bool {
	// WebView2 要求 STA
	runtime.LockOSThread()

	w := webview2.NewWithOptions(webview2.WebViewOptions{
		Debug:     false,
		AutoFocus: true,
		WindowOptions: webview2.WindowOptions{
			Title:  "智慧投屏 - " + boardName,
			Width:  1280,
			Height: 800,
			IconId: 0,
			Center: true,
		},
	})
	if w == nil {
		logf("⚠️ WebView2 创建失败(可能未安装 Runtime),将回退浏览器")
		return false
	}
	defer w.Destroy()

	// 稍等本地 HTTP 就绪
	time.Sleep(200 * time.Millisecond)
	w.SetSize(1280, 800, webview2.HintNone)
	w.Navigate(pageURL)
	logf("🪟 WebView2 单窗口已打开")
	w.Run() // 阻塞直到窗口关闭
	logf("WebView 已关闭,进程退出")
	return true
}
