// Streams the Mac's system audio (everything you hear, e.g. a Meet call) to stdout as 16 kHz mono
// little-endian float32 PCM. Uses ScreenCaptureKit (macOS 13+); needs Screen Recording permission.
import Foundation
import ScreenCaptureKit
import CoreMedia

final class Tap: NSObject, SCStreamOutput, SCStreamDelegate {
    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, sb.isValid else { return }
        var list = AudioBufferList()
        var block: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sb, bufferListSizeNeededOut: nil, bufferListOut: &list,
            bufferListSize: MemoryLayout<AudioBufferList>.size, blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &block)
        guard status == noErr, let data = list.mBuffers.mData else { return }
        FileHandle.standardOutput.write(Data(bytes: data, count: Int(list.mBuffers.mDataByteSize)))
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        fputs("stream stopped: \(error)\n", stderr)
        exit(2)
    }
}

let tap = Tap()
Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else { fputs("no display found\n", stderr); exit(1) }
        let config = SCStreamConfiguration()
        config.capturesAudio = true
        config.sampleRate = 48000
        config.channelCount = 1
        config.excludesCurrentProcessAudio = true
        config.width = 2          // we only want audio; keep the video side tiny
        config.height = 2
        config.minimumFrameInterval = CMTime(value: 1, timescale: 1)
        let stream = SCStream(filter: SCContentFilter(display: display, excludingWindows: []),
                              configuration: config, delegate: tap)
        let queue = DispatchQueue(label: "audio")
        try stream.addStreamOutput(tap, type: .audio, sampleHandlerQueue: queue)
        try stream.addStreamOutput(tap, type: .screen, sampleHandlerQueue: queue) // ignored; avoids SCK warnings
        try await stream.startCapture()
    } catch {
        fputs("error: \(error)\n", stderr)
        exit(1)
    }
}
dispatchMain()
