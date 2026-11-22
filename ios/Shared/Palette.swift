import SwiftUI

/// The same palette as the Android app ("Night").
enum Palette {
    static let night = Color(hex: 0x0E0C14)
    static let paper = Color(hex: 0xF4F1EC)
    static let mist = Color(hex: 0xC9C3D9)
    static let lavender = Color(hex: 0xC4A8FF)
    static let violet = Color(hex: 0x6B4FD8)
    static let ember = Color(hex: 0xFF9F6E)
    static let spotify = Color(hex: 0x1ED760)
    static let appleMusic = Color(hex: 0xFF5E7A)
    static let youtube = Color(hex: 0xFF7A7A)
    static let local = Color(hex: 0xFFC857)

    static func display(_ size: CGFloat) -> Font { .custom("Space Grotesk", size: size).weight(.bold) }
    static func heading(_ size: CGFloat) -> Font { .custom("Space Grotesk", size: size).weight(.semibold) }
    static func body(_ size: CGFloat) -> Font { .custom("DM Sans", size: size) }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha
        )
    }
}

/// Visualizer looks, shared by the app and the widget.
enum VisualizerStyle: String, CaseIterable, Identifiable, Codable {
    case bars, mirror, dots, led, center, rainbow, wave

    var id: String { rawValue }

    var label: String {
        switch self {
        case .bars: return "Bars"
        case .mirror: return "Mirror"
        case .dots: return "Dots"
        case .led: return "LED"
        case .center: return "Center"
        case .rainbow: return "Rainbow"
        case .wave: return "Wave"
        }
    }
}

/// Draws the visualizer bars; used by the player screen and (statically) by the widget.
struct BarsShape: View {
    var bars: [Float]
    var style: VisualizerStyle
    var color: Color
    var spacing: CGFloat = 3

    var body: some View {
        Canvas { ctx, size in
            let n = max(bars.count, 1)
            let w = (size.width - spacing * CGFloat(n - 1)) / CGFloat(n)
            for (i, raw) in bars.enumerated() {
                let v = CGFloat(min(max(raw, 0.04), 1))
                let x = CGFloat(i) * (w + spacing)
                let tint: Color = style == .rainbow
                    ? Color(hue: Double(i) / Double(n), saturation: 0.65, brightness: 1)
                    : color
                switch style {
                case .bars, .rainbow:
                    let h = v * size.height
                    let r = CGRect(x: x, y: size.height - h, width: w, height: h)
                    ctx.fill(Path(roundedRect: r, cornerRadius: min(w / 2, 4)), with: .color(tint))
                case .mirror:
                    let h = v * size.height
                    let r = CGRect(x: x, y: (size.height - h) / 2, width: w, height: h)
                    ctx.fill(Path(roundedRect: r, cornerRadius: min(w / 2, 4)), with: .color(tint))
                case .center:
                    let h = v * size.height / 2
                    let top = CGRect(x: x, y: size.height / 2 - h, width: w, height: h)
                    let bottom = CGRect(x: x, y: size.height / 2, width: w, height: h * 0.6)
                    ctx.fill(Path(roundedRect: top, cornerRadius: min(w / 2, 3)), with: .color(tint))
                    ctx.fill(Path(roundedRect: bottom, cornerRadius: min(w / 2, 3)), with: .color(tint.opacity(0.45)))
                case .dots:
                    let d = min(w, size.height / 6)
                    let y = size.height - v * (size.height - d) - d
                    ctx.fill(Path(ellipseIn: CGRect(x: x + (w - d) / 2, y: y, width: d, height: d)), with: .color(tint))
                case .led:
                    let cells = 10
                    let cellH = size.height / CGFloat(cells)
                    let lit = Int((v * CGFloat(cells)).rounded())
                    for c in 0..<cells {
                        let y = size.height - CGFloat(c + 1) * cellH
                        let r = CGRect(x: x, y: y + 1, width: w, height: cellH - 2)
                        let on = c < lit
                        let cellColor: Color = c >= cells - 2 ? Palette.ember : (c >= cells - 4 ? Palette.lavender : tint)
                        ctx.fill(Path(roundedRect: r, cornerRadius: 1.5), with: .color(on ? cellColor : tint.opacity(0.12)))
                    }
                case .wave:
                    break
                }
            }
            if style == .wave {
                var path = Path()
                for (i, raw) in bars.enumerated() {
                    let v = CGFloat(min(max(raw, 0.04), 1))
                    let x = CGFloat(i) / CGFloat(max(n - 1, 1)) * size.width
                    let y = size.height / 2 - (i % 2 == 0 ? 1 : -1) * v * size.height / 2
                    if i == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
                }
                ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            }
        }
    }
}
