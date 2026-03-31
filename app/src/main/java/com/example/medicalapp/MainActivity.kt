package com.example.medicalapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.medicalapp.model.IDCardInfo
import com.example.medicalapp.ocr.IDCardOCRHelper
import com.example.medicalapp.face.AliyunFaceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    
    private val PICK_IMAGE = 100
    private val FACE_CAPTURE = 101
    
    private lateinit var ivIdCard: ImageView
    private lateinit var etName: EditText
    private lateinit var etIdNumber: EditText
    private lateinit var etGender: EditText
    private lateinit var etAddress: EditText
    private lateinit var btnFaceCompare: Button
    private lateinit var btnLogs: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var layoutUpload: android.widget.LinearLayout
    
    private var ocrHelper: IDCardOCRHelper? = null
    private var aliyunFaceHelper: AliyunFaceHelper? = null
    private var currentIdCardBitmap: Bitmap? = null
    private var currentIdCardInfo: IDCardInfo? = null
    
    private val API_KEY = "Su4BMNAumYZWBzJbuiL1wASF"
    private val SECRET_KEY = "2yw7FNQ3EvobHqy41ZxIoTnLQYcVW83K"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        initHelpers()
        
        LogActivity.addLog("Main", "App started")
    }
    
    private fun initViews() {
        ivIdCard = findViewById(R.id.ivIdCard)
        etName = findViewById(R.id.etName)
        etIdNumber = findViewById(R.id.etIdNumber)
        etGender = findViewById(R.id.etGender)
        etAddress = findViewById(R.id.etAddress)
        btnFaceCompare = findViewById(R.id.btnFaceCompare)
        btnLogs = findViewById(R.id.btnLogs)
        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        layoutUpload = findViewById(R.id.layoutUpload)
        
        layoutUpload.setOnClickListener {
            openGallery()
        }
        
        ivIdCard.setOnClickListener {
            openGallery()
        }
        
        btnFaceCompare.setOnClickListener {
            startFaceVerification()
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
    
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE)
    }
    
    private fun startFaceVerification() {
        if (currentIdCardInfo == null) {
            Toast.makeText(this, "请先上传身份证照片", Toast.LENGTH_SHORT).show()
            return
        }
        
        LogActivity.addLog("Main", "Starting face capture activity")
        val intent = Intent(this, FaceCaptureActivity::class.java)
        startActivityForResult(intent, FACE_CAPTURE)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            val imageUri: Uri? = data.data
            imageUri?.let {
                LogActivity.addLog("Main", "Loading image from gallery")
                handleImageSelected(it)
            }
        } else if (requestCode == FACE_CAPTURE && resultCode == RESULT_OK && data != null) {
            val faceBitmap: Bitmap? = data.getParcelableExtra("face_bitmap")
            faceBitmap?.let {
                LogActivity.addLog("Main", "Face captured successfully")
                performFaceVerification(it)
            }
        } else if (requestCode == FACE_CAPTURE) {
            LogActivity.addLog("Main", "Face capture cancelled")
        }
    }
    
    private fun handleImageSelected(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream = contentResolver.openInputStream(imageUri)
                    BitmapFactory.decodeStream(inputStream)
                }
                
                bitmap?.let {
                    currentIdCardBitmap = it
                    ivIdCard.setImageBitmap(it)
                    layoutUpload.visibility = android.view.View.GONE
                    
                    LogActivity.addLog("OCR", "Starting OCR recognition")
                    
                    val info = withContext(Dispatchers.IO) {
                        performBaiduOCR(it)
                    }
                    
                    if (info != null) {
                        currentIdCardInfo = info
                        displayIDCardInfo(info)
                        tvStatus.text = "ID Card recognized successfully"
                        btnFaceCompare.isEnabled = true
                        LogActivity.addLog("OCR", "Recognition successful: ${info.name}")
                    } else {
                        tvStatus.text = "OCR failed. Please input manually."
                        LogActivity.addLog("OCR", "Failed to recognize")
                    }
                }
                
            } catch (e: Exception) {
                LogActivity.addLog("OCR", "Exception: ${e.message}")
                tvStatus.text = "Error loading image"
            }
        }
    }
    
    private fun performBaiduOCR(bitmap: Bitmap): IDCardInfo? {
        try {
            LogActivity.addLog("OCR", "Starting inline Baidu OCR...")
            
            val tokenUrl = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=$API_KEY&client_secret=$SECRET_KEY"
            val tokenRequest = Request.Builder()
                .url(tokenUrl)
                .post(FormBody.Builder().build())
                .build()
            
            val client = OkHttpClient()
            LogActivity.addLog("OCR", "Getting token...")
            val tokenResponse = client.newCall(tokenRequest).execute()
            val tokenBody = tokenResponse.body?.string()
            
            val token = JSONObject(tokenBody ?: "{}").optString("access_token")
            if (token.isEmpty()) {
                LogActivity.addLog("OCR", "Failed to get token")
                return null
            }
            LogActivity.addLog("OCR", "Got token: ${token.take(20)}...")
            
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val imageBase64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
            LogActivity.addLog("OCR", "Image base64 length: ${imageBase64.length}")
            
            val ocrUrl = "https://aip.baidubce.com/rest/2.0/ocr/v1/idcard?access_token=$token&id_card_side=front"
            val ocrBody = FormBody.Builder()
                .add("image", imageBase64)
                .add("detect_direction", "true")
                .build()
            
            val ocrRequest = Request.Builder()
                .url(ocrUrl)
                .post(ocrBody)
                .build()
            
            LogActivity.addLog("OCR", "Calling Baidu OCR API...")
            val ocrResponse = client.newCall(ocrRequest).execute()
            val ocrResult = ocrResponse.body?.string()
            LogActivity.addLog("OCR", "OCR response received")
            
            return parseOCRResult(ocrResult ?: "")
            
        } catch (e: Exception) {
            LogActivity.addLog("OCR", "OCR Exception: ${e.javaClass.simpleName} - ${e.message}")
            return null
        }
    }
    
    private fun parseOCRResult(jsonStr: String): IDCardInfo? {
        try {
            // 直接用字符串查找（绕过中文键名问题）
            fun extractField(fieldName: String): String {
                val keyPattern = "\"$fieldName\":{\"words\":\""
                val startIdx = jsonStr.indexOf(keyPattern)
                if (startIdx == -1) {
                    LogActivity.addLog("OCR", "Field '$fieldName' not found in JSON")
                    return ""
                }
                
                val valueStart = startIdx + keyPattern.length
                val valueEnd = jsonStr.indexOf("\"", valueStart)
                if (valueEnd == -1) return ""
                
                return jsonStr.substring(valueStart, valueEnd)
            }
            
            val name = extractField("姓名")
            val idNumber = extractField("公民身份号码")
            val gender = extractField("性别")
            val address = extractField("住址")
            
            LogActivity.addLog("OCR", "Extracted - Name: '$name', ID: '$idNumber', Gender: '$gender'")
            
            if (name.isEmpty() && idNumber.isEmpty()) {
                LogActivity.addLog("OCR", "Empty extraction result")
                return null
            }
            
            return IDCardInfo(
                name = name,
                idNumber = idNumber,
                gender = gender,
                address = address
            )
            
        } catch (e: Exception) {
            LogActivity.addLog("OCR", "Parse exception: ${e.message}")
            return null
        }
    }
    
    private fun displayIDCardInfo(info: IDCardInfo) {
        etName.setText(info.name)
        etIdNumber.setText(info.idNumber)
        etGender.setText(info.gender)
        etAddress.setText(info.address)
    }
    
    private fun performFaceVerification(faceBitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                LogActivity.addLog("Face", "Sending to Aliyun API for comparison")
                
                val idCardBitmap = currentIdCardBitmap
                if (idCardBitmap == null) {
                    LogActivity.addLog("Face", "No ID card bitmap available")
                    return@launch
                }
                
                val result = withContext(Dispatchers.IO) {
                    aliyunFaceHelper?.compareFaces(idCardBitmap, faceBitmap)
                }
                
                result?.let { (score, message) ->
                    LogActivity.addLog("Face", "API Result: score=$score, message=$message")
                    
                    val isSamePerson = score > 60.0
                    val similarity = score.toInt()
                    
                    if (isSamePerson) {
                        tvResult.text = "SAME PERSON (Similarity: $similarity%)"
                        tvResult.setBackgroundColor(android.graphics.Color.GREEN)
                        LogActivity.addLog("Face", "Result: SAME PERSON (Similarity: $similarity%)")
                    } else {
                        tvResult.text = "DIFFERENT PERSON (Similarity: $similarity%)"
                        tvResult.setBackgroundColor(android.graphics.Color.RED)
                        LogActivity.addLog("Face", "Result: DIFFERENT PERSON (Similarity: $similarity%)")
                    }
                    tvResult.visibility = android.view.View.VISIBLE
                }
                
            } catch (e: Exception) {
                LogActivity.addLog("Face", "Exception: ${e.message}")
                tvStatus.text = "Face verification error"
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ocrHelper?.close()
        aliyunFaceHelper?.close()
    }
}



