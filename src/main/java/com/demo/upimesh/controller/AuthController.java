package com.demo.upimesh.controller;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("5000.00");

    private final AccountRepository accounts;

    public AuthController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
        String vpa = normalizeVpa(firstVpa(
                request.userId(),
                request.phoneNumber(),
                request.vpa()));

        if (vpa == null) {
            return ResponseEntity.badRequest().body(new RegisterResponse(
                    "error",
                    "VPA is required",
                    null));
        }

        String holderName = firstNonBlankNonVpa(
                request.fullName(),
                request.name(),
                request.phoneNumber(),
                request.userId());
        if (holderName == null) {
            holderName = vpa;
        }
        String accountHolderName = holderName;

        Account account = accounts.findById(vpa)
                .orElseGet(() -> new Account(vpa, accountHolderName, INITIAL_BALANCE));
        accounts.save(account);

        return ResponseEntity.ok(new RegisterResponse(
                "registered",
                "Registration successful",
                account.getVpa()));
    }

    private static String firstVpa(String... values) {
        for (String value : values) {
            if (isVpa(value)) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlankNonVpa(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !isVpa(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isVpa(String value) {
        return value != null && value.contains("@") && !value.isBlank();
    }

    private static String normalizeVpa(String vpa) {
        return vpa == null ? null : vpa.trim().toLowerCase(Locale.ROOT);
    }

    public record RegisterRequest(
            String userId,
            String phoneNumber,
            String publicKey,
            String fullName,
            String name,
            String vpa,
            String pin) {
    }

    public record RegisterResponse(String status, String message, String userId) {
    }
}
