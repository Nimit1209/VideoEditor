package com.example.videoeditor.controller;

import com.example.videoeditor.dto.AuthRequest;
import com.example.videoeditor.dto.AuthResponse;
import com.example.videoeditor.dto.UserProfileResponse;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.AuthService;
import jakarta.mail.MessagingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, JwtUtil jwtUtil, UserRepository userRepository) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            // Handle invalid email or domain errors
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new AuthResponse(null, request.getEmail(), null, e.getMessage(), false));
        } catch (MessagingException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AuthResponse(null, request.getEmail(), null,
                            "Failed to send verification email", false));
        }
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<String> resendVerification(@RequestParam String email) {
        try {
            authService.resendVerificationEmail(email);
            return ResponseEntity.ok("Verification email resent successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (MessagingException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to send verification email. Please try again.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An error occurred while resending the verification email. Please try again.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleLogin(@RequestBody GoogleAuthRequest request) throws Exception {
        return ResponseEntity.ok(authService.googleLogin(request.getToken()));
    }

    @GetMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@RequestParam String token) {
        try {
            // Verify email and get JWT token
            String jwtToken = authService.verifyEmail(token);
            return ResponseEntity.ok(new AuthResponse(
                    jwtToken,
                    null,
                    null,
                    "Email verified successfully",
                    true
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new AuthResponse(
                    null,
                    null,
                    null,
                    e.getMessage(),
                    false
            ));
        }
    }

    @PostMapping("/developer-login")
    public ResponseEntity<AuthResponse> developerLogin(@RequestBody AuthRequest request) {
        try {
            AuthResponse response = authService.developerLogin(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(null, request.getEmail(), null, e.getMessage(), false));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getUserProfile(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.substring(7); // Remove "Bearer " prefix
        String userEmail = jwtUtil.extractEmail(token);

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Log the profile picture URL for debugging
        System.out.println("Profile Picture URL: " + user.getProfilePicture());


        UserProfileResponse profileResponse = new UserProfileResponse(
                user.getEmail(),
                user.getName(),
                user.getProfilePicture(),
                user.isGoogleAuth()
        );

        return ResponseEntity.ok(profileResponse);
    }
}

class GoogleAuthRequest {
    private String token;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}