import SwiftUI
import AVFoundation
import QuartzCore

// MARK: - 会话模型

/// 把 StreamClient / VideoLayerView / AudioPlayer 粘在一起，并做音画同步与统计。
final class PlayerModel: ObservableObject {
    @Published var phase: Phase = .connecting
    @Published var errorText: String?
    @Published var hostName: String = "LumoCast"
    @Published var fps: Double = 0
    @Published var mbps: Double = 0
    @Published var latencyMs: Int = 0
    @Published var rttMs: Int = 0
    @Published var audioOn = false
    @Published var canTouch = false

    enum Phase { case connecting, live, failed }

    let videoView = VideoLayerView(frame: .zero)
    private let audio = AudioPlayer()
    private let client = StreamClient()

    private var offset: Double?
    private var frames = 0
    private var bytes = 0
    private var dropped = 0
    private var lastPts: UInt32 = 0
    private var lastFrameAt: Double = 0
    private var statsTimer: Timer?
    private var isRunning = false

    private let lateThresholdMs: Double = 150
    private var audioReady = false

    func start(ip: String, port: UInt16, pin: String?) {
        guard !isRunning else { return }
        isRunning = true
        client.onEvent = { [weak self] event in
            DispatchQueue.main.async { self?.handle(event) }
        }
        client.connect(ip: ip, port: port, pin: pin)
        startStats()
    }

    func stop() {
        isRunning = false
        statsTimer?.invalidate()
        statsTimer = nil
        client.close()
        audio.stop()
        videoView.reset()
    }

    func requestKeyframe() { client.requestKeyframe() }

    func sendTouch(_ action: String, _ x: Double, _ y: Double) {
        client.sendTouch(action, x: x, y: y)
    }

    // MARK: 事件

    private func handle(_ event: StreamClient.Event) {
        switch event {
        case .welcome(let w):
            hostName = w.name
            canTouch = w.canTouch
            phase = .live
            errorText = nil
            lastFrameAt = CACurrentMediaTime()

        case .config(let csd):
            if !videoView.configure(csd: csd) {
                errorText = "解码器配置失败"
            }

        case .audioConfig(let asc):
            audioReady = audio.configure(asc: asc)
            audioOn = audioReady

        case .video(let data, let pts, let key):
            frames += 1
            bytes += data.count
            lastFrameAt = CACurrentMediaTime()

            // ---- 音画同步闸门（audio-master）
            if audioReady, !key, let off = offset {
                let hostNow = CACurrentMediaTime() * 1000 + off
                let heardAt = hostNow - audio.pipelineLatencyMs
                if Double(pts) < heardAt - lateThresholdMs {
                    dropped += 1
                    return
                }
            }
            lastPts = pts
            videoView.enqueue(annexB: data, ptsMs: pts)

        case .audio(let data, let pts):
            if audioReady {
                audio.submit(data, ptsMs: pts)
            }

        case .clock(let value):
            offset = value

        case .rtt(let ms):
            rttMs = ms

        case .hostControl(let obj):
            if (obj["t"] as? String) == "end" {
                phase = .failed
                errorText = "主机结束了共享"
            }
            if (obj["t"] as? String) == "rotate" {
                videoView.reset()
            }

        case .closed(let reason):
            phase = .failed
            errorText = reason
        }
    }

    // MARK: 统计

    private func startStats() {
        statsTimer?.invalidate()
        var lastFrames = 0
        var lastBytes = 0
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self, self.phase == .live else { return }
            self.fps = Double(self.frames - lastFrames)
            self.mbps = Double(self.bytes - lastBytes) * 8 / 1_000_000
            lastFrames = self.frames
            lastBytes = self.bytes

            if let off = self.offset, self.lastPts > 0 {
                let hostNow = CACurrentMediaTime() * 1000 + off
                self.latencyMs = max(0, Int(hostNow - Double(self.lastPts)))
            }
            // 1.5s 没新帧 → 要 IDR
            if CACurrentMediaTime() - self.lastFrameAt > 1.5, self.lastFrameAt > 0 {
                self.client.requestKeyframe()
            }
        }
    }
}

// MARK: - 视频视图桥接

struct VideoSurface: UIViewRepresentable {
    let view: VideoLayerView

    func makeUIView(context: Context) -> VideoLayerView { view }
    func updateUIView(_ uiView: VideoLayerView, context: Context) {}
}

// MARK: - 首页

struct ContentView: View {
    @StateObject private var discovery = DiscoveryService()
    @State private var pendingDevice: DiscoveredDevice?
    @State private var pin = ""
    @State private var active: DiscoveredDevice?
    @State private var activePin: String?

    var body: some View {
        NavigationView {
            ZStack {
                LinearGradient(colors: [Color(hex: 0x0B0D17), Color(hex: 0x121026)],
                               startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        header
                        Text("这些设备就在同一个 Wi-Fi 里，视频点对点直连，不经过任何服务器。")
                            .font(.footnote)
                            .foregroundColor(.secondary)

                        if discovery.devices.isEmpty {
                            searching
                        } else {
                            ForEach(discovery.devices) { device in
                                DeviceRow(device: device) { tap(device) }
                            }
                        }
                    }
                    .padding(20)
                }
            }
            .navigationBarHidden(true)
            .onAppear { discovery.start() }
            .onDisappear { discovery.stop() }
        }
        .navigationViewStyle(.stack)
        .sheet(item: $pendingDevice) { device in
            pinSheet(device)
        }
        .fullScreenCover(item: $active) { device in
            PlayerScreen(device: device, pin: activePin ?? "") { active = nil }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 11)
                .fill(LinearGradient(colors: [Color(hex: 0x6C5CE7), Color(hex: 0x00C6FF)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 34, height: 34)
                .overlay(Image(systemName: "airplayvideo").font(.system(size: 15, weight: .semibold)).foregroundColor(.white))
            Text("LumoCast").font(.title3.bold())
            Spacer()
            Text("FREE")
                .font(.system(size: 9, weight: .bold))
                .padding(.horizontal, 7).padding(.vertical, 3)
                .background(Capsule().fill(Color.white.opacity(0.08)))
                .foregroundColor(.secondary)
        }
    }

    private var searching: some View {
        VStack(spacing: 10) {
            ProgressView().tint(Color(hex: 0x6C5CE7))
            Text("正在搜索附近的设备…").font(.subheadline.weight(.semibold))
            Text("在手机上打开 LumoCast 并点「共享本机屏幕」。\n搜不到？确认两边是同一个 Wi-Fi。")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    private func pinSheet(_ device: DiscoveredDevice) -> some View {
        VStack(spacing: 16) {
            Text("输入连接 PIN").font(.headline)
            Text("在「\(device.name)」的屏幕上可以看到 4 位数字")
                .font(.footnote).foregroundColor(.secondary)
            TextField("••••", text: $pin)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.system(size: 34, weight: .semibold, design: .monospaced))
                .padding()
                .background(RoundedRectangle(cornerRadius: 14).fill(Color.white.opacity(0.07)))
            Button {
                activePin = pin
                active = device
                pendingDevice = nil
                pin = ""
            } label: {
                Text("连接").frame(maxWidth: .infinity).padding(.vertical, 13)
            }
            .buttonStyle(.borderedProminent)
            .disabled(pin.count != 4)
            Spacer()
        }
        .padding(24)
        .presentationDetents([.height(320)])
    }

    private func tap(_ device: DiscoveredDevice) {
        pin = ""
        if device.pinRequired {
            pendingDevice = device
        } else {
            activePin = ""
            active = device
        }
    }
}

struct DeviceRow: View {
    let device: DiscoveredDevice
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 13) {
                RoundedRectangle(cornerRadius: 13)
                    .fill(Color(hex: 0x6C5CE7).opacity(0.16))
                    .frame(width: 42, height: 42)
                    .overlay(Image(systemName: "iphone").foregroundColor(Color(hex: 0x6C5CE7)))
                VStack(alignment: .leading, spacing: 3) {
                    Text(device.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text("\(device.ip):\(String(device.port)) · \(device.width)×\(device.height) · \(device.fps)fps")
                        .font(.caption2).foregroundColor(.secondary)
                }
                Spacer()
                if device.pinRequired {
                    Image(systemName: "lock.fill").font(.caption2).foregroundColor(.secondary)
                }
                Text(device.free ? "FREE" : "PRO")
                    .font(.system(size: 9, weight: .bold))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill(Color.white.opacity(0.07)))
                    .foregroundColor(.secondary)
            }
            .padding(15)
            .background(RoundedRectangle(cornerRadius: 20).fill(Color.white.opacity(0.055)))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white.opacity(0.09)))
        }
        .buttonStyle(.plain)
        .foregroundColor(.primary)
    }
}

// MARK: - 播放页

struct PlayerScreen: View {
    let device: DiscoveredDevice
    let pin: String
    let onClose: () -> Void

    @StateObject private var model = PlayerModel()
    @State private var hudVisible = true

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(view: model.videoView).ignoresSafeArea()

            if model.phase == .connecting {
                VStack(spacing: 12) {
                    ProgressView().tint(.white)
                    Text("正在连接 \(device.name)…").font(.footnote).foregroundColor(.white.opacity(0.75))
                }
            }

            if model.phase == .failed {
                VStack(spacing: 10) {
                    Text(model.errorText ?? "连接已断开").font(.headline).foregroundColor(.white)
                    Text("\(device.ip):\(String(device.port))").font(.caption2).foregroundColor(.white.opacity(0.5))
                    Button("重新连接") {
                        model.stop()
                        model.start(ip: device.ip, port: device.port, pin: pin)
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 8)
                }
            }

            if hudVisible {
                VStack {
                    HStack(spacing: 10) {
                        Button(action: onClose) {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(10)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.hostName).font(.footnote.weight(.semibold)).foregroundColor(.white)
                            Text(String(format: "%.0f fps · %.1f Mbps · %dms 延迟%@",
                                        model.fps, model.mbps, model.latencyMs,
                                        model.audioOn ? " · 有声" : " · 静音"))
                                .font(.caption2).foregroundColor(.white.opacity(0.6))
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                    .background(LinearGradient(colors: [.black.opacity(0.6), .clear],
                                              startPoint: .top, endPoint: .bottom))
                    Spacer()
                }
            }
        }
        .statusBarHidden(true)
        .onTapGesture { withAnimation { hudVisible.toggle() } }
        .onAppear {
            model.start(ip: device.ip, port: device.port, pin: pin)
        }
        .onDisappear { model.stop() }
    }
}

// MARK: - 颜色工具

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
