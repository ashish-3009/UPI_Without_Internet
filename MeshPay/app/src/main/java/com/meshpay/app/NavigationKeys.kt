package com.meshpay.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Register : NavKey
@Serializable data object HomeWallet : NavKey
@Serializable data object SendPayment : NavKey
