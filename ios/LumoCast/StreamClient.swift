import Foundation
import Network
import QuartzCore

/// 接收端 TCP 会话：握手 → 收包 → 分发视频/音频/控制。
/// 同时用 PONG 里的主机时钟估算时钟偏移，供音画同步使用。
final class StreamClient {

    struct Welcome {
        let name: String
        let width: Int
        let height: Int
        let fps: Int
        let free: Bool
        let canTouch: Bool
    }

    enum Event {
        case welcome(Welcome)
        case config(Data)
        case audioConfig(Data)
        case video(Data, ptsMs: UInt32, keyframe: Bool)
        case audio(Data, ptsMs: UInt32)
        case hostControl([String: Any])
        case closed(String)
        case clock(Double?)      // host − local（毫秒，单调时钟基准）
        case rtt(Int)
    }

    var onEvent: ((Event) -> Void)?

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "app.lumocast.stream")
    private let reader = PacketReader()
    private var handshakeDone = false
    private var handshakeBuffer = Data()
    private var pingTimer: DispatchSourceTimer?
    private var pingSeq: UInt32 = 1
    private var offset: Double?
    private var lastRTT: Int = 0

    private(set) var hostOffsetMs: Double? { offset }

    func connect(ip: String, port: UInt16, pin: String?) {
        let host = NWEndpoint.Host(ip)
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        if let tcpOptions = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcpOptions.noDelay = true
            tcpOptions.connectionTimeout = 5
        }
        let conn = NWConnection(host: host, port: NWEndpoint.Port(rawValue: port) ?? 8737, using: params)
        connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.sendHello(pin: pin)
            case .failed(let err):
                self?.onEvent?(.closed(err.localizedDescription))
            case .cancelled:
                self?.onEvent?(.closed("连接已取消"))
            default:
                break
            }
        }
        conn.start(queue: queue)
        startReceive(on: conn)
    }

    func close() {
        pingTimer?.cancel()
        pingTimer = nil
        connection?.cancel()
        connection = nil
    }

    // MARK: - 握手

    private func sendHello(pin: String?) {
        let hello: [String: Any] = [
            "t": "hello",
            "v": Int(Proto.version),
            "role": "viewer",
            "device": "LumoCast iOS",
            "pin": pin ?? "",
        ]
        guard let json = try? JSONSerialization.data(withJSONObject: hello) else { return }
        var line = json
        line.append(0x0A)
        connection?.send(content: line, completion: .contentProcessed { _ in })
    }

    private func startReceive(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error {
                self.onEvent?(.closed(error.localizedDescription))
                return
            }
            if let data, !data.isEmpty {
                self.handleIncoming(data)
            }
            if isComplete {
                self.onEvent?(.closed("对端关闭了连接"))
                return
            }
            self.startReceive(on: conn)
        }
    }

    private func handleIncoming(_ data: Data) {
        if !handshakeDone {
            handshakeBuffer.append(data)
            guard let nlIndex = handshakeBuffer.firstIndex(of: 0x0A) else { return }
            let lineData = handshakeBuffer[handshakeBuffer.startIndex ..< nlIndex]
            let rest = Data(handshakeBuffer[(nlIndex + 1)...])
            handshakeDone = true
            handshakeBuffer = Data()

            guard let obj = try? JSONSerialization.jsonObject(with: lineData) as? [String: Any] else {
                onEvent?(.closed("握手响应无法解析"))
                return
            }
            if (obj["t"] as? String) == "error" {
                onEvent?(.closed(obj["msg"] as? String ?? "被拒绝"))
                return
            }
            guard (obj["t"] as? String) == "welcome" else {
                onEvent?(.closed("协议错误：期望 welcome"))
                return
            }
            let caps = obj["cap"] as? [String] ?? []
            let welcome = Welcome(
                name: obj["name"] as? String ?? "LumoCast",
                width: obj["w"] as? Int ?? 1080,
                height: obj["h"] as? Int ?? 1920,
                fps: obj["fps"] as? Int ?? 30,
                free: obj["free"] as? Bool ?? true,
                canTouch: caps.contains("touch")
            )
            onEvent?(.welcome(welcome))
            startPing()
            if !rest.isEmpty { handleIncoming(rest) }
            return
        }

        do {
            for packet in try reader.append(data) {
                dispatch(packet)
            }
        } catch {
            onEvent?(.closed(error.localizedDescription))
        }
    }

    private func dispatch(_ packet: LumoPacket) {
        switch packet.type {
        case Proto.Kind.config.rawValue:
            onEvent?(.config(packet.payload))
        case Proto.Kind.audioConfig.rawValue:
            onEvent?(.audioConfig(packet.payload))
        case Proto.Kind.video.rawValue:
            onEvent?(.video(packet.payload, ptsMs: packet.ptsMs, keyframe: packet.isKeyframe))
        case Proto.Kind.audio.rawValue:
            onEvent?(.audio(packet.payload, ptsMs: packet.ptsMs))
        case Proto.Kind.control.rawValue:
            if let obj = try? JSONSerialization.jsonObject(with: packet.payload) as? [String: Any] {
                onEvent?(.hostControl(obj))
            }
        case Proto.Kind.pong.rawValue:
            handlePong(packet.payload)
        case Proto.Kind.ping.rawValue:
            // 回 PONG（8 字节原样回填）
            var pong = Data()
            var header = makeHeader(type: Proto.Kind.pong.rawValue, flags: 0, ptsMs: 0,
                                    len: UInt32(packet.payload.count))
            pong.append(header)
            pong.append(packet.payload.prefix(8))
            _ = header // silence
            connection?.send(content: pong, completion: .contentProcessed { _ in })
        default:
            break
        }
    }

    // MARK: - 时钟

    private func startPing() {
        pingTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 0.2, repeating: 1.0)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            var payload = Data()
            var seq = self.pingSeq.bigEndian
            var ms = UInt32(truncatingIfNeeded: Int64(CACurrentMediaTime() * 1000)).bigEndian
            withUnsafeBytes(of: &seq) { payload.append(contentsOf: $0) }
            withUnsafeBytes(of: &ms) { payload.append(contentsOf: $0) }
            let packet = self.makeHeader(type: Proto.Kind.ping.rawValue, flags: 0, ptsMs: 0,
                                         len: UInt32(payload.count)) + payload
            self.pingSeq &+= 1
            self.connection?.send(content: packet, completion: .contentProcessed { _ in })
        }
        timer.resume()
        pingTimer = timer
    }

    private func handlePong(_ payload: Data) {
        guard payload.count >= 8 else { return }
        let nowMs = Int64(CACurrentMediaTime() * 1000)
        let sentLocal = readU32(payload, 4)
        let rtt = max(0, Int(nowMs - Int64(sentLocal)))
        lastRTT = rtt

        if payload.count >= 12 {
            let hostNow = Int64(readU32(payload, 8))
            let sample = Double(hostNow + Int64(rtt / 2) - nowMs)
            offset = offset.map { $0 * 0.85 + sample * 0.15 } ?? sample
            onEvent?(.clock(offset))
        }
        onEvent?(.rtt(rtt))
    }

    // MARK: - 发送控制

    func sendControl(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else { return }
        let packet = makeHeader(type: Proto.Kind.control.rawValue, flags: 0, ptsMs: 0,
                                len: UInt32(data.count)) + data
        connection?.send(content: packet, completion: .contentProcessed { _ in })
    }

    func requestKeyframe() { sendControl(["t": "kf"]) }

    func sendTouch(_ action: String, x: Double, y: Double) {
        sendControl(["t": "in", "e": action, "x": x, "y": y])
    }

    // MARK: - 工具

    private func makeHeader(type: UInt8, flags: UInt8, ptsMs: UInt32, len: UInt32) -> Data {
        var d = Data(capacity: Proto.headerSize)
        d.append(Proto.magic0)
        d.append(Proto.magic1)
        d.append(Proto.version)
        d.append(type)
        d.append(flags)
        d.append(contentsOf: [0, 0, 0])
        var p = ptsMs.bigEndian
        withUnsafeBytes(of: &p) { d.append(contentsOf: $0) }
        var l = len.bigEndian
        withUnsafeBytes(of: &l) { d.append(contentsOf: $0) }
        return d
    }

    private func readU32(_ data: Data, _ offset: Int) -> UInt32 {
        let base = data.startIndex + offset
        return (UInt32(data[base]) << 24) | (UInt32(data[base + 1]) << 16)
            | (UInt32(data[base + 2]) << 8) | UInt32(data[base + 3])
    }
}
