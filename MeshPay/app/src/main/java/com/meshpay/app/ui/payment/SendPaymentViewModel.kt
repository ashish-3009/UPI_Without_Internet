package com.meshpay.app.ui.payment

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshpay.app.data.UserSession
import com.meshpay.app.data.WalletRepository
import com.meshpay.app.nearby.MeshPacket
import com.meshpay.app.nearby.NearbyMeshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import java.util.UUID

sealed class SendPaymentUiState {
    data object Idle : SendPaymentUiState()
    data object Sending : SendPaymentUiState()
    data class Success(val message: String) : SendPaymentUiState()
    data class Error(val message: String) : SendPaymentUiState()
}

class SendPaymentViewModel(application: Application) : AndroidViewModel(application) {
    private val nearbyService = NearbyMeshService.getInstance(application)
    private val walletRepository = WalletRepository()

    private val _senderVpa = MutableStateFlow(UserSession.getRegisteredVpa(application).orEmpty())
    val senderVpa: StateFlow<String> = _senderVpa.asStateFlow()

    private val _uiState = MutableStateFlow<SendPaymentUiState>(SendPaymentUiState.Idle)
    val uiState: StateFlow<SendPaymentUiState> = _uiState.asStateFlow()

    fun sendPayment(receiverVpa: String, amountText: String, pin: String) {
        val sender = _senderVpa.value
        val receiver = receiverVpa.trim().lowercase(Locale.ROOT)
        val amount = amountText.toIntOrNull()

        if (sender.isBlank()) {
            _uiState.value = SendPaymentUiState.Error("Register a wallet before sending.")
            return
        }
        if (receiver.isBlank()) {
            _uiState.value = SendPaymentUiState.Error("Recipient VPA is required.")
            return
        }
        if (amount == null || amount <= 0) {
            _uiState.value = SendPaymentUiState.Error("Enter a valid amount.")
            return
        }
        if (pin.isBlank()) {
            _uiState.value = SendPaymentUiState.Error("Wallet PIN is required.")
            return
        }
        if (nearbyService.connectedEndpoints.value.isEmpty()) {
            _uiState.value = SendPaymentUiState.Error("Connect to a nearby peer before sending.")
            return
        }

        val packet = MeshPacket(
            packetId = UUID.randomUUID().toString(),
            sender = sender,
            receiver = receiver,
            amount = amount,
            timestamp = Instant.now().toString()
        )
        _uiState.value = SendPaymentUiState.Sending
        viewModelScope.launch(Dispatchers.IO) {
            val sent = nearbyService.broadcastMeshPacket(packet)
            _uiState.value = if (sent) {
                val pendingBalance = walletRepository.getWallet(sender)
                    .getOrNull()
                    ?.let { wallet -> wallet.balance - amount }
                val balanceText = pendingBalance?.let {
                    " Pending local balance: Rs. ${String.format(Locale.US, "%.2f", it)}"
                } ?: " Balance refreshes after backend settlement."
                SendPaymentUiState.Success("Packet ${packet.packetId.take(8)} sent to nearby peers.$balanceText")
            } else {
                SendPaymentUiState.Error("Packet could not be sent. Reconnect to a nearby peer and try again.")
            }
        }
    }
}
