package com.example.medicalapp.ocr

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.medicalapp.BuildConfig
import com.example.medicalapp.model.IDCardInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class IDCardOCRHelper {
    
    private val TAG = "AliyunOCR"
    private val accessKeyId = BuildConfig.ALIYUN_ACCESS_KEY_ID
    private val accessKeySecret = BuildConfig.ALIYUN_ACCESS_KEY_SECRET
    
    private val client = OkHttpClient.Builder().build()
    
    suspend fun recognizeIDCard(bitmap: Bitmap): IDCardInfo? {
        return withContext(Dispatchers.IO) {
            try {
                if (accessKeyId.isEmpty() || accessKeySecret.isEmpty()) {
                    Log.e(TAG, "Credentials not configured")
                    return@withContext null
                }
                
                val imageBase64 = bitmapToBase64(bitmap)
                Log.d(TAG, "Image base64 length: ${imageBase64.length}")
                callOCRAPI(imageBase64)
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception: ${e.message}", e)
                null
            }
        }
    }
    
    private fun callOCRAPI(imageBase64: String): IDCardInfo? {
        // 尝试两个可能的 endpoint
        val endpoints = listOf(
            "https://ocr-api.cn-hangzhou.aliyuncs.com",
            "https://ocr.cn-hangzhou.aliyuncs.com"
        )
        
        for (url in endpoints) {
            try {
                Log.d(TAG, "Trying endpoint: $url")
                val result = tryCallEndpoint(url, imageBase64)
                if (result != null) {
                    Log.d(TAG, "Success with endpoint: $url")
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed with $url: ${e.message}")
            }
        }
        
        return null
    }
    
    private fun tryCallEndpoint(url: String, imageBase64: String): IDCardInfo? {
        val params = mutableMapOf(
            "Action" to "RecognizeIdcard",
            "Version" to "2021-07-07",
            "Format" to "JSON",
            "AccessKeyId" to accessKeyId,
            "SignatureMethod" to "HMAC-SHA1",
            "Timestamp" to getTimestamp(),
            "SignatureVersion" to "1.0",
            "SignatureNonce" to UUID.randomUUID().toString(),
            "ImageURL" to imageBase64  // 尝试 ImageURL 而不是 body
        )
        
        val signature = calculateSignature(params, accessKeySecret)
        params["Signature"] = signature
        
        val formBody = FormBody.Builder().apply {
            params.forEach { (key, value) ->
                add(key, value)
            }
        }.build()
        
        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .build()
        
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        
        Log.d(TAG, "Response code: ${response.code}")
        Log.d(TAG, "Response body: $body")
        
        if (body == null) {
            Log.e(TAG, "Empty response body")
            return null
        }
        
        return parseResponse(body)
    }
    
    private fun parseResponse(body: String): IDCardInfo? {
        return try {
            val json = JSONObject(body)
            
            // 检查错误
            if (json.has("Code")) {
                val code = json.getString("Code")
                val message = json.optString("Message", "Unknown error")
                Log.e(TAG, "API Error: $code - $message")
                return null
            }
            
            if (!json.has("Data")) {
                Log.e(TAG, "No Data field in response")
                return null
            }
            
            val data = json.getJSONObject("Data")
            Log.d(TAG, "Data object: ${data.toString(2)}")
            
            // 尝试多种可能的返回结构
            val frontResult = data.optJSONObject("FrontResult")
                ?: data.optJSONObject("frontResult")
                ?: data.optJSONObject("face")
            
            if (frontResult == null) {
                Log.e(TAG, "No front result found, available keys: ${data.keys().asSequence().toList()}")
                return null
            }
            
            Log.d(TAG, "Front result: ${frontResult.toString(2)}")
            
            // 尝试多种可能的字段名
            val name = frontResult.optString("Name")
                .ifEmpty { frontResult.optString("name") }
            
            val idNumber = frontResult.optString("IDNumber")
                .ifEmpty { frontResult.optString("idNumber") }
                .ifEmpty { frontResult.optString("cardNumber") }
            
            val gender = frontResult.optString("Gender")
                .ifEmpty { frontResult.optString("gender") }
                .ifEmpty { frontResult.optString("sex") }
            
            val address = frontResult.optString("Address")
                .ifEmpty { frontResult.optString("address") }
            
            Log.d(TAG, "Parsed: name=$name, idNumber=$idNumber, gender=$gender")
            
            if (name.isNotEmpty() || idNumber.isNotEmpty()) {
                IDCardInfo(
                    name = name,
                    idNumber = idNumber,
                    gender = gender,
                    address = address
                )
            } else {
                Log.e(TAG, "Parsed fields are empty")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Parse exception: ${e.message}", e)
            null
        }
    }
    
    private fun calculateSignature(params: Map<String, String>, secret: String): String {
        val sortedParams = params.toSortedMap()
        val queryString = sortedParams.map { (key, value) ->
            "${percentEncode(key)}=${percentEncode(value)}"
        }.joinToString("&")
        
        val stringToSign = "POST&${percentEncode("/")}&${percentEncode(queryString)}"
        val signKey = "$secret&"
        
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signKey.toByteArray(), "HmacSHA1"))
        val signature = mac.doFinal(stringToSign.toByteArray())
        
        return Base64.encodeToString(signature, Base64.DEFAULT).trim()
    }
    
    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }
    
    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    fun close() {}
}
