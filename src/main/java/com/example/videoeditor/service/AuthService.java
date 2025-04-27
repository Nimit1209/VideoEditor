package com.example.videoeditor.service;

import com.example.videoeditor.dto.AuthRequest;
import com.example.videoeditor.dto.AuthResponse;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.VerificationToken;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.repository.VerificationTokenRepository;
import com.example.videoeditor.security.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    public AuthService(UserRepository userRepository, VerificationTokenRepository verificationTokenRepository,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil, EmailService emailService) {
        this.userRepository = userRepository;
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.emailService = emailService;
    }

    public AuthResponse register(AuthRequest request) throws MessagingException {
        // Enhanced email validation
        if (!isValidEmail(request.getEmail())) {
            throw new RuntimeException("Please enter a valid email address");
        }

        // Check email domain validity
        if (!isEmailDomainValid(request.getEmail())) {
            throw new RuntimeException("The email domain is not valid");
        }

        Optional<User> existingUser = userRepository.findByEmail(request.getEmail());
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (user.isGoogleAuth()) {
                throw new RuntimeException("Email is already associated with a Google account. Please log in with Google.");
            }
            throw new RuntimeException("This email is already registered");
        }

        // Validate password strength
        if (!isPasswordValid(request.getPassword())) {
            throw new RuntimeException("Password must be at least 8 characters with at least one letter and one number");
        }

        // Create and save user
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setGoogleAuth(false);
        user.setEmailVerified(false);
        userRepository.save(user);

        // Generate and save verification token
        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken = new VerificationToken(
                token,
                user,
                LocalDateTime.now().plusHours(24)
        );
        verificationTokenRepository.save(verificationToken);

        // Send verification email
        try {
            emailService.sendVerificationEmail(user.getEmail(), token);
        } catch (MessagingException e) {
            // If email fails to send, delete the user
            userRepository.delete(user);
            throw new RuntimeException("Failed to send verification email. Please check your email address.");
        }

        return new AuthResponse(
                null,
                user.getEmail(),
                null,
                "Verification email sent. Please check your inbox to complete registration.",
                false
        );
    }

    private boolean isEmailDomainValid(String email) {
        try {
            String domain = email.substring(email.indexOf('@') + 1);
            // Check if domain has a valid structure
            return domain.matches("^([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}$");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPasswordValid(String password) {
        // At least 8 chars, contains letter and number
        return password != null && password.length() >= 8 &&
                password.matches(".*[a-zA-Z].*") &&
                password.matches(".*[0-9].*");
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (user.isGoogleAuth()) {
            throw new RuntimeException("This account is linked to Google. Please log in with Google.");
        }

        if (!user.isEmailVerified()) {
            throw new RuntimeException("Email not verified. Please check your inbox for the verification email.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(
                token,
                user.getEmail(),
                user.getName(),
                "Login successful",
                true
        );
    }

    public AuthResponse googleLogin(String idTokenString) throws Exception {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();

        GoogleIdToken idToken = verifier.verify(idTokenString);
        if (idToken == null) {
            throw new RuntimeException("Invalid Google ID token");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String name = (String) payload.get("name");

        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            if (name != null && (user.getName() == null || user.getName().isEmpty())) {
                user.setName(name);
            }
            user.setGoogleAuth(true);
            if (user.getPassword() == null || user.getPassword().isEmpty()) {
                user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            }
            user.setEmailVerified(true);
            userRepository.save(user);
        } else {
            user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            user.setGoogleAuth(true);
            user.setEmailVerified(true);
            userRepository.save(user);
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(
                token,
                user.getEmail(),
                user.getName(),
                "Google login successful",
                true
        );
    }

    public String verifyEmail(String token) {
        System.out.println("Verifying token: " + token); // Debug log
        VerificationToken verificationToken = verificationTokenRepository.findByToken(token)
                .orElseThrow(() -> {
                    System.out.println("Token not found: " + token); // Debug log
                    return new RuntimeException("Invalid or unknown verification token");
                });

        System.out.println("Token expiry: " + verificationToken.getExpiryDate()); // Debug log
        if (verificationToken.getExpiryDate().isBefore(LocalDateTime.now())) {
            verificationTokenRepository.delete(verificationToken);
            System.out.println("Token expired: " + token); // Debug log
            throw new RuntimeException("Verification token has expired. Please request a new verification email.");
        }

        User user = verificationToken.getUser();
        System.out.println("User email: " + user.getEmail() + ", verified: " + user.isEmailVerified()); // Debug log
        if (user.isEmailVerified()) {
            System.out.println("Email already verified for user: " + user.getEmail()); // Debug log
            throw new RuntimeException("Email has already been verified. Please log in.");
        }

        user.setEmailVerified(true);
        userRepository.save(user);
        verificationTokenRepository.delete(verificationToken);
        System.out.println("Email verified successfully for user: " + user.getEmail()); // Debug log

        // Generate JWT token for immediate authentication
        String jwtToken = jwtUtil.generateToken(user.getEmail());
        System.out.println("Generated JWT token: " + jwtToken); // Debug log
        return jwtToken;
    }

    private boolean isValidEmail(String email) {
        String emailRegex = "^[A-Za-z0-9+_.-]+@(.+)$";
        return email != null && email.matches(emailRegex);
    }

    public void resendVerificationEmail(String email) throws MessagingException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (user.isEmailVerified()) {
            throw new RuntimeException("Email already verified");
        }

        verificationTokenRepository.deleteByUser(user);

        String token = UUID.randomUUID().toString();
        VerificationToken verificationToken = new VerificationToken(
                token,
                user,
                LocalDateTime.now().plusHours(24)
        );
        verificationTokenRepository.save(verificationToken);
        emailService.sendVerificationEmail(user.getEmail(), token);
    }
}