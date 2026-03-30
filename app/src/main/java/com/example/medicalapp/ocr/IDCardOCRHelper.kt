package com.example.medicalapp.ocr

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.medicalapp.model.IDCardInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class IDCardOCRHelper {
    
    private val TAG = "BaiduOCR"
    private val API_KEY = "Su4BMNAumYZWBzJbuI1wASF"
    private val SECRET_KEY = "Zyw7FNQ3EvobHqy41ZxloTnLQYcVW83K"
    
    private val client = OkHttpClient.Builder().build()
    private var accessToken: String? = null
    
    suspend fun recognizeIDCard(bitmap: Bitmap): IDCardInfo? {
        return withContext(Dispatchers.IO) {
            try {
                if (accessToken == null) {
                    accessToken = getAccessToken()
                    Log.d(TAG, "Got access token: ${accessToken?.take(10)}...")
                }
                
                if (accessToken == null) {
                    Log.e(TAG, "Failed to get access token")
                    return@withContext null
                }
                
                val imageBase64 = bitmapToBase64(bitmap)
                Log.d(TAG, "Image base64 length: ${imageBase64.length}")
                
                val result = callBaiduOCR(imageBase64)
                Log.d(TAG, "OCR Result: $result")
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception: ${e.message}", e)
                null
            }
        }
    }
    
    private fun getAccessToken(): String? {
        val url = "https://aip.baidubce.com/oauth/2.0/token" +
                "?grant_type=client_credentials" +
                "&client_id=$API_KEY" +
                "&client_secret=$SECRET_KEY"
        
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().build())
            .build()
        
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            Log.d(TAG, "Token response: $body")
            
            if (body == null) return null
            
            val json = JSONObject(body)
            val token = json.optString("access_token")
            if (token.isEmpty()) {
                Log.e(TAG, "Empty token in response")
                null
            } else {
                token
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get token error: ${e.message}")
            null
        }
    }
    
    private fun callBaiduOCR(imageBase64: String): IDCardInfo? {
        val url = "https://aip.baidubce.com/rest/2.0/ocr/v1/idcard" +
                "?access_token=$accessToken" +
                "&id_card_side=front"
        
        val formBody = FormBody.Builder()
            .add("image", imageBase64)
            .add("detect_direction", "true")
            .build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            Log.d(TAG, "OCR API response: $body")
            
            if (body == null) {
                Log.e(TAG, "Empty response body")
                return null
            }
            
            val json = JSONObject(body)
            
            if (json.has("error_code")) {
                val errorCode = json.getInt("error_code")
                val errorMsg = json.optString("error_msg")
                Log.e(TAG, "Baidu API Error $errorCode: $errorMsg")
                
                if (errorCode == 110 || errorCode == 111) {
                    accessToken = null
                }
                return null
            }
            
            val wordsResult = json.optJSONObject("words_result")
            if (wordsResult == null) {
                Log.e(TAG, "No words_result in response")
                return null
            }
            
            val name = extractField(wordsResult, "姓名")
            val idNumber = extractField(wordsResult, "公民身份号码")
            val gender = extractField(wordsResult, "性别")
            val address = extractField(wordsResult, "住址")
            
            Log.d(TAG, "Parsed - Name: $name, ID: $idNumber, Gender: $gender")
            
            if (name.isEmpty() && idNumber.isEmpty()) {
                Log.e(TAG, "Parsed fields are empty")
                return null
            }
            
            IDCardInfo(
                name = name,
                idNumber = idNumber,
                gender = gender,
                address = address
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
            null
        }
    }
    
    private fun extractField(wordsResult: JSONObject, fieldName: String): String {
        val fieldObj = wordsResult.optJSONObject(fieldName)
        val value = fieldObj?.optString("words", "") ?: ""
        Log.d(TAG, "Field [$fieldName]: $value")
        return value
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    fun close() {}
}
