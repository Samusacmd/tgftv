package com.telegramfiretv.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R
import com.telegramfiretv.databinding.ActivityLoginBinding
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class LoginActivity : FragmentActivity() {

    private lateinit var binding: ActivityLoginBinding

    private enum class Step { PHONE, CODE, PASSWORD, EMAIL, EMAIL_CODE, BLOCKED }
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
                binding.titleView.text = getString(R.string.login_title)
                showInput(getString(R.string.hint_phone), InputType.TYPE_CLASS_PHONE, prefill = "+39")
                binding.statusView.text = ""
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                step = Step.CODE
                showInput(getString(R.string.hint_code), InputType.TYPE_CLASS_NUMBER)
                binding.statusView.text = ""
            }
            TdApi.AuthorizationStateWaitEmailAddress.CONSTRUCTOR -> {
                step = Step.EMAIL
                showInput(
                    getString(R.string.hint_email),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                )
                binding.statusView.text = ""
            }
            TdApi.AuthorizationStateWaitEmailCode.CONSTRUCTOR -> {
                step = Step.EMAIL_CODE
                showInput(getString(R.string.hint_email_code), InputType.TYPE_CLASS_NUMBER)
                binding.statusView.text = ""
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                step = Step.PASSWORD
                showInput(
                    getString(R.string.hint_password),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                binding.statusView.text = ""
            }
            TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR -> {
                step = Step.BLOCKED
                showBlocked(
                    "Questo numero non ha ancora un account Telegram.\n\n" +
                        "Crea prima l'account dall'app ufficiale Telegram, poi rientra qui per accedere."
                )
            }
            TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR -> {
                step = Step.BLOCKED
                val link = (state as TdApi.AuthorizationStateWaitOtherDeviceConfirmation).link
                showBlocked(
                    "Conferma l'accesso da un dispositivo già connesso a Telegram.\n\n" +
                        "Apri questo link (o scansiona il QR corrispondente) dal telefono:\n\n$link"
                )
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR,
            TdApi.AuthorizationStateClosing.CONSTRUCTOR,
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                step = Step.BLOCKED
                showBlocked("Disconnessione in corso…")
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun showInput(hint: String, inputType: Int, prefill: String = "") {
        binding.inputField.visibility = View.VISIBLE
        binding.nextButton.visibility = View.VISIBLE
        binding.inputField.hint = hint
        binding.inputField.inputType = inputType
        binding.inputField.setText(prefill)
        binding.inputField.setSelection(binding.inputField.text?.length ?: 0)
        binding.inputField.requestFocus()
    }

    private fun showBlocked(message: String) {
        binding.inputField.visibility = View.GONE
        binding.nextButton.visibility = View.GONE
        binding.statusView.text = message
    }

    private fun onNext() {
        val value = binding.inputField.text.toString().trim()
        if (value.isEmpty()) return
        when (step) {
            Step.PHONE -> TdClient.sendPhone(value)
            Step.CODE -> TdClient.sendCode(value)
            Step.PASSWORD -> TdClient.sendPassword(value)
            Step.EMAIL -> TdClient.sendEmailAddress(value)
            Step.EMAIL_CODE -> TdClient.sendEmailCode(value)
            Step.BLOCKED -> return
        }
        binding.statusView.text = getString(R.string.loading)
    }

    override fun onDestroy() {
        super.onDestroy()
        TdClient.removeAuthListener(authListener)
    }
}
