package com.example.phonecam

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

private const val MAX_CONNECTION_TIMEOUT = 2000L
private const val IMAGE_WIDTH = 1280
private const val IMAGE_HEIGHT = 720
private const val MAX_IMAGES_IN_BUFFER = 3
private const val JPEG_QUALITY = 50.toByte()
private const val FRAME_RATE = 24

class MainActivity : ComponentActivity() {
    private lateinit var socket: Socket
    private lateinit var outputStream: OutputStream
    private lateinit var networkExecutor: ExecutorService
    private lateinit var frameQueue: LinkedBlockingQueue<Image>
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraId: String
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var handler: Handler

    private var tag = "stream"
    private var isConnected = false
    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startAppWithPermissionsGranted()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startAppWithPermissionsGranted()
        }
    }

    private fun startAppWithPermissionsGranted() {
        lockScreenOrientation()

        setContentView(R.layout.activity_main)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIdList = cameraManager.cameraIdList

        val cameraBackId = cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val cameraFrontId = cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }

        cameraId = cameraFrontId ?: cameraBackId ?: throw IllegalStateException("No available camera found")
        frameQueue = LinkedBlockingQueue()

        // Create a thread for camera operations
        val handlerThread = HandlerThread("CameraThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        val ipAddressEditText = findViewById<EditText>(R.id.ipAddressEditText)
        val portEditText = findViewById<EditText>(R.id.portEditText)
        val switchCameraButton = findViewById<Button>(R.id.switchCameraButton)
        val streamCameraButton = findViewById<Button>(R.id.streamCameraButton)

        val sharedPref = this.getPreferences(Context.MODE_PRIVATE)
        ipAddressEditText.setText(sharedPref.getString("ipAddress", ""))
        portEditText.setText(sharedPref.getInt("port", 0).toString())

        val clickListener = View.OnClickListener { view ->
            val ipAddress = ipAddressEditText.text.toString()
            val portStr = portEditText.text.toString()

            if (ipAddress.isBlank() || portStr.isBlank()) {
                Toast.makeText(this, "IP address and port must not be empty", Toast.LENGTH_SHORT)
                    .show()
                return@OnClickListener
            }

            val port = try {
                portStr.toInt()
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Port must be a valid integer", Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }

            with(sharedPref.edit()) {
                putString("ipAddress", ipAddress)
                putInt("port", port)
                apply()
            }

            when (view.id) {
                R.id.streamCameraButton -> {
                    if (!isStreaming) {
                        startStreaming(ipAddress, port)
                    } else {
                        stopStreaming()
                    }
                }

                R.id.switchCameraButton -> {
                    if (isStreaming) {
                        stopStreaming()

                        // Add timeout to wait connection with server
                        Thread.sleep(MAX_CONNECTION_TIMEOUT)

                        switchCamera(cameraBackId, cameraFrontId)
                        startStreaming(ipAddress, port)
                    } else {
                        switchCamera(cameraBackId, cameraFrontId)
                    }
                }
            }
        }

        streamCameraButton.setOnClickListener(clickListener)
        switchCameraButton.setOnClickListener(clickListener)
    }

    /**
     * Start the camera streaming process.
     */
    private fun startStreaming(ipAddress: String, port: Int) {
        networkExecutor = Executors.newSingleThreadExecutor()
        sendCameraFrames(ipAddress, port)

        // Add timeout to wait connection with server
        Thread.sleep(MAX_CONNECTION_TIMEOUT)

        if (isConnected) {
            isStreaming = true
            captureCameraFrames()
            findViewById<Button>(R.id.streamCameraButton).text = "Stop Streaming"
        }
        networkExecutor.shutdown()
    }

    /**
     * Stop the camera streaming process.
     */
    private fun stopStreaming() {
        isStreaming = false
        try {
            captureSession.close()
            cameraDevice.close()
            if (isConnected) {
                socket.close()
            }
        } catch (e: IOException) {
            Log.d(tag, "Error: ${e.message}")
        } finally {
            if (isConnected) {
                networkExecutor.shutdownNow()
                isConnected = false
            }
        }
        findViewById<Button>(R.id.streamCameraButton).text = "Start Streaming"
    }

    /**
     * Switch between the front and back camera.
     */
    private fun switchCamera(cameraBackId: String?, cameraFrontId: String?) {
        cameraId = if (cameraId == cameraBackId) {
            if (cameraFrontId != null) {
                Toast.makeText(this, "Switching to front camera", Toast.LENGTH_SHORT).show()
                cameraFrontId.toString()
            } else {
                Toast.makeText(this, "No front camera available", Toast.LENGTH_SHORT).show()
                cameraBackId.toString()
            }
        } else {
            if (cameraBackId != null) {
                Toast.makeText(this, "Switching to back camera", Toast.LENGTH_SHORT).show()
                cameraBackId.toString()
            } else {
                Toast.makeText(this, "No back camera available", Toast.LENGTH_SHORT).show()
                cameraFrontId.toString()
            }
        }
    }

    /**
     * Capture camera frames and push them into the shared queue.
     */
    private fun captureCameraFrames() {
        val imageReader =
            ImageReader.newInstance(IMAGE_WIDTH, IMAGE_HEIGHT, ImageFormat.JPEG, MAX_IMAGES_IN_BUFFER)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(tag, "Push frame into queue")
            frameQueue.offer(image)
        }, handler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera

                    val sensorOrientation = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION)

                    val captureRequestBuilder =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(FRAME_RATE, FRAME_RATE)
                            )
                            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                        }

                    val outputConfig = OutputConfiguration(imageReader.surface)

                    val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        listOf(outputConfig),
                        Executors.newSingleThreadExecutor(),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                captureSession.setRepeatingRequest(
                                    captureRequestBuilder.build(),
                                    null,
                                    handler
                                )
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Failed to configure camera",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        })

                    camera.createCaptureSession(sessionConfig)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, handler)
        }  catch (e: SecurityException) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Take camera frames from the shared queue and send them to a server.
     */
    private fun sendCameraFrames(ipAddress: String, port: Int) {
        networkExecutor.execute {
            try {
                socket = Socket(ipAddress, port)
                outputStream = socket.getOutputStream()
                isConnected = true

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connected to server", Toast.LENGTH_SHORT).show()
                }

                val frameSize = ByteBuffer.allocate(4)

                while (!Thread.currentThread().isInterrupted) {
                    Log.d(tag, "Take frame from queue")
                    val frame = frameQueue.take()

                    try {
                        val buffer = frame.planes[0].buffer
                        val frameBytes = ByteArray(buffer.remaining())
                        buffer.get(frameBytes)

                        val frameBytesSize = frameBytes.size
                        Log.d(tag, "Frame size: $frameBytesSize")

                        frameSize.clear()
                        frameSize.putInt(frameBytesSize)
                        outputStream.write(frameSize.array())

                        outputStream.write(frameBytes)
                        outputStream.flush()
                    } catch (e: Exception) {
                        Log.d(tag, "Error: ${e.message}")
                    } finally {
                        frame.close()
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Error: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to connect to server", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try {
                    outputStream.close()
                    socket.close()
                } catch (e: Exception) {
                    Log.d(tag, "Error: ${e.message}")
                }
                isConnected = false
            }
        }
    }

    /**
     * Lock the screen orientation to its current state.
     */
    private fun lockScreenOrientation() {
        val orientation = resources.configuration.orientation

        requestedOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
    }

    /**
     * Clean up resources when the activity is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdown()
        handler.looper.quitSafely()
    }
}