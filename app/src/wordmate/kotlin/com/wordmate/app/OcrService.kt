package com.wordmate.app

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrService {
    fun extract(text: String): List<String> =
        Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(text).map { it.value }.distinctBy(::wordKey).take(300).toList()
    suspend fun recognize(context: Context, uri: Uri): List<String> {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        return suspendCancellableCoroutine { continuation ->
            val scanner = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            scanner.process(image).addOnSuccessListener {
                scanner.close()
                if (continuation.isActive) continuation.resume(extract(it.text))
            }.addOnFailureListener {
                scanner.close()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("图片识别失败，请换一张清晰图片或手动输入。"))
            }
        }
    }
}
