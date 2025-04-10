package com.example.videoeditor.service;

import com.example.videoeditor.dto.AuthRequest;
import com.example.videoeditor.dto.AuthResponse;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import java.util.Collections;
import java.util.Optional;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public AuthResponse register(AuthRequest request) {
        Optional<User> existingUser = userRepository.findByEmail(request.getEmail());
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            // If the user exists and was created via Google OAuth, prevent registration
            if (user.isGoogleAuth()) {
                throw new RuntimeException("Email is already associated with a Google account. Please log in with Google.");
            }
            // If the user exists and was created via email/password, throw an error
            throw new RuntimeException("User already exists!");
        }

        // Create a new user
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setGoogleAuth(false); // Explicitly set for regular users
        userRepository.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), null);
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        // If the user is a Google-authenticated user, they can't log in with email/password
        if (user.isGoogleAuth()) {
            throw new RuntimeException("This account is linked to Google. Please log in with Google.");
        }

        // Verify password for email/password users
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getName());
    }

    public AuthResponse googleLogin(String idTokenString) throws Exception {
        // Initialize GoogleIdTokenVerifier
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

        // Check if the user already exists
        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        if (existingUserOpt.isPresent()) {
            // User exists, update their profile
            user = existingUserOpt.get();
            // Update name if provided by Google and not already set
            if (name != null && (user.getName() == null || user.getName().isEmpty())) {
                user.setName(name);
            }
            // Mark as Google-authenticated
            user.setGoogleAuth(true);
            // If the user previously signed up with email/password, keep their password
            // Otherwise, set a dummy password
            if (user.getPassword() == null || user.getPassword().isEmpty()) {
                user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            }
            userRepository.save(user);
        } else {
            // User doesn't exist, create a new one
            user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setPassword(passwordEncoder.encode("GOOGLE_AUTH_" + System.currentTimeMillis()));
            user.setGoogleAuth(true);
            userRepository.save(user);
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return new AuthResponse(token, user.getEmail(), user.getName());
    }

}