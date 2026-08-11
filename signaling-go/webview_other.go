//go:build !windows

package main

// 非 Windows: 不内嵌 WebView,由 main 回退系统浏览器
func openEmbeddedView(pageURL string) bool {
	return false
}
