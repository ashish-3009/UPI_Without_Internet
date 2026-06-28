package com.meshpay.app.ui.wallet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeWalletScreen(
    onNavigateToSendPayment: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeWalletViewModel = viewModel()
) {
    val context = LocalContext.current
    val logs = viewModel.logs
    val connectedEndpoints by viewModel.connectedEndpoints.collectAsStateWithLifecycle()
    val isAdvertising by viewModel.isAdvertising.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val lastReceivedPacket by viewModel.lastReceivedPacket.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val registeredVpa by viewModel.registeredVpa.collectAsStateWithLifecycle()
    val walletState by viewModel.walletState.collectAsStateWithLifecycle()
    val uploadedPacketIds by viewModel.uploadedPacketIds.collectAsStateWithLifecycle()
    val currentPacketUploaded = lastReceivedPacket?.packetId?.let { it in uploadedPacketIds } == true
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val walletBalanceText = when (val state = walletState) {
        is WalletUiState.Loaded -> formatWalletAmount(state.balance)
        is WalletUiState.Error -> state.lastBalance?.let(::formatWalletAmount) ?: "Rs. --"
        WalletUiState.Loading -> "Refreshing..."
        WalletUiState.Idle -> "Rs. --"
    }
    val walletStatusText = when (val state = walletState) {
        is WalletUiState.Error -> state.message
        WalletUiState.Loading -> "Refreshing backend balance..."
        is WalletUiState.Loaded -> "Synced with backend"
        WalletUiState.Idle -> "Balance not loaded"
    }

    // Determine required permissions based on SDK level
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    // State to track if a permission request is currently active to prevent multiple triggers
    var isPermissionRequestActive by remember { mutableStateOf(false) }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        isPermissionRequestActive = false
        val allGranted = permissionsMap.values.all { it }
        if (allGranted) {
            val message = "Nearby permissions granted"
            viewModel.logUiMessage(message)
            snackbarMessage = message
        } else {
            val message = "Nearby permissions are required for mesh payments"
            viewModel.logUiMessage(message)
            snackbarMessage = message
        }
    }

    // Helper to check if all permissions are granted
    fun hasAllPermissions(ctx: Context): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Wrap execution with permission check
    fun runWithPermissions(action: () -> Unit) {
        if (hasAllPermissions(context)) {
            action()
        } else if (!isPermissionRequestActive) {
            isPermissionRequestActive = true
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    // Request permissions automatically once when the screen is first loaded
    LaunchedEffect(Unit) {
        if (!hasAllPermissions(context) && !isPermissionRequestActive) {
            isPermissionRequestActive = true
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    LaunchedEffect(snackbarMessage) {
        val message = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        snackbarMessage = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "MeshPay Wallet", 
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // 1. Balance card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Offline Wallet Balance",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Medium
                            )
                            TextButton(
                                onClick = { viewModel.refreshWalletBalance() },
                                enabled = walletState !is WalletUiState.Loading,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                if (walletState is WalletUiState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else {
                                    Text("Refresh", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = walletBalanceText,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = walletStatusText,
                            fontSize = 12.sp,
                            color = if (walletState is WalletUiState.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "VPA: ${registeredVpa.ifBlank { "Not registered" }}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Text(
                                text = "Connected Peers: ${connectedEndpoints.size}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (connectedEndpoints.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // 2. Main Wallet Action Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onNavigateToSendPayment,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("💸 Send Pay", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopNearbyServices() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🛑 Stop Nearby", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 3. Nearby Mesh Network Controls Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Nearby Offline Mesh Engine",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Discover other devices and route packets fully offline.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Advertising: ${if (isAdvertising) "On" else "Off"}  |  Discovery: ${if (isDiscovering) "On" else "Off"}  |  Link: ${if (isConnected) "Connected" else "Idle"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Controls Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { runWithPermissions { viewModel.startMesh() } },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Text("Start Mesh", fontSize = 13.sp)
                            }
                            Button(
                                onClick = { runWithPermissions { viewModel.discoverPeers() } },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Text("Discover Peers", fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { runWithPermissions { viewModel.sendTestPacket() } },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            ),
                            enabled = connectedEndpoints.isNotEmpty()
                        ) {
                            Text("Send Test Packet", fontWeight = FontWeight.Bold)
                        }

                        if (connectedEndpoints.isEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚠️ Connect to a peer first to send packets.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }

            // 4. Last Received Packet Details
            lastReceivedPacket?.let { packet ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📥 Received Packet payload",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = packet.timestamp.toDisplayTime(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Packet ID: ${packet.packetId}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "From: ${packet.sender}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    text = "To: ${packet.receiver}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Amount: ₹${formatPlainAmount(packet.amount)}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
            // 5. Upload Packet to Backend Bridge
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "🌐 Internet Bridge Upload",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "When online, upload the received mesh packet to the backend for settlement.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { viewModel.uploadPacketToBridge() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = lastReceivedPacket != null &&
                                    uploadState !is UploadUiState.Uploading &&
                                    !currentPacketUploaded,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            if (uploadState is UploadUiState.Uploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Uploading…")
                            } else {
                                Text("📤 Upload Packet to Backend", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (lastReceivedPacket == null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚠️ No mesh packet received yet.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        } else if (currentPacketUploaded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "This packet has already been uploaded.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }
            }

            // 6. Upload Result Card
            when (val state = uploadState) {
                is UploadUiState.Success -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = when (state.status) {
                                    "SETTLED" -> Color(0xFF1B5E20).copy(alpha = 0.15f)
                                    "DUPLICATE_DROPPED" -> Color(0xFFE65100).copy(alpha = 0.15f)
                                    else -> Color(0xFFB71C1C).copy(alpha = 0.15f)
                                }
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, when (state.status) {
                                "SETTLED" -> Color(0xFF4CAF50)
                                "DUPLICATE_DROPPED" -> Color(0xFFFF9800)
                                else -> Color(0xFFF44336)
                            })
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when (state.status) {
                                            "SETTLED" -> "✅ SETTLED"
                                            "DUPLICATE_DROPPED" -> "⚠️ DUPLICATE_DROPPED"
                                            "INVALID" -> "❌ INVALID"
                                            "REJECTED" -> "❌ REJECTED"
                                            else -> "ℹ️ ${state.status}"
                                        },
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (state.status) {
                                            "SETTLED" -> Color(0xFF4CAF50)
                                            "DUPLICATE_DROPPED" -> Color(0xFFFF9800)
                                            else -> Color(0xFFF44336)
                                        }
                                    )
                                    TextButton(
                                        onClick = { viewModel.resetUploadState() },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Dismiss", fontSize = 11.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.message,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
                is UploadUiState.Error -> {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFB71C1C).copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color(0xFFF44336))
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "❌ Upload Failed",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF44336)
                                    )
                                    TextButton(
                                        onClick = { viewModel.resetUploadState() },
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Dismiss", fontSize = 11.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.message,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
                else -> { /* Idle or Uploading — no card */ }
            }

            // 7. Console Log
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🖥️ Mesh Log Console",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Green,
                                fontFamily = FontFamily.Monospace
                            )
                            TextButton(
                                onClick = { viewModel.clearLogs() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Clear", color = Color.Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(Color.Black)
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    text = "Console idle. Mesh activities will log here.",
                                    color = Color.LightGray.copy(alpha = 0.5f),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(8.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    reverseLayout = true
                                ) {
                                    items(logs.size) { index ->
                                        val log = logs[logs.lastIndex - index]
                                        Text(
                                            text = log,
                                            color = if (log.contains("fail", ignoreCase = true) || log.contains("reject", ignoreCase = true)) Color.Red else if (log.contains("connect", ignoreCase = true) || log.contains("success", ignoreCase = true)) Color.Cyan else Color.Green,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun String.toDisplayTime(): String {
    val value = trim()
    if (value.contains('T')) {
        return value.substringAfter('T').substringBefore('.').removeSuffix("Z")
    }
    return value.split(" ").lastOrNull().orEmpty()
}

private fun formatWalletAmount(amount: Double): String {
    return "Rs. ${String.format(Locale.US, "%.2f", amount)}"
}

private fun formatPlainAmount(amount: Double): String {
    return String.format(Locale.US, "%.2f", amount)
}

// Helper border function not needed
