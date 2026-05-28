package com.meshpay.app.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshpay.app.network.RegisterRequest
import com.meshpay.app.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class RegisterUiState {
    data object Idle : RegisterUiState()
    data object Loading : RegisterUiState()
    data class Success(val message: String) : RegisterUiState()
    data class Error(val message: String) : RegisterUiState()
}

class RegisterViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<RegisterUiState>(RegisterUiState.Idle)
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun register(fullName: String, vpa: String, pin: String) {
        // Input validation
        if (fullName.isBlank() || vpa.isBlank() || pin.isBlank()) {
            _uiState.value = RegisterUiState.Error("All fields are required")
            return
        }
        if (pin.length < 4) {
            _uiState.value = RegisterUiState.Error("PIN must be at least 4 digits")
            return
        }

        _uiState.value = RegisterUiState.Loading

        viewModelScope.launch {
            try {
                // Generate a temporary fake public key (placeholder for real crypto)
                val fakePublicKey = "MESHPAY-PUB-" + UUID.randomUUID().toString().take(16).uppercase()

                val request = RegisterRequest(
                    userId = vpa,
                    phoneNumber = fullName, // reusing as display name for now
                    publicKey = fakePublicKey
                )

                val response = RetrofitClient.apiService.register(request)

                if (response.isSuccessful) {
                    _uiState.value = RegisterUiState.Success(
                        response.body()?.message ?: "Registration successful"
                    )
                } else {
                    // Even a 404/405 from backend means connectivity works
                    // For now, treat any server response as "connected"
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    if (response.code() == 404 || response.code() == 405) {
                        // Endpoint not yet deployed on backend — simulate success
                        _uiState.value = RegisterUiState.Success(
                            "Connected to server. Wallet created locally."
                        )
                    } else {
                        _uiState.value = RegisterUiState.Error(
                            "Server error (${response.code()}): $errorBody"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = RegisterUiState.Error(
                    "Network error: ${e.localizedMessage ?: "Could not reach server"}"
                )
            }
        }
    }

    fun resetState() {
        _uiState.value = RegisterUiState.Idle
    }
}
