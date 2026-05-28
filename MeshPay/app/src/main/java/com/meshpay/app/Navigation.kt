package com.meshpay.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.meshpay.app.data.UserSession
import com.meshpay.app.ui.register.RegisterScreen
import com.meshpay.app.ui.wallet.HomeWalletScreen
import com.meshpay.app.ui.payment.SendPaymentScreen

@Composable
fun MainNavigation() {
  val context = LocalContext.current
  val startDestination = if (UserSession.getRegisteredVpa(context).isNullOrBlank()) Register else HomeWallet
  val backStack = rememberNavBackStack(startDestination)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Register> {
          RegisterScreen(
            onNavigateToHome = { 
              backStack.removeLastOrNull()
              backStack.add(HomeWallet) 
            },
            modifier = Modifier.safeDrawingPadding().padding(16.dp)
          )
        }
        entry<HomeWallet> {
          HomeWalletScreen(
            onNavigateToSendPayment = { 
              backStack.add(SendPayment) 
            },
            modifier = Modifier.safeDrawingPadding().padding(16.dp)
          )
        }
        entry<SendPayment> {
          SendPaymentScreen(
            onNavigateBack = { 
              backStack.removeLastOrNull() 
            },
            modifier = Modifier.safeDrawingPadding().padding(16.dp)
          )
        }
      },
  )
}
