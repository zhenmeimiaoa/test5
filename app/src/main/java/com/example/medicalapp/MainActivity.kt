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
                
                LogActivity.addLog("OCR", "Calling ocrHelper.recognizeIDCard...")
                // 内联百度 OCR 测试
                val info = withContext(Dispatchers.IO) {
                    try {
                        LogActivity.addLog("OCR", "Starting inline Baidu OCR...")
                        
                        // 直接在这里调用百度 OCR
                        val API_KEY = "Su4BMNAumYZWBzJbuiL1wASF"
                        val SECRET_KEY = "2yw7FNQ3EvobHqy41ZxIoTnLQYcVW83K"
                        
                        // 1. 获取 token
                        val tokenUrl = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=$API_KEY&client_secret=$SECRET_KEY"
                        val tokenRequest = okhttp3.Request.Builder()
                            .url(tokenUrl)
                            .post(okhttp3.FormBody.Builder().build())
                            .build()
                        
                        val client = okhttp3.OkHttpClient()
                        LogActivity.addLog("OCR", "Getting token...")
                        val tokenResponse = client.newCall(tokenRequest).execute()
                        val tokenBody = tokenResponse.body?.string()
                        LogActivity.addLog("OCR", "Token response: $tokenBody")
                        
                        val token = org.json.JSONObject(tokenBody ?: "{}").optString("access_token")
                        if (token.isEmpty()) {
                            LogActivity.addLog("OCR", "Failed to get token")
                            return@withContext null
                        }
                        LogActivity.addLog("OCR", "Got token: ${token.take(20)}...")
                        
                        // 2. 转换图片
                        val outputStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outputStream)
                        val imageBase64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
                        LogActivity.addLog("OCR", "Image base64 length: ${imageBase64.length}")
                        
                        // 3. 调用 OCR
                        val ocrUrl = "https://aip.baidubce.com/rest/2.0/ocr/v1/idcard?access_token=$token&id_card_side=front"
                        val ocrBody = okhttp3.FormBody.Builder()
                            .add("image", imageBase64)
                            .add("detect_direction", "true")
                            .build()
                        
                        val ocrRequest = okhttp3.Request.Builder()
                            .url(ocrUrl)
                            .post(ocrBody)
                            .build()
                        
                        LogActivity.addLog("OCR", "Calling Baidu OCR API...")
                        val ocrResponse = client.newCall(ocrRequest).execute()
                        val ocrResult = ocrResponse.body?.string()
                        LogActivity.addLog("OCR", "OCR response: $ocrResult")
                        
                        // 4. 解析结果
                        val json = org.json.JSONObject(ocrResult ?: "{}")
                        if (json.has("error_code")) {
                            LogActivity.addLog("OCR", "API error: ${json.optString("error_msg")}")
                            return@withContext null
                        }
                        
                        val wordsResult = json.optJSONObject("words_result") 
                        if (wordsResult == null) {
                            LogActivity.addLog("OCR", "words_result is null")
                            return@withContext null
                        }
                        
                        LogActivity.addLog("OCR", "words_result keys: ${wordsResult.keys().asSequence().toList()}")
                        
                        val nameObj = wordsResult.optJSONObject("姓名")
                        val idObj = wordsResult.optJSONObject("公民身份号码")
                        val genderObj = wordsResult.optJSONObject("性别")
                        val addressObj = wordsResult.optJSONObject("住址")
                        
                        LogActivity.addLog("OCR", "nameObj: $nameObj, idObj: $idObj")
                        
                        val name = nameObj?.optString("words") ?: ""
                        val idNumber = idObj?.optString("words") ?: ""
                        val gender = genderObj?.optString("words") ?: ""
                        val address = addressObj?.optString("words") ?: ""
                        
                        LogActivity.addLog("OCR", "Parsed: name='$name', id='$idNumber', gender='$gender'")
                        
                        if (name.isEmpty() && idNumber.isEmpty()) {
                            LogActivity.addLog("OCR", "Empty result")
                            return@withContext null
                        }
                        
                        com.example.medicalapp.model.IDCardInfo(
                            name = name,
                            idNumber = idNumber,
                            gender = gender,
                            address = address
                        )
                        
                    } catch (e: Exception) {
                        LogActivity.addLog("OCR", "Inline OCR Exception: ${e.javaClass.simpleName}")
                        LogActivity.addLog("OCR", "Exception message: ${e.message}")
                        LogActivity.addLog("OCR", "Stack: ${e.stackTrace.take(5).joinToString("\n")}")
                        null
                    }
                }
                
                LogActivity.addLog("OCR", "info is null: ${info == null}")
                
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





