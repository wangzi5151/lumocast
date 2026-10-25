import Foundation
import Darwin

struct DiscoveredDevice: Identifiable, Equatable {
    let id: String          // ip:port
    let ip: String
    let port: UInt16
    let name: String
    let pinRequired: Bool
    let free: Bool
    let width: Int
    let height: Int
    let fps: Int
    var lastSeen: Date
}

/// UDP 8738 监听 ANNOUNCE，并周期性发 PROBE。
/// 用 BSD socket 而不是 Network.framework：广播接收 + SO_REUSEADDR 用 C API 最直接。
final class DiscoveryService: ObservableObject {
    @Published private(set) var devices: [DiscoveredDevice] = []

    private var socketFD: Int32 = -1
    private var running = false
    private let queue = DispatchQueue(label: "app.lumocast.discovery")
    private var deviceMap: [String: DiscoveredDevice] = [:]

    func start() {
        guard !running else { return }
        running = true
        queue.async { [weak self] in self?.listenLoop() }
    }

    func stop() {
        running = false
        if socketFD >= 0 {
            close(socketFD)
            socketFD = -1
        }
    }

    private func listenLoop() {
        socketFD = socket(AF_INET, SOCK_DGRAM, 0)
        guard socketFD >= 0 else { return }

        var yes: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(socketFD, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = Proto.discoveryPort.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY

        let bindResult = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                bind(socketFD, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { return }

        // 读超时，便于定期发 PROBE 和清理过期设备
        var tv = timeval(tv_sec: 1, tv_usec: 0)
        setsockopt(socketFD, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var buffer = [UInt8](repeating: 0, count: 2048)
        var lastProbe = Date.distantPast

        while running {
            if Date().timeIntervalSince(lastProbe) > 4 {
                lastProbe = Date()
                sendProbe()
            }

            var from = sockaddr_in()
            var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    recvfrom(socketFD, &buffer, buffer.count, 0, sa, &fromLen)
                }
            }
            if n > 0 {
                let data = Data(bytes: buffer, count: n)
                if let ip = Self.ipString(from.sin_addr) {
                    handle(data, ip: ip)
                }
            }
            purgeStale()
        }
    }

    private func sendProbe() {
        guard socketFD >= 0 else { return }
        let payload = Data(#"{"svc":"lumocast","t":"probe","v":1}"#.utf8)
        for target in Self.broadcastAddresses() {
            var addr = sockaddr_in()
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = Proto.discoveryPort.bigEndian
            addr.sin_addr.s_addr = target
            _ = withUnsafePointer(to: &addr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    payload.withUnsafeBytes { raw in
                        sendto(socketFD, raw.baseAddress, payload.count, 0, sa,
                               socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
    }

    private func handle(_ data: Data, ip: String) {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        guard (obj["svc"] as? String) == "lumocast" else { return }
        if let t = obj["t"] as? String, t != "announce" { return }

        let port = UInt16(obj["port"] as? Int ?? Int(Proto.controlPort))
        guard port > 0 else { return }
        let key = "\(ip):\(port)"
        let device = DiscoveredDevice(
            id: key,
            ip: ip,
            port: port,
            name: obj["name"] as? String ?? "Android",
            pinRequired: obj["pin"] as? Bool ?? true,
            free: obj["free"] as? Bool ?? true,
            width: obj["w"] as? Int ?? 1080,
            height: obj["h"] as? Int ?? 1920,
            fps: obj["fps"] as? Int ?? 30,
            lastSeen: Date()
        )
        deviceMap[key] = device
        publish()
    }

    private func purgeStale() {
        let now = Date()
        let before = deviceMap.count
        deviceMap = deviceMap.filter { now.timeIntervalSince($0.value.lastSeen) < 6.5 }
        if deviceMap.count != before { publish() }
    }

    private func publish() {
        let list = deviceMap.values.sorted { $0.name < $1.name }
        DispatchQueue.main.async { [weak self] in self?.devices = list }
    }

    // MARK: helpers

    private static func ipString(_ addr: in_addr) -> String? {
        var a = addr
        var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &a, &buf, socklen_t(INET_ADDRSTRLEN)) != nil else { return nil }
        return String(cString: buf)
    }

    private static func broadcastAddresses() -> [in_addr_t] {
        var result: [in_addr_t] = [in_addr_t(0xFFFF_FFFF)] // 255.255.255.255
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else { return result }
        defer { freeifaddrs(ifaddrPtr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let flags = Int32(p.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) != 0
            let isLoopback = (flags & IFF_LOOPBACK) != 0
            let isBroadcast = (flags & IFF_BROADCAST) != 0
            if isUp, !isLoopback, isBroadcast, let addr = p.pointee.ifa_addr,
               addr.pointee.sa_family == UInt8(AF_INET),
               let dst = p.pointee.ifa_dstaddr {
                let bcast = dst.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                if bcast != 0 { result.append(bcast) }
            }
            ptr = p.pointee.ifa_next
        }
        return Array(Set(result))
    }
}
