package com.telegramfiretv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R
import com.telegramfiretv.databinding.ActivityLoginBinding
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class LoginActivity : FragmentActivity() {

    private lateinit var binding: ActivityLoginBinding

    private enum class Step { PHONE, CODE, PASSWORD }
    private var step = Step.PHONE

    private val authListener: (TdApi.AuthorizationState?) -> Unit =
        { state -> runOnUiThread { applyState(state) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.nextButton.setBackgroundResource(R.drawable.bg_button)
        binding.nextButton.setTextColor(0xFFFFFFFF.toInt())
        binding.nextButton.setOnClickListener { onNext() }

        TdClient.addAuthListener(authListener)
        applyState(TdClient.authState)
    }

    private fun applyState(state: TdApi.AuthorizationState?) {
        when (state?.constructor) {
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                step = Step.PHONE
                binding.inputField.hint = getString(R.string.hint_phone)
                binding.inputField.inputType = InputType.TYPE_CLASS_PHONE
                binding.inputField.setText("+39")
                binding.inputField.setSelection(binding.inputField.text?.length ?: 0)
                binding.statusView.text = ""
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                step = Step.CODE
                binding.inputField.hint = getString(R.string.hint_code)
                binding.inputField.inputType = InputType.TYPE_CLASS_NUMBER
                binding.inputField.setText("")
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                step = Step.PASSWORD
                binding.inputField.hint = getString(R.string.hint_password)
                binding.inputField.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.inputField.setText("")
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun onNext() {
        val value = binding.inputField.text.toString().trim()
        if (value.isEmpty()) return
        when (step) {
            Step.PHONE -> TdClient.sendPhone(value)
            Step.CODE -> TdClient.sendCode(value)
            Step.PASSWORD -> TdClient.sendPassword(value)
        }
        binding.statusView.text = getString(R.string.loading)
    }

    override fun onDestroy() {
        super.onDestroy()
        TdClient.removeAuthListener(authListener)
    }
}
