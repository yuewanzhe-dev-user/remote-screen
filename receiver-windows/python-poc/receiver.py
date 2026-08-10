"""
智慧投屏 - 黑板端接收器 (Step1 PoC)
====================================
监听 TCP 8888,接收 pad 推来的 H.264 流,用 FFmpeg 解码显示。

协议(与 pad 端 ScreenCastService 对应):
  连接建立后前 8 字节 = [width(4,big-endian)][height(4,big-endian)]
  随后 = H.264 裸流

原理:
  - 起 socket 收数据 → 通过子进程管道喂给 ffmpeg
  - ffmpeg 解码 H.264 → SDL 窗口显示画面
  - 这是验证链路最快的写法,正式版转 C# + 硬解渲染

依赖:
  只需装 ffmpeg,并把 ffmpeg.exe 放到 PATH。
  Python 用标准库 socket,无第三方依赖。

运行:
  python receiver.py            # 默认监听 0.0.0.0:8888
  python receiver.py 9000       # 自定义端口
"""
import socket
import struct
import subprocess
import sys
import threading

DEFAULT_PORT = 8888


def start_ffmpeg(width: int, height: int) -> subprocess.Popen:
    """启动 ffmpeg 子进程:从 stdin 收 H.264,解码后用 SDL 显示窗口"""
    cmd = [
        "ffmpeg",
        "-probesize", "32",          # 极小探测,首帧尽快出
        "-analyzeduration", "0",     # 不分析,低延迟
        "-f", "h264",
        "-video_size", f"{width}x{height}",
        "-i", "pipe:0",              # 从标准输入读
        "-pix_fmt", "yuv420p",
        "-f", "sdl", "智慧投屏 - 黑板端",  # SDL 窗口标题
    ]
    print(f"[黑板端] 启动 ffmpeg: {' '.join(cmd)}")
    return subprocess.Popen(cmd, stdin=subprocess.PIPE)


def serve_client(conn: socket.socket, addr):
    """处理一个 pad 的连接"""
    print(f"[黑板端] pad 已连接: {addr}")
    try:
        # 1. 先读 8 字节分辨率头
        header = recv_exact(conn, 8)
        if not header:
            print(f"[黑板端] {addr} 未发送分辨率头,断开")
            return
        width, height = struct.unpack(">II", header)
        print(f"[黑板端] 收到分辨率: {width}x{height}")

        # 2. 启动 ffmpeg 解码显示
        ff = start_ffmpeg(width, height)
        try:
            # 3. 持续把收到的 H.264 数据喂给 ffmpeg
            while True:
                data = conn.recv(65536)
                if not data:
                    break
                ff.stdin.write(data)
        except BrokenPipeError:
            print("[黑板端] ffmpeg 窗口已关闭")
        finally:
            try:
                ff.stdin.close()
            except Exception:
                pass
            ff.wait()
    except Exception as e:
        print(f"[黑板端] 处理 {addr} 出错: {e}")
    finally:
        conn.close()
        print(f"[黑板端] {addr} 断开")


def recv_exact(conn: socket.socket, n: int) -> bytes:
    """精确读取 n 个字节"""
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return b""
        buf.extend(chunk)
    return bytes(buf)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(5)
    print(f"[黑板端] 监听中 0.0.0.0:{port},等待 pad 连接…")

    while True:
        conn, addr = srv.accept()
        # 一个 pad 一个线程(Step1 只演示单路;正式版做多路调度)
        threading.Thread(target=serve_client, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
