package org.focuslock.app

import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScanner(onQr: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onQr)
    val errorCallback by rememberUpdatedState(onError)
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val scanned = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            if (executor.isShutdown) return@Runnable
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor, object : ImageAnalysis.Analyzer {
                    @OptIn(ExperimentalGetImage::class)
                    override fun analyze(image: androidx.camera.core.ImageProxy) {
                        val media = image.image
                        if (media == null || scanned.get()) { image.close(); return }
                        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { codes ->
                                val qr = codes.firstOrNull { it.rawValue?.startsWith("FL2.") == true }?.rawValue
                                if (qr != null && scanned.compareAndSet(false, true)) callback(qr)
                            }
                            .addOnCompleteListener { image.close() }
                    }
                })
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { errorCallback("Camera could not start. Request teacher review below.") }
        }
        providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
fun ScanScreen(sessionId: Int, loading: Boolean, pending: Boolean, error: String?, result: String?, onQr: (String) -> Unit, onDecline: () -> Unit, onReview: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var reason by remember { mutableStateOf("Camera or network unavailable during class") }
    var scanned by remember(sessionId) { mutableStateOf(false) }
    var pendingQr by remember(sessionId) { mutableStateOf<String?>(null) }
    var cameraError by remember(sessionId) { mutableStateOf<String?>(null) }
    if (pendingQr != null) AlertDialog(
        onDismissRequest = { pendingQr = null; scanned = false },
        title = { Text("Before submitting your QR") },
        text = { Text("If you accept, FocusLock will record this QR check-in and automatically start voluntary focus when this class is active. While focus is active, leaving the app with the screen on may send a neutral review event to your teacher. You can Pause or Exit at any time; this does not change an existing attendance record. DND needs separate Android access. If you decline, this QR will not grant attendance automatically. Your request will go to your teacher for review; you are not automatically marked absent. This QR may expire while you decide, so you may need to scan the current code again.") },
        confirmButton = { GTextButton(onClick = { val qr = pendingQr!!; pendingQr = null; onQr(qr) }) { Text("Accept and submit QR") } },
        dismissButton = { GTextButton(onClick = { pendingQr = null; onDecline() }) { Text("Decline · teacher review") } }
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassIconButton(onBack, "Back") { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        Text("Scan classroom QR", style = MaterialTheme.typography.headlineMedium)
        Text("The code refreshes every 20 seconds. A check-in is evidence of participation, not proof of presence.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val cameraAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!cameraAvailable) Text("No camera on this device. Request teacher review below.")
        else if (!granted) GButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Allow camera") }
        else if (!scanned && result == null && cameraError == null) CameraFrame(aspect = 1f, guide = false) { QrScanner(onQr = { value -> scanned = true; pendingQr = value }, onError = { cameraError = it }, modifier = Modifier.fillMaxSize()) }
        cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) CircularProgressIndicator()
        if (result != null) Text(result, color = MaterialTheme.colorScheme.primary)
        if (error != null) { Text(error, color = MaterialTheme.colorScheme.error); if (scanned) GTextButton(onClick = { scanned = false }) { Text("Scan again") } }
        if (pending) Text("Pending teacher review. Nothing is counted yet.", color = MaterialTheme.colorScheme.tertiary)
        GTextField(reason, { reason = it }, "Reason for teacher review")
        GOutlinedButton(onClick = { onReview(reason) }, enabled = !loading && reason.trim().length >= 10, modifier = Modifier.fillMaxWidth()) { Text("Request teacher review") }
    }
}
