package ro.andi.phonebarriers

import okhttp3.*
import okhttp3.FormBody
import java.io.IOException

object CallRepository {

    public const val KEY_TO = BuildConfig.TEST_PHONE_NUMBER_TO
    public const val KEY_FROM = BuildConfig.TEST_PHONE_NUMBER_FROM

    private val client = OkHttpClient()
    private const val TWILIO_FUNC_URL = BuildConfig.TWILIO_FUNC_URL
    private const val TWILIO_FUNC_SECRET = BuildConfig.TWILIO_FUNC_SECRET

    fun triggerOneRing(to: String, from: String, onResult: (Boolean) -> Unit) {


        val formBody = FormBody.Builder()
            .add("to", to)
            .add("from", from)
            .add("secret_key", TWILIO_FUNC_SECRET)
            .build()

        val request = Request.Builder()
            .url(TWILIO_FUNC_URL)
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                onResult(false)
            }
            override fun onResponse(call: Call, response: Response) {
                println("Response: ${response.body?.string()}")
                onResult(response.isSuccessful)
                response.close() // Good practice to close
            }
        })
    }
}