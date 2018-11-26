package co.ealarcon.mlkitscanner

import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.annotation.WorkerThread
import android.support.v7.app.AppCompatActivity
import android.widget.Toast
import com.google.firebase.FirebaseApp
import com.google.firebase.ml.common.FirebaseMLException
import com.google.firebase.ml.common.FirebaseMLException.UNAVAILABLE
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import kotlinx.android.synthetic.main.activity_main.cameraPreview
import kotlinx.android.synthetic.main.activity_main.contents
import kotlinx.coroutines.experimental.*
import kotlin.coroutines.experimental.CoroutineContext

private const val TIMEOUT_FOR_RETRY = 500L

class MainActivity : AppCompatActivity(), CoroutineScope {

    private var job = Job()
    private var imageJob: Deferred<String?>? = null
    private var scanOn = false
    private lateinit var detector: FirebaseVisionBarcodeDetector

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        FirebaseApp.initializeApp(this)

        val options = FirebaseVisionBarcodeDetectorOptions.Builder()
            .setBarcodeFormats(
                FirebaseVisionBarcode.FORMAT_ALL_FORMATS)
            .build()
        detector = FirebaseVision.getInstance().getVisionBarcodeDetector(options)
    }

    override fun onStart() {
        super.onStart()
        cameraPreview.onStart()
        scanOn = true
    }

    override fun onResume() {
        super.onResume()
        cameraPreview.onResume()
        job = launch {
            startProcessing()
        }
    }

    override fun onPause() {
        cameraPreview.onPause()
        super.onPause()
    }

    override fun onStop() {
        scanOn = false
        job.cancel()
        cameraPreview.onStop()
        super.onStop()
    }

    @WorkerThread
    private suspend fun startProcessing() {
        while (scanOn) {
            processPreview()
            delay(TIMEOUT_FOR_RETRY)
        }
    }

    private fun processPreview() {
        cameraPreview.captureImage { _, bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val image = FirebaseVisionImage.fromBitmap(bitmap)
            if (imageJob?.isCompleted != false) {
                imageJob = async {
                    val deferred = CompletableDeferred<String?>()
                    detector.detectInImage(image)
                        .addOnSuccessListener { results ->
                            if (results.isNotEmpty()) {
                                deferred.complete(results[0].rawValue)
                            } else {
                                deferred.complete(null)
                            }
                        }
                        .addOnFailureListener { throwable ->
                            //The firebase model hasn't downloaded yet
                            if (throwable is FirebaseMLException && throwable.code == UNAVAILABLE) {
                                Toast.makeText(this@MainActivity,
                                    getString(R.string.model_download_error),
                                    Toast.LENGTH_LONG).show()
                            }
                            deferred.complete(null)
                        }

                    deferred.await()
                }
            }

            launch(Dispatchers.IO) {
                if (imageJob != null) {
                    val code = imageJob?.await()
                    imageJob?.cancel()
                    launch(Dispatchers.Main) {
                        if (code.isNullOrEmpty()) {
                            contents.text = getString(R.string.scanning)
                        } else {
                            contents.text = code
                        }
                    }
                }
            }

        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        cameraPreview.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
