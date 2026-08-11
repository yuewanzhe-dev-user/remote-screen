# scrcpy-smart 智慧投屏

学生主动投屏方案:pad 抓屏 → 编码 H.264 → TCP 推流 → 黑板解码显示。
学生/老师打开 app 输入黑板 IP → 授权一次 → 投屏到本教室黑板。

## ⚠️ 关于技术栈,先说清楚

- **pad 发送端必须用原生 Android(Kotlin)**,因为抓屏(`MediaProjection`)和硬件编码(`MediaCodec`)只有原生 API 能调,uniapp/web 技术栈做不到。
- **但本地不需要装 Android Studio**。代码用 GitHub Actions 云端编译,你 push 代码就出 apk(见下文)。
- **黑板接收端用 Go**,编译成单个 exe,双击即可运行,无需安装任何运行环境(只需附带 ffmpeg.exe)。

## 目录结构

```
scrcpy-smart/
├── sender-android/              # pad 端(Android apk,原生 Kotlin)
│   ├── app/build.gradle
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       └── java/com/smartcast/sender/
│           ├── MainActivity.kt        # 入口界面:输 IP → 开始投屏
│           └── ScreenCastService.kt   # 核心:抓屏+编码+推流
├── receiver-windows/
│   ├── go/                      # ⭐ 黑板端(Go,编译成单 exe)
│   │   ├── main.go
│   │   └── go.mod
│   └── python-poc/              # 旧版 Python 参考实现(可忽略)
│       └── receiver.py
└── .github/workflows/build.yml  # ⭐ 云编译配置:push 自动出 apk + exe
```

## 本地需要装什么?(几乎为零)

| 角色 | 本地要装的东西 |
|---|---|
| 你(开发) | 只需要 git,用来 push 代码 |
| 黑板电脑 | 只需要 [ffmpeg.exe](https://ffmpeg.org/download.html),和投屏接收端.exe 放一起 |
| pad | 装云编译出来的 apk 即可 |

## 怎么编译出 apk 和 exe?(用 GitHub 云编译,不占本地)

1. 把整个 `scrcpy-smart` 目录 push 到 GitHub(新建一个仓库)
2. 进入 GitHub 仓库页面 → 点顶部 `Actions` 标签
3. 左边选 `构建 pad 端 APK 和黑板端 EXE` → 右边点 `Run workflow` → 等约 5-10 分钟
4. 编译完成,在本次运行记录里下拉到 `Artifacts` 区,下载两个压缩包:
   - `智慧投屏-pad端-apk.zip` → 解压得到 apk,装到 pad
   - `智慧投屏-黑板端-exe.zip` → 解压得到 exe,放到黑板电脑

(首次跑会自动生成 gradle wrapper,之后正常出包)

## 运行步骤

### 第 1 步:黑板端启动
1. 拿到 `投屏接收端.exe` + `ffplay.exe`,放在黑板电脑同一目录
2. 双击 `投屏接收端.exe`,出现黑底窗口显示 `✅ 黑板端已启动,监听 0.0.0.0:8888`
3. (可选)命令行执行 `投屏接收端.exe 9000` 可换端口;`投屏接收端.exe 8888 "三年二班黑板"` 可自定义黑板名称(名称会显示在 pad 的发现列表和画面窗口标题里,方便多教室区分)

### 第 2 步:pad 端投屏
1. 把云编译出的 apk 装到 pad(测试阶段可用数据线 adb install;正式版给厂商推送)
2. 打开"智慧投屏" app → 自动搜到本教室黑板(列表点一下即可投,不用输 IP)
3. 搜不到时兜底:手动输黑板 IP(例如 `192.168.1.100`)、端口 `8888`
4. 点"开始投屏" → 系统弹授权 → 点"开始投屏"(Android 14 起每次投屏都需授权,无法勾选"不再提示")
5. 黑板端弹出一个 SDL 窗口,实时显示 pad 画面

## 协议 v2(两端要对齐,需同时更新)

连接建立后,pad 依次发送:

```
1. 一行 JSON 身份:{"v":1,"name":"设备型号"}
2. 8 字节分辨率头:[width : 4 字节,大端] [height : 4 字节,大端]
3. 随后持续发送 H.264 裸流 → 黑板按 H.264 解码显示
```

黑板端 → pad 的控制消息(行分隔 JSON):

| 消息 | 含义 | pad 收到后行为 |
|---|---|---|
| `{"cmd":"hb"}` | 心跳,每 5 秒一条 | 黑板端失联判定依据(连续 20 秒无消息则重连) |
| `{"cmd":"kicked"}` | 被其他 pad 顶替 | 停止投屏,不再重连 |
| `{"cmd":"stop"}` | 黑板端结束投屏(老师关掉画面窗口) | 停止投屏,不再重连 |

## 关键参数(在 ScreenCastService.kt 中可调)

| 参数 | 当前值 | 说明 |
|---|---|---|
| 码率 | 初始 4 Mbps,自适应 1.5M~4M | 弱 WiFi 自动降码率保流畅,网络恢复后自动升回 |
| 帧率 KEY_FRAME_RATE | 30 fps | 流畅度 |
| 关键帧间隔 KEY_I_FRAME_INTERVAL | 2 秒 | 越小画面恢复越快但码率越高 |
| 端口 | 8888 | 两端要一致 |

## Step1 验证标准

- [ ] pad 打开 app,点选自动发现的黑板(或输 IP),点投屏,授权后黑板端出现 pad 画面
- [ ] pad 操作(滑动/打开 app),黑板画面同步,延迟在 1 秒以内
- [ ] pad 切后台或熄屏,投屏继续(已持 wakelock);黑板窗口可关闭,pad 自动停止
- [ ] 学生 A 投屏中,学生 B 点投屏 → 黑板切到 B,A 的 pad 显示"已被其他投屏替换"

## 已知限制(后续 Step 处理)

- **无身份校验**:黑板名称可自定义用于区分教室,但未做房间码握手,同一网段的 pad 理论上仍可投到别教室黑板(Step3 加教室号/pad 号握手)
- **无多路/录制**:Step4 起加多 pad 排队、录制留痕
- **无音频**:当前仅画面,后续可加 AAC 音频

## 故障排查

| 现象 | 排查 |
|---|---|
| 云编译失败 | 在 Actions 里点进失败的那次,看日志,通常是 gradle wrapper 或 SDK 版本问题 |
| pad 点投屏没反应 | `adb logcat -s ScreenCastService` 看日志,多半是编码器配置失败或 socket 连不上 |
| 黑板端一直没连接 | pad 上 ping 黑板 IP;黑板防火墙放行 8888(首次运行 Windows 会弹防火墙,点允许) |
| 黑板窗口黑屏/花屏 | 正常,等 1-2 秒关键帧到来;持续花屏是码率太低,弱 WiFi 下自适应码率会自动处理 |
| 提示找不到 ffplay | 把 ffplay.exe 和投屏接收端.exe 放同一目录,或把 ffmpeg 加入系统 PATH |
| 延迟很大 | ffmpeg 已开低延迟参数;检查 pad 分辨率是否过高(2K/4K 可降到 1080p) |
| pad 显示"已被其他投屏替换" | 正常,有其他 pad 顶掉了本次投屏 |
| pad 显示"黑板端已结束投屏" | 正常,老师关掉了黑板画面窗口,重新点投屏即可 |
| pad 显示"黑板端失联,正在重连…" | 黑板电脑关机/断网了,恢复后自动重连 |
| pad 点投屏授权后闪退 | 多为个别国产 ROM 编码器不兼容扩展参数(MediaCodec configure 抛 IllegalArgumentException)。已做多级配置回退 + 防崩溃兜底;若仍闪退,`adb logcat -s ScreenCastService` 看日志 |
