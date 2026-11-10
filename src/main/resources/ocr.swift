// Reads the text in an image using macOS Vision (on-device) and prints it, one line per text row, in
// reading order. Usage: ocr <image-path>. Exits non-zero on failure.
import Foundation
import Vision
import ImageIO

guard CommandLine.arguments.count > 1 else { fputs("usage: ocr <image>\n", stderr); exit(2) }
let url = URL(fileURLWithPath: CommandLine.arguments[1])
guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fputs("error: cannot read image\n", stderr); exit(1)
}

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = true
request.automaticallyDetectsLanguage = true

do { try VNImageRequestHandler(cgImage: image, options: [:]).perform([request]) }
catch { fputs("error: \(error)\n", stderr); exit(1) }

// Vision returns boxes in normalised coordinates with the origin at the bottom-left: sort top-to-bottom,
// then left-to-right within a row.
let observations = (request.results ?? []).sorted {
    let dy = $0.boundingBox.midY - $1.boundingBox.midY
    if abs(dy) > 0.008 { return dy > 0 }
    return $0.boundingBox.minX < $1.boundingBox.minX
}
var lines: [String] = []
var lastY: CGFloat = -1
for o in observations {
    guard let text = o.topCandidates(1).first?.string else { continue }
    if lastY >= 0 && abs(o.boundingBox.midY - lastY) <= 0.008, !lines.isEmpty { lines[lines.count - 1] += "  " + text }
    else { lines.append(text) }
    lastY = o.boundingBox.midY
}
print(lines.joined(separator: "\n"))
