import SwiftUI
import AVFoundation

struct QrScannerView: UIViewControllerRepresentable {
    let onScanned: (String) -> Void

    func makeUIViewController(context: Context) -> QrScannerVC {
        let vc = QrScannerVC()
        vc.onScanned = onScanned
        return vc
    }

    func updateUIViewController(_ uiViewController: QrScannerVC, context: Context) {}
}

final class QrScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScanned: ((String) -> Void)?
    private var session: AVCaptureSession?
    private var handled = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        let sess = AVCaptureSession()
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              sess.canAddInput(input) else { return }
        sess.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard sess.canAddOutput(output) else { return }
        sess.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: sess)
        preview.frame = view.layer.bounds
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)

        session = sess
        DispatchQueue.global(qos: .userInitiated).async { sess.startRunning() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        session?.stopRunning()
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !handled,
              let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let val = obj.stringValue else { return }
        handled = true
        session?.stopRunning()
        onScanned?(val)
    }
}
