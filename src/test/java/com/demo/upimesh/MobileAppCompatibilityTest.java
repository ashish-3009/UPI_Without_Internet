package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MobileAppCompatibilityTest {

    @Autowired private MockMvc mvc;
    @Autowired private AccountRepository accounts;
    @Autowired private IdempotencyService idempotency;

    @BeforeEach
    void resetIdempotency() {
        idempotency.clear();
    }

    @Test
    void registerEndpointMatchesDebugApkContract() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "mobile-user@meshpay",
                                  "phoneNumber": "Mobile User",
                                  "publicKey": "fakePublicKey"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("registered"))
                .andExpect(jsonPath("$.message").value("Registration successful"))
                .andExpect(jsonPath("$.userId").value("mobile-user@meshpay"));

        Account account = accounts.findById("mobile-user@meshpay").orElseThrow();
        assertEquals("Mobile User", account.getHolderName());
        assertTrue(account.getBalance().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void bridgeIngestAcceptsNearbyPacketShapeFromDebugApk() throws Exception {
        accounts.save(new Account("apk-sender@meshpay", "APK Sender", new BigDecimal("500.00")));
        accounts.save(new Account("apk-receiver@meshpay", "APK Receiver", new BigDecimal("100.00")));

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String nearbyPacket = """
                {
                  "packetId": "apk-packet-1",
                  "timestamp": "%s",
                  "sender": "apk-sender@meshpay",
                  "receiver": "apk-receiver@meshpay",
                  "amount": 75
                }
                """.formatted(timestamp).replace("\n", "");
        String request = """
                {
                  "packetId": "apk-packet-1",
                  "ttl": 5,
                  "createdAt": "%s",
                  "ciphertext": %s
                }
                """.formatted(timestamp, jsonString(nearbyPacket));

        mvc.perform(post("/api/bridge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.message").value("Packet settled successfully"))
                .andExpect(jsonPath("$.packetId").value("apk-packet-1"))
                .andExpect(jsonPath("$.hash").exists());

        assertEquals(new BigDecimal("425.00"), accounts.findById("apk-sender@meshpay").orElseThrow().getBalance());
        assertEquals(new BigDecimal("175.00"), accounts.findById("apk-receiver@meshpay").orElseThrow().getBalance());
    }

    @Test
    void walletEndpointReturnsCurrentBalanceAfterSettlement() throws Exception {
        accounts.save(new Account("wallet-sender@meshpay", "Wallet Sender", new BigDecimal("500.00")));
        accounts.save(new Account("wallet-receiver@meshpay", "Wallet Receiver", new BigDecimal("100.00")));

        String timestamp = Instant.now().toString();
        String nearbyPacket = """
                {
                  "packetId": "wallet-packet-1",
                  "timestamp": "%s",
                  "sender": "wallet-sender@meshpay",
                  "receiver": "wallet-receiver@meshpay",
                  "amount": 75
                }
                """.formatted(timestamp).replace("\n", "");
        String request = """
                {
                  "packetId": "wallet-packet-1",
                  "ttl": 5,
                  "createdAt": "%s",
                  "ciphertext": %s
                }
                """.formatted(timestamp, jsonString(nearbyPacket));

        mvc.perform(post("/api/bridge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));

        mvc.perform(get("/api/wallet/wallet-sender@meshpay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vpa").value("wallet-sender@meshpay"))
                .andExpect(jsonPath("$.balance").value(425.00));

        mvc.perform(get("/api/wallet/wallet-receiver@meshpay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vpa").value("wallet-receiver@meshpay"))
                .andExpect(jsonPath("$.balance").value(175.00));
    }

    @Test
    void bridgeIngestAcceptsUtcIsoTimestampFromDebugApk() throws Exception {
        accounts.save(new Account("iso-sender@meshpay", "ISO Sender", new BigDecimal("500.00")));
        accounts.save(new Account("iso-receiver@meshpay", "ISO Receiver", new BigDecimal("100.00")));

        String timestamp = Instant.now().toString();
        String nearbyPacket = """
                {
                  "packetId": "iso-packet-1",
                  "timestamp": "%s",
                  "sender": "iso-sender@meshpay",
                  "receiver": "iso-receiver@meshpay",
                  "amount": 75
                }
                """.formatted(timestamp).replace("\n", "");
        String request = """
                {
                  "packetId": "iso-packet-1",
                  "ttl": 5,
                  "createdAt": "%s",
                  "ciphertext": %s
                }
                """.formatted(timestamp, jsonString(nearbyPacket));

        mvc.perform(post("/api/bridge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.message").value("Packet settled successfully"));
    }

    @Test
    void bridgeIngestAllowsFiveMinuteFutureClockSkew() throws Exception {
        accounts.save(new Account("skew-sender@meshpay", "Skew Sender", new BigDecimal("500.00")));
        accounts.save(new Account("skew-receiver@meshpay", "Skew Receiver", new BigDecimal("100.00")));

        String timestamp = Instant.now().plusSeconds(240).toString();
        String nearbyPacket = """
                {
                  "packetId": "skew-packet-1",
                  "timestamp": "%s",
                  "sender": "skew-sender@meshpay",
                  "receiver": "skew-receiver@meshpay",
                  "amount": 75
                }
                """.formatted(timestamp).replace("\n", "");
        String request = """
                {
                  "packetId": "skew-packet-1",
                  "ttl": 5,
                  "createdAt": "%s",
                  "ciphertext": %s
                }
                """.formatted(timestamp, jsonString(nearbyPacket));

        mvc.perform(post("/api/bridge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
    }

    @Test
    void bridgeIngestReportsRejectedWhenSenderHasInsufficientBalance() throws Exception {
        accounts.save(new Account("poor-sender@meshpay", "Poor Sender", new BigDecimal("10.00")));
        accounts.save(new Account("reject-receiver@meshpay", "Reject Receiver", new BigDecimal("100.00")));

        String timestamp = Instant.now().toString();
        String nearbyPacket = """
                {
                  "packetId": "reject-packet-1",
                  "timestamp": "%s",
                  "sender": "poor-sender@meshpay",
                  "receiver": "reject-receiver@meshpay",
                  "amount": 75
                }
                """.formatted(timestamp).replace("\n", "");
        String request = """
                {
                  "packetId": "reject-packet-1",
                  "ttl": 5,
                  "createdAt": "%s",
                  "ciphertext": %s
                }
                """.formatted(timestamp, jsonString(nearbyPacket));

        mvc.perform(post("/api/bridge/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("Payment rejected: insufficient balance"));

        // No money moved on a rejected payment.
        assertEquals(new BigDecimal("10.00"), accounts.findById("poor-sender@meshpay").orElseThrow().getBalance());
        assertEquals(new BigDecimal("100.00"), accounts.findById("reject-receiver@meshpay").orElseThrow().getBalance());
    }

    private String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
