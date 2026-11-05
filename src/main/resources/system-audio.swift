// Streams the Mac's system audio (everything you hear, e.g. a Meet call) to stdout as 16 kHz mono
// little-endian float32 PCM, using a Core Audio process tap (macOS 14.2+). Needs the "System Audio
// Recording" permission for the app that launched it. Errors go to stderr; exits non-zero on failure.
import Foundation
import CoreAudio
import AudioToolbox

func fail(_ msg: String, _ status: OSStatus = 0) -> Never {
    fputs("error: \(msg)\(status != 0 ? " (OSStatus \(status))" : "")\n", stderr)
    exit(1)
}

// 1. A global tap on all system output, mixed down to stereo, not muting what you hear.
let tapDescription = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
tapDescription.name = "meeting-copilot"
tapDescription.isPrivate = true
tapDescription.muteBehavior = .unmuted

var tapID = AudioObjectID(kAudioObjectUnknown)
var status = AudioHardwareCreateProcessTap(tapDescription, &tapID)
guard status == noErr else {
    fail("could not create the system audio tap; allow System Audio Recording for the app you launched from", status)
}

// 2. What format does the tap deliver?
var formatAddress = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat,
                                               mScope: kAudioObjectPropertyScopeGlobal,
                                               mElement: kAudioObjectPropertyElementMain)
var format = AudioStreamBasicDescription()
var formatSize = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
status = AudioObjectGetPropertyData(tapID, &formatAddress, 0, nil, &formatSize, &format)
guard status == noErr, format.mSampleRate > 0 else { fail("could not read the tap format", status) }
let channels = max(1, Int(format.mChannelsPerFrame))
let interleaved = (format.mFormatFlags & kAudioFormatFlagIsNonInterleaved) == 0
fputs("tap format: \(format.mSampleRate) Hz, \(channels) ch, interleaved=\(interleaved)\n", stderr)

// 3. A private aggregate device containing the tap, so we can attach an IO callback to it.
let aggregate: [String: Any] = [
    kAudioAggregateDeviceNameKey: "meeting-copilot-tap",
    kAudioAggregateDeviceUIDKey: UUID().uuidString,
    kAudioAggregateDeviceIsPrivateKey: true,
    kAudioAggregateDeviceTapAutoStartKey: true,
    kAudioAggregateDeviceTapListKey: [[
        kAudioSubTapUIDKey: tapDescription.uuid.uuidString,
        kAudioSubTapDriftCompensationKey: true,
    ]],
]
var deviceID = AudioObjectID(kAudioObjectUnknown)
status = AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &deviceID)
guard status == noErr else { fail("could not create the aggregate device", status) }

// 4. Mix to mono, box-filter down to 16 kHz, write to stdout.
let step = format.mSampleRate / 16000.0
var position = 0.0, accumulator: Float = 0, accumulated: Float = 0
let stdout = FileHandle.standardOutput

func consume(_ mono: Float, into out: inout [Float]) {
    accumulator += mono
    accumulated += 1
    position += 1
    if position >= step {
        out.append(accumulator / accumulated)
        position -= step
        accumulator = 0
        accumulated = 0
    }
}

var procID: AudioDeviceIOProcID?
let queue = DispatchQueue(label: "meeting-copilot.audio", qos: .userInteractive)
status = AudioDeviceCreateIOProcIDWithBlock(&procID, deviceID, queue) { _, inputData, _, _, _ in
    let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: inputData))
    var out = [Float]()
    if interleaved {
        guard let buffer = buffers.first, let raw = buffer.mData else { return }
        let samples = raw.assumingMemoryBound(to: Float.self)
        let frames = Int(buffer.mDataByteSize) / (MemoryLayout<Float>.size * channels)
        for f in 0..<frames {
            var sum: Float = 0
            for c in 0..<channels { sum += samples[f * channels + c] }
            consume(sum / Float(channels), into: &out)
        }
    } else {
        let frames = buffers.first.map { Int($0.mDataByteSize) / MemoryLayout<Float>.size } ?? 0
        for f in 0..<frames {
            var sum: Float = 0
            for b in buffers { if let raw = b.mData { sum += raw.assumingMemoryBound(to: Float.self)[f] } }
            consume(sum / Float(max(1, buffers.count)), into: &out)
        }
    }
    if !out.isEmpty { out.withUnsafeBufferPointer { stdout.write(Data(buffer: $0)) } }
}
guard status == noErr, let proc = procID else { fail("could not create the audio callback", status) }

status = AudioDeviceStart(deviceID, proc)
guard status == noErr else { fail("could not start capturing", status) }
fputs("started\n", stderr)

// Clean up the tap when asked to stop.
signal(SIGTERM, SIG_IGN)
let stopper = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
stopper.setEventHandler {
    AudioDeviceStop(deviceID, proc)
    AudioDeviceDestroyIOProcID(deviceID, proc)
    AudioHardwareDestroyAggregateDevice(deviceID)
    AudioHardwareDestroyProcessTap(tapID)
    exit(0)
}
stopper.resume()
dispatchMain()
