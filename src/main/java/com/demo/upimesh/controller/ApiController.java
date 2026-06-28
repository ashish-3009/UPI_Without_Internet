package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Public REST surface.
 *
 * The endpoints split into three groups:
 *   /api/server-key      → so simulated senders can fetch the server's public key
 *   /api/mesh/*          → simulator endpoints (inject, gossip, flush)
 *   /api/bridge/ingest   → THE real production endpoint a real bridge node would hit
 *   /api/accounts, /api/transactions → for the dashboard
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private ServerKeyHolder serverKey;
    @Autowired private DemoService demo;
    @Autowired private MeshSimulatorService mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository accountRepo;
    @Autowired private TransactionRepository txRepo;
    @Autowired private IdempotencyService idempotency;
    @Autowired private ObjectMapper objectMapper;

    private static final DateTimeFormatter MOBILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ------------------------------------------------------------------ key

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    }

    // ---------------------------------------------------------------- demo

    /**
     * Demo helper: build a packet on the server (simulating a sender phone)
     * and inject it into the mesh at the given device.
     */
    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl);

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        return ResponseEntity.ok(Map.of(
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    public static class DemoSendRequest {
        public String senderVpa;
        public String receiverVpa;
        public BigDecimal amount;
        public String pin;
        public Integer ttl;
        public String startDevice;
    }

    // -------------------------------------------------------------- mesh sim

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of(
                "devices", deviceData,
                "idempotencyCacheSize", idempotency.size()
        );
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        );
    }

    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * They all upload everything they hold to /api/bridge/ingest.
     *
     * THIS is the moment the duplicate-storm idempotency case is tested:
     * if multiple bridge nodes hold the same packet, the server gets multiple
     * concurrent POSTs of the same ciphertext, and only one should settle.
     */
    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();

        List<Map<String, Object>> results = new ArrayList<>();
        // Upload them in parallel to actually exercise concurrent idempotency.
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.outcome(),
                        "reason", r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });

        return Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        );
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and idempotency cache cleared");
    }

    // -------------------------------------------------------------- bridge

    /**
     * THE PRODUCTION ENDPOINT.
     * In a real deployment, the Android app's bridge logic POSTs here whenever
     * the device has internet and is holding mesh packets.
     */
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @RequestBody JsonNode payload,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        BridgeIngestionService.PlainDemoPacket plainPacket = plainDemoPacket(payload);
        if (plainPacket != null) {
            BridgeIngestionService.IngestResult r =
                    bridge.ingestPlainDemoPacket(plainPacket, bridgeNodeId, hopCount);
            return ResponseEntity.ok(bridgeResponse(plainPacket.packetId(), r));
        }

        MeshPacket packet = encryptedMeshPacket(payload);
        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(bridgeResponse(packet.getPacketId(), r));
    }

    private BridgeIngestionService.PlainDemoPacket plainDemoPacket(JsonNode payload) {
        JsonNode rawPacket = payload;
        String hashMaterial = payload.toString();

        String ciphertext = text(payload, "ciphertext");
        if (ciphertext != null && ciphertext.trim().startsWith("{")) {
            try {
                rawPacket = objectMapper.readTree(ciphertext);
                hashMaterial = ciphertext;
            } catch (Exception ignored) {
                rawPacket = payload;
            }
        }

        String sender = firstText(rawPacket, "senderVpa", "sender");
        String receiver = firstText(rawPacket, "receiverVpa", "receiver");
        if (sender == null || receiver == null) {
            return null;
        }

        BigDecimal amount = decimal(rawPacket, "amount");
        if (amount == null) {
            return null;
        }

        String packetId = firstText(rawPacket, "packetId");
        if (packetId == null) {
            packetId = firstText(payload, "packetId");
        }
        if (packetId == null) {
            packetId = UUID.randomUUID().toString();
        }

        long signedAt = timestampMillis(firstText(rawPacket, "signedAt", "timestamp", "createdAt"));
        return new BridgeIngestionService.PlainDemoPacket(
                packetId,
                sender,
                receiver,
                amount,
                signedAt,
                hashMaterial);
    }

    private MeshPacket encryptedMeshPacket(JsonNode payload) {
        MeshPacket packet = new MeshPacket();
        packet.setPacketId(Optional.ofNullable(text(payload, "packetId"))
                .orElse(UUID.randomUUID().toString()));
        packet.setTtl(payload.path("ttl").asInt(5));
        packet.setCreatedAt(createdAtMillis(payload.path("createdAt")));
        packet.setCiphertext(text(payload, "ciphertext"));
        return packet;
    }

    private Map<String, Object> bridgeResponse(String packetId, BridgeIngestionService.IngestResult result) {
        String message = result.reason() == null ? messageFor(result.outcome()) : result.reason();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.outcome());
        response.put("message", message);
        response.put("packetId", packetId);
        response.put("hash", result.packetHash());
        response.put("outcome", result.outcome());
        response.put("packetHash", result.packetHash());
        response.put("reason", result.reason());
        response.put("transactionId", result.transactionId());
        return response;
    }

    private String messageFor(String outcome) {
        return switch (outcome) {
            case "SETTLED" -> "Packet settled successfully";
            case "DUPLICATE_DROPPED" -> "Duplicate packet dropped";
            case "REJECTED" -> "Payment rejected: insufficient balance";
            case "INVALID" -> "Invalid packet";
            default -> outcome;
        };
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private BigDecimal decimal(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long createdAtMillis(JsonNode value) {
        if (value == null || value.isNull()) {
            return Instant.now().toEpochMilli();
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        return timestampMillis(value.asText());
    }

    private long timestampMillis(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now().toEpochMilli();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // Fall through to formatted timestamp parsing.
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Fall through to the legacy mobile timestamp format.
        }
        try {
            return LocalDateTime.parse(value, MOBILE_TIMESTAMP)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return Instant.now().toEpochMilli();
        }
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/wallet/{vpa}")
    public ResponseEntity<?> wallet(@PathVariable String vpa) {
        return accountRepo.findById(vpa.toLowerCase(Locale.ROOT))
                .<ResponseEntity<?>>map(account -> ResponseEntity.ok(Map.of(
                        "vpa", account.getVpa(),
                        "balance", account.getBalance()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }
}
