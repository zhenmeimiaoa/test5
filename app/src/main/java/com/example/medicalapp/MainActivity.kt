package com.example.medicalapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.medicalapp.face.AliyunFaceHelper
import com.example.medicalapp.model.IDCardInfo
import com.example.medicalapp.ocr.IDCardOCRHelper
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var ivIdCard: ImageView
    private lateinit var layoutUpload: LinearLayout
    private lateinit var etName: EditText
    private lateinit var etIdNumber: EditText
    private lateinit var etGender: EditText
    private lateinit var etAddress: EditText
    private lateinit var btnEdit: ImageButton
    private lateinit var btnFaceCompare: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnLogs: Button

    private var idCardBitmap: Bitmap? = null
    private var isEditing = false
    
    private var ocrHelper: IDCardOCRHelper? = null
    private var aliyunFaceHelper: AliyunFaceHelper? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }
    
    private val faceCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val faceBitmap = FaceCaptureActivity.capturedFaceBitmap
            faceBitmap?.let {
                LogActivity.addLog("Main", "Face captured successfully")
                compareFaces(it)
                FaceCaptureActivity.capturedFaceBitmap = null
            } ?: run {
                LogActivity.addLog("Main", "ERROR: Face bitmap is null")
                tvStatus.text = "Error: Failed to get face image"
                btnFaceCompare.isEnabled = true
            }
        } else {
            LogActivity.addLog("Main", "Face capture cancelled")
            tvStatus.text = "Face capture cancelled"
            btnFaceCompare.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        LogActivity.addLog("Main", "App started")
        
        checkPermissions()
        initViews()
        initHelpers()
    }

    private fun initViews() {
        ivIdCard = findViewById(R.id.ivIdCard)
        layoutUpload = findViewById(R.id.layoutUpload)
        etName = findViewById(R.id.etName)
        etIdNumber = findViewById(R.id.etIdNumber)
        etGender = findViewById(R.id.etGender)
        etAddress = findViewById(R.id.etAddress)
        btnEdit = findViewById(R.id.btnEdit)
        btnFaceCompare = findViewById(R.id.btnFaceCompare)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        btnLogs = findViewById(R.id.btnLogs)

        layoutUpload.setOnClickListener {
            pickImage.launch("image/*")
        }

        btnEdit.setOnClickListener {
            toggleEditMode()
        }

        btnFaceCompare.setOnClickListener {
            startFaceCapture()
        }
        
        btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun initHelpers() {
        ocrHelper = IDCardOCRHelper()
        aliyunFaceHelper = AliyunFaceHelper()
        LogActivity.addLog("Main", "Helpers initialized")
    }

    private fun checkPermissions(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
            false
        } else {
            true
        }
    }

    private fun handleImageSelected(uri: Uri) {
        scope.launch {
            try {
                LogActivity.addLog("Main", "Loading image from gallery")
                val bitmap = withContext(Dispatchers.IO) {
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
                
                idCardBitmap = bitmap
                ivIdCard.setImageBitmap(bitmap)
                layoutUpload.visibility = View.GONE
                
                tvStatus.text = "Recognizing..."
                LogActivity.addLog("OCR", "Starting OCR recognition")
                
                val info = withContext(Dispatchers.IO) {
                    ocrHelper?.recognizeIDCard(bitmap)
                }
                
                if (info != null) {
                    fillForm(info)
                    btnEdit.visibility = View.VISIBLE
                    btnFaceCompare.isEnabled = true
                    tvStatus.text = "ID card recognized. Tap Start Face Verification."
                    LogActivity.addLog("OCR", "Success: ${info.name}, ${info.idNumber}")
                } else {
                    tvStatus.text = "OCR failed. Please input manually."
                    enableEditMode(true)
                    btnFaceCompare.isEnabled = true
                    LogActivity.addLog("OCR", "Failed to recognize")
                }
                
            } catch (e: Exception) {
                val error = "Error loading image: ${e.message}"
                tvStatus.text = error
                LogActivity.addLog("ERROR", error)
            }
        }
    }

    private fun fillForm(info: IDCardInfo) {
        etName.setText(info.name)
        etIdNumber.setText(info.idNumber)
        etGender.setText(info.gender)
        etAddress.setText(info.address)
    }

    private fun toggleEditMode() {
        isEditing = !isEditing
        enableEditMode(isEditing)
        if (!isEditing) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            LogActivity.addLog("Main", "Manual edit saved")
        } else {
            LogActivity.addLog("Main", "Edit mode enabled")
        }
    }

    private fun enableEditMode(enable: Boolean) {
        etName.isEnabled = enable
        etIdNumber.isEnabled = enable
        etGender.isEnabled = enable
        etAddress.isEnabled = enable
    }

    private fun startFaceCapture() {
        if (idCardBitmap == null) {
            Toast.makeText(this, "Please upload ID card first", Toast.LENGTH_SHORT).show()
            return
        }

        btnFaceCompare.isEnabled = false
        tvStatus.text = "Opening face capture..."
        tvResult.visibility = View.GONE
        LogActivity.addLog("Main", "Starting face capture activity")
        
        val intent = Intent(this, FaceCaptureActivity::class.java)
        faceCaptureLauncher.launch(intent)
    }

    private fun compareFaces(cameraBitmap: Bitmap) {
        scope.launch {
            try {
                tvStatus.text = "Connecting to Aliyun AI..."
                LogActivity.addLog("Face", "Sending to Aliyun API for comparison")
                
                val (score, message) = withContext(Dispatchers.IO) {
                    aliyunFaceHelper?.compareFaces(idCardBitmap!!, cameraBitmap) 
                        ?: Pair(0.0, "Helper not initialized")
                }
                
                LogActivity.addLog("Face", "API Result: score=$score, message=$message")
                
                withContext(Dispatchers.Main) {
                    tvResult.visibility = View.VISIBLE
                    
                    if (message == "Success") {
                        val isMatch = score >= 60.0
                        val resultText = if (isMatch) {
                            "SAME PERSON (Similarity: ${"%.1f".format(score)}%)"
                        } else {
                            "DIFFERENT PERSON (Similarity: ${"%.1f".format(score)}%)"
                        }
                        tvResult.text = resultText
                        tvResult.setBackgroundColor(
                            if (isMatch) android.graphics.Color.parseColor("#4CAF50")
                            else android.graphics.Color.parseColor("#F44336")
                        )
                        tvStatus.text = "Verification completed"
                        LogActivity.addLog("Face", "Result: $resultText")
                    } else {
                        tvResult.text = "Error: $message"
                        tvResult.setBackgroundColor(android.graphics.Color.parseColor("#FFC107"))
                        tvStatus.text = "Verification failed"
                        LogActivity.addLog("ERROR", "API Error: $message")
                    }
                }
                
            } catch (e: Exception) {
                val error = "Comparison failed: ${e.message}"
                tvStatus.text = error
                LogActivity.addLog("ERROR", error)
            }
            
            withContext(Dispatchers.Main) {
                btnFaceCompare.isEnabled = idCardBitmap != null
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrHelper?.close()
        aliyunFaceHelper?.close()
        scope.cancel()
    }
}
