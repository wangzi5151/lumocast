import Foundation

/// LumoCast 线协议 v1.1 —— 与 android/.../Protocol.kt、web/js/protocol.js 逐字节一致。
enum Proto {
    static let magic0: UInt8 = 0x4C
    static let magic1: UInt8 = 0x43
    static let version: UInt8 = 1
    static let headerSize = 16
    static let maxPayload = 4 * 1024 * 1024

    static let controlPort: UInt16 = 8737
    static let discoveryPort: UInt16 = 8738

    enum Kind: UInt8 {
        case config = 1
        case video = 2
        case audio = 3
        case ping = 4
        case pong = 5
        case control = 6
        case audioConfig = 7
    }

    static let flagKeyframe: UInt8 = 0x01
}

struct LumoPacket {
    let type: UInt8
    let flags: UInt8
    let ptsMs: UInt32
    let payload: Data

    var isKeyframe: Bool { flags & Proto.flagKeyframe != 0 }
}

/// 流式拆包：TCP 不保证边界，喂多少都行。
final class PacketReader {
    private var buffer = Data()

    func append(_ chunk: Data) throws -> [LumoPacket] {
        buffer.append(chunk)
        var packets: [LumoPacket] = []
        var offset = 0

        while buffer.count - offset >= Proto.headerSize {
            let start = buffer.startIndex + offset
            guard buffer[start] == Proto.magic0, buffer[start + 1] == Proto.magic1 else {
                throw LumoError.protocolViolation("bad magic")
            }
            let version = buffer[start + 2]
            guard version == Proto.version else {
                throw LumoError.protocolViolation("unsupported version \(version)")
            }
            let type = buffer[start + 3]
            let flags = buffer[start + 4]
            let pts = buffer.withUnsafeBytes { raw -> UInt32 in
                let base = raw.baseAddress!.advanced(by: offset + 8)
                return base.loadUnaligned(as: UInt32.self).bigEndian
            }
            let len = buffer.withUnsafeBytes { raw -> UInt32 in
                let base = raw.baseAddress!.advanced(by: offset + 12)
                return base.loadUnaligned(as: UInt32.self).bigEndian
            }
            guard len <= UInt32(Proto.maxPayload) else {
                throw LumoError.protocolViolation("payload too large: \(len)")
            }
            if buffer.count - offset - Proto.headerSize < Int(len) { break }

            let payloadStart = start + Proto.headerSize
            let payload = buffer.subdata(in: payloadStart ..< (payloadStart + Int(len)))
            packets.append(LumoPacket(type: type, flags: flags, ptsMs: pts, payload: payload))
            offset += Proto.headerSize + Int(len)
        }

        if offset > 0 {
            buffer = buffer.subdata(in: (buffer.startIndex + offset) ..< buffer.endIndex)
        }
        return packets
    }
}

enum LumoError: LocalizedError {
    case protocolViolation(String)
    case handshake(String)
    case disconnected

    var errorDescription: String? {
        switch self {
        case .protocolViolation(let m): return "协议错误：\(m)"
        case .handshake(let m): return m
        case .disconnected: return "连接已断开"
        }
    }
}

enum AnnexB {
    /// 拆出 NAL 单元（不含起始码）
    static func nals(_ data: Data) -> [Data] {
        var result: [Data] = []
        var i = data.startIndex
        var start: Int? = nil
        let end = data.endIndex

        while i + 2 < end {
            if data[i] == 0, data[i + 1] == 0, data[i + 2] == 1 {
                if let s = start { if let nal = clean(data, s, i) { result.append(nal) } }
                start = i + 3
                i += 3
            } else if i + 3 < end, data[i] == 0, data[i + 1] == 0, data[i + 2] == 0, data[i + 3] == 1 {
                if let s = start { if let nal = clean(data, s, i) { result.append(nal) } }
                start = i + 4
                i += 4
            } else {
                i += 1
            }
        }
        if let s = start, s < end { if let nal = clean(data, s, end) { result.append(nal) } }
        return result
    }

    private static func clean(_ data: Data, _ s: Int, _ e: Int) -> Data? {
        var end = e
        while end > s, data[end - 1] == 0 { end -= 1 }
        return end > s ? data.subdata(in: s ..< end) : nil
    }

    static func nalType(_ nal: Data) -> UInt8 {
        nal.isEmpty ? 0 : nal[nal.startIndex] & 0x1F
    }

    static func spsPps(_ csd: Data) -> (sps: Data, pps: Data)? {
        var sps: Data?
        var pps: Data?
        for nal in nals(csd) {
            switch nalType(nal) {
            case 7: if sps == nil { sps = nal }
            case 8: if pps == nil { pps = nal }
            default: break
            }
        }
        guard let s = sps, let p = pps else { return nil }
        return (s, p)
    }

    /// Annex-B → AVCC（4 字节长度前缀），VideoToolbox 需要这种格式
    static func toAvcc(_ data: Data) -> Data? {
        let video = nals(data).filter { (1 ... 5).contains(nalType($0)) }
        guard !video.isEmpty else { return nil }
        var out = Data()
        for nal in video {
            var len = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &len) { out.append(contentsOf: $0) }
            out.append(nal)
        }
        return out
    }
}
