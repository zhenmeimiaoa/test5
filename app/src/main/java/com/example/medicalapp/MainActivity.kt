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
        
        btnFaceCompare.isEnabled = true
        btnFaceCompare.alpha = 1.0f
        
        layoutUpload.setOnClickListener {
            try {
                LogActivity.addLog("Main", "Upload clicked")
                openGallery()
            } catch (e: Exception) {
                LogActivity.addLog("Main", "Upload error: ")
                Toast.makeText(this, "Error: ", Toast.LENGTH_LONG).show()
            }
        }
        
        ivIdCard.setOnClickListener {
            try {
                LogActivity.addLog("Main", "Image clicked")
                openGallery()
            } catch (e: Exception) {
                LogActivity.addLog("Main", "Image click error: ")
                Toast.makeText(this, "Error: ", Toast.LENGTH_LONG).show()
            }
        }
        
        btnFaceCompare.setOnClickListener {
            try {
                LogActivity.addLog("Main", "Face compare clicked")
                startFaceVerification()
            } catch (e: Exception) {
                LogActivity.addLog("Main", "Face compare error: ")
                Toast.makeText(this, "Error: ", Toast.LENGTH_LONG).show()
            }
        }
        
        btnLogs.setOnClickListener {
            try {
                startActivity(Intent(this, LogActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun initHelpers() {
        try {
            ocrHelper = IDCardOCRHelper()
            aliyunFaceHelper = AliyunFaceHelper()
            LogActivity.addLog("Main", "Helpers initialized")
        } catch (e: Exception) {
            LogActivity.addLog("Main", "Helper init error: ")
        }
    }
    
    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, PICK_IMAGE)
        } catch (e: Exception) {
            LogActivity.addLog("Main", "Gallery error: ")
            Toast.makeText(this, "Cannot open gallery: ", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun startFaceVerification() {
        LogActivity.addLog("Main", "Face verification started, info=")
        
        if (currentIdCardInfo == null) {
            Toast.makeText(this, "Please upload ID card first", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val intent = Intent(this, FaceCaptureActivity::class.java)
            startActivityForResult(intent, FACE_CAPTURE)
        } catch (e: Exception) {
            LogActivity.addLog("Main", "Face capture error: ")
            Toast.makeText(this, "Cannot start: ", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        LogActivity.addLog("Main", "Result: code=, result=")
        
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            data.data?.let {
                LogActivity.addLog("Main", "Image selected: ")
                handleImageSelected(it)
            }
        } else if (requestCode == FACE_CAPTURE && resultCode == RESULT_OK && data != null) {
            val faceBitmap: Bitmap? = data.getParcelableExtra("face_bitmap")
            faceBitmap?.let {
                LogActivity.addLog("Main", "Face captured")
                performFaceVerification(it)
            }
        }
    }
    
    private fun handleImageSelected(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream = contentResolver.openInputStream(imageUri)
                    BitmapFactory.decodeStream(inputStream)
                }
                
                if (bitmap == null) {
                    tvStatus.text = "Failed to load image"
                    return@launch
                }
                
                currentIdCardBitmap = bitmap
                ivIdCard.setImageBitmap(bitmap)
                layoutUpload.visibility = android.view.View.GONE
                tvStatus.text = "Recognizing..."
                
                LogActivity.addLog("OCR", "Starting recognition")
                
                val info = withContext(Dispatchers.IO) {
                    performBaiduOCR(bitmap)
                }
                
                if (info != null) {
                    currentIdCardInfo = info
                    displayIDCardInfo(info)
                    tvStatus.text = "Success: "
                    LogActivity.addLog("OCR", "Success: ")
                } else {
                    tvStatus.text = "OCR failed, please input manually"
                    LogActivity.addLog("OCR", "Failed")
                }
                
            } catch (e: Exception) {
                LogActivity.addLog("OCR", "Error: ")
                tvStatus.text = "Error: "
            }
        }
    }
    
    private fun performBaiduOCR(bitmap: Bitmap): IDCardInfo? {
        try {
            val tokenUrl = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=&client_secret="
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
                LogActivity.addLog("OCR", "Token empty")
                return null
            }
            
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val imageBase64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
            
            val ocrUrl = "https://aip.baidubce.com/rest/2.0/ocr/v1/idcard?access_token=&id_card_side=front"
            val ocrBody = FormBody.Builder()
                .add("image", imageBase64)
                .add("detect_direction", "true")
                .build()
            
            val ocrRequest = Request.Builder()
                .url(ocrUrl)
                .post(ocrBody)
                .build()
            
            LogActivity.addLog("OCR", "Calling API...")
            val ocrResponse = client.newCall(ocrRequest).execute()
            val ocrResult = ocrResponse.body?.string()
            
            LogActivity.addLog("OCR", "Response: ...")
            
            return parseOCRResult(ocrResult ?: "")
            
        } catch (e: Exception) {
            LogActivity.addLog("OCR", "Exception: ")
            return null
        }
    }
    
    private fun parseOCRResult(jsonStr: String): IDCardInfo? {
        try {
            val nameIdx = jsonStr.indexOf("\"姓名\":{\"words\":\"")
            val idIdx = jsonStr.indexOf("\"公民身份号码\":{\"words\":\"")
            val genderIdx = jsonStr.indexOf("\"性别\":{\"words\":\"")
            val addrIdx = jsonStr.indexOf("\"住址\":{\"words\":\"")
            
            fun extractValue(idx: Int): String {
                if (idx == -1) return ""
                val start = idx + 13
                val end = jsonStr.indexOf("\"", start)
                return if (end == -1) "" else jsonStr.substring(start, end)
            }
            
            val name = extractValue(nameIdx)
            val idNumber = extractValue(idIdx)
            val gender = extractValue(genderIdx)
            val address = extractValue(addrIdx)
            
            LogActivity.addLog("OCR", "Got name='' id=''")
            
            if (name.isEmpty() && idNumber.isEmpty()) return null
            
            return IDCardInfo(name, idNumber, gender, address)
            
        } catch (e: Exception) {
            LogActivity.addLog("OCR", "Parse error: ")
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
                val idCardBitmap = currentIdCardBitmap
                if (idCardBitmap == null) {
                    tvStatus.text = "No ID card image"
                    return@launch
                }
                
                val result = withContext(Dispatchers.IO) {
                    aliyunFaceHelper?.compareFaces(idCardBitmap, faceBitmap)
                }
                
                result?.let { (score, message) ->
                    val isSamePerson = score > 60.0
                    val similarity = score.toInt()
                    
                    if (isSamePerson) {
                        tvResult.text = "SAME PERSON (%)"
                        tvResult.setBackgroundColor(android.graphics.Color.GREEN)
                    } else {
                        tvResult.text = "DIFFERENT PERSON (%)"
                        tvResult.setBackgroundColor(android.graphics.Color.RED)
                    }
                    tvResult.visibility = android.view.View.VISIBLE
                }
                
            } catch (e: Exception) {
                LogActivity.addLog("Face", "Error: ")
                tvStatus.text = "Face error: "
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ocrHelper?.close()
        aliyunFaceHelper?.close()
    }
}
