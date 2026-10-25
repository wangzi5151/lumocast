import AVFoundation
import UIKit

/// H.264 → CMSampleBuffer → AVSampleBufferDisplayLayer。
/// 这是 iOS 上唯一能「零拷贝硬解裸码流」的路径（AVPlayer 只吃容器格式）。
final class VideoLayerView: UIView {

    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }

    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }

    private var formatDescription: CMVideoFormatDescription?

    override init(frame: CGRect) {
        super.init(frame: frame)
        setup()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setup()
    }

    private func setup() {
        backgroundColor = .black
        displayLayer.videoGravity = .resizeAspect
        if #available(iOS 14.0, *) {
            displayLayer.preventsDisplaySleepDuringVideoPlayback = true
        }
    }

    func reset() {
        formatDescription = nil
        displayLayer.flushAndRemoveImage()
    }

    /// @param csd CONFIG 包的 Annex-B 负载（SPS+PPS）
    func configure(csd: Data) -> Bool {
        guard let (sps, pps) = AnnexB.spsPps(csd) else { return false }

        let ok: Bool = sps.withUnsafeBytes { spsRaw in
            pps.withUnsafeBytes { ppsRaw in
                guard let spsBase = spsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsBase = ppsRaw.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                    return false
                }
                let pointers: [UnsafePointer<UInt8>] = [spsBase, ppsBase]
                let sizes: [Int] = [sps.count, pps.count]
                var desc: CMVideoFormatDescription?
                let status = pointers.withUnsafeBufferPointer { ptrBuf in
                    sizes.withUnsafeBufferPointer { sizeBuf in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers: ptrBuf.baseAddress!,
                            parameterSetSizes: sizeBuf.baseAddress!,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut: &desc
                        )
                    }
                }
                if status == noErr, let d = desc {
                    self.formatDescription = d
                    self.displayLayer.flushAndRemoveImage()
                    return true
                }
                return false
            }
        }
        return ok
    }

    /// 送一帧
    @discardableResult
    func enqueue(annexB: Data, ptsMs: UInt32) -> Bool {
        guard let format = formatDescription else { return false }
        guard let avcc = AnnexB.toAvcc(annexB), !avcc.isEmpty else { return false }

        var blockBuffer: CMBlockBuffer?
        let memStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard memStatus == kCMBlockBufferNoErr, let bb = blockBuffer else { return false }

        let copyStatus: OSStatus = avcc.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return -1 }
            return CMBlockBufferReplaceDataBytes(
                with: base,
                blockBuffer: bb,
                offsetIntoDestination: 0,
                dataLength: avcc.count
            )
        }
        guard copyStatus == kCMBlockBufferNoErr else { return false }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 1000),
            presentationTimeStamp: CMTime(value: CMTimeValue(ptsMs), timescale: 1000),
            decodeTimeStamp: .invalid
        )
        var sampleSize = avcc.count
        var sampleBuffer: CMSampleBuffer?

        let createStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: bb,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard createStatus == noErr, let sb = sampleBuffer else { return false }

        if #available(iOS 14.0, *) {
            // 立刻显示，不等待（低延迟）
            if let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true),
               CFArrayGetCount(attachments) > 0 {
                let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
                CFDictionarySetValue(dict,
                                     Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                     Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
            }
        }

        if displayLayer.status == .failed {
            displayLayer.flush()
        }
        if displayLayer.isReadyForMoreMediaData {
            displayLayer.enqueue(sb)
            return true
        }
        return false
    }
}
